package org.example;

import javax.sip.*;
import javax.sip.address.*;
import javax.sip.header.*;
import javax.sip.message.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

public class SipVideoCallClient implements SipListener {

    /** 配置来源：项目根目录的 config.properties；不存在则全部使用内置默认值 */
    private static final Properties CONFIG = loadConfig();

    private SipStack sipStack;
    private SipProvider sipProvider;
    private AddressFactory addressFactory;
    private HeaderFactory headerFactory;
    private MessageFactory messageFactory;
    private ListeningPoint listeningPoint;

    // 配置信息：优先读 config.properties，未配置项用默认值
    private String username   = cfg("sip.username",     "100");
    private String password   = cfg("sip.password",     "100");
    private String serverIp   = cfg("sip.server.ip",    "10.122.213.68");
    private int    serverPort = intCfg("sip.server.port", 5060);
    private String localIp    = cfg("sip.local.ip",     "10.122.213.68");
    private int    localPort  = intCfg("sip.local.port",  5061);

    private String targetUser = cfg("sip.target.user",  "101");

    /** 通话时长（秒），到时自动挂断 */
    private static final int CALL_DURATION_SEC = intCfg("call.duration.sec", 180);

    /** 启动后是否自动呼叫被叫（测文字消息时建议设为 false） */
    private static final boolean AUTO_INVITE = boolCfg("call.auto.invite", true);

    /** 常驻模式：true = 程序持续运行直到 /quit 或 Ctrl+C；false = 跑完固定时长自动退出 */
    private static final boolean KEEP_ALIVE = boolCfg("app.keep.alive", true);

    /** 常驻模式下阻塞主线程，收到 /quit 时释放，让主线程走正常的关闭流程 */
    private static final java.util.concurrent.CountDownLatch SHUTDOWN_LATCH =
            new java.util.concurrent.CountDownLatch(1);

    private Dialog dialog;
    private ClientTransaction inviteTransaction;
    private volatile boolean isRegistered = false;

    /** 当前通话的开始时间 / 对端 / 方向，用于通话结束时统计时长（0 表示当前无通话） */
    private volatile long callStartMillis = 0;
    private volatile String callPeer = "";
    private volatile String callDirection = "";

    /**
     * UI 桥接回调。
     * 命令行模式不设置（为 null）；Web 网页端会注入实现，把事件推送给浏览器。
     */
    public interface Ui {
        /** 收到/发出文字消息 */
        void onMessage(String peer, String text, boolean outgoing);

        /** 收到/发出文件消息；type 为 IMG / VOICE / VIDEO，savedPath 为本地落盘路径 */
        void onFile(String peer, String type, String name, String savedPath, boolean outgoing);

        /** A rejected incoming file is never advertised as complete. */
        default void onFileError(String peer, String reason) { }

        /** 通话状态变化：calling / ringing / incoming / active / ended */
        void onCallState(String state, String peer);

        /** 一帧视频（JPEG 原始字节）；local=true 为本地摄像头画面 */
        void onVideoFrame(byte[] jpeg, boolean local);

        /** 会议模式下收到某位参与者的视频帧（from 为分机号） */
        default void onConferenceFrame(String from, byte[] jpeg) { onVideoFrame(jpeg, false); }

        /** 某位参与者离开会议视频房间 */
        default void onConferencePeerLeft(String from) { }

        /** 会议视频开启/关闭（网页据此切换多路画面布局） */
        default void onConferenceState(boolean on, String room) { }
    }

    private volatile Ui ui;

    /** Web 模式注入 UI 回调 */
    public void setUi(Ui ui) {
        this.ui = ui;
    }

    /** 是否已注册成功（Web 端查询用） */
    public boolean isRegistered() {
        return isRegistered;
    }

    private void notifyMessage(String peer, String text, boolean outgoing) {
        Ui u = this.ui;
        if (u != null) {
            try { u.onMessage(peer, text, outgoing); } catch (Exception ignore) { }
        }
    }

    private void notifyFile(String peer, String type, String name, String path, boolean outgoing) {
        Ui u = this.ui;
        if (u != null) {
            try { u.onFile(peer, type, name, path, outgoing); } catch (Exception ignore) { }
        }
    }

    private void notifyCall(String state, String peer) {
        Ui u = this.ui;
        if (u != null) {
            try { u.onCallState(state, peer); } catch (Exception ignore) { }
        }
    }

    private void notifyVideo(byte[] jpeg, boolean local) {
        Ui u = this.ui;
        if (u != null) {
            try { u.onVideoFrame(jpeg, local); } catch (Exception ignore) { }
        }
    }

    private void notifyConferenceFrame(String from, byte[] jpeg) {
        Ui u = this.ui;
        if (u != null) {
            try { u.onConferenceFrame(from, jpeg); } catch (Exception ignore) { }
        }
    }

    private void notifyConferencePeerLeft(String from) {
        Ui u = this.ui;
        if (u != null) {
            try { u.onConferencePeerLeft(from); } catch (Exception ignore) { }
        }
    }

    private void notifyConferenceState(boolean on, String room) {
        Ui u = this.ui;
        if (u != null) {
            try { u.onConferenceState(on, room); } catch (Exception ignore) { }
        }
    }

    /** 文字消息的 CSeq 序号，每发一条递增 */
    private final java.util.concurrent.atomic.AtomicLong messageCSeq = new java.util.concurrent.atomic.AtomicLong(1);

    /**
     * 文件分片大小（Base64 字符数），图片与语音消息共用。
     * 实测：单个 SIP MESSAGE 的 Content-Length 超过 1000 左右就会被丢弃
     * （总 UDP 载荷超过以太网 MTU 1472 字节后产生 IP 分片，分片包被链路丢弃）。
     * 因此这里保守取 800，保证加上约 300 字节 SIP 头后仍不分片。
     */
    private final int FILE_CHUNK_SIZE = intCfg("im.file.chunk.size", 800);
    private final ReceivedFileStore fileStore;
    private final FileTransferReceiver fileReceiver;
    private java.util.concurrent.ScheduledExecutorService fileCleanup;

    public SipVideoCallClient() {
        this(new ReceivedFileStore(java.nio.file.Paths.get("received")));
    }

    SipVideoCallClient(ReceivedFileStore fileStore) {
        this.fileStore = java.util.Objects.requireNonNull(fileStore, "fileStore");
        this.fileReceiver = new FileTransferReceiver(fileStore);
    }

    public ReceivedFileStore getFileStore() { return fileStore; }

    /** 语音消息默认时长（秒） */
    private static final int VOICE_DURATION_SEC = intCfg("im.voice.duration.sec", 5);
    /** 最近收到的语音消息文件，供 /play 重放 */
    private volatile File lastVoiceFile;
    /** 最近收到的视频消息文件 */
    private volatile File lastVideoFile;

    // ---------- 视频 ----------
    /** 是否启用视频（SDP 中带 m=video） */
    private static final boolean VIDEO_ENABLED = boolCfg("video.enabled", true);
    private static final int VIDEO_WIDTH   = intCfg("video.width", 320);
    private static final int VIDEO_HEIGHT  = intCfg("video.height", 240);
    private static final int VIDEO_FPS     = intCfg("video.fps", 10);
    /** JPEG 质量：配置里用百分数，便于写整数 */
    private static final float VIDEO_QUALITY = intCfg("video.quality.percent", 50) / 100f;
    /** 本机视频 RTP 端口（会写进 SDP 告知对端） */
    private final int videoLocalPort = intCfg("video.local.port", 8020);

    private VideoCapture videoCapture;
    private RtpVideoSender videoSender;
    private RtpVideoReceiver videoReceiver;
    private DatagramSocket videoSocket;
    private VideoWindow videoWindow;
    private String remoteVideoIp;
    private int remoteVideoPort = 0;
    private volatile boolean videoRunning = false;
    /** 本次通话是否带视频（false = 纯语音通话） */
    private volatile boolean videoCall = false;

    /** 会议视频（拨 *6*N 时启用；音频仍由 SIP 会议室混音） */
    private volatile ConferenceVideo confVideo;
    /** 当前会议房间号（*6*1 中的 1）；非会议通话为 null */
    private volatile String conferenceRoom;
    /** 会议视频中继地址（默认就是 SIP 服务器那台机） */
    private static final String VIDEO_RELAY_HOST = cfg("video.relay.host", cfg("sip.server.ip", "127.0.0.1"));
    private static final int VIDEO_RELAY_PORT = intCfg("video.relay.port", 9100);

    // 媒体组件
    private AudioCapture audioCapture;
    private RTPAudioSender rtpSender;
    private RTPAudioReceiver rtpReceiver;

    private int localAudioPort = intCfg("media.local.port", 8000);
    private String remoteAudioIp;
    private int remoteAudioPort;
    /** 音频电平监视线程的运行标志 */
    private volatile boolean levelMonitorRunning = false;

    // ---------- 配置加载 ----------

    /** 读取项目根目录下的 config.properties（不存在则全部用默认值） */
    private static Properties loadConfig() {
        Properties p = new Properties();
        // 可用 -Dsip.config=xxx.properties 指定配置文件，
        // 便于在同一台机器上跑多个客户端（各自不同的账号/端口）做联调。
        String path = System.getProperty("sip.config", "config.properties");
        File f = new File(path);
        if (!f.exists()) {
            System.out.println("未找到 " + path + "，使用内置默认配置");
            return p;
        }
        try (InputStream in = new FileInputStream(f);
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
            System.out.println("已加载配置文件: " + f.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("读取 " + path + " 失败，改用默认配置: " + e.getMessage());
        }
        return p;
    }

    private static String cfg(String key, String def) {
        String v = CONFIG.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    private static int intCfg(String key, int def) {
        String v = CONFIG.getProperty(key);
        if (v == null || v.trim().isEmpty()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("配置项 " + key + " 不是合法整数，使用默认值 " + def);
            return def;
        }
    }

    private static boolean boolCfg(String key, boolean def) {
        String v = CONFIG.getProperty(key);
        if (v == null || v.trim().isEmpty()) return def;
        return Boolean.parseBoolean(v.trim());
    }

    public static void main(String[] args) {
        try {
            SipVideoCallClient client = new SipVideoCallClient();
            client.init();

            // 等待注册完成
            Thread.sleep(3000);

            if (client.isRegistered) {
                if (AUTO_INVITE) {
                    System.out.println("\n开始呼叫用户 " + client.targetUser + "...");
                    client.makeCall();
                } else {
                    System.out.println("\n已关闭自动呼叫（call.auto.invite=false）");
                }
                client.startChat();
            } else {
                System.out.println("注册失败，无法发起呼叫");
            }

            if (KEEP_ALIVE) {
                // 常驻模式：一直运行，直到用户在控制台输入 /quit（或按 Ctrl+C）
                System.out.println("\n[常驻模式] 程序将持续运行；输入 /quit 或按 Ctrl+C 退出");
                SHUTDOWN_LATCH.await();
                System.out.println("\n收到退出信号，正在关闭...");
            } else {
                // 非常驻：等通话（含自动挂断）结束后再退出
                Thread.sleep((CALL_DURATION_SEC + 60) * 1000L);
            }

            client.shutdown();

        } catch (Exception e) {
            e.printStackTrace();
        }

        // JAIN SIP 的定时器/事件线程是非守护线程，若不显式退出，
        // 进程会一直存活、占用本地端口，导致下次启动 BindException
        System.exit(0);
    }

    /** 停止媒体流并关闭 SIP 协议栈，释放本地端口 */
    public void shutdown() {
        if (fileCleanup != null) fileCleanup.shutdownNow();
        fileReceiver.close();
        try {
            stopMedia();
            if (sipStack != null) {
                sipStack.stop();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void init() throws Exception {
        SipFactory sipFactory = SipFactory.getInstance();
        sipFactory.setPathName("gov.nist");

        Properties properties = new Properties();
        properties.setProperty("javax.sip.STACK_NAME", "SipClient100");
        properties.setProperty("javax.sip.IP_ADDRESS", localIp);
        properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "16");
        properties.setProperty("gov.nist.javax.sip.DEBUG_LOG", "sipDebug.txt");
        properties.setProperty("gov.nist.javax.sip.SERVER_LOG", "sipServer.txt");

        sipStack = sipFactory.createSipStack(properties);
        headerFactory = sipFactory.createHeaderFactory();
        addressFactory = sipFactory.createAddressFactory();
        messageFactory = sipFactory.createMessageFactory();

        listeningPoint = sipStack.createListeningPoint(localIp, localPort, "udp");
        sipProvider = sipStack.createSipProvider(listeningPoint);
        sipProvider.addSipListener(this);

        System.out.println("SIP客户端初始化完成");
        System.out.println("本机账号: " + username + "    被叫账号: " + targetUser);
        System.out.println("本地地址: " + localIp + ":" + localPort);
        System.out.println("服务器地址: " + serverIp + ":" + serverPort);
        System.out.println("自动挂断: " + CALL_DURATION_SEC + " 秒");

        // 注册
        register();

        // 启动定时重新注册，避免 Expires 到期后失去联系
        startReRegister();
        startFileCleanup();
    }

    /**
     * 定时重新注册。
     * REGISTER 中的 Expires=3600（1 小时），若不续订，1 小时后服务器会认为本机离线。
     * 这里每隔 sip.register.interval.sec 秒重发一次 REGISTER（每次用新的 Call-ID，
     * 因此 CSeq 可以从 1 重新开始，服务端会以同 Contact 覆盖旧绑定）。
     */
    private void startReRegister() {
        int interval = intCfg("sip.register.interval.sec", 300);
        if (interval <= 0) {
            System.out.println("定时重新注册: 已关闭 (sip.register.interval.sec <= 0)");
            return;
        }
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(interval * 1000L);
                    System.out.println("\n[定时重新注册] 距上次注册 " + interval + " 秒，重新发送 REGISTER...");
                    register();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("定时重新注册失败: " + e.getMessage());
                }
            }
        }, "ReRegister");
        t.setDaemon(true);
        t.start();
        System.out.println("定时重新注册: 每 " + interval + " 秒一次");
    }

    // 注册到SIP服务器
    private void register() throws Exception {
        System.out.println("\n开始注册用户: " + username);

        String fromSipAddress = "sip:" + username + "@" + serverIp;
        Address fromAddress = addressFactory.createAddress(fromSipAddress);
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, String.valueOf(System.currentTimeMillis()));

        Address toAddress = addressFactory.createAddress(fromSipAddress);
        ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

        ViaHeader viaHeader = headerFactory.createViaHeader(localIp, localPort, "udp", null);
        List<ViaHeader> viaHeaders = new ArrayList<>();
        viaHeaders.add(viaHeader);

        CallIdHeader callIdHeader = sipProvider.getNewCallId();
        CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(1L, Request.REGISTER);
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);

        SipURI requestURI = addressFactory.createSipURI(null, serverIp);
        requestURI.setPort(serverPort);

        Request request = messageFactory.createRequest(requestURI, Request.REGISTER,
                callIdHeader, cSeqHeader, fromHeader, toHeader, viaHeaders, maxForwards);

        SipURI contactURI = addressFactory.createSipURI(username, localIp);
        contactURI.setPort(localPort);
        Address contactAddress = addressFactory.createAddress(contactURI);
        ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
        request.addHeader(contactHeader);

        ExpiresHeader expiresHeader = headerFactory.createExpiresHeader(3600);
        request.addHeader(expiresHeader);

        ClientTransaction registerTransaction = sipProvider.getNewClientTransaction(request);
        registerTransaction.sendRequest();

        System.out.println("REGISTER请求已发送");
    }

    // 带认证的注册
    private void registerWithAuth(Response challengeResponse) throws Exception {
        System.out.println("发送带认证信息的REGISTER");

        WWWAuthenticateHeader wwwAuthHeader = (WWWAuthenticateHeader) challengeResponse.getHeader(WWWAuthenticateHeader.NAME);
        String realm = wwwAuthHeader.getRealm();
        String nonce = wwwAuthHeader.getNonce();
        String algorithm = wwwAuthHeader.getAlgorithm();
        if (algorithm == null) algorithm = "MD5";

        String fromSipAddress = "sip:" + username + "@" + serverIp;
        Address fromAddress = addressFactory.createAddress(fromSipAddress);
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, String.valueOf(System.currentTimeMillis()));

        Address toAddress = addressFactory.createAddress(fromSipAddress);
        ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

        ViaHeader viaHeader = headerFactory.createViaHeader(localIp, localPort, "udp", null);
        List<ViaHeader> viaHeaders = new ArrayList<>();
        viaHeaders.add(viaHeader);

        CallIdHeader callIdHeader = sipProvider.getNewCallId();
        CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(2L, Request.REGISTER);
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);

        SipURI requestURI = addressFactory.createSipURI(null, serverIp);
        requestURI.setPort(serverPort);

        Request request = messageFactory.createRequest(requestURI, Request.REGISTER,
                callIdHeader, cSeqHeader, fromHeader, toHeader, viaHeaders, maxForwards);

        SipURI contactURI = addressFactory.createSipURI(username, localIp);
        contactURI.setPort(localPort);
        Address contactAddress = addressFactory.createAddress(contactURI);
        ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
        request.addHeader(contactHeader);

        ExpiresHeader expiresHeader = headerFactory.createExpiresHeader(3600);
        request.addHeader(expiresHeader);

        String uri = "sip:" + serverIp + ":" + serverPort;
        String response = calculateDigestResponse(username, realm, password, nonce, "REGISTER", uri, algorithm);

        AuthorizationHeader authHeader = headerFactory.createAuthorizationHeader("Digest");
        authHeader.setUsername(username);
        authHeader.setRealm(realm);
        authHeader.setNonce(nonce);
        authHeader.setURI(addressFactory.createURI(uri));
        authHeader.setResponse(response);
        authHeader.setAlgorithm(algorithm);
        request.addHeader(authHeader);

        ClientTransaction registerTransaction = sipProvider.getNewClientTransaction(request);
        registerTransaction.sendRequest();

        System.out.println("带认证的REGISTER已发送");
    }

    // 发起呼叫
    /** 发起通话（默认带视频，供命令行客户端使用） */
    public void makeCall() throws Exception {
        makeCall(true);
    }

    /**
     * 发起通话。
     *
     * @param video true = 视频通话（SDP 带 m=video）；false = 纯语音通话
     */
    public void makeCall(boolean video) throws Exception {
        // 拨 *6*N 视为进入会议室 N：音频走服务器会议室混音，视频走应用层中继
        Matcher roomMatcher = Pattern.compile("^\\*6\\*(\\d+)$").matcher(targetUser);
        this.conferenceRoom = roomMatcher.matches() ? roomMatcher.group(1) : null;
        this.videoCall = video && VIDEO_ENABLED;
        String fromSipAddress = "sip:" + username + "@" + serverIp;
        Address fromAddress = addressFactory.createAddress(fromSipAddress);
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, String.valueOf(System.currentTimeMillis()));

        String toSipAddress = "sip:" + targetUser + "@" + serverIp;
        Address toAddress = addressFactory.createAddress(toSipAddress);
        ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

        ViaHeader viaHeader = headerFactory.createViaHeader(localIp, localPort, "udp", null);
        List<ViaHeader> viaHeaders = new ArrayList<>();
        viaHeaders.add(viaHeader);

        CallIdHeader callIdHeader = sipProvider.getNewCallId();
        CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(1L, Request.INVITE);
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);

        SipURI requestURI = addressFactory.createSipURI(targetUser, serverIp);
        requestURI.setPort(serverPort);

        Request request = messageFactory.createRequest(requestURI, Request.INVITE,
                callIdHeader, cSeqHeader, fromHeader, toHeader, viaHeaders, maxForwards);

        SipURI contactURI = addressFactory.createSipURI(username, localIp);
        contactURI.setPort(localPort);
        Address contactAddress = addressFactory.createAddress(contactURI);
        ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
        request.addHeader(contactHeader);

        ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application", "sdp");

        String sdpData = buildLocalSdp();

        System.out.println("\n发出的SDP:\n" + sdpData);
        byte[] contents = sdpData.getBytes();
        request.setContent(contents, contentTypeHeader);

        inviteTransaction = sipProvider.getNewClientTransaction(request);
        inviteTransaction.sendRequest();

        System.out.println("INVITE请求已发送到: " + targetUser);
        markCallStart("OUT", targetUser);
        notifyCall("calling", targetUser);
    }

    /**
     * 呼叫任意号码：普通分机号（如 101），或会议室号码（如 *6*1）。
     *
     * <p>拨号后 {@code targetUser} 会切换为该号码，后续消息也随之发给它；
     * 想恢复点对点聊天，在网页左侧重新点选联系人即可。
     *
     * @param video  true = 视频通话；false = 纯语音通话
     * @param target 被叫号码
     */
    public void makeCall(boolean video, String target) throws Exception {
        if (target != null && !target.trim().isEmpty()) {
            this.targetUser = target.trim();
        }
        makeCall(video);
    }

    /** 记录通话开始（供后台统计） */
    private void markCallStart(String direction, String peer) {
        callStartMillis = System.currentTimeMillis();
        callPeer = peer;
        callDirection = direction;
        EventLog.log("CALL_START", direction, peer, "");
    }

    // 带认证的INVITE
    private void inviteWithAuth(Response challengeResponse, ClientTransaction originalTransaction) throws Exception {
        System.out.println("发送带认证信息的INVITE");

        ProxyAuthenticateHeader proxyAuthHeader = (ProxyAuthenticateHeader) challengeResponse.getHeader(ProxyAuthenticateHeader.NAME);
        WWWAuthenticateHeader wwwAuthHeader = (WWWAuthenticateHeader) challengeResponse.getHeader(WWWAuthenticateHeader.NAME);

        String realm, nonce, algorithm;
        boolean isProxy = false;

        if (proxyAuthHeader != null) {
            realm = proxyAuthHeader.getRealm();
            nonce = proxyAuthHeader.getNonce();
            algorithm = proxyAuthHeader.getAlgorithm();
            isProxy = true;
        } else if (wwwAuthHeader != null) {
            realm = wwwAuthHeader.getRealm();
            nonce = wwwAuthHeader.getNonce();
            algorithm = wwwAuthHeader.getAlgorithm();
        } else {
            System.out.println("无法获取认证头");
            return;
        }

        if (algorithm == null) algorithm = "MD5";

        sendAuthenticatedInvite(realm, nonce, algorithm, isProxy);
    }

    private void sendAuthenticatedInvite(String realm, String nonce, String algorithm, boolean isProxy) throws Exception {
        String fromSipAddress = "sip:" + username + "@" + serverIp;
        Address fromAddress = addressFactory.createAddress(fromSipAddress);
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, String.valueOf(System.currentTimeMillis()));

        String toSipAddress = "sip:" + targetUser + "@" + serverIp;
        Address toAddress = addressFactory.createAddress(toSipAddress);
        ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

        ViaHeader viaHeader = headerFactory.createViaHeader(localIp, localPort, "udp", null);
        List<ViaHeader> viaHeaders = new ArrayList<>();
        viaHeaders.add(viaHeader);

        CallIdHeader callIdHeader = sipProvider.getNewCallId();
        CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(2L, Request.INVITE);
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);

        SipURI requestURI = addressFactory.createSipURI(targetUser, serverIp);
        requestURI.setPort(serverPort);

        Request request = messageFactory.createRequest(requestURI, Request.INVITE,
                callIdHeader, cSeqHeader, fromHeader, toHeader, viaHeaders, maxForwards);

        SipURI contactURI = addressFactory.createSipURI(username, localIp);
        contactURI.setPort(localPort);
        Address contactAddress = addressFactory.createAddress(contactURI);
        ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
        request.addHeader(contactHeader);

        String uri = "sip:" + targetUser + "@" + serverIp + ":" + serverPort;
        String response = calculateDigestResponse(username, realm, password, nonce, "INVITE", uri, algorithm);

        if (isProxy) {
            ProxyAuthorizationHeader authHeader = headerFactory.createProxyAuthorizationHeader("Digest");
            authHeader.setUsername(username);
            authHeader.setRealm(realm);
            authHeader.setNonce(nonce);
            authHeader.setURI(addressFactory.createURI(uri));
            authHeader.setResponse(response);
            authHeader.setAlgorithm(algorithm);
            request.addHeader(authHeader);
        } else {
            AuthorizationHeader authHeader = headerFactory.createAuthorizationHeader("Digest");
            authHeader.setUsername(username);
            authHeader.setRealm(realm);
            authHeader.setNonce(nonce);
            authHeader.setURI(addressFactory.createURI(uri));
            authHeader.setResponse(response);
            authHeader.setAlgorithm(algorithm);
            request.addHeader(authHeader);
        }

        ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application", "sdp");
        String sdpData = buildLocalSdp();

        System.out.println("\n发出的SDP:\n" + sdpData);
        byte[] contents = sdpData.getBytes();
        request.setContent(contents, contentTypeHeader);

        inviteTransaction = sipProvider.getNewClientTransaction(request);
        inviteTransaction.sendRequest();

        System.out.println("带认证的INVITE已发送");
    }

    // 启动媒体流
    private void startMedia(String remoteSdp) {
        if (rtpReceiver != null) {
            // 已经在通话中：拨会议室时服务器还会回呼本机并再带一份 SDP，
            // 若重复建流会二次绑定同一端口（BindException）。此处直接忽略。
            System.out.println("媒体流已在运行，忽略重复的启动请求");
            return;
        }
        try {
            // 解析对方的SDP，获取IP和端口
            parseRemoteSDP(remoteSdp);
            System.out.println("\n=== 启动音频流 ===");
            System.out.println("本地音频端口: " + localAudioPort);
            System.out.println("远程音频地址: " + remoteAudioIp + ":" + remoteAudioPort);

            // 启动RTP接收器（绑定 localAudioPort，该端口已在上述 SDP 中宣告）
            rtpReceiver = new RTPAudioReceiver(localAudioPort);
            rtpReceiver.start();

            // 启动RTP发送器：复用接收器的同一个 socket，保证「收发同端口」。
            // 若用独立端口发送，对端做对称 RTP 校验时会丢弃数据包，导致单向/无音频。
            rtpSender = new RTPAudioSender(rtpReceiver.getSocket());
            rtpSender.setRemote(remoteAudioIp, remoteAudioPort);

            // 启动音频采集
            audioCapture = new AudioCapture();
            audioCapture.start((data, length) -> {
                // 将采集的音频通过RTP发送
                rtpSender.sendAudio(data, length);
            });

            System.out.println("✓ 音频流已启动，可以开始通话！");
            startLevelMonitor();
            if (conferenceRoom != null) startConferenceVideo();
            else startVideo();

        } catch (Exception e) {
            System.err.println("启动媒体流失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 构造本机 SDP（音频 + 可选视频），主叫和被叫共用 */
    private String buildLocalSdp() {
        return "v=0\r\n" +
                "o=" + username + " 123456 654321 IN IP4 " + localIp + "\r\n" +
                "s=Audio/Video Call\r\n" +
                "c=IN IP4 " + localIp + "\r\n" +
                "t=0 0\r\n" +
                "m=audio " + localAudioPort + " RTP/AVP 0 8 101\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=rtpmap:101 telephone-event/8000\r\n" +
                (videoCall ? videoSdpLine() : "");
    }

    /** 生成 SDP 的视频行（未启用视频时返回空串） */
    private String videoSdpLine() {
        if (!VIDEO_ENABLED) return "";
        return "m=video " + videoLocalPort + " RTP/AVP 26\r\n"
                + "a=rtpmap:26 JPEG/90000\r\n";
    }

    /** 启动视频通道：采集 -> JPEG -> RTP 发送；同时接收对端画面并显示 */
    private void startVideo() {
        if (!VIDEO_ENABLED) return;
        if (!videoCall) {
            System.out.println("本次为语音通话，不启用视频");
            return;
        }
        if (remoteVideoPort <= 0) {
            System.out.println("对端 SDP 未提供 m=video，本次通话不带视频");
            return;
        }
        try {
            System.out.println("\n=== 启动视频流 ===");
            System.out.println("本地视频端口: " + videoLocalPort);
            System.out.println("远程视频地址: " + remoteVideoIp + ":" + remoteVideoPort);

            videoSocket = new DatagramSocket(videoLocalPort);

            videoReceiver = new RtpVideoReceiver(videoSocket, jpeg -> {
                notifyVideo(jpeg, false);
                if (videoWindow != null) {
                    java.awt.image.BufferedImage img = VideoCodec.decode(jpeg);
                    if (img != null) videoWindow.updateRemote(img);
                }
            });
            videoReceiver.start();

            videoSender = new RtpVideoSender(videoSocket);
            videoSender.setRemote(InetAddress.getByName(remoteVideoIp), remoteVideoPort);
            videoSender.setFps(VIDEO_FPS);

            try {
                videoCapture = new VideoCapture(VIDEO_WIDTH, VIDEO_HEIGHT);
                videoCapture.start();
            } catch (Exception camEx) {
                // 摄像头打不开（例如被别的程序占用）时，保留"只看不发"的能力，
                // 而不是把整条视频通道拆掉——对端画面仍然能收能看。
                System.err.println("摄像头不可用（" + camEx.getMessage() + "），本次仅接收视频");
                videoCapture = null;
            }

            // 命令行模式弹出自带窗口；Web 模式画面由网页显示，不弹 Swing 窗口
            if (ui == null) {
                videoWindow = new VideoWindow(VIDEO_WIDTH, VIDEO_HEIGHT);
                videoWindow.showWindow();
            }

            videoRunning = true;
            startVideoLoop();
            System.out.println("✓ 视频流已启动（"
                    + VIDEO_WIDTH + "x" + VIDEO_HEIGHT + " @" + VIDEO_FPS + "fps）");
        } catch (Exception e) {
            System.err.println("启动视频失败: " + e.getMessage());
            stopVideo();
        }
    }

    /** 采集循环：按帧率抓帧 -> 编码 -> 发送，同时刷新本地预览 */
    private void startVideoLoop() {
        Thread t = new Thread(() -> {
            long interval = 1000L / Math.max(1, VIDEO_FPS);
            while (videoRunning) {
                long t0 = System.currentTimeMillis();
                try {
                    if (videoCapture != null) {
                        java.awt.image.BufferedImage img = videoCapture.grab();
                        if (img != null) {
                            if (videoWindow != null) videoWindow.updateLocal(img);
                            byte[] jpeg = VideoCodec.encode(img, VIDEO_QUALITY);
                            videoSender.sendFrame(jpeg);
                            notifyVideo(jpeg, true);
                        }
                    } else {
                        Thread.sleep(interval); // 无摄像头：只收不发
                    }
                } catch (Exception e) {
                    System.err.println("视频采集/编码失败: " + e.getMessage());
                }
                long sleep = interval - (System.currentTimeMillis() - t0);
                if (sleep > 0) {
                    try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
                }
            }
        }, "VideoLoop");
        t.setDaemon(true);
        t.start();
    }

    /** 停止视频并打印统计 */
    private void stopVideo() {
        if (!videoRunning && videoCapture == null && videoSocket == null) return;
        videoRunning = false;
        System.out.println("\n停止视频流...");

        long vs = (videoSender != null) ? videoSender.getFramesSent() : 0;
        long vr = (videoReceiver != null) ? videoReceiver.getFramesReceived() : 0;
        long vd = (videoReceiver != null) ? videoReceiver.getDroppedFrames() : 0;
        long fs = (videoSender != null) ? videoSender.getLastFrameSize() : 0;
        System.out.println("=== 视频收发统计 ===");
        System.out.println("  已发送帧: " + vs + "  (末帧 " + fs + " 字节)");
        System.out.println("  已接收帧: " + vr + "   丢弃: " + vd);
        if (vr == 0) System.out.println("  !! 未收到视频帧 —— 对端到你方向不通");
        else if (vs == 0) System.out.println("  !! 未发送视频帧 —— 摄像头可能没出图");
        else System.out.println("  OK 双向都有视频帧");

        if (videoCapture != null) { videoCapture.stop(); videoCapture = null; }
        if (videoReceiver != null) { videoReceiver.stop(); videoReceiver = null; }
        if (videoSocket != null) { videoSocket.close(); videoSocket = null; }
        videoSender = null;
        if (videoWindow != null) { videoWindow.closeWindow(); videoWindow = null; }
        System.out.println("视频流已停止");
    }

    /**
     * 启动会议视频：加入与会议室号同名的视频房间。
     * 音频仍走 SIP 会议室（服务器混音），视频走应用层中继，因此支持多人。
     */
    private void startConferenceVideo() {
        if (!VIDEO_ENABLED) return;
        final String room = conferenceRoom;
        try {
            confVideo = new ConferenceVideo(VIDEO_RELAY_HOST, VIDEO_RELAY_PORT,
                    room, username, VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS, VIDEO_QUALITY,
                    new ConferenceVideo.Listener() {
                        @Override
                        public void onLocalFrame(byte[] jpeg) {
                            notifyVideo(jpeg, true);
                        }

                        @Override
                        public void onRemoteFrame(String from, byte[] jpeg) {
                            notifyConferenceFrame(from, jpeg);
                        }

                        @Override
                        public void onPeerLeft(String from) {
                            notifyConferencePeerLeft(from);
                        }
                    });
            confVideo.start();
            notifyConferenceState(true, room);
        } catch (Exception e) {
            System.err.println("启动会议视频失败: " + e.getMessage());
            stopConferenceVideo();
        }
    }

    private void stopConferenceVideo() {
        if (confVideo != null) {
            confVideo.stop();
            confVideo = null;
            notifyConferenceState(false, conferenceRoom);
        }
    }

    /**
     * 每 2 秒打印一次上下行音频电平，用于客观判断音频是否真的有内容（不靠人耳）。
     * 电平 = 16bit PCM 的绝对平均值，范围 0~32767：
     *   静音 ≈ 0~300    正常说话 ≈ 500~8000    越大越响
     */
    private void startLevelMonitor() {
        levelMonitorRunning = true;
        Thread t = new Thread(() -> {
            System.out.println("\n[音频电平监视] 每 2 秒打印一次：你对手机说话看「上行」，手机说话看「下行」");
            while (levelMonitorRunning) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                }
                if (!levelMonitorRunning || rtpSender == null || rtpReceiver == null) continue;
                System.out.println("[音频电平]  上行(你说话)=" + rtpSender.getMicLevel()
                        + "   下行(对端说话)=" + rtpReceiver.getRecvLevel()
                        + "   收发包数=" + rtpSender.getPacketsSent() + "/" + rtpReceiver.getPacketsReceived());
            }
            System.out.println("[音频电平监视] 已停止");
        }, "LevelMonitor");
        t.setDaemon(true);
        t.start();
    }

    // 解析SDP获取远程媒体信息
    private void parseRemoteSDP(String sdp) {
        System.out.println("\n收到的SDP:");
        System.out.println(sdp);

        // 解析 c= 行获取IP
        Pattern cPattern = Pattern.compile("c=IN IP4 ([\\d.]+)");
        Matcher cMatcher = cPattern.matcher(sdp);
        if (cMatcher.find()) {
            remoteAudioIp = cMatcher.group(1);
        }

        // 解析 m=audio 行获取端口
        Pattern mPattern = Pattern.compile("m=audio (\\d+)");
        Matcher mMatcher = mPattern.matcher(sdp);
        if (mMatcher.find()) {
            remoteAudioPort = Integer.parseInt(mMatcher.group(1));
        }

        // 解析 m=video 行获取端口（对端可能不支持视频）
        remoteVideoPort = 0;
        Pattern vPattern = Pattern.compile("m=video (\\d+)");
        Matcher vMatcher = vPattern.matcher(sdp);
        if (vMatcher.find()) {
            remoteVideoPort = Integer.parseInt(vMatcher.group(1));
            remoteVideoIp = remoteAudioIp;   // 同一 SDP 里的 c= 行对音视频通用
        }

        System.out.println("解析结果 - IP: " + remoteAudioIp
                + ", 音频端口: " + remoteAudioPort
                + ", 视频端口: " + (remoteVideoPort > 0 ? remoteVideoPort : "无"));
    }

    // 停止媒体流
    private void stopMedia() {
        // 通话结束：记录时长供后台统计
        if (callStartMillis > 0) {
            long durSec = (System.currentTimeMillis() - callStartMillis) / 1000;
            EventLog.log("CALL_END", callDirection, callPeer, String.valueOf(durSec));
            notifyCall("ended", callPeer);
            callStartMillis = 0;
        }
        // 关键：清空 dialog，否则下一次通话会沿用上一次的 dialog，
        // 导致 BYE/ACK 发给上一次的对端（表现为对方收到挂断后画面卡住）
        dialog = null;
        inviteTransaction = null;
        System.out.println("\n停止音频流...");
        levelMonitorRunning = false;
        stopVideo();
        stopConferenceVideo();
        conferenceRoom = null;

        // 先打印收发统计：用于判断音频是否真的双向流动（不依赖人耳）
        long sent = (rtpSender != null) ? rtpSender.getPacketsSent() : 0;
        long recv = (rtpReceiver != null) ? rtpReceiver.getPacketsReceived() : 0;
        System.out.println("=== RTP 收发统计 ===");
        System.out.println("  已发送音频包: " + sent);
        System.out.println("  已接收音频包: " + recv);
        if (recv == 0) {
            System.out.println("  !! 未收到任何音频包 —— 对端到你方向不通");
        } else if (sent == 0) {
            System.out.println("  !! 未发送任何音频包 —— 麦克风可能没采集到数据");
        } else {
            System.out.println("  OK 双向都有音频包，媒体通道正常");
        }

        if (audioCapture != null) {
            audioCapture.stop();
            audioCapture = null;
        }
        if (rtpSender != null) {
            rtpSender.close();
            rtpSender = null;
        }
        if (rtpReceiver != null) {
            rtpReceiver.stop();
            rtpReceiver = null;
        }

        System.out.println("音频流已停止");
    }

    // 计算Digest认证响应
    private String calculateDigestResponse(String username, String realm, String password,
                                           String nonce, String method, String uri, String algorithm) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);

            String a1 = username + ":" + realm + ":" + password;
            byte[] ha1Bytes = md.digest(a1.getBytes());
            String ha1 = toHexString(ha1Bytes);

            String a2 = method + ":" + uri;
            md.reset();
            byte[] ha2Bytes = md.digest(a2.getBytes());
            String ha2 = toHexString(ha2Bytes);

            String a3 = ha1 + ":" + nonce + ":" + ha2;
            md.reset();
            byte[] responseBytes = md.digest(a3.getBytes());

            return toHexString(responseBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void sendAck(Response response) throws Exception {
        CSeqHeader cseq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        Request ackRequest = dialog.createAck(cseq.getSeqNumber());
        dialog.sendAck(ackRequest);
        System.out.println("ACK已发送");
    }

    public void hangup() throws Exception {
        if (dialog != null) {
            Request byeRequest = dialog.createRequest(Request.BYE);
            ClientTransaction byeTransaction = sipProvider.getNewClientTransaction(byeRequest);
            dialog.sendRequest(byeTransaction);
            System.out.println("BYE请求已发送");
        }
        stopMedia();
    }

    // ---------- 文字消息（SIP MESSAGE，RFC 3428）----------

    /** 向被叫发送一条文字消息 */
    public void sendMessage(String text) throws Exception {
        sendRaw(text);
        EventLog.log("MSG", "OUT", targetUser, "TEXT:" + text.length());
        notifyMessage(targetUser, text, true);
    }

    /** 当前对端分机 */
    public String getTargetUser() {
        return targetUser;
    }

    /** 切换对端分机（Web 端点头像/联系人时用） */
    public void setTargetUser(String user) {
        if (user != null && !user.trim().isEmpty()) {
            this.targetUser = user.trim();
        }
    }

    /** 用本机麦克风录一段固定时长的语音并发出（Web 端的快捷方式） */
    public void recordAndSendVoice(int seconds) throws Exception {
        if (isMediaActive()) throw new Exception("通话进行中，麦克风被占用，无法录制语音消息");
        if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("录制时长需在1~60秒之间");
        java.nio.file.Path temporary = Files.createTempFile("sip-voice-", ".wav");
        try {
            File recorded = VoiceIO.record(seconds, temporary.toFile());
            if (recorded == null) throw new Exception("没有采集到音频数据");
            keepAndSendRecording(recorded);
        } finally { Files.deleteIfExists(temporary); }
    }

    // ---------- 可随时开始/停止的录音（Web 端"语音"按钮）----------

    private VoiceIO.Recorder recorder;

    /** 开始录音；通话中或已在录音时抛异常 */
    public synchronized void startVoiceRecord() throws Exception {
        if (isMediaActive()) {
            throw new Exception("通话进行中，麦克风被占用，无法录制语音消息");
        }
        if (recorder != null) {
            throw new Exception("正在录音中");
        }
        VoiceIO.Recorder r = new VoiceIO.Recorder();
        r.start();
        recorder = r;
    }

    /** 停止录音并作为语音消息发出；返回保存的文件名 */
    public synchronized String stopVoiceRecordAndSend() throws Exception {
        if (recorder == null) throw new Exception("当前没有在录音");
        java.nio.file.Path temporary = Files.createTempFile("sip-voice-", ".wav");
        VoiceIO.Recorder current = recorder;
        recorder = null;
        try {
            File recorded = current.stop(temporary.toFile());
            if (recorded == null) throw new Exception("没有采集到音频数据");
            return keepAndSendRecording(recorded).getOriginalName();
        } finally { Files.deleteIfExists(temporary); }
    }

    private ReceivedFileStore.StoredFile keepAndSendRecording(File recorded) throws Exception {
        ReceivedFileStore.StoredFile saved;
        try (java.io.InputStream input = Files.newInputStream(recorded.toPath())) {
            saved = fileStore.save("voice-" + UUID.randomUUID().toString() + ".wav", input);
        }
        try {
            sendFile(saved.getPath().toFile(), "VOICE", saved.getOriginalName());
            return saved;
        } catch (Exception failure) {
            try { fileStore.deleteOwned(saved); } catch (java.io.IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** 是否正在录音 */
    public boolean isRecording() {
        return recorder != null;
    }

    /** 已录制的秒数 */
    public double recordedSeconds() {
        VoiceIO.Recorder r = recorder;
        return r == null ? 0 : r.seconds();
    }

    /** 向被叫发送一条 SIP MESSAGE，body 为消息体原文 */
    private void sendRaw(String body) throws Exception {
        sendRaw(body, targetUser);
    }

    private void sendRaw(String body, String recipient) throws Exception {
        String fromSipAddress = "sip:" + username + "@" + serverIp;
        Address fromAddress = addressFactory.createAddress(fromSipAddress);
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, String.valueOf(System.currentTimeMillis()));

        String toSipAddress = "sip:" + recipient + "@" + serverIp;
        Address toAddress = addressFactory.createAddress(toSipAddress);
        ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

        ViaHeader viaHeader = headerFactory.createViaHeader(localIp, localPort, "udp", null);
        List<ViaHeader> viaHeaders = new ArrayList<>();
        viaHeaders.add(viaHeader);

        CallIdHeader callIdHeader = sipProvider.getNewCallId();
        CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(messageCSeq.getAndIncrement(), Request.MESSAGE);
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);

        SipURI requestURI = addressFactory.createSipURI(recipient, serverIp);
        requestURI.setPort(serverPort);

        Request request = messageFactory.createRequest(requestURI, Request.MESSAGE,
                callIdHeader, cSeqHeader, fromHeader, toHeader, viaHeaders, maxForwards);

        SipURI contactURI = addressFactory.createSipURI(username, localIp);
        contactURI.setPort(localPort);
        Address contactAddress = addressFactory.createAddress(contactURI);
        request.addHeader(headerFactory.createContactHeader(contactAddress));

        // 声明可接收纯文本，便于对端回复
        request.addHeader(headerFactory.createAcceptHeader("text", "plain"));

        // SIP 消息体统一使用 UTF-8，避免中文乱码
        ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("text", "plain");
        request.setContent(body.getBytes(StandardCharsets.UTF_8), contentTypeHeader);

        ClientTransaction transaction = sipProvider.getNewClientTransaction(request);
        transaction.sendRequest();
    }

    // ---------- 文件消息（Base64 分片，走 SIP MESSAGE）----------
    //
    // 帧格式：#FILE|<类型>|<文件id>|<文件名Base64>|<片序号>|<总片数>|<Base64数据>
    //   类型：IMG = 图片，VOICE = 语音消息，VIDEO = 视频消息
    // 说明：这是本项目自定义的应用层分片协议，只有本项目客户端之间可以互通
    //       （手机端 Linphone 不认识这个格式）。

    /** 文件类型的中文名，用于日志 */
    private String typeName(String type) {
        if ("VOICE".equals(type)) return "语音消息";
        if ("IMG".equals(type)) return "图片";
        if ("VIDEO".equals(type)) return "视频消息";
        return "文件";
    }

    /** 从 From 头里取出分机号（如 sip:101@host → 101） */
    private static String peerOf(FromHeader from) {
        if (from == null) return "";
        try {
            javax.sip.address.URI uri = from.getAddress().getURI();
            if (uri instanceof SipURI) {
                String u = ((SipURI) uri).getUser();
                if (u != null) return u;
            }
        } catch (Exception ignore) {
            // 取不到就算了，不影响主流程
        }
        return "";
    }

    /** Send a local file using the unchanged wire format and a fixed recipient. */
    public void sendFile(File file, String type) throws Exception {
        if (file == null) throw new IllegalArgumentException("FILE_REQUIRED");
        sendFile(file, type, file.getName());
    }

    /** Upload storage names are private: the wire/UI retains the validated original display name. */
    public void sendFile(File file, String type, String originalName) throws Exception {
        if (file == null) throw new IllegalArgumentException("FILE_REQUIRED");
        final String recipient = targetUser;
        FileMessageSender.SentFile sent = FileMessageSender.send(file.toPath(), originalName, type,
                FILE_CHUNK_SIZE, recipient, (to, frame) -> {
                    sendRaw(frame, to);
                    try { Thread.sleep(30); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
                });
        System.out.println(typeName(type) + "分片已提交 SIP 发送（不代表对端完整接收），共 " + sent.getChunks() + " 片");
        EventLog.log("MSG", "OUT", recipient, type + ":" + sent.getSize());
        notifyFile(recipient, type, originalName, file.getAbsolutePath(), true);
    }

    private static boolean isFileFrame(byte[] raw) {
        byte[] prefix = "#FILE|".getBytes(StandardCharsets.US_ASCII);
        if (raw == null || raw.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (raw[i] != prefix[i]) return false;
        return true;
    }

    private int receiveFileFrame(byte[] raw, String peer) {
        FileTransferReceiver.Result result;
        String prefix = new String(raw, 0, Math.min(raw.length, 256), StandardCharsets.US_ASCII);
        if (raw.length > 8192) result = fileReceiver.rejectOversized(peer, prefix);
        else {
            try {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(raw)).toString();
                result = fileReceiver.accept(peer, text);
            } catch (java.nio.charset.CharacterCodingException invalidUtf8) {
                result = fileReceiver.rejectMalformed(peer, prefix);
            }
        }
        if (result.getSipStatus() >= 300) {
            System.err.println("[附件接收失败] " + result.getError());
            Ui current = ui;
            if (current != null) {
                try { current.onFileError(peer, result.getError()); }
                catch (RuntimeException failure) { System.err.println("File error UI failed: " + failure.getClass().getSimpleName()); }
            }
        } else if (result.isComplete()) {
            ReceivedFileStore.StoredFile saved = result.getStoredFile();
            File out = saved.getPath().toFile();
            String type = result.getType();
            EventLog.log("MSG", "IN", peer, type + ":" + saved.getSize());
            notifyFile(peer, type, saved.getOriginalName(), out.getAbsolutePath(), false);
            System.out.println("[附件接收完成] " + saved.getStorageName() + " bytes=" + saved.getSize()
                    + " sha256=" + saved.getSha256());
            if ("VOICE".equals(type)) {
                lastVoiceFile = out;
                Thread player = new Thread(() -> VoiceIO.play(out), "VoicePlay");
                player.setDaemon(true); player.start();
            } else if ("VIDEO".equals(type)) {
                lastVideoFile = out;
                // Never Desktop.open an untrusted VIDEO: the declared type could
                // contain an executable. Web playback/manual opening stays available.
                System.out.println("[视频消息] 已安全保存，请在网页中播放或自行确认后打开。");
            }
        }
        return result.getSipStatus();
    }

    private synchronized void startFileCleanup() {
        if (fileCleanup != null) return;
        fileCleanup = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "FileReceiveCleanup"); thread.setDaemon(true); return thread;
        });
        fileCleanup.scheduleWithFixedDelay(() -> {
            try { fileReceiver.expire(); }
            catch (RuntimeException failure) { System.err.println("File cleanup failed: " + failure.getClass().getSimpleName()); }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** 当前是否有媒体流在跑（即通话进行中） */
    private boolean isMediaActive() {
        return rtpSender != null || rtpReceiver != null;
    }

    /** 启动控制台聊天：输入一行回车即发送，同时接收并显示对方消息 */
    private void startChat() {
        Thread t = new Thread(() -> {
            System.out.println("\n===== 消息功能已就绪（文字 / 图片 / 语音 / 视频）=====");
            System.out.println("输入内容后回车         → 发送文字给 " + targetUser);
            System.out.println("输入 /img <图片路径>    → 发送图片");
            System.out.println("输入 /video <视频路径>  → 发送视频消息");
            System.out.println("输入 /rec [秒数]        → 录一段语音消息并发送（默认 "
                    + VOICE_DURATION_SEC + " 秒）");
            System.out.println("输入 /play              → 重放最近收到的语音消息");
            System.out.println("输入 /quit              → 退出程序");
            try (Scanner sc = new Scanner(System.in)) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine().trim();
                    if (line.isEmpty()) continue;
                    if (line.equals("/quit")) {
                        System.out.println("收到退出指令，正在关闭...");
                        SHUTDOWN_LATCH.countDown();
                        break;
                    }
                    if (line.startsWith("/img ")) {
                        if (!isRegistered) {
                            System.out.println("尚未注册成功，无法发送");
                            continue;
                        }
                        try {
                            sendFile(new File(line.substring(5).trim()), "IMG");
                        } catch (Exception e) {
                            System.err.println("图片发送失败: " + e.getMessage());
                        }
                        continue;
                    }
                    if (line.startsWith("/video ")) {
                        if (!isRegistered) {
                            System.out.println("尚未注册成功，无法发送");
                            continue;
                        }
                        try {
                            sendFile(new File(line.substring(7).trim()), "VIDEO");
                        } catch (Exception e) {
                            System.err.println("视频发送失败: " + e.getMessage());
                        }
                        continue;
                    }
                    if (line.startsWith("/rec")) {
                        if (isMediaActive()) {
                            System.out.println("通话进行中，麦克风正被占用，无法录制语音消息");
                            continue;
                        }
                        if (!isRegistered) {
                            System.out.println("尚未注册成功，无法发送");
                            continue;
                        }
                        int sec = VOICE_DURATION_SEC;
                        String arg = line.substring(4).trim();
                        if (!arg.isEmpty()) {
                            try {
                                sec = Integer.parseInt(arg);
                            } catch (NumberFormatException e) {
                                System.out.println("用法：/rec [秒数]，例如 /rec 8");
                                continue;
                            }
                        }
                        if (sec <= 0 || sec > 60) {
                            System.out.println("录制时长需在 1~60 秒之间");
                            continue;
                        }
                        try {
                            recordAndSendVoice(sec);
                        } catch (Exception e) {
                            System.err.println("语音消息发送失败: " + e.getMessage());
                        }
                        continue;
                    }
                    if (line.equals("/play")) {
                        if (lastVoiceFile == null) {
                            System.out.println("还没有收到过语音消息");
                        } else {
                            VoiceIO.play(lastVoiceFile);
                        }
                        continue;
                    }
                    if (!isRegistered) {
                        System.out.println("尚未注册成功，无法发送");
                        continue;
                    }
                    try {
                        sendMessage(line);
                        System.out.println("[我 → " + targetUser + "] " + line);
                    } catch (Exception e) {
                        System.err.println("消息发送失败: " + e.getMessage());
                    }
                }
            }
        }, "ConsoleChat");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void processRequest(RequestEvent requestEvent) {
        Request request = requestEvent.getRequest();
        ServerTransaction serverTransaction = requestEvent.getServerTransaction();

        System.out.println("\n收到请求: " + request.getMethod());

        try {
            if (request.getMethod().equals(Request.BYE)) {
                if (serverTransaction == null) {
                    serverTransaction = sipProvider.getNewServerTransaction(request);
                }
                Response response = messageFactory.createResponse(200, request);
                serverTransaction.sendResponse(response);
                System.out.println("通话已结束");
                stopMedia();
            } else if (request.getMethod().equals(Request.MESSAGE)) {
                if (serverTransaction == null) {
                    serverTransaction = sipProvider.getNewServerTransaction(request);
                }
                byte[] raw = request.getRawContent();
                FromHeader msgFrom = (FromHeader) request.getHeader(FromHeader.NAME);
                String peer = peerOf(msgFrom);
                if (isFileFrame(raw)) {
                    int status = receiveFileFrame(raw, peer);
                    serverTransaction.sendResponse(messageFactory.createResponse(status, request));
                    return;
                } else {
                    String text = (raw == null) ? "" : new String(raw, StandardCharsets.UTF_8);
                    String from = (msgFrom != null) ? msgFrom.getAddress().toString() : "未知";
                    System.out.println("\n[收到消息] 来自 " + from + "：" + text);
                    EventLog.log("MSG", "IN", peer, "TEXT:" + text.length());
                    notifyMessage(peer, text, false);
                }
                Response response = messageFactory.createResponse(200, request);
                serverTransaction.sendResponse(response);
            } else if (request.getMethod().equals(Request.INVITE)) {
                handleIncomingInvite(request, serverTransaction);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 处理来电：自动接听。
     *
     * <p>流程：100 Trying -> 180 Ringing -> 200 OK（携带本机 SDP），
     * 然后把对端 SDP 交给 startMedia 建立音频与视频通道。
     *
     * <p>本项目定位是课程演示客户端，所以采用"自动接听"而不是弹窗确认。
     */
    private void handleIncomingInvite(Request request, ServerTransaction tx) throws Exception {
        if (tx == null) {
            tx = sipProvider.getNewServerTransaction(request);
        }

        tx.sendResponse(messageFactory.createResponse(Response.TRYING, request));

        FromHeader from = (FromHeader) request.getHeader(FromHeader.NAME);
        System.out.println("\n[来电] 来自 " + ((from != null) ? from.getAddress() : "未知")
                + "，自动接听...");
        notifyCall("incoming", peerOf(from));

        // Contact 头：告知对端本机的可达地址
        SipURI contactURI = addressFactory.createSipURI(username, localIp);
        contactURI.setPort(localPort);
        Address contactAddress = addressFactory.createAddress(contactURI);

        Response ringing = messageFactory.createResponse(Response.RINGING, request);
        ringing.addHeader(headerFactory.createContactHeader(contactAddress));
        tx.sendResponse(ringing);

        // 先看主叫 SDP 里有没有 m=video，决定本次是否带视频
        byte[] offer = request.getRawContent();
        String remoteSdp = (offer == null) ? "" : new String(offer, StandardCharsets.UTF_8);
        videoCall = VIDEO_ENABLED && remoteSdp.contains("m=video");

        // 200 OK + 本机 SDP
        Response ok = messageFactory.createResponse(Response.OK, request);
        ok.addHeader(headerFactory.createContactHeader(contactAddress));
        ContentTypeHeader ct = headerFactory.createContentTypeHeader("application", "sdp");
        String localSdp = buildLocalSdp();
        System.out.println("\n发出的SDP:\n" + localSdp);
        ok.setContent(localSdp.getBytes(StandardCharsets.UTF_8), ct);
        tx.sendResponse(ok);

        if (dialog == null) {
            dialog = tx.getDialog();
        }
        System.out.println("✓ 已接听，通话建立成功!");
        markCallStart("IN", peerOf(from));
        notifyCall("active", peerOf(from));

        // 对端 SDP -> 建立媒体（音频 + 视频）
        byte[] raw = request.getRawContent();
        if (raw != null && raw.length > 0) {
            startMedia(new String(raw, StandardCharsets.UTF_8));
        }

        // 达到设定通话时长后自动挂断
        new Thread(() -> {
            try {
                Thread.sleep(CALL_DURATION_SEC * 1000L);
                System.out.println("\n通话达到 " + CALL_DURATION_SEC + " 秒，自动挂断...");
                hangup();
            } catch (Exception e) {
                // 对方提前挂断时会走到这里，属正常
            }
        }, "AutoHangupCallee").start();
    }

    @Override
    public void processResponse(ResponseEvent responseEvent) {
        Response response = responseEvent.getResponse();
        int statusCode = response.getStatusCode();
        CSeqHeader cseq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        ClientTransaction clientTransaction = responseEvent.getClientTransaction();

        System.out.println("\n收到响应: " + statusCode + " " + response.getReasonPhrase() +
                " (CSeq: " + cseq.getMethod() + ")");

        try {
            if (cseq.getMethod().equals(Request.REGISTER)) {
                if (statusCode == Response.OK) {
                    System.out.println("✓ 注册成功!");
                    isRegistered = true;
                } else if (statusCode == Response.UNAUTHORIZED) {
                    System.out.println("需要认证，重新发送带认证的REGISTER");
                    registerWithAuth(response);
                }
            } else if (cseq.getMethod().equals(Request.INVITE)) {
                if (statusCode == Response.TRYING) {
                    System.out.println("服务器正在处理呼叫...");
                } else if (statusCode == Response.RINGING) {
                    System.out.println("对方振铃中...");
                    notifyCall("ringing", targetUser);
                } else if (statusCode == Response.OK) {
                    System.out.println("✓ 对方接听! 通话建立成功!");
                    notifyCall("active", targetUser);

                    if (dialog == null && clientTransaction != null) {
                        dialog = clientTransaction.getDialog();
                    }

                    sendAck(response);

                    // 启动音频流
                    if (response.getContent() != null) {
                        String remoteSdp = new String((byte[]) response.getContent());
                        startMedia(remoteSdp);
                    }

                    // 达到设定通话时长后自动挂断
                    new Thread(() -> {
                        try {
                            Thread.sleep(CALL_DURATION_SEC * 1000L);
                            System.out.println("\n通话达到 " + CALL_DURATION_SEC + " 秒，自动挂断...");
                            hangup();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();

                } else if (statusCode == Response.UNAUTHORIZED || statusCode == Response.PROXY_AUTHENTICATION_REQUIRED) {
                    System.out.println("INVITE需要认证，重新发送");
                    inviteWithAuth(response, clientTransaction);
                } else if (statusCode == Response.BUSY_HERE) {
                    System.out.println("对方忙");
                } else if (statusCode == Response.DECLINE) {
                    System.out.println("对方拒绝");
                } else if (statusCode >= 400) {
                    System.out.println("呼叫失败: " + statusCode);
                }
            } else if (cseq.getMethod().equals(Request.BYE)) {
                if (statusCode == Response.OK) {
                    System.out.println("通话已正常结束");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void processTimeout(TimeoutEvent timeoutEvent) {
        System.out.println("请求超时");

        // 注册超时通常意味着服务器刚重启或暂时不可达。
        // 这里 30 秒后立即重试，服务器一恢复就能很快重新注册上，
        // 而不用干等到下一个重注册周期。
        try {
            ClientTransaction tx = timeoutEvent.getClientTransaction();
            if (tx != null && tx.getRequest() != null
                    && Request.REGISTER.equals(tx.getRequest().getMethod())) {
                System.out.println("[注册超时] 30 秒后自动重试注册...");
                Thread retry = new Thread(() -> {
                    try {
                        Thread.sleep(30000);
                        register();
                    } catch (Exception e) {
                        System.err.println("重试注册失败: " + e.getMessage());
                    }
                }, "RegisterRetry");
                retry.setDaemon(true);
                retry.start();
            }
        } catch (Exception e) {
            // 拿不到事务信息时忽略，不影响主流程
        }
    }

    @Override
    public void processIOException(IOExceptionEvent exceptionEvent) {
        System.out.println("IO异常: " + exceptionEvent);
    }

    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent transactionTerminatedEvent) {
        // 正常事务终止，不输出
    }

    @Override
    public void processDialogTerminated(DialogTerminatedEvent dialogTerminatedEvent) {
        System.out.println("对话终止");
    }
}

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
import java.util.concurrent.ConcurrentHashMap;
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

    private volatile String targetUser = cfg("sip.target.user",  "101");

    /** 通话时长（秒），到时自动挂断 */
    private static final int CALL_DURATION_SEC = intCfg("call.duration.sec", 180);

    /** 启动后是否自动呼叫被叫（测文字消息时建议设为 false） */
    private static final boolean AUTO_INVITE = boolCfg("call.auto.invite", true);

    /** 常驻模式：true = 程序持续运行直到 /quit 或 Ctrl+C；false = 跑完固定时长自动退出 */
    private static final boolean KEEP_ALIVE = boolCfg("app.keep.alive", true);

    /** 常驻模式下阻塞主线程，收到 /quit 时释放，让主线程走正常的关闭流程 */
    private static final java.util.concurrent.CountDownLatch SHUTDOWN_LATCH =
            new java.util.concurrent.CountDownLatch(1);

    /** SIP objects belong to one immutable lifecycle identity, never to the next call. */
    private final CallLifecycleManager calls;
    private final LinkedHashMap<String, SipCall> sipCalls = new LinkedHashMap<>();
    private final LinkedHashMap<String, Boolean> orphanEndedDialogs = new LinkedHashMap<>();
    private volatile CallSession mediaSession;
    private CallSession cleanupSession;
    private volatile boolean shuttingDown;
    private volatile Thread reRegisterThread;
    private Thread videoLoopThread;
    private Thread levelMonitorThread;

    public SipVideoCallClient() { this(new CallLifecycleManager()); }

    SipVideoCallClient(CallLifecycleManager calls) {
        this.calls = Objects.requireNonNull(calls, "calls");
    }

    private static final class SipCall {
        final CallSession session;
        final String localTag;
        final String remoteTag;
        final Set<Long> inviteSequences = new HashSet<>();
        final Set<String> terminatedDialogs = new HashSet<>();
        ClientTransaction inviteTx;
        ServerTransaction incomingTx;
        Request incomingRequest;
        Response finalResponse;
        Dialog dialog;
        String acceptedRemoteTag;
        String remoteSdp;
        boolean provisional;
        boolean cancelWanted;
        boolean cancelSent;
        boolean finalSent;
        boolean answerSent;
        boolean incomingAckReceived;
        boolean endedNotified;
        int authenticationAttempts;

        SipCall(CallSession session, String localTag, String remoteTag) {
            this.session = session;
            this.localTag = localTag;
            this.remoteTag = remoteTag;
            inviteSequences.add(session.getInviteCSeq());
        }
    }

    private void remember(SipCall call) {
        sipCalls.put(call.session.getCallId(), call);
        while (sipCalls.size() > 64) {
            Iterator<Map.Entry<String, SipCall>> it = sipCalls.entrySet().iterator();
            while (it.hasNext()) {
                SipCall old = it.next().getValue();
                if (old != call && !calls.isActive(old.session)) { it.remove(); break; }
            }
        }
    }

    private static String callLabel(CallSession session) {
        if (session == null) return "[call no-media-owner] ";
        return "[call " + session.getId().substring(0, 8)
                + " Call-ID=" + logValue(session.getCallId())
                + " seq=" + session.getInviteCSeq()
                + " state=" + session.getState()
                + " peer=" + logValue(session.getPeer()) + "] ";
    }

    private static String logValue(String value) {
        if (value == null) return "-";
        String safe = value.replace('\r', '_').replace('\n', '_');
        return safe.length() > 200 ? safe.substring(0, 200) : safe;
    }

    private static void logCall(SipCall call, String event) {
        System.out.println(callLabel(call.session) + event);
    }

    private static String callId(Message message) {
        CallIdHeader h = (CallIdHeader) message.getHeader(CallIdHeader.NAME);
        return h == null ? null : h.getCallId();
    }

    private static String fromTag(Message message) {
        FromHeader h = (FromHeader) message.getHeader(FromHeader.NAME);
        return h == null ? null : h.getTag();
    }

    private static String toTag(Message message) {
        ToHeader h = (ToHeader) message.getHeader(ToHeader.NAME);
        return h == null ? null : h.getTag();
    }

    private static long sequence(Message message) {
        CSeqHeader h = (CSeqHeader) message.getHeader(CSeqHeader.NAME);
        return h == null ? -1 : h.getSeqNumber();
    }

    private static String branch(Request request) {
        ViaHeader h = (ViaHeader) request.getHeader(ViaHeader.NAME);
        return h == null ? null : h.getBranch();
    }

    private static boolean tagEquals(String expected, String actual) {
        return expected != null && expected.equals(actual);
    }

    private boolean ownsRequest(SipCall call, Request request) {
        if (call == null || !Objects.equals(call.session.getCallId(), callId(request))) return false;
        String remote = call.session.getDirection() == CallSession.Direction.OUTGOING
                ? call.acceptedRemoteTag : call.remoteTag;
        return tagEquals(remote, fromTag(request)) && tagEquals(call.localTag, toTag(request));
    }

    private static boolean sameDialog(Dialog a, Dialog b) {
        if (a == null || b == null) return false;
        if (a == b) return true;
        return a.getCallId() != null && b.getCallId() != null
                && Objects.equals(a.getCallId().getCallId(), b.getCallId().getCallId())
                && tagEquals(a.getLocalTag(), b.getLocalTag())
                && tagEquals(a.getRemoteTag(), b.getRemoteTag());
    }

    private boolean matchesResponse(SipCall call, Response response) {
        return call != null && call.session.getDirection() == CallSession.Direction.OUTGOING
                && Objects.equals(call.session.getCallId(), callId(response))
                && tagEquals(call.localTag, fromTag(response))
                && call.inviteSequences.contains(sequence(response));
    }
    private boolean isRegistered = false;

    /** 当前通话的开始时间 / 对端 / 方向，用于通话结束时统计时长（0 表示当前无通话） */
    private volatile long callStartMillis = 0;
    private CallSession loggedSession;
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
    private long messageCSeq = 1;

    /**
     * 文件分片大小（Base64 字符数），图片与语音消息共用。
     * 实测：单个 SIP MESSAGE 的 Content-Length 超过 1000 左右就会被丢弃
     * （总 UDP 载荷超过以太网 MTU 1472 字节后产生 IP 分片，分片包被链路丢弃）。
     * 因此这里保守取 800，保证加上约 300 字节 SIP 头后仍不分片。
     */
    private final int FILE_CHUNK_SIZE = intCfg("im.file.chunk.size", 800);
    /** 文件重组缓冲：文件id -> 分片数组（未到达的位置为 null） */
    private final Map<String, List<String>> fileChunks = new ConcurrentHashMap<>();
    /** 文件名（Base64 编码）：文件id -> nameB64 */
    private final Map<String, String> fileNames = new ConcurrentHashMap<>();
    /** 文件类型：文件id -> IMG / VOICE */
    private final Map<String, String> fileTypes = new ConcurrentHashMap<>();

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

    /** Release this client's session, tasks and SIP stack without affecting another instance. */
    public void shutdown() {
        synchronized (calls) {
            if (shuttingDown) return;
            shuttingDown = true;
            CallSession active = calls.active();
            if (active != null) {
                SipCall call = sipCalls.get(active.getCallId());
                try { endLocal(call, "shutdown"); }
                catch (Exception e) { finishCall(call, "shutdown"); }
            }
            if (mediaSession != null) stopMedia();
            calls.close();
        }
        Thread registerThread = reRegisterThread;
        if (registerThread != null) registerThread.interrupt();
        if (sipStack != null) sipStack.stop();
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
            while (!shuttingDown && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(interval * 1000L);
                    System.out.println("\n[定时重新注册] 距上次注册 " + interval + " 秒，重新发送 REGISTER...");
                    if (!shuttingDown) register();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("定时重新注册失败: " + e.getMessage());
                }
            }
        }, "ReRegister");
        t.setDaemon(true);
        reRegisterThread = t;
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
        synchronized (calls) { beginOutgoingCall(video, targetUser); }
    }

    /** The selected contact may change later; the SIP session keeps this peer snapshot. */
    public void makeCall(boolean video, String target) throws Exception {
        synchronized (calls) {
            String peer = target == null || target.trim().isEmpty() ? targetUser : target.trim();
            beginOutgoingCall(video, peer);
            targetUser = peer;
        }
    }

    private void beginOutgoingCall(boolean video, String peer) throws Exception {
        if (shuttingDown) throw new IllegalStateException("Client is shutting down");
        if (calls.active() != null) throw new IllegalStateException("Another call is active");
        Matcher roomMatcher = Pattern.compile("^\\*6\\*(\\d+)$").matcher(peer);
        String room = roomMatcher.matches() ? roomMatcher.group(1) : null;
        boolean withVideo = video && VIDEO_ENABLED;
        String localTag = UUID.randomUUID().toString();
        Address from = addressFactory.createAddress("sip:" + username + "@" + serverIp);
        Address to = addressFactory.createAddress("sip:" + peer + "@" + serverIp);
        List<ViaHeader> vias = new ArrayList<>();
        vias.add(headerFactory.createViaHeader(localIp, localPort, "udp", null));
        CallIdHeader id = sipProvider.getNewCallId();
        SipURI uri = addressFactory.createSipURI(peer, serverIp);
        uri.setPort(serverPort);
        Request request = messageFactory.createRequest(uri, Request.INVITE, id,
                headerFactory.createCSeqHeader(1L, Request.INVITE),
                headerFactory.createFromHeader(from, localTag),
                headerFactory.createToHeader(to, null), vias, headerFactory.createMaxForwardsHeader(70));
        request.addHeader(localContact());
        request.setContent(buildLocalSdp(withVideo).getBytes(StandardCharsets.UTF_8),
                headerFactory.createContentTypeHeader("application", "sdp"));
        ClientTransaction transaction = sipProvider.getNewClientTransaction(request);
        CallSession session = calls.beginOutgoing(id.getCallId(), 1L, transaction.getBranchId(), peer, withVideo, room);
        SipCall call = new SipCall(session, localTag, null);
        remember(call);
        logCall(call, "outgoing INVITE created");
        try {
            call.inviteTx = transaction;
            notifyCall("calling", session.getPeer());
            armInviteDeadline(call);
            call.inviteTx.sendRequest();
        } catch (Exception e) {
            finishCall(call, "invite-send-failed");
            throw e;
        }
    }

    private ContactHeader localContact() throws Exception {
        SipURI uri = addressFactory.createSipURI(username, localIp);
        uri.setPort(localPort);
        return headerFactory.createContactHeader(addressFactory.createAddress(uri));
    }

    private void armInviteDeadline(SipCall call) {
        calls.armTimer(call.session, CallLifecycleManager.TimerKind.INVITE_DEADLINE,
                Math.max(1, intCfg("call.invite.timeout.sec", 64)) * 1000L,
                () -> {
                    synchronized (calls) {
                        if (!calls.isActive(call.session)) return;
                        try { endLocal(call, "invite-timeout"); }
                        catch (Exception e) { finishCall(call, "invite-timeout"); }
                    }
                });
    }

    /** 记录通话开始（供后台统计） */
    private void markCallStart(CallSession session) {
        loggedSession = session;
        String direction = session.getDirection() == CallSession.Direction.OUTGOING ? "OUT" : "IN";
        String peer = session.getPeer();
        callStartMillis = System.currentTimeMillis();
        callPeer = peer;
        callDirection = direction;
        EventLog.log("CALL_START", direction, peer, "");
    }

    // Preserve Call-ID, From tag, URI and SDP when retrying the same INVITE with credentials.
    private void inviteWithAuth(Response challengeResponse, SipCall call) throws Exception {
        if (call.authenticationAttempts >= 2 || call.inviteTx == null) {
            throw new IllegalStateException("INVITE authentication retry limit reached");
        }
        ProxyAuthenticateHeader proxy = (ProxyAuthenticateHeader) challengeResponse.getHeader(ProxyAuthenticateHeader.NAME);
        WWWAuthenticateHeader www = (WWWAuthenticateHeader) challengeResponse.getHeader(WWWAuthenticateHeader.NAME);
        if (proxy == null && www == null) throw new IllegalArgumentException("Missing authentication challenge");
        String realm = proxy != null ? proxy.getRealm() : www.getRealm();
        String nonce = proxy != null ? proxy.getNonce() : www.getNonce();
        String algorithm = proxy != null ? proxy.getAlgorithm() : www.getAlgorithm();
        if (algorithm == null) algorithm = "MD5";
        Request request = (Request) call.inviteTx.getRequest().clone();
        request.removeHeader(AuthorizationHeader.NAME);
        request.removeHeader(ProxyAuthorizationHeader.NAME);
        long next = sequence(request) + 1L;
        ((CSeqHeader) request.getHeader(CSeqHeader.NAME)).setSeqNumber(next);
        ((ViaHeader) request.getHeader(ViaHeader.NAME)).removeParameter("branch");
        String uri = request.getRequestURI().toString();
        String digest = calculateDigestResponse(username, realm, password, nonce, Request.INVITE, uri, algorithm);
        if (digest == null) throw new IllegalArgumentException("Unsupported authentication algorithm");
        AuthorizationHeader auth = proxy != null
                ? headerFactory.createProxyAuthorizationHeader("Digest")
                : headerFactory.createAuthorizationHeader("Digest");
        auth.setUsername(username);
        auth.setRealm(realm);
        auth.setNonce(nonce);
        auth.setURI(request.getRequestURI());
        auth.setResponse(digest);
        auth.setAlgorithm(algorithm);
        request.addHeader(auth);
        ClientTransaction replacement = sipProvider.getNewClientTransaction(request);
        call.authenticationAttempts++;
        call.inviteSequences.add(next);
        call.inviteTx = replacement;
        call.provisional = false;
        calls.bindInvite(call.session, next, replacement.getBranchId());
        replacement.sendRequest();
        logCall(call, "authenticated INVITE retry sent");
    }

    // One media owner is reserved by the lifecycle manager before calling this method.
    // Package visibility allows device-free SIP adapter tests, without a production test mode.
    void startMedia(String remoteSdp) {
        if (rtpReceiver != null) throw new IllegalStateException("Media is already allocated");
        final CallSession owner = mediaSession;
        try {
            parseRemoteSDP(remoteSdp);
            if (remoteAudioIp == null || remoteAudioPort < 1 || remoteAudioPort > 65535)
                throw new IllegalArgumentException("Invalid remote audio endpoint");
            rtpReceiver = new RTPAudioReceiver(localAudioPort);
            rtpReceiver.start();
            rtpSender = new RTPAudioSender(rtpReceiver.getSocket());
            rtpSender.setRemote(remoteAudioIp, remoteAudioPort);
            final RTPAudioSender sender = rtpSender;
            audioCapture = new AudioCapture();
            audioCapture.start((data, length) -> {
                if (mediaSession == owner) sender.sendAudio(data, length);
            });
            startLevelMonitor();
            if (conferenceRoom != null) startConferenceVideo();
            else startVideo();
        } catch (Exception failure) {
            // activateMedia unwinds the whole partially-created owner, then sends BYE.
            throw new IllegalStateException("Could not start call media", failure);
        }
    }

    /** 构造本机 SDP（音频 + 可选视频），主叫和被叫共用 */
    private String buildLocalSdp(boolean withVideo) {
        return "v=0\r\n" +
                "o=" + username + " 123456 654321 IN IP4 " + localIp + "\r\n" +
                "s=Audio/Video Call\r\n" +
                "c=IN IP4 " + localIp + "\r\n" +
                "t=0 0\r\n" +
                "m=audio " + localAudioPort + " RTP/AVP 0 8 101\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=rtpmap:101 telephone-event/8000\r\n" +
                (withVideo ? videoSdpLine() : "");
    }

    /** 生成 SDP 的视频行（未启用视频时返回空串） */
    private String videoSdpLine() {
        if (!VIDEO_ENABLED) return "";
        return "m=video " + videoLocalPort + " RTP/AVP 26\r\n"
                + "a=rtpmap:26 JPEG/90000\r\n";
    }

    /** 启动视频通道：采集 -> JPEG -> RTP 发送；同时接收对端画面并显示 */
    private void startVideo() throws Exception {
        if (!VIDEO_ENABLED || !videoCall || remoteVideoPort <= 0) return;
        final CallSession owner = mediaSession;
        try {
            videoSocket = new DatagramSocket(videoLocalPort);
            if (ui == null) {
                videoWindow = new VideoWindow(VIDEO_WIDTH, VIDEO_HEIGHT);
                videoWindow.showWindow();
            }
            final VideoWindow window = videoWindow;
            videoReceiver = new RtpVideoReceiver(videoSocket, jpeg -> {
                if (mediaSession != owner) return;
                notifyVideo(jpeg, false);
                if (window != null) {
                    java.awt.image.BufferedImage image = VideoCodec.decode(jpeg);
                    if (image != null && mediaSession == owner) window.updateRemote(image);
                }
            });
            videoReceiver.start();
            videoSender = new RtpVideoSender(videoSocket);
            videoSender.setRemote(InetAddress.getByName(remoteVideoIp), remoteVideoPort);
            videoSender.setFps(VIDEO_FPS);
            try {
                videoCapture = new VideoCapture(VIDEO_WIDTH, VIDEO_HEIGHT);
                videoCapture.start();
            } catch (Exception unavailable) {
                // Preserve the existing receive-only fallback for an unavailable camera.
                if (videoCapture != null) videoCapture.stop();
                videoCapture = null;
                System.err.println("Camera unavailable; retaining receive-only video");
            }
            videoRunning = true;
            startVideoLoop();
        } catch (Exception failure) {
            stopVideo();
            throw failure;
        }
    }

    /** 采集循环：按帧率抓帧 -> 编码 -> 发送，同时刷新本地预览 */
    private void startVideoLoop() {
        final CallSession owner = mediaSession;
        final VideoCapture capture = videoCapture;
        final RtpVideoSender sender = videoSender;
        final VideoWindow window = videoWindow;
        Thread t = new Thread(() -> {
            long interval = 1000L / Math.max(1, VIDEO_FPS);
            while (videoRunning && mediaSession == owner) {
                long began = System.currentTimeMillis();
                try {
                    if (capture != null) {
                        java.awt.image.BufferedImage image = capture.grab();
                        if (image != null && mediaSession == owner) {
                            if (window != null) window.updateLocal(image);
                            byte[] jpeg = VideoCodec.encode(image, VIDEO_QUALITY);
                            if (mediaSession != owner) break;
                            sender.sendFrame(jpeg);
                            notifyVideo(jpeg, true);
                        }
                    }
                } catch (Exception failure) {
                    if (mediaSession == owner && videoRunning)
                        System.err.println("Video capture/encoding failed");
                }
                long delay = interval - (System.currentTimeMillis() - began);
                if (delay > 0) {
                    try { Thread.sleep(delay); }
                    catch (InterruptedException e) { break; }
                }
            }
        }, "VideoLoop");
        t.setDaemon(true);
        videoLoopThread = t;
        t.start();
    }

    /** 停止视频并打印统计 */
    private void stopVideo() {
        videoRunning = false;
        interruptAndJoin(videoLoopThread);
        videoLoopThread = null;
        VideoCapture capture = videoCapture;
        RtpVideoReceiver receiver = videoReceiver;
        DatagramSocket socket = videoSocket;
        VideoWindow window = videoWindow;
        videoCapture = null;
        videoReceiver = null;
        videoSocket = null;
        videoSender = null;
        videoWindow = null;
        if (socket != null) socket.close();
        if (capture != null) {
            try { capture.stop(); } catch (RuntimeException e) { System.err.println("Camera cleanup failed"); }
        }
        if (receiver != null) {
            try { receiver.stop(); } catch (RuntimeException e) { System.err.println("Video receiver cleanup failed"); }
        }
        if (window != null) {
            try { window.closeWindow(); } catch (RuntimeException e) { System.err.println("Video window cleanup failed"); }
        }
    }

    /**
     * 启动会议视频：加入与会议室号同名的视频房间。
     * 音频仍走 SIP 会议室（服务器混音），视频走应用层中继，因此支持多人。
     */
    private void startConferenceVideo() throws Exception {
        if (!VIDEO_ENABLED || !videoCall) return;
        final CallSession owner = mediaSession;
        final String room = conferenceRoom;
        try {
            confVideo = new ConferenceVideo(VIDEO_RELAY_HOST, VIDEO_RELAY_PORT,
                    room, username, VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS, VIDEO_QUALITY,
                    new ConferenceVideo.Listener() {
                        @Override
                        public void onLocalFrame(byte[] jpeg) {
                            if (mediaSession == owner) notifyVideo(jpeg, true);
                        }

                        @Override
                        public void onRemoteFrame(String from, byte[] jpeg) {
                            if (mediaSession == owner) notifyConferenceFrame(from, jpeg);
                        }

                        @Override
                        public void onPeerLeft(String from) {
                            if (mediaSession == owner) notifyConferencePeerLeft(from);
                        }
                    });
            confVideo.start();
            notifyConferenceState(true, room);
        } catch (Exception e) {
            stopConferenceVideo();
            throw e;
        }
    }

    private void stopConferenceVideo() {
        ConferenceVideo video = confVideo;
        confVideo = null;
        if (video != null) {
            try { video.stop(); }
            catch (RuntimeException failure) { System.err.println("Conference video cleanup failed"); }
            finally { notifyConferenceState(false, conferenceRoom); }
        }
    }

    /**
     * 每 2 秒打印一次上下行音频电平，用于客观判断音频是否真的有内容（不靠人耳）。
     * 电平 = 16bit PCM 的绝对平均值，范围 0~32767：
     *   静音 ≈ 0~300    正常说话 ≈ 500~8000    越大越响
     */
    private void startLevelMonitor() {
        levelMonitorRunning = true;
        final CallSession owner = mediaSession;
        final RTPAudioSender sender = rtpSender;
        final RTPAudioReceiver receiver = rtpReceiver;
        Thread t = new Thread(() -> {
            while (levelMonitorRunning && mediaSession == owner) {
                try { Thread.sleep(2000); }
                catch (InterruptedException e) { break; }
                if (!levelMonitorRunning || mediaSession != owner) break;
                System.out.println("[RTP] sent=" + sender.getPacketsSent()
                        + " received=" + receiver.getPacketsReceived()
                        + " mic=" + sender.getMicLevel() + " remote=" + receiver.getRecvLevel());
            }
        }, "LevelMonitor");
        t.setDaemon(true);
        levelMonitorThread = t;
        t.start();
    }

    // 解析SDP获取远程媒体信息
    private void parseRemoteSDP(String sdp) {
        System.out.println("\n收到的SDP:");
        System.out.println(sdp);

        remoteAudioIp = null;
        remoteAudioPort = 0;
        remoteVideoIp = null;
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

    // Only finishCall for the matching owner calls this, under the lifecycle monitor.
    void stopMedia() {
        CallSession owner = mediaSession != null ? mediaSession : cleanupSession;
        mediaSession = null;
        System.out.println(callLabel(owner) + "RTP cleanup: audioSent="
                + (rtpSender == null ? 0 : rtpSender.getPacketsSent())
                + " audioReceived=" + (rtpReceiver == null ? 0 : rtpReceiver.getPacketsReceived())
                + " videoSent=" + (videoSender == null ? 0 : videoSender.getFramesSent())
                + " videoReceived=" + (videoReceiver == null ? 0 : videoReceiver.getFramesReceived())
                + " videoDropped=" + (videoReceiver == null ? 0 : videoReceiver.getDroppedFrames()));
        levelMonitorRunning = false;
        interruptAndJoin(levelMonitorThread);
        levelMonitorThread = null;
        stopVideo();
        stopConferenceVideo();
        AudioCapture capture = audioCapture;
        RTPAudioSender sender = rtpSender;
        RTPAudioReceiver receiver = rtpReceiver;
        audioCapture = null;
        rtpSender = null;
        rtpReceiver = null;
        // Clear ownership first, so callbacks that were already in flight cannot send new audio.
        mediaSession = null;
        if (capture != null) {
            try { capture.stop(); } catch (RuntimeException e) { System.err.println("Audio capture cleanup failed"); }
        }
        if (sender != null) {
            try { sender.close(); } catch (RuntimeException e) { System.err.println("Audio sender cleanup failed"); }
        }
        if (receiver != null) {
            try { receiver.stop(); } catch (RuntimeException e) { System.err.println("Audio receiver cleanup failed"); }
        }
        conferenceRoom = null;
        videoCall = false;
        remoteAudioIp = null;
        remoteAudioPort = 0;
        remoteVideoIp = null;
        remoteVideoPort = 0;
        System.out.println("Call media released");
    }

    private static void interruptAndJoin(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        thread.interrupt();
        try { thread.join(1000L); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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

    private void sendAck(Dialog dialog, Response response) throws Exception {
        Request ack = dialog.createAck(sequence(response));
        dialog.sendAck(ack);
    }

    private void sendBye(SipCall call, Dialog dialog) throws Exception {
        if (dialog == null) return;
        String key = String.valueOf(dialog.getLocalTag()) + "/" + String.valueOf(dialog.getRemoteTag());
        if (call.terminatedDialogs.contains(key)) return;
        // Bound fork metadata; excess forks can still be terminated without retaining them.
        boolean remember = call.terminatedDialogs.size() < 16;
        if (remember) call.terminatedDialogs.add(key);
        try {
            Request bye = dialog.createRequest(Request.BYE);
            ClientTransaction tx = sipProvider.getNewClientTransaction(bye);
            dialog.sendRequest(tx);
            logCall(call, "BYE sent");
        } catch (Exception e) {
            if (remember) call.terminatedDialogs.remove(key);
            throw e;
        }
    }

    private void sendCancel(SipCall call) throws Exception {
        if (call.cancelSent || call.inviteTx == null || !call.provisional) return;
        Request cancel = call.inviteTx.createCancel();
        ClientTransaction tx = sipProvider.getNewClientTransaction(cancel);
        call.cancelSent = true;
        try {
            tx.sendRequest();
            logCall(call, "CANCEL sent");
        }
        catch (Exception e) { call.cancelSent = false; throw e; }
    }

    public void hangup() throws Exception {
        synchronized (calls) {
            CallSession session = calls.active();
            if (session != null) endLocal(sipCalls.get(session.getCallId()), "local-hangup");
        }
    }

    private void endLocal(SipCall call, String reason) throws Exception {
        if (call == null) return;
        call.cancelWanted = true;
        boolean waitingForAck = call.session.getDirection() == CallSession.Direction.INCOMING
                && call.answerSent && !call.incomingAckReceived;
        CallLifecycleManager.HangupAction action = calls.requestHangup(call.session);
        logCall(call, "local end reason=" + reason + " action=" + action);
        try {
            if (action == CallLifecycleManager.HangupAction.BYE) sendBye(call, call.dialog);
            else if (action == CallLifecycleManager.HangupAction.CANCEL) sendCancel(call);
            else if (call.session.getDirection() == CallSession.Direction.INCOMING && !call.finalSent) {
                Response declined = taggedResponse(Response.DECLINE, call.incomingRequest, call.localTag);
                call.finalResponse = declined;
                call.finalSent = true;
                call.incomingTx.sendResponse(declined);
            }
            // Outgoing WAIT: RFC 3261 forbids CANCEL before the first provisional response.
            // Incoming final 200: wait for ACK or the retained 32-second deadline before BYE.
        } finally {
            finishCall(call, reason);
            if (waitingForAck) {
                calls.armRetainedEndTimer(call.session,
                        Math.max(32, intCfg("call.ack.timeout.sec", 32)) * 1000L, () -> {
                            synchronized (calls) {
                                if (call.incomingAckReceived) return;
                                try { sendBye(call, call.dialog); }
                                catch (Exception ignored) { System.err.println("Could not terminate unacknowledged old call"); }
                            }
                        });
            }
        }
    }

    private void activateMedia(SipCall call) {
        if (!calls.claimMedia(call.session)) return;
        mediaSession = call.session;
        logCall(call, "media startup claimed");
        videoCall = call.session.isVideo();
        conferenceRoom = call.session.getConferenceRoom();
        try {
            if (call.remoteSdp == null || call.remoteSdp.trim().isEmpty()) {
                throw new IllegalArgumentException("Missing remote SDP");
            }
            startMedia(call.remoteSdp);
            if (!calls.isActive(call.session) || mediaSession != call.session) return;
            markCallStart(call.session);
            logCall(call, "media active");
            notifyCall("active", call.session.getPeer());
            calls.armAutoHangup(call.session, Math.max(1, CALL_DURATION_SEC) * 1000L, () -> {
                synchronized (calls) {
                    if (!calls.isActive(call.session)) return;
                    try { endLocal(call, "auto-hangup"); }
                    catch (Exception e) { finishCall(call, "auto-hangup"); }
                }
            });
        } catch (Exception e) {
            System.err.println("Media startup failed; ending this call");
            try { sendBye(call, call.dialog); }
            catch (Exception ignored) { System.err.println("Could not send BYE after media startup failure"); }
            finishCall(call, "media-start-failed");
        } catch (Error failure) {
            // Native media linkage/VM errors must not strand the allocated RTP owner.
            // Preserve and rethrow the original Error; cleanup failures remain inspectable.
            try { sendBye(call, call.dialog); }
            catch (Throwable cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            try { finishCall(call, "media-start-error"); }
            catch (Throwable cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void finishCall(SipCall call, String reason) {
        if (call == null) return;
        if (calls.finish(call.session, reason)) logCall(call, "ended reason=" + reason);
        if (calls.claimCleanup(call.session) && mediaSession == call.session) {
            mediaSession = null;
            cleanupSession = call.session;
            try { stopMedia(); }
            catch (RuntimeException e) { System.err.println("Media cleanup failed"); }
            finally { cleanupSession = null; }
        }
        if (!call.endedNotified) {
            call.endedNotified = true;
            if (callStartMillis > 0 && loggedSession == call.session) {
                EventLog.log("CALL_END", callDirection, callPeer,
                        String.valueOf((System.currentTimeMillis() - callStartMillis) / 1000L));
                callStartMillis = 0;
                loggedSession = null;
            }
            notifyCall("ended", call.session.getPeer());
        }
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
        if (isMediaActive()) {
            throw new Exception("通话进行中，麦克风被占用，无法录制语音消息");
        }
        File dir = new File("received");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "voice-" + System.currentTimeMillis() + ".wav");
        File rec = VoiceIO.record(seconds, out);
        if (rec == null) throw new Exception("没有采集到音频数据");
        // 注意：不删除文件——发送方也要能回放自己发的语音
        sendFile(rec, "VOICE");
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
        if (recorder == null) {
            throw new Exception("当前没有在录音");
        }
        VoiceIO.Recorder r = recorder;
        recorder = null;
        File dir = new File("received");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "voice-" + System.currentTimeMillis() + ".wav");
        File rec = r.stop(out);
        if (rec == null) throw new Exception("没有采集到音频数据");
        sendFile(rec, "VOICE");
        return out.getName();
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
        CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(messageCSeq++, Request.MESSAGE);
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);

        SipURI requestURI = addressFactory.createSipURI(targetUser, serverIp);
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

    /** 发送文件：读取 -> Base64 -> 分片 -> 逐条 MESSAGE 发出 */
    public void sendFile(File file, String type) throws Exception {
        if (!file.exists() || !file.isFile()) {
            System.out.println("文件不存在: " + file.getAbsolutePath());
            return;
        }

        byte[] data = Files.readAllBytes(file.toPath());
        String b64 = Base64.getEncoder().encodeToString(data);
        int total = (b64.length() + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE;
        String fileId = Long.toHexString(System.currentTimeMillis());
        String nameB64 = Base64.getEncoder().encodeToString(
                file.getName().getBytes(StandardCharsets.UTF_8));

        System.out.println("开始发送" + typeName(type) + ": " + file.getName()
                + "  |  原始 " + data.length + " 字节"
                + " -> Base64 " + b64.length() + " 字符"
                + " -> 分 " + total + " 片");

        for (int i = 0; i < total; i++) {
            int from = i * FILE_CHUNK_SIZE;
            int to = Math.min(from + FILE_CHUNK_SIZE, b64.length());
            String frame = "#FILE|" + type + "|" + fileId + "|" + nameB64 + "|" + i + "|" + total
                    + "|" + b64.substring(from, to);
            sendRaw(frame);
            Thread.sleep(30); // 轻微限速，避免瞬间冲击服务器
        }

        System.out.println(typeName(type) + "发送完成: " + file.getName() + "（共 " + total + " 片）");
        EventLog.log("MSG", "OUT", targetUser, type + ":" + data.length);
        notifyFile(targetUser, type, file.getName(), file.getAbsolutePath(), true);
    }

    /** 处理收到的文件分片，凑齐后解码落盘；语音消息额外自动播放 */
    private void handleFileChunk(String body, String peer) {
        String[] p = body.split("\\|", 7);
        if (p.length < 7) return;

        String type = p[1];
        String id = p[2];
        String nameB64 = p[3];
        int index, total;
        try {
            index = Integer.parseInt(p[4]);
            total = Integer.parseInt(p[5]);
        } catch (NumberFormatException e) {
            return;
        }
        String chunk = p[6];
        if (total <= 0 || index < 0 || index >= total) return;

        fileNames.putIfAbsent(id, nameB64);
        fileTypes.putIfAbsent(id, type);
        List<String> slots = fileChunks.computeIfAbsent(id,
                k -> new ArrayList<>(Collections.nCopies(total, (String) null)));

        synchronized (slots) {
            if (index < slots.size()) slots.set(index, chunk);
        }

        if (index == 0) {
            System.out.println("\n[" + typeName(type) + "接收中] id=" + id + "  共 " + total + " 片 ...");
        }

        // 检查是否已凑齐
        StringBuilder sb = new StringBuilder();
        synchronized (slots) {
            for (String s : slots) {
                if (s == null) return; // 还没收齐
                sb.append(s);
            }
        }

        try {
            byte[] data = Base64.getDecoder().decode(sb.toString());
            String name = new String(Base64.getDecoder().decode(fileNames.get(id)),
                    StandardCharsets.UTF_8);
            File dir = new File("received");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, name);
            Files.write(out.toPath(), data);
            System.out.println("[收到" + typeName(type) + "] 已保存: " + out.getAbsolutePath()
                    + "  (" + data.length + " 字节, " + total + " 片)");
            EventLog.log("MSG", "IN", peer, type + ":" + data.length);
            notifyFile(peer, type, name, out.getAbsolutePath(), false);

            if ("VOICE".equals(type)) {
                lastVoiceFile = out;
                System.out.println("[语音消息] 自动播放中...（可用 /play 重放）");
                // 播放会阻塞，必须放到独立线程，不能卡住 SIP 事件线程
                Thread player = new Thread(() -> VoiceIO.play(out), "VoicePlay");
                player.setDaemon(true);
                player.start();
            } else if ("VIDEO".equals(type)) {
                lastVideoFile = out;
                System.out.println("[视频消息] 正在用系统默认播放器打开...");
                // 打开外部程序同样不能占用 SIP 事件线程
                final File vf = out;
                Thread opener = new Thread(() -> {
                    try {
                        if (java.awt.Desktop.isDesktopSupported()) {
                            java.awt.Desktop.getDesktop().open(vf);
                        } else {
                            System.out.println("[视频消息] 当前环境不支持自动打开，请手动播放: "
                                    + vf.getAbsolutePath());
                        }
                    } catch (Exception ex) {
                        System.out.println("[视频消息] 自动打开失败，请手动播放: " + vf.getAbsolutePath());
                    }
                }, "VideoOpen");
                opener.setDaemon(true);
                opener.start();
            }
        } catch (Exception e) {
            System.err.println("文件保存失败: " + e.getMessage());
        } finally {
            fileChunks.remove(id);
            fileNames.remove(id);
            fileTypes.remove(id);
        }
    }

    /** 当前是否有媒体流在跑（即通话进行中） */
    private boolean isMediaActive() {
        synchronized (calls) { return calls.active() != null || mediaSession != null; }
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
                            File wav = File.createTempFile("voice-", ".wav");
                            File rec = VoiceIO.record(sec, wav);
                            if (rec != null) {
                                sendFile(rec, "VOICE");
                                rec.delete();
                            }
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
    public void processRequest(RequestEvent event) {
        Request request = event.getRequest();
        // File assembly/playback must not block CANCEL or BYE on the lifecycle monitor.
        if (Request.MESSAGE.equals(request.getMethod())) {
            try { handleIncomingMessage(event); }
            catch (Exception e) { System.err.println("SIP MESSAGE handling failed"); }
            return;
        }
        synchronized (calls) {
            try {
                if (Request.INVITE.equals(request.getMethod())) {
                    handleIncomingInvite(event);
                } else if (Request.ACK.equals(request.getMethod())) {
                    handleIncomingAck(event);
                } else if (Request.CANCEL.equals(request.getMethod())) {
                    handleIncomingCancel(event);
                } else if (Request.BYE.equals(request.getMethod())) {
                    SipCall call = sipCalls.get(callId(request));
                    boolean owned = ownsRequest(call, request)
                            && (event.getDialog() == null || call.dialog == null
                                || sameDialog(call.dialog, event.getDialog()));
                    serverTransaction(event).sendResponse(messageFactory.createResponse(
                            owned ? Response.OK : Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST, request));
                    if (owned) finishCall(call, "remote-bye");
                }
            } catch (Exception e) {
                System.err.println("SIP request handling failed: " + request.getMethod());
                SipCall call = sipCalls.get(callId(request));
                // A malformed foreign or re-INVITE request never terminates the current call.
                if (Request.ACK.equals(request.getMethod()) && ownsRequest(call, request)
                        && call.session.getState() != CallState.ESTABLISHED) {
                    try { sendBye(call, call.dialog); } catch (Exception ignored) { }
                    finishCall(call, "ack-processing-failed");
                }
            }
        }
    }

    /** Existing one-to-one and file MESSAGE handling is independent of call ownership. */
    private void handleIncomingMessage(RequestEvent event) throws Exception {
        Request request = event.getRequest();
        ServerTransaction tx = serverTransaction(event);
        byte[] raw = request.getRawContent();
        String text = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
        FromHeader from = (FromHeader) request.getHeader(FromHeader.NAME);
        String peer = peerOf(from);
        if (text.startsWith("#FILE|")) handleFileChunk(text, peer);
        else {
            String address = from == null ? "unknown" : from.getAddress().toString();
            System.out.println("[收到消息] 来自 " + address + "：" + text);
            EventLog.log("MSG", "IN", peer, "TEXT:" + text.length());
            notifyMessage(peer, text, false);
        }
        tx.sendResponse(messageFactory.createResponse(Response.OK, request));
    }

    private ServerTransaction serverTransaction(RequestEvent event) throws Exception {
        return event.getServerTransaction() != null ? event.getServerTransaction()
                : sipProvider.getNewServerTransaction(event.getRequest());
    }

    /** Auto-answer is preserved, but media starts only after the matching ACK. */
    private void handleIncomingInvite(RequestEvent event) throws Exception {
        Request request = event.getRequest();
        ServerTransaction tx = serverTransaction(event);
        SipCall existing = sipCalls.get(callId(request));
        if (existing != null && existing.session.getDirection() == CallSession.Direction.INCOMING
                && sequence(request) == existing.session.getInviteCSeq()
                && tagEquals(existing.remoteTag, fromTag(request))
                && Objects.equals(branch(request), branch(existing.incomingRequest))
                && (Objects.equals(toTag(request), toTag(existing.incomingRequest))
                    || tagEquals(existing.localTag, toTag(request)))) {
            if (existing.finalResponse != null) tx.sendResponse(existing.finalResponse);
            return;
        }
        // Renegotiation and unrelated callback dialogs must not borrow the active media.
        if (toTag(request) != null) {
            tx.sendResponse(messageFactory.createResponse(ownsRequest(existing, request)
                    ? Response.NOT_ACCEPTABLE_HERE : Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST, request));
            return;
        }
        if (shuttingDown || calls.active() != null) {
            tx.sendResponse(messageFactory.createResponse(Response.BUSY_HERE, request));
            return;
        }
        String remoteTag = fromTag(request);
        if (callId(request) == null || remoteTag == null || sequence(request) <= 0) {
            tx.sendResponse(messageFactory.createResponse(Response.BAD_REQUEST, request));
            return;
        }
        String sdp = request.getRawContent() == null ? ""
                : new String(request.getRawContent(), StandardCharsets.UTF_8);
        String localTag = UUID.randomUUID().toString();
        FromHeader from = (FromHeader) request.getHeader(FromHeader.NAME);
        CallSession session;
        try {
            session = calls.beginIncoming(callId(request), sequence(request), branch(request),
                    peerOf(from), VIDEO_ENABLED && sdp.contains("m=video"), null);
        } catch (IllegalArgumentException e) {
            tx.sendResponse(messageFactory.createResponse(Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST, request));
            return;
        }
        SipCall call = new SipCall(session, localTag, remoteTag);
        call.incomingRequest = request;
        call.incomingTx = tx;
        call.remoteSdp = sdp;
        remember(call);
        logCall(call, "incoming INVITE received");
        notifyCall("incoming", session.getPeer());
        try {
            tx.sendResponse(messageFactory.createResponse(Response.TRYING, request));
            if (!calls.isActive(session)) return;
            Response ringing = taggedResponse(Response.RINGING, request, localTag);
            ringing.addHeader(localContact());
            tx.sendResponse(ringing);
            // A reentrant CANCEL from a transaction test/provider can finish the early dialog.
            if (!calls.isActive(session)) return;
            Response ok = taggedResponse(Response.OK, request, localTag);
            ok.addHeader(localContact());
            ok.setContent(buildLocalSdp(session.isVideo()).getBytes(StandardCharsets.UTF_8),
                    headerFactory.createContentTypeHeader("application", "sdp"));
            call.finalResponse = ok;
            call.finalSent = true;
            call.answerSent = true;
            calls.awaitingAck(session);
            call.dialog = tx.getDialog() != null ? tx.getDialog() : event.getDialog();
            calls.armTimer(session, CallLifecycleManager.TimerKind.ACK_DEADLINE,
                    Math.max(32, intCfg("call.ack.timeout.sec", 32)) * 1000L, () -> {
                        synchronized (calls) {
                            if (!calls.isActive(session)) return;
                            call.cancelWanted = true;
                            try { sendBye(call, call.dialog); } catch (Exception ignored) { }
                            finishCall(call, "ack-timeout");
                        }
                    });
            tx.sendResponse(ok);
        } catch (Exception e) {
            finishCall(call, "answer-send-failed");
            throw e;
        }
    }

    private Response taggedResponse(int status, Request request, String localTag) throws Exception {
        Response response = messageFactory.createResponse(status, request);
        ToHeader to = (ToHeader) response.getHeader(ToHeader.NAME);
        if (to != null) to.setTag(localTag);
        return response;
    }

    private void handleIncomingAck(RequestEvent event) throws Exception {
        Request request = event.getRequest();
        SipCall call = sipCalls.get(callId(request));
        if (call == null || call.session.getDirection() != CallSession.Direction.INCOMING
                || !call.answerSent || !ownsRequest(call, request)
                || sequence(request) != call.session.getInviteCSeq()) return;
        if (event.getDialog() != null) {
            if (call.dialog != null && !sameDialog(call.dialog, event.getDialog())) return;
            call.dialog = event.getDialog();
        }
        call.incomingAckReceived = true;
        logCall(call, "matching ACK received");
        calls.cancelTimer(call.session, CallLifecycleManager.TimerKind.END_DEADLINE);
        CallLifecycleManager.AcceptResult result = calls.acknowledged(call.session);
        if (result == CallLifecycleManager.AcceptResult.START_MEDIA) activateMedia(call);
        else if (result == CallLifecycleManager.AcceptResult.TERMINATE_LATE) sendBye(call, call.dialog);
    }

    private void handleIncomingCancel(RequestEvent event) throws Exception {
        Request cancel = event.getRequest();
        SipCall call = sipCalls.get(callId(cancel));
        boolean matches = call != null && call.session.getDirection() == CallSession.Direction.INCOMING
                && sequence(cancel) == call.session.getInviteCSeq()
                && tagEquals(call.remoteTag, fromTag(cancel))
                && Objects.equals(branch(cancel), branch(call.incomingRequest))
                && (toTag(cancel) == null || tagEquals(call.localTag, toTag(cancel)));
        serverTransaction(event).sendResponse(messageFactory.createResponse(matches ? Response.OK
                : Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST, cancel));
        if (!matches || call.finalSent) return; // CANCEL cannot revoke a final 200.
        Response terminated = taggedResponse(Response.REQUEST_TERMINATED, call.incomingRequest, call.localTag);
        call.finalResponse = terminated;
        call.finalSent = true;
        call.incomingTx.sendResponse(terminated);
        finishCall(call, "remote-cancel");
    }

    @Override
    public void processResponse(ResponseEvent event) {
        Response response = event.getResponse();
        CSeqHeader cseq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        if (cseq == null) return;
        synchronized (calls) {
            try {
                if (Request.REGISTER.equals(cseq.getMethod())) {
                    if (shuttingDown) return;
                    if (response.getStatusCode() == Response.OK) isRegistered = true;
                    else if (response.getStatusCode() == Response.UNAUTHORIZED) registerWithAuth(response);
                } else if (Request.INVITE.equals(cseq.getMethod())) {
                    handleInviteResponse(event);
                }
                // CANCEL/BYE responses belong to already-cleaned old transactions. They never
                // terminate a new session, including 200 CANCEL followed by 487 INVITE.
            } catch (Exception e) {
                System.err.println("SIP response handling failed: " + cseq.getMethod());
                SipCall call = sipCalls.get(callId(response));
                if (matchesResponse(call, response) && calls.isActive(call.session)
                        && call.session.getState() != CallState.ESTABLISHED
                        && sequence(response) == call.session.getInviteCSeq()) {
                    if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                        try { sendBye(call, call.dialog); } catch (Exception ignored) { }
                    }
                    finishCall(call, "response-processing-failed");
                }
            }
        }
    }

    private void handleInviteResponse(ResponseEvent event) throws Exception {
        Response response = event.getResponse();
        SipCall call = sipCalls.get(callId(response));
        if (!matchesResponse(call, response)) {
            if (call == null && response.getStatusCode() >= 200 && response.getStatusCode() < 300)
                terminateUntrackedSuccess(event);
            return;
        }
        ClientTransaction tx = event.getClientTransaction();
        int status = response.getStatusCode();
        logCall(call, "INVITE response status=" + status + " responseSeq=" + sequence(response));
        boolean currentAttempt = sequence(response) == call.session.getInviteCSeq()
                && (tx == null || tx == call.inviteTx
                    || calls.matchesInvite(call.session, sequence(response), tx.getBranchId()));
        if (status >= 200 && status < 300) {
            Dialog dialog = event.getDialog();
            if (dialog == null && tx != null) dialog = tx.getDialog();
            if (dialog == null && tagEquals(call.acceptedRemoteTag, toTag(response))) dialog = call.dialog;
            // A 2xx ACK is end-to-end and must be retransmitted for every duplicate 2xx.
            if (!validResponseDialog(dialog, response)) return;
            boolean fork = call.acceptedRemoteTag != null
                    && !tagEquals(call.acceptedRemoteTag, toTag(response));
            if (currentAttempt && !fork && call.dialog == null) call.dialog = dialog;
            sendAck(dialog, response);
            if (!currentAttempt || fork) {
                sendBye(call, dialog);
                return;
            }
            if (call.acceptedRemoteTag == null) call.acceptedRemoteTag = toTag(response);
            call.dialog = dialog;
            call.remoteSdp = response.getRawContent() == null ? ""
                    : new String(response.getRawContent(), StandardCharsets.UTF_8);
            CallLifecycleManager.AcceptResult result = calls.accepted(call.session);
            if (call.cancelWanted || result == CallLifecycleManager.AcceptResult.TERMINATE_LATE) {
                sendBye(call, dialog);
                finishCall(call, "late-accept-after-end");
            } else if (result == CallLifecycleManager.AcceptResult.START_MEDIA) activateMedia(call);
            return;
        }
        if (!currentAttempt || call.session.getState() == CallState.ESTABLISHED
                || call.acceptedRemoteTag != null) return;
        if (status < 200) {
            call.provisional = true;
            calls.provisional(call.session);
            if (call.cancelWanted) sendCancel(call);
            else if (status == Response.RINGING && calls.ringing(call.session))
                notifyCall("ringing", call.session.getPeer());
            return;
        }
        if (status == Response.UNAUTHORIZED || status == Response.PROXY_AUTHENTICATION_REQUIRED) {
            if (!call.cancelWanted && calls.isActive(call.session)) inviteWithAuth(response, call);
            else finishCall(call, "authentication-after-end");
        } else {
            finishCall(call, "invite-failed-" + status);
        }
    }

    private void terminateUntrackedSuccess(ResponseEvent event) throws Exception {
        Response response = event.getResponse();
        Dialog dialog = event.getDialog();
        if (dialog == null && event.getClientTransaction() != null)
            dialog = event.getClientTransaction().getDialog();
        // Only the stack's own matching Dialog can authorize cleanup of a pruned late 2xx.
        if (!validResponseDialog(dialog, response)) return;
        sendAck(dialog, response);
        String key = callId(response) + "/" + fromTag(response) + "/" + toTag(response);
        if (orphanEndedDialogs.containsKey(key)) return;
        Request bye = dialog.createRequest(Request.BYE);
        dialog.sendRequest(sipProvider.getNewClientTransaction(bye));
        orphanEndedDialogs.put(key, Boolean.TRUE);
        if (orphanEndedDialogs.size() > 64) orphanEndedDialogs.remove(orphanEndedDialogs.keySet().iterator().next());
    }

    private boolean validResponseDialog(Dialog dialog, Response response) {
        return dialog != null && dialog.getCallId() != null
                && Objects.equals(dialog.getCallId().getCallId(), callId(response))
                && tagEquals(fromTag(response), dialog.getLocalTag())
                && tagEquals(toTag(response), dialog.getRemoteTag());
    }

    @Override
    public void processTimeout(TimeoutEvent event) {
        if (event.getTimeout() != Timeout.TRANSACTION) return;
        synchronized (calls) {
            ClientTransaction tx = event.getClientTransaction();
            if (tx != null && tx.getRequest() != null) {
                Request request = tx.getRequest();
                if (Request.INVITE.equals(request.getMethod())) {
                    SipCall call = sipCalls.get(callId(request));
                    if (call != null && tx == call.inviteTx && calls.isActive(call.session)
                            && call.session.getState() != CallState.ESTABLISHED
                            && calls.matchesInvite(call.session, sequence(request), tx.getBranchId()))
                        finishCall(call, "invite-transaction-timeout");
                } else if (Request.REGISTER.equals(request.getMethod()) && !shuttingDown) {
                    Thread retry = new Thread(() -> {
                        try {
                            Thread.sleep(30000);
                            if (!shuttingDown) register();
                        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        catch (Exception e) { System.err.println("Registration retry failed"); }
                    }, "RegisterRetry");
                    retry.setDaemon(true);
                    retry.start();
                }
            } else if (event.isServerTransaction()) {
                ServerTransaction server = event.getServerTransaction();
                if (server != null && server.getRequest() != null) {
                    SipCall call = sipCalls.get(callId(server.getRequest()));
                    if (call != null && server == call.incomingTx && calls.isActive(call.session)
                            && call.session.getState() != CallState.ESTABLISHED) {
                        if (call.answerSent) {
                            try { sendBye(call, call.dialog); } catch (Exception ignored) { }
                        }
                        finishCall(call, "incoming-transaction-timeout");
                    }
                }
            }
        }
    }

    @Override
    public void processIOException(IOExceptionEvent event) {
        // JAIN IOExceptionEvent has an endpoint, not a call identity. A shared-proxy I/O
        // event must not kill an unrelated call; its scoped deadline/timeout handles it.
        System.err.println("SIP transport I/O error; waiting for the matching transaction deadline");
    }

    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent event) {
        // Successful INVITE transactions can terminate while their dialog is still active.
    }

    @Override
    public void processDialogTerminated(DialogTerminatedEvent event) {
        synchronized (calls) {
            Dialog dialog = event.getDialog();
            if (dialog == null || dialog.getCallId() == null) return;
            SipCall call = sipCalls.get(dialog.getCallId().getCallId());
            if (call != null && sameDialog(call.dialog, dialog)) finishCall(call, "dialog-terminated");
        }
    }
}

package org.example;

import javax.sip.*;
import javax.sip.address.*;
import javax.sip.header.*;
import javax.sip.message.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    private String targetUser = cfg("sip.target.user",  "101");

    /** 通话时长（秒），到时自动挂断 */
    private static final int CALL_DURATION_SEC = intCfg("call.duration.sec", 180);

    /** 启动后是否自动呼叫被叫（测文字消息时建议设为 false） */
    private static final boolean AUTO_INVITE = boolCfg("call.auto.invite", true);

    private Dialog dialog;
    private ClientTransaction inviteTransaction;
    private boolean isRegistered = false;

    /** 文字消息的 CSeq 序号，每发一条递增 */
    private long messageCSeq = 1;

    /**
     * 图片分片大小（Base64 字符数）。
     * 实测：单个 SIP MESSAGE 的 Content-Length 超过 1000 左右就会被丢弃
     * （总 UDP 载荷超过以太网 MTU 1472 字节后产生 IP 分片，分片包被链路丢弃）。
     * 因此这里保守取 800，保证加上约 300 字节 SIP 头后仍不分片。
     */
    private final int IMG_CHUNK_SIZE = intCfg("im.image.chunk.size", 800);
    /** 图片重组缓冲：图片id -> 分片数组（未到达的位置为 null） */
    private final Map<String, List<String>> imgChunks = new ConcurrentHashMap<>();
    /** 图片文件名（Base64 编码）：图片id -> nameB64 */
    private final Map<String, String> imgNames = new ConcurrentHashMap<>();

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
        File f = new File("config.properties");
        if (!f.exists()) {
            System.out.println("未找到 config.properties，使用内置默认配置");
            return p;
        }
        try (InputStream in = new FileInputStream(f);
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
            System.out.println("已加载配置文件: " + f.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("读取 config.properties 失败，改用默认配置: " + e.getMessage());
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

            // 保持程序运行：等通话（含自动挂断）结束后再退出
            Thread.sleep((CALL_DURATION_SEC + 60) * 1000L);

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
    public void makeCall() throws Exception {
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

        String sdpData = "v=0\r\n" +
                "o=" + username + " 123456 654321 IN IP4 " + localIp + "\r\n" +
                "s=Audio Call\r\n" +
                "c=IN IP4 " + localIp + "\r\n" +
                "t=0 0\r\n" +
                "m=audio " + localAudioPort + " RTP/AVP 0 8 101\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=rtpmap:101 telephone-event/8000\r\n";

        byte[] contents = sdpData.getBytes();
        request.setContent(contents, contentTypeHeader);

        inviteTransaction = sipProvider.getNewClientTransaction(request);
        inviteTransaction.sendRequest();

        System.out.println("INVITE请求已发送到: " + targetUser);
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
        String sdpData = "v=0\r\n" +
                "o=" + username + " 123456 654321 IN IP4 " + localIp + "\r\n" +
                "s=Audio Call\r\n" +
                "c=IN IP4 " + localIp + "\r\n" +
                "t=0 0\r\n" +
                "m=audio " + localAudioPort + " RTP/AVP 0 8 101\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=rtpmap:101 telephone-event/8000\r\n";

        byte[] contents = sdpData.getBytes();
        request.setContent(contents, contentTypeHeader);

        inviteTransaction = sipProvider.getNewClientTransaction(request);
        inviteTransaction.sendRequest();

        System.out.println("带认证的INVITE已发送");
    }

    // 启动媒体流
    private void startMedia(String remoteSdp) {
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

        } catch (Exception e) {
            System.err.println("启动媒体流失败: " + e.getMessage());
            e.printStackTrace();
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

        System.out.println("解析结果 - IP: " + remoteAudioIp + ", Port: " + remoteAudioPort);
    }

    // 停止媒体流
    private void stopMedia() {
        System.out.println("\n停止音频流...");
        levelMonitorRunning = false;

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
        }
        if (rtpSender != null) {
            rtpSender.close();
        }
        if (rtpReceiver != null) {
            rtpReceiver.stop();
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

    private void hangup() throws Exception {
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

    // ---------- 图片消息（Base64 分片，走 SIP MESSAGE）----------
    //
    // 帧格式：#IMG|<图片id>|<文件名Base64>|<片序号>|<总片数>|<Base64数据>
    // 说明：这是本项目自定义的应用层分片协议，只有本项目客户端之间可以互通。

    /** 发送图片：读取文件 -> Base64 -> 分片 -> 逐条 MESSAGE 发出 */
    public void sendImage(File file) throws Exception {
        if (!file.exists() || !file.isFile()) {
            System.out.println("图片文件不存在: " + file.getAbsolutePath());
            return;
        }

        byte[] data = Files.readAllBytes(file.toPath());
        String b64 = Base64.getEncoder().encodeToString(data);
        int total = (b64.length() + IMG_CHUNK_SIZE - 1) / IMG_CHUNK_SIZE;
        String imgId = Long.toHexString(System.currentTimeMillis());
        String nameB64 = Base64.getEncoder().encodeToString(
                file.getName().getBytes(StandardCharsets.UTF_8));

        System.out.println("开始发送图片: " + file.getName()
                + "  |  原始 " + data.length + " 字节"
                + " -> Base64 " + b64.length() + " 字符"
                + " -> 分 " + total + " 片");

        for (int i = 0; i < total; i++) {
            int from = i * IMG_CHUNK_SIZE;
            int to = Math.min(from + IMG_CHUNK_SIZE, b64.length());
            String frame = "#IMG|" + imgId + "|" + nameB64 + "|" + i + "|" + total
                    + "|" + b64.substring(from, to);
            sendRaw(frame);
            Thread.sleep(30); // 轻微限速，避免瞬间冲击服务器
        }

        System.out.println("图片发送完成: " + file.getName() + "（共 " + total + " 片）");
    }

    /** 处理收到的图片分片，凑齐后解码落盘 */
    private void handleImageChunk(String body) {
        String[] p = body.split("\\|", 6);
        if (p.length < 6) return;

        String id = p[1];
        String nameB64 = p[2];
        int index, total;
        try {
            index = Integer.parseInt(p[3]);
            total = Integer.parseInt(p[4]);
        } catch (NumberFormatException e) {
            return;
        }
        String chunk = p[5];
        if (total <= 0 || index < 0 || index >= total) return;

        imgNames.putIfAbsent(id, nameB64);
        List<String> slots = imgChunks.computeIfAbsent(id,
                k -> new ArrayList<>(Collections.nCopies(total, (String) null)));

        synchronized (slots) {
            if (index < slots.size()) slots.set(index, chunk);
        }

        if (index == 0) {
            System.out.println("\n[图片接收中] id=" + id + "  共 " + total + " 片 ...");
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
            String name = new String(Base64.getDecoder().decode(imgNames.get(id)),
                    StandardCharsets.UTF_8);
            File dir = new File("received");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, name);
            Files.write(out.toPath(), data);
            System.out.println("[收到图片] 已保存: " + out.getAbsolutePath()
                    + "  (" + data.length + " 字节, " + total + " 片)");
        } catch (Exception e) {
            System.err.println("图片保存失败: " + e.getMessage());
        } finally {
            imgChunks.remove(id);
            imgNames.remove(id);
        }
    }

    /** 启动控制台聊天：输入一行回车即发送，同时接收并显示对方消息 */
    private void startChat() {
        Thread t = new Thread(() -> {
            System.out.println("\n===== 文字 / 图片消息已就绪 =====");
            System.out.println("输入内容后回车 → 发送文字给 " + targetUser);
            System.out.println("输入 /img <图片路径> → 发送图片");
            System.out.println("输入 /quit → 退出程序");
            try (Scanner sc = new Scanner(System.in)) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine().trim();
                    if (line.isEmpty()) continue;
                    if (line.equals("/quit")) {
                        System.out.println("收到退出指令");
                        System.exit(0);
                    }
                    if (line.startsWith("/img ")) {
                        if (!isRegistered) {
                            System.out.println("尚未注册成功，无法发送");
                            continue;
                        }
                        try {
                            sendImage(new File(line.substring(5).trim()));
                        } catch (Exception e) {
                            System.err.println("图片发送失败: " + e.getMessage());
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
                String text = (raw == null) ? "" : new String(raw, StandardCharsets.UTF_8);
                if (text.startsWith("#IMG|")) {
                    handleImageChunk(text);
                } else {
                    FromHeader msgFrom = (FromHeader) request.getHeader(FromHeader.NAME);
                    String from = (msgFrom != null) ? msgFrom.getAddress().toString() : "未知";
                    System.out.println("\n[收到消息] 来自 " + from + "：" + text);
                }
                Response response = messageFactory.createResponse(200, request);
                serverTransaction.sendResponse(response);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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
                } else if (statusCode == Response.OK) {
                    System.out.println("✓ 对方接听! 通话建立成功!");

                    if (dialog == null) {
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
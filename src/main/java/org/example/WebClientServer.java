package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Web 网页聊天客户端：把现有的 SIP/RTP 引擎（{@link SipVideoCallClient}）包一层 HTTP 服务，
 * 浏览器通过网页完成聊天、收发文件、音视频通话。
 *
 * <p>通信方式：
 * <ul>
 *   <li>浏览器 -&gt; 服务端：POST /api/send（文字）、POST /api/file（文件原始字节）、
 *       POST /api/call、POST /api/hangup</li>
 *   <li>服务端 -&gt; 浏览器：GET /api/stream（SSE），推送消息、文件、通话状态、视频帧</li>
 * </ul>
 *
 * <p>注意：音频仍由运行本程序的电脑的麦克风/扬声器处理（浏览器只做界面与视频显示）。
 */
public class WebClientServer {

    private static final Properties CONFIG = loadConfig();
    private static final String MY_USER = cfg("sip.username", "100");
    private static final String PEER_USER = cfg("sip.target.user", "101");
    private static final String MSS_HOST = cfg("mss.web.host", cfg("sip.server.ip", "127.0.0.1"));
    private static final int MSS_PORT = intCfg("mss.web.port", 8080);
    private static final String MSS_PWD = cfg("mss.web.password", "1234");

    /** 复用的 miniSIPServer 客户端（在线联系人） */
    private static volatile MssClient MSS = new MssClient(MSS_HOST, MSS_PORT, MSS_PWD);

    /** 已连接的浏览器 SSE 通道 */
    private static final Set<SseClient> CLIENTS = ConcurrentHashMap.newKeySet();

    private static volatile SipVideoCallClient sip;
    private static final GroupService GROUPS = new GroupService(MY_USER, (recipient, body, completion) -> {
        SipVideoCallClient current = sip;
        if (current == null || !current.isRegistered()) throw new IllegalStateException("SIP_NOT_REGISTERED");
        current.sendGroupEnvelope(recipient, body, completion);
    });
    private static final GroupWebApi GROUPS_API = new GroupWebApi(GROUPS, () -> sip != null && sip.isRegistered());
    private static final java.util.concurrent.ExecutorService GROUP_EVENTS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "GroupUiEvents"); thread.setDaemon(true); return thread;
    });
    private static Path RECEIVED_DIR = Paths.get("received");
    private static final Semaphore SSE_SLOTS = new Semaphore(4);

    public static void main(String[] args) throws Exception {
        // Validate secrets and TLS before binding. Defaults expose the control service only on loopback.
        WebServerSettings settings = WebServerSettings.from(CONFIG);
        SSLContext tls = settings.loadSslContext();
        String token = PrivateWebToken.loadOrCreate(settings.getTokenPath());
        HttpServer server = createListener(settings, tls);
        WebAccessControl access = new WebAccessControl(token, settings.getScheme(),
                settings.authorities(server.getAddress().getPort()), !settings.isRemoteEnabled());
        registerRoutes(server, access);
        GROUPS.setListener(() -> GROUP_EVENTS.execute(() -> broadcast(GroupWebApi.event(GROUPS))));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("=====================================================");
        System.out.println("  网页版客户端已启动");
        String host = settings.getBindAddress().getHostAddress();
        if (host.indexOf(':') >= 0) host = "[" + host + "]";
        System.out.println("  访问地址: " + settings.getScheme() + "://" + host + ":" + settings.getPort() + "/");
        System.out.println("  登录令牌文件（仅本机读取，不要分享）: " + settings.getTokenPath());
        System.out.println("  本机账号: " + MY_USER + "    对端账号: " + PEER_USER);
        System.out.println("  按 Ctrl+C 退出");
        System.out.println("=====================================================");

        // 2) 再启动 SIP 引擎（放到后台线程，避免阻塞 Web 服务）
        Thread engine = new Thread(() -> {
            try {
                sip = new SipVideoCallClient();
                sip.setUi(new UiBridge());
                sip.setGroupMessageHandler(GROUPS::receive);
                sip.init();
            } catch (Exception e) {
                System.err.println("SIP 引擎启动失败，请检查本机 SIP 配置及诊断日志。");
            }
        }, "SipEngine");
        engine.setDaemon(true);
        engine.start();

        // 3) 主线程挂住，进程不退出
        new CountDownLatch(1).await();
    }

    /** Construction helper used by isolated HTTP tests; does not initialize SIP or start a server. */
    static HttpServer createListener(WebServerSettings settings, SSLContext tls) throws IOException {
        return createListener(settings, tls, settings.getPort());
    }

    static HttpServer createListener(WebServerSettings settings, SSLContext tls, int port) throws IOException {
        InetSocketAddress address = new InetSocketAddress(settings.getBindAddress(), port);
        if ("https".equals(settings.getScheme()) != (tls != null)) {
            throw new IllegalArgumentException("Listener TLS does not match configured scheme");
        }
        if (tls == null) return HttpServer.create(address, 0);
        HttpsServer https = HttpsServer.create(address, 0);
        https.setHttpsConfigurator(new HttpsConfigurator(tls) {
            @Override public void configure(HttpsParameters parameters) {
                SSLParameters ssl = tls.getDefaultSSLParameters();
                ssl.setProtocols(new String[]{"TLSv1.2"});
                parameters.setSSLParameters(ssl);
            }
        });
        return https;
    }

    /** One guarded root dispatcher: future group/file routes must stay inside this boundary. */
    static void registerRoutes(HttpServer server, WebAccessControl access) {
        server.createContext("/", access.wrap(ex -> {
            try {
                String path = ex.getRequestURI().getPath();
                switch (path) {
                    case "/": handleRoot(ex); break;
                    case "/web-access.js": serveResource(ex, "web-access.js", "application/javascript; charset=utf-8"); break;
                    case "/group-chat.js": serveResource(ex, "group-chat.js", "application/javascript; charset=utf-8"); break;
                    case "/api/groups": GROUPS_API.handle(ex); break;
                    case "/api/stream": handleStream(ex, access); break;
                    case "/api/state": handleState(ex); break;
                    case "/api/contacts": handleContacts(ex); break;
                    case "/api/send": handleSend(ex); break;
                    case "/api/file": handleFile(ex); break;
                    case "/api/call": handleCall(ex); break;
                    case "/api/hangup": handleHangup(ex); break;
                    case "/api/record": handleRecord(ex); break;
                    case "/api/record/start": handleRecordStart(ex); break;
                    case "/api/record/stop": handleRecordStop(ex); break;
                    case "/api/setpeer": handleSetPeer(ex); break;
                    default:
                        if (path.startsWith("/files/")) handleFiles(ex);
                        else sendText(ex, 404, "NOT_FOUND");
                }
            } catch (Exception failure) {
                sendText(ex, 500, "REQUEST_FAILED");
            }
        }));
    }

    // ---------------- 浏览器 -> 服务端 ----------------

    private static void handleRoot(HttpExchange ex) {
        try {
            serveResource(ex, "chat.html", "text/html; charset=utf-8");
        } catch (Exception e) {
            sendText(ex, 500, "PAGE_UNAVAILABLE");
        }
    }

    private static void handleState(HttpExchange ex) {
        JsonObject o = new JsonObject();
        o.addProperty("registered", sip != null && sip.isRegistered());
        o.addProperty("me", MY_USER);
        o.addProperty("peer", sip != null ? sip.getTargetUser() : PEER_USER);
        sendJson(ex, o);
    }

    /** 切换对端分机：POST /api/setpeer?user=101 */
    private static void handleSetPeer(HttpExchange ex) {
        try {
            String user = param(ex.getRequestURI().getRawQuery(), "user");
            requireUser(user);
            if (sip == null) { sendJson(ex, 503, err("引擎未就绪")); return; }
            sip.setTargetUser(user);
            JsonObject o = ok();
            o.addProperty("peer", sip != null ? sip.getTargetUser() : PEER_USER);
            sendJson(ex, o);
        } catch (Exception e) {
            sendJson(ex, 400, err("切换对端失败，请检查分机号码"));
        }
    }

    /** 录制并发送语音消息：POST /api/record?sec=5 */
    private static void handleRecord(HttpExchange ex) {
        try {
            if (sip == null || !sip.isRegistered()) {
                sendJson(ex, err("尚未注册成功"));
                return;
            }
            int sec = 5;
            String q = param(ex.getRequestURI().getRawQuery(), "sec");
            if (q != null) sec = Integer.parseInt(q);
            if (sec < 1 || sec > 30) throw new IllegalArgumentException("Invalid duration");
            sip.recordAndSendVoice(sec);
            sendJson(ex, ok());
        } catch (Exception e) {
            sendJson(ex, 400, err("录音失败，请检查时长和音频设备"));
        }
    }

    private static void handleContacts(HttpExchange ex) {
        JsonArray list = new JsonArray();
        try {
            JsonArray raw = MSS.list("localuser");
            for (int i = 0; i < raw.size(); i++) {
                JsonObject r = raw.get(i).getAsJsonObject();
                JsonObject e = new JsonObject();
                e.addProperty("user", str(r, "usrname"));
                int state = r.has("state") && r.get("state").isJsonPrimitive() ? r.get("state").getAsInt() : 0;
                e.addProperty("online", state == 1 || state == 2 || state == 3);
                e.addProperty("state", state);
                list.add(e);
            }
        } catch (Exception ignore) {
            // 服务器不可用时返回空列表
        }
        JsonObject o = new JsonObject();
        o.add("contacts", list);
        sendJson(ex, o);
    }

    private static void handleSend(HttpExchange ex) {
        try {
            if (sip == null || !sip.isRegistered()) {
                sendJson(ex, err("尚未注册成功"));
                return;
            }
            String text = readBody(ex);
            if (text == null || text.trim().isEmpty()) {
                sendJson(ex, err("内容为空"));
                return;
            }
            sip.sendMessage(text);
            sendJson(ex, ok());
        } catch (Exception e) {
            sendJson(ex, 500, err("消息发送失败"));
        }
    }

    /** 上传并发送文件：POST /api/file?name=xxx&type=IMG|VOICE|VIDEO，body 为文件原始字节 */
    private static void handleFile(HttpExchange ex) {
        try {
            if (sip == null || !sip.isRegistered()) {
                sendJson(ex, err("尚未注册成功"));
                return;
            }
            String query = ex.getRequestURI().getRawQuery();
            String name = param(query, "name");
            String type = param(query, "type");
            if (name == null || name.trim().isEmpty()) name = "file.bin";
            if (type == null || type.trim().isEmpty()) type = "IMG";
            name = sanitize(name);

            byte[] data = readBodyBytes(ex);
            File dir = RECEIVED_DIR.toFile();
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, name);
            Files.write(out.toPath(), data);

            sip.sendFile(out, type);
            sendJson(ex, ok());
        } catch (Exception e) {
            sendJson(ex, 500, err("文件发送失败"));
        }
    }

    /** 开始录音：POST /api/record/start */
    private static void handleRecordStart(HttpExchange ex) {
        try {
            if (sip == null || !sip.isRegistered()) {
                sendJson(ex, err("尚未注册成功"));
                return;
            }
            sip.startVoiceRecord();
            sendJson(ex, ok());
        } catch (Exception e) {
            sendJson(ex, 500, err("录音操作失败"));
        }
    }

    /** 停止录音并发送：POST /api/record/stop */
    private static void handleRecordStop(HttpExchange ex) {
        try {
            if (sip == null) {
                sendJson(ex, err("引擎未就绪"));
                return;
            }
            String name = sip.stopVoiceRecordAndSend();
            JsonObject o = ok();
            o.addProperty("name", name);
            sendJson(ex, o);
        } catch (Exception e) {
            sendJson(ex, 500, err("录音操作失败"));
        }
    }

    /** 呼叫：POST /api/call?video=1|0&target=号码（target 省略则呼叫当前对端） */
    private static void handleCall(HttpExchange ex) {
        try {
            if (sip == null || !sip.isRegistered()) {
                sendJson(ex, err("尚未注册成功"));
                return;
            }
            String query = ex.getRequestURI().getRawQuery();
            String videoFlag = param(query, "video");
            if (videoFlag != null && !videoFlag.equals("0") && !videoFlag.equals("1")) throw new IllegalArgumentException("Invalid video flag");
            boolean video = "1".equals(videoFlag);
            String target = param(query, "target");
            if (target != null) requireUser(target);
            sip.makeCall(video, target);
            JsonObject o = ok();
            o.addProperty("video", video);
            o.addProperty("target", sip.getTargetUser());
            sendJson(ex, o);
        } catch (Exception e) {
            sendJson(ex, 400, err("呼叫失败，请检查分机号码和媒体状态"));
        }
    }

    private static void handleHangup(HttpExchange ex) {
        try {
            if (sip != null) sip.hangup();
            sendJson(ex, ok());
        } catch (Exception e) {
            sendJson(ex, 500, err("挂断失败"));
        }
    }

    // ---------------- 服务端 -> 浏览器（SSE） ----------------

    private static void handleStream(HttpExchange ex, WebAccessControl access) {
        if (!SSE_SLOTS.tryAcquire()) {
            ex.getResponseHeaders().set("Retry-After", "15");
            sendText(ex, 429, "TOO_MANY_STREAMS");
            return;
        }
        SseClient client = null;
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("Connection", "keep-alive");
            ex.sendResponseHeaders(200, 0);
            client = new SseClient(ex.getResponseBody(), () -> access.isAuthenticated(ex));
            CLIENTS.add(client);
            // 立即回一条 state，让页面知道自己连上了
            JsonObject hello = new JsonObject();
            hello.addProperty("t", "state");
            hello.addProperty("registered", sip != null && sip.isRegistered());
            hello.addProperty("me", MY_USER);
            hello.addProperty("peer", sip != null ? sip.getTargetUser() : PEER_USER);
            client.send("data: " + hello + "\n\n");
            // 群状态由前端 /api/groups 主动拉取并轮询；SSE 首帧只保留 state，
            // 避免绕过 WebAccessControl 的会话撤销检查。
            // 保持连接：定期发心跳，直到写失败（浏览器关闭）
            while (true) {
                Thread.sleep(15000);
                client.send(": ping\n\n");
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (client != null) { CLIENTS.remove(client); client.close(); }
            SSE_SLOTS.release();
            ex.close();
        }
    }

    /** 给所有浏览器推送一条事件 */
    private static void broadcast(JsonObject o) {
        if (CLIENTS.isEmpty()) return;
        String msg = "data: " + o + "\n\n";
        for (SseClient c : CLIENTS) {
            try {
                c.send(msg);
            } catch (Exception e) {
                CLIENTS.remove(c);
                c.close();
            }
        }
    }

    /** SIP 引擎回调 -> 转成 JSON 推给浏览器 */
    private static final class UiBridge implements SipVideoCallClient.Ui {
        @Override
        public void onMessage(String peer, String text, boolean outgoing) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "msg");
            o.addProperty("peer", peer);
            o.addProperty("text", text);
            o.addProperty("out", outgoing);
            broadcast(o);
        }

        @Override
        public void onFile(String peer, String type, String name, String path, boolean outgoing) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "file");
            o.addProperty("peer", peer);
            o.addProperty("ftype", type);
            o.addProperty("name", name);
            o.addProperty("url", "/files/" + name);
            o.addProperty("out", outgoing);
            broadcast(o);
        }

        @Override
        public void onCallState(String state, String peer) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "call");
            o.addProperty("state", state);
            o.addProperty("peer", peer);
            broadcast(o);
        }

        @Override
        public void onVideoFrame(byte[] jpeg, boolean local) {
            if (CLIENTS.isEmpty()) return; // 没人在看，省点 CPU
            JsonObject o = new JsonObject();
            o.addProperty("t", "video");
            o.addProperty("local", local);
            o.addProperty("img", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg));
            broadcast(o);
        }

        @Override
        public void onConferenceFrame(String from, byte[] jpeg) {
            if (CLIENTS.isEmpty()) return;
            JsonObject o = new JsonObject();
            o.addProperty("t", "cvideo");
            o.addProperty("from", from);
            o.addProperty("img", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg));
            broadcast(o);
        }

        @Override
        public void onConferencePeerLeft(String from) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "cvideo-left");
            o.addProperty("from", from);
            broadcast(o);
        }

        @Override
        public void onConferenceState(boolean on, String room) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "conf");
            o.addProperty("on", on);
            o.addProperty("room", room);
            broadcast(o);
        }
    }

    private static final class SseClient {
        private final OutputStream out;
        private final BooleanSupplier authorized;

        SseClient(OutputStream out, BooleanSupplier authorized) {
            this.out = out;
            this.authorized = authorized;
        }

        synchronized void send(String data) throws Exception {
            if (!authorized.getAsBoolean()) { close(); throw new IOException("SESSION_EXPIRED"); }
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void close() {
            try {
                out.close();
            } catch (Exception ignore) {
            }
        }
    }

    // ---------------- 文件访问 ----------------

    /** GET /files/<name> —— 把 received/ 下的文件提供给浏览器（图片/视频展示用） */
    private static void handleFiles(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            String name = sanitize(path.substring(path.lastIndexOf('/') + 1));
            File f = RECEIVED_DIR.resolve(name).toFile();
            if (!f.exists() || !f.isFile()) {
                sendText(ex, 404, "文件不存在");
                return;
            }
            byte[] body = Files.readAllBytes(f.toPath());
            String contentType = guessType(name);
            ex.getResponseHeaders().set("Content-Type", contentType);
            if ("application/octet-stream".equals(contentType)) {
                ex.getResponseHeaders().set("Content-Disposition", "attachment");
            }
            if ("HEAD".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        } catch (Exception e) {
            sendText(ex, 500, "读取文件失败");
        }
    }

    private static String guessType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }

    // ---------------- 工具 ----------------

    private static void serveResource(HttpExchange ex, String name, String contentType) throws Exception {
        try (InputStream in = WebClientServer.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                sendText(ex, 404, "资源不存在: " + name);
                return;
            }
            byte[] body = readAll(in);
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static void sendJson(HttpExchange ex, JsonObject o) { sendJson(ex, 200, o); }

    private static void sendJson(HttpExchange ex, int status, JsonObject o) {
        try {
            byte[] body = o.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        } catch (Exception ignore) {
        }
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static JsonObject err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return o;
    }

    private static void sendText(HttpExchange ex, int code, String text) {
        try {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(code, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        } catch (Exception ignore) {
        }
    }

    private static byte[] readBodyBytes(HttpExchange ex) throws Exception {
        return readAll(ex.getRequestBody());
    }

    private static String readBody(HttpExchange ex) throws Exception {
        return new String(readBodyBytes(ex), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String param(String query, String key) {
        if (query == null) return null;
        String result = null;
        for (String kv : query.split("&", -1)) {
            int i = kv.indexOf('=');
            if (i <= 0) throw new IllegalArgumentException("Invalid query");
            try {
                String name = java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8");
                String value = java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
                    throw new IllegalArgumentException("Invalid query value");
                if (name.equals(key)) {
                    if (result != null) throw new IllegalArgumentException("Duplicate query parameter");
                    result = value;
                }
            } catch (java.io.UnsupportedEncodingException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return result;
    }

    private static void requireUser(String user) {
        if (user == null || !user.matches("[A-Za-z0-9_.+*\\-]{1,64}"))
            throw new IllegalArgumentException("Invalid SIP user");
    }

    /** Legacy filename handling; receive/storage hardening lives in the independent file-security change. */
    private static String sanitize(String name) {
        String n = name.replace('\\', '/');
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        n = n.replace("..", "_");
        return n.isEmpty() ? "file.bin" : n;
    }

    private static String str(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : "";
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        String path = System.getProperty("sip.config", "config.properties");
        File f = new File(path);
        if (!f.exists()) return p;
        try (InputStream in = new FileInputStream(f);
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (Exception e) {
            System.err.println("读取 " + path + " 失败: " + e.getMessage());
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
            return def;
        }
    }
}

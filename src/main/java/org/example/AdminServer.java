package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台管理端（Web 版）。
 *
 * <p>用 JDK 自带的 HttpServer 起一个小服务，浏览器访问即可看到管理仪表盘：
 * <ul>
 *   <li>分机与在线状态（实时读 miniSIPServer）</li>
 *   <li>会议室列表（读 miniSIPServer）</li>
 *   <li>通话记录与统计（读客户端写的 logs/events.tsv）</li>
 *   <li>消息统计（同上，按类型/方向汇总）</li>
 * </ul>
 *
 * <p>鉴权：简单口令，登录后下发 adminsid 会话 Cookie。
 */
public class AdminServer {

    private static final Properties CONFIG = loadConfig();

    private static final int PORT = intCfg("admin.port", 8090);
    private static final String PASSWORD = cfg("admin.password", "1234");
    private static final String MSS_HOST = cfg("mss.web.host", cfg("sip.server.ip", "127.0.0.1"));
    private static final int MSS_PORT = intCfg("mss.web.port", 8080);
    private static final String MSS_PWD = cfg("mss.web.password", "1234");

    /**
     * 复用的 miniSIPServer 客户端：只登录一次，后续请求共用同一个会话。
     * （如果每次请求都重新登录，服务器会因频繁登录而拒绝，表现为 HTTP 302 拿不到会话）
     */
    private static final MssClient MSS = new MssClient(MSS_HOST, MSS_PORT, MSS_PWD);

    /** 已登录的会话令牌 */
    private static final Set<String> SESSIONS = ConcurrentHashMap.newKeySet();

    /** 客户端事件日志（由 SipVideoCallClient 写入） */
    private static final Path EVENT_FILE = Paths.get("logs", "events.tsv");

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", AdminServer::handleRoot);
        server.createContext("/login", AdminServer::handleLogin);
        server.createContext("/logout", AdminServer::handleLogout);
        server.createContext("/api/summary", AdminServer::handleSummary);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();

        System.out.println("=====================================================");
        System.out.println("  后台管理端已启动");
        System.out.println("  访问地址: http://localhost:" + PORT + "/");
        System.out.println("  管理员口令: " + (PASSWORD.isEmpty() ? "(空)" : PASSWORD));
        System.out.println("  miniSIPServer: " + MSS_HOST + ":" + MSS_PORT);
        System.out.println("  按 Ctrl+C 退出");
        System.out.println("=====================================================");
    }

    // ---------------- HTTP 处理 ----------------

    private static void handleRoot(HttpExchange ex) {
        try {
            if (!isAuthed(ex)) {
                serveResource(ex, "login.html", "text/html; charset=utf-8");
            } else {
                serveResource(ex, "dashboard.html", "text/html; charset=utf-8");
            }
        } catch (Exception e) {
            sendText(ex, 500, "服务器内部错误: " + e.getMessage());
        }
    }

    private static void handleLogin(HttpExchange ex) {
        try {
            String body = readBody(ex);
            String pwd = param(body, "password");
            if (pwd == null) {
                pwd = param(ex.getRequestURI().getRawQuery(), "password");
            }
            if (pwd != null && pwd.equals(PASSWORD)) {
                String token = UUID.randomUUID().toString();
                SESSIONS.add(token);
                ex.getResponseHeaders().set("Set-Cookie",
                        "adminsid=" + token + "; Path=/; HttpOnly");
                redirect(ex, "/");
            } else {
                redirect(ex, "/?error=1");
            }
        } catch (Exception e) {
            redirect(ex, "/?error=1");
        }
    }

    private static void handleLogout(HttpExchange ex) {
        try {
            ex.getResponseHeaders().set("Set-Cookie", "adminsid=; Path=/; Max-Age=0");
            redirect(ex, "/");
        } catch (Exception e) {
            // 忽略
        }
    }

    private static void handleSummary(HttpExchange ex) {
        try {
            if (!isAuthed(ex)) {
                sendText(ex, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            JsonObject summary = buildSummary();
            byte[] bytes = summary.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            sendText(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // ---------------- 汇总数据 ----------------

    private static JsonObject buildSummary() {
        JsonObject out = new JsonObject();

        // ---- 分机与在线状态（实时读 miniSIPServer）----
        JsonArray exts = new JsonArray();
        int online = 0, ringing = 0, busy = 0, offline = 0;
        boolean serverOnline = true;
        String serverError = "";
        try {
            JsonArray raw = MSS.list("localuser");
            for (int i = 0; i < raw.size(); i++) {
                JsonObject r = raw.get(i).getAsJsonObject();
                JsonObject e = new JsonObject();
                String user = str(r, "usrname");
                int state = r.has("state") && r.get("state").isJsonPrimitive() ? r.get("state").getAsInt() : 0;
                String stateText;
                switch (state) {
                    case 1: stateText = "在线"; online++; break;
                    case 2: stateText = "振铃"; ringing++; break;
                    case 3: stateText = "通话中"; busy++; break;
                    default: stateText = "离线"; offline++; break;
                }
                e.addProperty("user", user);
                e.addProperty("state", stateText);
                e.addProperty("addr", str(r, "addr"));
                e.addProperty("agent", str(r, "userAgent"));
                e.addProperty("desc", str(r, "description"));
                exts.add(e);
            }
        } catch (Exception e) {
            serverOnline = false;
            serverError = e.getMessage();
        }
        out.add("extensions", exts);
        out.addProperty("serverOnline", serverOnline);
        out.addProperty("serverError", serverError);
        out.addProperty("extTotal", exts.size());
        out.addProperty("extOnline", online);
        out.addProperty("extRinging", ringing);
        out.addProperty("extBusy", busy);
        out.addProperty("extOffline", offline);

        // ---- 会议室（实时读 miniSIPServer）----
        JsonArray rooms = new JsonArray();
        if (serverOnline) {
            try {
                JsonArray raw = MSS.list("confroom");
                for (int i = 0; i < raw.size(); i++) {
                    JsonObject r = raw.get(i).getAsJsonObject();
                    JsonObject room = new JsonObject();
                    room.addProperty("id", str(r, "roomID"));
                    room.addProperty("desc", str(r, "description"));
                    rooms.add(room);
                }
            } catch (Exception ignore) {
                // 会议室读取失败不影响其它数据
            }
        }
        out.add("rooms", rooms);
        out.addProperty("roomCount", rooms.size());

        // ---- 通话与消息统计（读客户端事件日志）----
        Stats stats = parseEvents();

        out.addProperty("callTotal", stats.callTotal);
        out.addProperty("callDurationTotal", stats.callDurationTotal);
        out.addProperty("callAvgDuration", stats.callTotal == 0 ? 0 : stats.callDurationTotal / stats.callTotal);

        // 按对端统计通话
        JsonArray perPeer = new JsonArray();
        List<String> peers = new ArrayList<>(stats.callsPerPeer.keySet());
        Collections.sort(peers, (a, b) -> stats.callsPerPeer.get(b) - stats.callsPerPeer.get(a));
        for (String p : peers) {
            JsonObject o = new JsonObject();
            o.addProperty("peer", p);
            o.addProperty("count", stats.callsPerPeer.get(p));
            o.addProperty("duration", stats.durationPerPeer.getOrDefault(p, 0L));
            perPeer.add(o);
        }
        out.add("callPerPeer", perPeer);

        // 最近通话
        JsonArray recentCalls = new JsonArray();
        int from = Math.max(0, stats.recentCalls.size() - 15);
        for (int i = stats.recentCalls.size() - 1; i >= from; i--) {
            recentCalls.add(stats.recentCalls.get(i));
        }
        out.add("recentCalls", recentCalls);

        // 消息按类型/方向
        out.addProperty("msgTotal", stats.msgTotal);
        JsonArray msgByType = new JsonArray();
        for (String t : new String[]{"TEXT", "IMG", "VOICE", "VIDEO"}) {
            JsonObject o = new JsonObject();
            o.addProperty("type", t);
            o.addProperty("out", stats.msgOut.getOrDefault(t, 0));
            o.addProperty("in", stats.msgIn.getOrDefault(t, 0));
            msgByType.add(o);
        }
        out.add("msgByType", msgByType);

        return out;
    }

    /** 从 logs/events.tsv 解析出统计结果 */
    private static Stats parseEvents() {
        Stats s = new Stats();
        File f = EVENT_FILE.toFile();
        if (!f.exists()) {
            return s;
        }
        try {
            List<String> lines = Files.readAllLines(EVENT_FILE, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split("\t", -1);
                if (p.length < 5) continue;
                String time = p[0], kind = p[1], dir = p[2], peer = p[3], extra = p[4];

                if ("CALL_END".equals(kind)) {
                    long dur = 0;
                    try { dur = Long.parseLong(extra.trim()); } catch (Exception ignore) {}
                    s.callTotal++;
                    s.callDurationTotal += dur;
                    s.callsPerPeer.merge(peer, 1, Integer::sum);
                    s.durationPerPeer.merge(peer, dur, (a, b) -> a + b);
                    JsonObject c = new JsonObject();
                    c.addProperty("time", time);
                    c.addProperty("dir", dir);
                    c.addProperty("peer", peer);
                    c.addProperty("duration", dur);
                    s.recentCalls.add(c);
                } else if ("MSG".equals(kind)) {
                    s.msgTotal++;
                    String type = extra.contains(":") ? extra.substring(0, extra.indexOf(':')) : extra;
                    if ("OUT".equals(dir)) {
                        s.msgOut.merge(type, 1, Integer::sum);
                    } else {
                        s.msgIn.merge(type, 1, Integer::sum);
                    }
                }
            }
        } catch (Exception ignore) {
            // 日志解析失败就返回已统计的部分
        }
        return s;
    }

    private static class Stats {
        int callTotal = 0;
        long callDurationTotal = 0;
        final java.util.Map<String, Integer> callsPerPeer = new java.util.HashMap<>();
        final java.util.Map<String, Long> durationPerPeer = new java.util.HashMap<>();
        final List<JsonObject> recentCalls = new ArrayList<>();
        int msgTotal = 0;
        final java.util.Map<String, Integer> msgOut = new java.util.HashMap<>();
        final java.util.Map<String, Integer> msgIn = new java.util.HashMap<>();
    }

    // ---------------- 工具 ----------------

    private static boolean isAuthed(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return false;
        for (String part : cookie.split(";")) {
            String kv = part.trim();
            if (kv.startsWith("adminsid=")) {
                return SESSIONS.contains(kv.substring("adminsid=".length()));
            }
        }
        return false;
    }

    private static void serveResource(HttpExchange ex, String name, String contentType) throws Exception {
        try (InputStream in = AdminServer.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                sendText(ex, 404, "资源不存在: " + name);
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] body = bos.toByteArray();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
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

    private static void redirect(HttpExchange ex, String location) {
        try {
            ex.getResponseHeaders().set("Location", location);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        } catch (Exception ignore) {
        }
    }

    private static String readBody(HttpExchange ex) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        InputStream in = ex.getRequestBody();
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 从 a=b&amp;c=d 形式的字符串里取参数（做 URL 解码） */
    private static String param(String query, String key) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int idx = kv.indexOf('=');
            if (idx > 0 && kv.substring(0, idx).equals(key)) {
                try {
                    return java.net.URLDecoder.decode(kv.substring(idx + 1), "UTF-8");
                } catch (Exception e) {
                    return kv.substring(idx + 1);
                }
            }
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : "";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        String path = System.getProperty("sip.config", "config.properties");
        File f = new File(path);
        if (!f.exists()) {
            return p;
        }
        try (InputStream in = new FileInputStream(f);
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
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
            return def;
        }
    }
}

package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * miniSIPServer Web 管理接口的轻量客户端。
 *
 * <p>接口约定（从服务器自带的前端 JS 反推得到，见 httpd/shared/js/*.js）：
 * <pre>
 *   GET /authorize?password=xxx                       -&gt; Set-Cookie: session=...
 *   GET /dbajax/&lt;table&gt;/list?content={"pageIndex":0}  -&gt; JSON 数组
 * </pre>
 *
 * <p>常用的 table：
 * <ul>
 *   <li>localuser —— 分机（含在线状态 state / IP / userAgent）</li>
 *   <li>confroom  —— 会议室</li>
 * </ul>
 */
public class MssClient {

    private final String host;
    private final int port;
    private final String password;
    private String session;

    public MssClient(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    private String base() {
        return "http://" + host + ":" + port;
    }

    /** 登录并拿到会话 cookie */
    public void login() throws Exception {
        URL url = new URL(base() + "/authorize?password=" + URLEncoder.encode(password, "UTF-8"));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int code = conn.getResponseCode();
        String cookie = conn.getHeaderField("Set-Cookie");
        conn.disconnect();
        if (cookie == null || cookie.trim().isEmpty()) {
            throw new Exception("miniSIPServer 登录失败（HTTP " + code
                    + "）：请检查 Web 管理端口与管理员密码是否正确。");
        }
        this.session = cookie.split(";")[0].trim();
    }

    /** 调用 /dbajax/&lt;table&gt;/list，返回 JSON 数组（无数据时返回空数组） */
    public synchronized JsonArray list(String table) throws Exception {
        if (session == null) login();
        String url = base() + "/dbajax/" + table + "/list?content="
                + URLEncoder.encode("{\"pageIndex\":0}", "UTF-8");
        String body = get(url, true);
        if (body == null || body.trim().isEmpty()) {
            return new JsonArray();
        }
        JsonElement el = JsonParser.parseString(body);
        return el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
    }

    /** 带会话的 GET；会话失效（401/403/302 跳登录）时自动重登一次 */
    private String get(String url, boolean reloginOnFail) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);
        if (session != null) {
            conn.setRequestProperty("Cookie", session);
        }
        int code = conn.getResponseCode();
        boolean sessionInvalid = (code == 401 || code == 403 || (code >= 300 && code < 400));
        if (sessionInvalid && reloginOnFail) {
            conn.disconnect();
            session = null;
            login();
            return get(url, false);
        }
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(in);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("miniSIPServer 接口返回 HTTP " + code);
        }
        return body;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}

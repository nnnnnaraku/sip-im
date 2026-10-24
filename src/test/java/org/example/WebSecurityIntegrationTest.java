package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** Real ephemeral loopback HTTP, fake SIP/MSS/device operations, and temporary files only. */
public class WebSecurityIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private final AtomicLong now = new AtomicLong(1000000L);
    private HttpServer server;
    private ExecutorService executor;
    private String base;
    private String cookie;
    private String csrf;
    private Path received;
    private final FakeSip fakeSip = new FakeSip();
    private final FakeMss fakeMss = new FakeMss();
    private Object oldSip, oldMss, oldReceived;
    private final List<Stream> streams = new ArrayList<Stream>();

    @Before public void setup() throws Exception {
        received = temporary.newFolder("received").toPath();
        oldSip = replace("sip", fakeSip);
        oldMss = replace("MSS", fakeMss);
        oldReceived = replace("RECEIVED_DIR", received);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        int port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;
        WebAccessControl access = new WebAccessControl(SECRET, "http",
                Collections.singleton("127.0.0.1:" + port), true, now::get);
        WebClientServer.registerRoutes(server, access);
        executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "web-security-loopback-test");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
    }

    @After public void cleanup() throws Exception {
        for (Stream stream : streams) stream.close();
        if (server != null) server.stop(0);
        if (executor != null) {
            executor.shutdownNow();
            assertTrue("test HTTP executor must terminate", executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        closeTrackedStreams();
        replace("sip", oldSip);
        replace("MSS", oldMss);
        replace("RECEIVED_DIR", oldReceived);
    }

    @Test public void publicPageAndScriptRemainAvailableWithoutSecretsOrCookie() throws Exception {
        Result page = request("GET", "/", null, false, false);
        assertEquals(200, page.status);
        assertTrue(page.text.contains("accessToken"));
        assertTrue(page.text.contains("/web-access.js"));
        assertFalse(page.text.contains(SECRET));
        assertNull(page.header("Set-Cookie"));
        assertEquals("no-store", page.header("Cache-Control"));
        assertEquals("DENY", page.header("X-Frame-Options"));
        assertEquals("nosniff", page.header("X-Content-Type-Options"));
        Result script = request("GET", "/web-access.js", null, false, false);
        assertEquals(200, script.status);
        assertTrue(script.text.contains("WebAccess"));
        assertNull(script.header("Access-Control-Allow-Origin"));
        assertEquals(0, fakeSip.calls.get());
        assertEquals(0, fakeMss.lists.get());
    }

    @Test public void anonymousStateContactsSseFilesAndControlsAreDenied() throws Exception {
        for (String path : Arrays.asList("/api/state", "/api/contacts", "/api/stream", "/files/a.png")) {
            assertEquals(path, 401, request("GET", path, null, false, false).status);
        }
        for (String path : Arrays.asList("/api/send", "/api/call", "/api/hangup", "/api/record/start", "/api/setpeer?user=102", "/api/file?type=IMG&name=a.png")) {
            assertEquals(path, 401, request("POST", path, bytes("x"), false, false).status);
        }
        assertEquals(0, fakeSip.calls.get());
        assertEquals(0, fakeMss.lists.get());
        assertEquals(0, received.toFile().list().length);
    }

    @Test public void loginCookieRestoresSessionWithoutExposingAccessSecret() throws Exception {
        Result result = login();
        assertEquals(200, result.status);
        assertTrue(result.header("Set-Cookie").contains("HttpOnly"));
        assertTrue(result.header("Set-Cookie").contains("SameSite=Strict"));
        assertFalse(result.header("Set-Cookie").contains("Domain="));
        assertFalse(result.text.contains(SECRET));
        Result session = request("GET", "/api/session", null, true, false);
        assertEquals(200, session.status);
        assertEquals(csrf, json(session).get("csrfToken").getAsString());
        assertTrue(json(session).get("authenticated").getAsBoolean());
    }

    @Test public void wrongTokenAndMissingLoginMarkerFailWithoutSip() throws Exception {
        Result wrong = request("POST", "/api/session/login", bytes("{\"token\":\"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB\"}"), false, false,
                "Content-Type", "application/json", "X-SIP-Login", "1");
        assertEquals(401, wrong.status);
        Result missing = request("POST", "/api/session/login", bytes("{\"token\":\"" + SECRET + "\"}"), false, false,
                "Content-Type", "application/json");
        assertEquals(403, missing.status);
        assertNull(wrong.header("Set-Cookie"));
        assertEquals(0, fakeSip.calls.get());
    }

    @Test public void stateAndContactsUseOnlyInjectedFakes() throws Exception {
        login();
        Result state = request("GET", "/api/state", null, true, false);
        assertEquals(200, state.status);
        assertTrue(json(state).get("registered").getAsBoolean());
        assertEquals("101", json(state).get("peer").getAsString());
        Result contacts = request("GET", "/api/contacts", null, true, false);
        assertEquals(200, contacts.status);
        assertEquals("101", json(contacts).getAsJsonArray("contacts").get(0).getAsJsonObject().get("user").getAsString());
        assertEquals(1, fakeMss.lists.get());
    }

    @Test public void legitimateTextAndContactSelectionPreserveOriginalApi() throws Exception {
        login();
        assertOk(request("POST", "/api/send", bytes("中文 hello <>&"), true, true));
        assertEquals("中文 hello <>&", fakeSip.lastText);
        assertEquals(1, fakeSip.messages.get());
        assertOk(request("POST", "/api/setpeer?user=102", null, true, true));
        assertEquals("102", fakeSip.target);
        assertEquals("102", json(request("GET", "/api/state", null, true, false)).get("peer").getAsString());
    }

    @Test public void legitimateCallHangupAndRecordingUseOnlyFakeCounters() throws Exception {
        login();
        assertOk(request("POST", "/api/call?video=1&target=102", null, true, true));
        assertTrue(fakeSip.video);
        assertEquals("102", fakeSip.target);
        assertOk(request("POST", "/api/hangup", null, true, true));
        assertOk(request("POST", "/api/record?sec=4", null, true, true));
        assertEquals(4, fakeSip.seconds);
        assertOk(request("POST", "/api/record/start", null, true, true));
        assertOk(request("POST", "/api/record/stop", null, true, true));
        assertEquals(1, fakeSip.dials.get());
        assertEquals(1, fakeSip.hangups.get());
        assertEquals(1, fakeSip.recordStarts.get());
        assertEquals(1, fakeSip.recordStops.get());
    }

    @Test public void controlsRequireCsrfEvenWithValidCookie() throws Exception {
        login();
        for (String path : Arrays.asList("/api/send", "/api/call", "/api/hangup", "/api/record/start", "/api/file?type=IMG&name=x.png", "/api/setpeer?user=102")) {
            assertEquals(path, 403, request("POST", path, bytes("x"), true, false).status);
        }
        assertEquals(403, request("POST", "/api/send", bytes("x"), true, false, "X-SIP-CSRF", "wrong").status);
        assertEquals(0, fakeSip.calls.get());
        assertEquals(0, received.toFile().list().length);
    }

    @Test public void wrongMethodsAreRejectedBeforeAnyAction() throws Exception {
        login();
        assertEquals(405, request("GET", "/api/call", null, true, false).status);
        assertEquals(405, request("POST", "/api/state", null, true, true).status);
        assertEquals(405, request("PUT", "/api/send", bytes("x"), true, true).status);
        assertEquals(405, request("OPTIONS", "/api/send", null, true, false).status);
        assertEquals(405, request("POST", "/", null, false, false).status);
        assertEquals(0, fakeSip.calls.get());
    }

    @Test public void crossOriginWithForgedCookieAndCsrfStillFails() throws Exception {
        login();
        String raw = raw("POST /api/send HTTP/1.1\r\nHost: " + authority() + "\r\nOrigin: https://evil.invalid\r\nCookie: " + cookie
                + "\r\nX-SIP-CSRF: " + csrf + "\r\nContent-Length: 4\r\nConnection: close\r\n\r\ntext");
        assertTrue(raw.startsWith("HTTP/1.1 403"));
        assertTrue(raw.contains("ORIGIN_DENIED"));
        assertEquals(0, fakeSip.calls.get());
    }

    @Test public void invalidHostAndFetchMetadataAreRejectedOnRealWire() throws Exception {
        login();
        String invalidHost = raw("GET /api/state HTTP/1.1\r\nHost: rebound.invalid:" + server.getAddress().getPort()
                + "\r\nCookie: " + cookie + "\r\nConnection: close\r\n\r\n");
        assertTrue(invalidHost.startsWith("HTTP/1.1 403"));
        assertTrue(invalidHost.contains("HOST_DENIED"));
        String site = raw("GET /api/state HTTP/1.1\r\nHost: " + authority() + "\r\nCookie: " + cookie
                + "\r\nSec-Fetch-Site: cross-site\r\nConnection: close\r\n\r\n");
        assertTrue(site.startsWith("HTTP/1.1 403"));
        assertTrue(site.contains("CROSS_SITE_DENIED"));
    }

    @Test public void normalSameOriginHeaderIsAcceptedWithoutCors() throws Exception {
        login();
        String result = raw("POST /api/send HTTP/1.1\r\nHost: " + authority() + "\r\nOrigin: " + base + "\r\nCookie: " + cookie
                + "\r\nX-SIP-CSRF: " + csrf + "\r\nSec-Fetch-Site: same-origin\r\nContent-Length: 4\r\nConnection: close\r\n\r\ntext");
        assertTrue(result.startsWith("HTTP/1.1 200"));
        assertEquals("text", fakeSip.lastText);
        assertFalse(result.toLowerCase().contains("access-control-allow-origin"));
    }

    @Test public void oversizedAndMalformedUtf8TextCannotReachSip() throws Exception {
        login();
        assertEquals(413, request("POST", "/api/send", new byte[16 * 1024 + 1], true, true).status);
        assertEquals(400, request("POST", "/api/send", new byte[]{(byte) 0xc3, (byte) 0x28}, true, true).status);
        assertEquals(413, request("POST", "/api/hangup", bytes("unexpected"), true, true).status);
        assertEquals(0, fakeSip.calls.get());
    }

    @Test public void authorizedAttachmentUploadAndDownloadRemainCompatible() throws Exception {
        login();
        byte[] sample = new byte[]{0, 1, 2, (byte) 255, 10};
        for (String type : Arrays.asList("IMG", "VOICE", "VIDEO")) {
            String name = "sample-" + type + ".bin";
            assertOk(request("POST", "/api/file?type=" + type + "&name=" + name, sample, true, true));
            assertEquals(type, fakeSip.lastFileType);
            assertArrayEquals(sample, fakeSip.lastFileBytes);
            assertEquals(401, request("GET", "/files/" + name, null, false, false).status);
            Result file = request("GET", "/files/" + name, null, true, false);
            assertEquals(200, file.status);
            assertArrayEquals(sample, file.body);
        }
        assertEquals(3, fakeSip.files.get());
    }

    @Test public void establishedSseGetsStateThenProtectedBroadcast() throws Exception {
        login();
        Stream stream = stream();
        assertEquals(200, stream.connection.getResponseCode());
        assertTrue(stream.connection.getContentType().startsWith("text/event-stream"));
        String hello = stream.reader.readLine();
        assertTrue(hello.startsWith("data: "));
        JsonObject state = JsonParser.parseString(hello.substring(6)).getAsJsonObject();
        assertEquals("state", state.get("t").getAsString());
        assertTrue(state.get("registered").getAsBoolean());
        assertEquals("", stream.reader.readLine());
        JsonObject event = new JsonObject(); event.addProperty("t", "msg"); event.addProperty("text", "synthetic-broadcast");
        broadcast(event);
        assertTrue(stream.reader.readLine().contains("synthetic-broadcast"));
    }

    @Test public void logoutRevokesEstablishedSseBeforeFurtherBroadcast() throws Exception {
        login();
        Stream stream = stream();
        assertTrue(stream.reader.readLine().startsWith("data: "));
        assertEquals("", stream.reader.readLine());
        Result logout = request("POST", "/api/session/logout", null, true, true);
        assertEquals(200, logout.status);
        assertTrue(logout.header("Set-Cookie").contains("Max-Age=0"));
        JsonObject event = new JsonObject(); event.addProperty("t", "msg"); event.addProperty("text", "must-not-arrive");
        broadcast(event);
        assertEquals(0, trackedStreams().size());
        try { assertNull("revoked SSE must end without protected event", stream.reader.readLine()); }
        catch (java.net.SocketException closed) { /* an actively closed HTTP stream is also terminal */ }
        assertEquals(401, request("GET", "/api/state", null, true, false).status);
        assertEquals(401, request("POST", "/api/send", bytes("x"), true, true).status);
        assertEquals(0, fakeSip.calls.get());
    }

    @Test public void fifthConcurrentSseIsRejectedAndOriginalFourRemainOpen() throws Exception {
        login();
        for (int i = 0; i < 4; i++) {
            Stream stream = stream();
            assertTrue(stream.reader.readLine().startsWith("data: "));
            assertEquals("", stream.reader.readLine());
        }
        Result fifth = request("GET", "/api/stream", null, true, false);
        assertEquals(429, fifth.status);
        assertEquals("15", fifth.header("Retry-After"));
        assertEquals(4, trackedStreams().size());
    }

    @Test public void expiredSessionBlocksSseBroadcastAndNewControls() throws Exception {
        login();
        Stream stream = stream(); stream.reader.readLine(); stream.reader.readLine();
        now.addAndGet(31L * 60 * 1000);
        JsonObject event = new JsonObject(); event.addProperty("t", "state"); broadcast(event);
        assertEquals(0, trackedStreams().size());
        assertEquals(401, request("POST", "/api/call", null, true, true).status);
        assertEquals(0, fakeSip.calls.get());
    }

    private Result login() throws Exception {
        Result result = request("POST", "/api/session/login", bytes("{\"token\":\"" + SECRET + "\"}"), false, false,
                "Content-Type", "application/json;charset=UTF-8", "X-SIP-Login", "1");
        assertEquals(200, result.status);
        cookie = result.header("Set-Cookie").split(";", 2)[0];
        csrf = json(result).get("csrfToken").getAsString();
        return result;
    }

    private Result request(String method, String path, byte[] body, boolean withCookie, boolean withCsrf, String... headers) throws Exception {
        HttpURLConnection connection = connection(path, withCookie);
        connection.setRequestMethod(method);
        if (withCsrf) connection.setRequestProperty("X-SIP-CSRF", csrf);
        for (int i = 0; i < headers.length; i += 2) connection.setRequestProperty(headers[i], headers[i + 1]);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        }
        try {
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] data = input == null ? new byte[0] : read(input);
            return new Result(status, data, connection.getHeaderFields());
        } finally { connection.disconnect(); }
    }

    private HttpURLConnection connection(String path, boolean withCookie) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setInstanceFollowRedirects(false);
        if (withCookie) connection.setRequestProperty("Cookie", cookie);
        return connection;
    }

    private Stream stream() throws Exception {
        HttpURLConnection connection = connection("/api/stream", true);
        Stream stream = new Stream(connection, new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)));
        streams.add(stream);
        return stream;
    }

    private String raw(String request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(server.getAddress(), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(read(socket.getInputStream()), StandardCharsets.UTF_8);
        }
    }

    private static byte[] read(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = source.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > 2 * 1024 * 1024) throw new IOException("Unexpected test response size");
            }
            return output.toByteArray();
        }
    }

    private String authority() { return "127.0.0.1:" + server.getAddress().getPort(); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JsonObject json(Result result) { return JsonParser.parseString(result.text).getAsJsonObject(); }
    private static void assertOk(Result result) { assertEquals(200, result.status); assertTrue(result.text, json(result).get("ok").getAsBoolean()); }
    private static Object replace(String name, Object value) throws Exception {
        Field field = WebClientServer.class.getDeclaredField(name); field.setAccessible(true);
        Object old = field.get(null); field.set(null, value); return old;
    }
    private static Set<?> trackedStreams() throws Exception {
        Field field = WebClientServer.class.getDeclaredField("CLIENTS"); field.setAccessible(true); return (Set<?>) field.get(null);
    }
    private static void closeTrackedStreams() throws Exception {
        Set<?> clients = trackedStreams();
        for (Object client : clients.toArray()) {
            Method close = client.getClass().getDeclaredMethod("close"); close.setAccessible(true); close.invoke(client);
        }
        clients.clear();
    }
    private static void broadcast(JsonObject event) throws Exception {
        Method method = WebClientServer.class.getDeclaredMethod("broadcast", JsonObject.class); method.setAccessible(true); method.invoke(null, event);
    }

    private static final class Result {
        final int status; final byte[] body; final String text; final Map<String, List<String>> headers;
        Result(int status, byte[] body, Map<String, List<String>> headers) { this.status = status; this.body = body; this.text = new String(body, StandardCharsets.UTF_8); this.headers = headers; }
        String header(String name) { for (Map.Entry<String, List<String>> item : headers.entrySet()) if (name.equalsIgnoreCase(item.getKey())) return item.getValue().get(0); return null; }
    }
    private static final class Stream {
        final HttpURLConnection connection; final BufferedReader reader;
        Stream(HttpURLConnection connection, BufferedReader reader) { this.connection = connection; this.reader = reader; }
        void close() { connection.disconnect(); try { reader.close(); } catch (IOException ignored) { } }
    }
    private static final class FakeMss extends MssClient {
        final AtomicInteger lists = new AtomicInteger();
        FakeMss() { super("127.0.0.1", 1, "unused-test-only"); }
        @Override public synchronized JsonArray list(String table) {
            assertEquals("localuser", table); lists.incrementAndGet();
            JsonArray rows = new JsonArray(); JsonObject row = new JsonObject(); row.addProperty("usrname", "101"); row.addProperty("state", 1); rows.add(row); return rows;
        }
        @Override public void login() { fail("Fake MSS must not attempt real authentication"); }
    }
    private static final class FakeSip extends SipVideoCallClient {
        final AtomicInteger calls = new AtomicInteger(), messages = new AtomicInteger(), dials = new AtomicInteger(), hangups = new AtomicInteger();
        final AtomicInteger recordStarts = new AtomicInteger(), recordStops = new AtomicInteger(), files = new AtomicInteger();
        volatile String target = "101", lastText, lastFileType;
        volatile byte[] lastFileBytes;
        volatile boolean video;
        volatile int seconds;
        @Override public void init() { fail("SIP initialization is forbidden in HTTP integration tests"); }
        @Override public boolean isRegistered() { return true; }
        @Override public String getTargetUser() { return target; }
        @Override public void setTargetUser(String user) { target = user; calls.incrementAndGet(); }
        @Override public void sendMessage(String text) { lastText = text; messages.incrementAndGet(); calls.incrementAndGet(); }
        @Override public void makeCall(boolean video, String target) { this.video = video; if (target != null) this.target = target; dials.incrementAndGet(); calls.incrementAndGet(); }
        @Override public void hangup() { hangups.incrementAndGet(); calls.incrementAndGet(); }
        @Override public void recordAndSendVoice(int seconds) { this.seconds = seconds; calls.incrementAndGet(); }
        @Override public synchronized void startVoiceRecord() { recordStarts.incrementAndGet(); calls.incrementAndGet(); }
        @Override public synchronized String stopVoiceRecordAndSend() { recordStops.incrementAndGet(); calls.incrementAndGet(); return "synthetic.wav"; }
        @Override public void sendFile(File file, String type) throws Exception { lastFileType = type; lastFileBytes = Files.readAllBytes(file.toPath()); files.incrementAndGet(); calls.incrementAndGet(); }
    }
}

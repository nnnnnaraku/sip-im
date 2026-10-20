package org.example;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** Pure exchanges only: no HTTP listener, SIP stack, camera or user file is opened. */
public class WebAccessControlTest {
    private static final String SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private final AtomicLong now = new AtomicLong(1000000L);
    private final AtomicInteger calls = new AtomicInteger();
    private WebAccessControl access;
    private HttpHandler wrapped;

    @Before public void setup() {
        access = guard(18081);
        wrapped = access.wrap(exchange -> {
            calls.incrementAndGet();
            ByteArrayOutputStream input = new ByteArrayOutputStream();
            byte[] block = new byte[1024];
            int count;
            while ((count = exchange.getRequestBody().read(block)) != -1) input.write(block, 0, count);
            byte[] output = input.size() == 0 ? "DELEGATE".getBytes(StandardCharsets.UTF_8) : input.toByteArray();
            exchange.sendResponseHeaders(200, output.length);
            exchange.getResponseBody().write(output);
            exchange.close();
        });
    }

    private WebAccessControl guard(int port) {
        return new WebAccessControl(SECRET, "http", new HashSet<String>(Arrays.asList(
                "localhost:" + port, "127.0.0.1:" + port)), true, now::get);
    }

    @Test public void publicPageHasNoCapabilityOrSession() throws Exception {
        Exchange exchange = run(new Exchange("GET", "/", ""));
        assertEquals(200, exchange.status);
        assertNull(exchange.response.getFirst("Set-Cookie"));
        assertFalse(exchange.text().contains(SECRET));
        assertEquals("no-store", exchange.response.getFirst("Cache-Control"));
        assertEquals("nosniff", exchange.response.getFirst("X-Content-Type-Options"));
        assertEquals("DENY", exchange.response.getFirst("X-Frame-Options"));
    }

    @Test public void publicScriptsRemainAvailable() throws Exception {
        assertEquals(200, run(new Exchange("GET", "/web-access.js", "")).status);
        assertEquals(200, run(new Exchange("GET", "/group-chat.js", "")).status);
    }

    @Test public void publicRootRejectsPost() throws Exception {
        Exchange result = run(new Exchange("POST", "/", ""));
        assertEquals(405, result.status);
        assertEquals("GET", result.response.getFirst("Allow"));
        assertEquals(0, calls.get());
    }

    @Test public void anonymousApiNeverReadsBodyOrCallsDelegate() throws Exception {
        Exchange exchange = new Exchange("POST", "/api/send", "secret text");
        run(exchange);
        assertEquals(401, exchange.status);
        assertEquals(0, exchange.reads);
        assertEquals(0, calls.get());
    }

    @Test public void anonymousStateSseFileAndUnknownAreProtected() throws Exception {
        for (String path : Arrays.asList("/api/state", "/api/contacts", "/api/stream", "/files/a.png", "/api/unknown")) {
            assertEquals(path, 401, run(new Exchange("GET", path, "")).status);
        }
    }

    @Test public void loginSetsHttpOnlyStrictCookieAndReturnsOnlyCsrf() throws Exception {
        Exchange exchange = loginExchange(SECRET);
        run(exchange);
        assertEquals(200, exchange.status);
        String cookie = exchange.response.getFirst("Set-Cookie");
        assertTrue(cookie.startsWith("SIPIMSESSION_18081="));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertFalse(cookie.contains("Secure"));
        assertFalse(exchange.text().contains(SECRET));
        assertEquals(43, csrf(exchange).length());
        assertTrue(JsonParser.parseString(exchange.text()).getAsJsonObject().get("authenticated").getAsBoolean());
    }

    @Test public void secureRemoteCookieRequiresHttps() throws Exception {
        WebAccessControl secure = new WebAccessControl(SECRET, "https", Collections.singleton("localhost:18081"), false, now::get);
        Exchange exchange = loginExchange(SECRET);
        secure.wrap(ex -> fail("Login is built in")).handle(exchange);
        assertEquals(200, exchange.status);
        assertTrue(exchange.response.getFirst("Set-Cookie").contains("; Secure"));
    }

    @Test(expected = IllegalArgumentException.class) public void remoteHttpIsRejectedAtConstruction() {
        new WebAccessControl(SECRET, "http", Collections.singleton("localhost:18081"), false);
    }

    @Test(expected = IllegalArgumentException.class) public void weakCapabilityIsRejectedAtConstruction() {
        new WebAccessControl("password", "http", Collections.singleton("localhost:18081"), true);
    }

    @Test(expected = IllegalArgumentException.class) public void authoritiesMustShareCookiePort() {
        new WebAccessControl(SECRET, "http", new HashSet<String>(Arrays.asList("localhost:1", "localhost:2")), true);
    }

    @Test public void badCapabilityIsRejected() throws Exception {
        assertEquals(401, run(loginExchange("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")).status);
    }

    @Test public void loginRequiresCustomHeader() throws Exception {
        Exchange exchange = loginExchange(SECRET);
        exchange.request.remove("X-SIP-Login");
        assertEquals(403, run(exchange).status);
    }

    @Test public void loginRejectsFormAndTextBodies() throws Exception {
        for (String type : Arrays.asList("text/plain", "application/x-www-form-urlencoded", "multipart/form-data")) {
            Exchange exchange = loginExchange(SECRET);
            exchange.request.set("Content-Type", type);
            assertEquals(415, run(exchange).status);
        }
    }

    @Test public void loginRejectsDuplicateAndUnexpectedJsonFields() throws Exception {
        for (String body : Arrays.asList("{}", "{\"token\":null}", "{\"token\":true}",
                "{\"token\":\"x\",\"token\":\"x\"}", "{\"other\":\"x\"}", "[]", "{\"token\":\"x\"}[]")) {
            Exchange exchange = new Exchange("POST", "/api/session/login", body);
            exchange.request.set("X-SIP-Login", "1");
            exchange.request.set("Content-Type", "application/json");
            assertEquals(body, 400, run(exchange).status);
        }
    }

    @Test public void loginJsonRejectsRawControlsAndMalformedUtf8() throws Exception {
        Exchange control = loginExchange(SECRET);
        control.setBody("{\"token\":\"a\nb\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, run(control).status);
        Exchange utf8 = loginExchange(SECRET);
        utf8.setBody(new byte[]{(byte) 0xc3, (byte) 0x28});
        assertEquals(400, run(utf8).status);
    }

    @Test public void loginHas1024ByteLimit() throws Exception {
        Exchange exchange = loginExchange(SECRET);
        exchange.setBody(new byte[1025]);
        assertEquals(413, run(exchange).status);
    }

    @Test public void loginQueryIsForbidden() throws Exception {
        Exchange exchange = loginExchange(SECRET);
        exchange.uri = URI.create("/api/session/login?token=unsafe");
        assertEquals(400, run(exchange).status);
        assertFalse(exchange.text().contains("unsafe"));
    }

    @Test public void sessionLookupReturnsExistingCsrf() throws Exception {
        Auth auth = auth();
        Exchange exchange = authorized("GET", "/api/session", "", auth);
        assertEquals(200, run(exchange).status);
        assertEquals(auth.csrf, csrf(exchange));
    }

    @Test public void apiNeedsValidSessionNotOnlySameOrigin() throws Exception {
        Exchange exchange = new Exchange("POST", "/api/call", "");
        exchange.request.set("Origin", "http://localhost:18081");
        exchange.request.set("X-SIP-CSRF", "untrusted");
        assertEquals(401, run(exchange).status);
    }

    @Test public void forgedSessionCookieDoesNotAuthenticate() throws Exception {
        Exchange exchange = new Exchange("GET", "/api/state", "");
        exchange.request.set("Cookie", "SIPIMSESSION_18081=" + SECRET);
        assertEquals(401, run(exchange).status);
    }

    @Test public void controlNeedsCorrectCsrf() throws Exception {
        Auth auth = auth();
        Exchange missing = authorized("POST", "/api/call", "", auth);
        missing.request.remove("X-SIP-CSRF");
        assertEquals(403, run(missing).status);
        Exchange wrong = authorized("POST", "/api/send", "hello", auth);
        wrong.request.set("X-SIP-CSRF", "incorrect");
        assertEquals(403, run(wrong).status);
        assertEquals(0, calls.get());
    }

    @Test public void legitimateBrowserControlAndNativeControlBothWork() throws Exception {
        Auth auth = auth();
        Exchange browser = authorized("POST", "/api/send", "中文 hello <>&", auth);
        browser.request.set("Origin", "http://localhost:18081");
        browser.request.set("Sec-Fetch-Site", "same-origin");
        assertEquals(200, run(browser).status);
        assertEquals("中文 hello <>&", browser.text());
        assertEquals(200, run(authorized("POST", "/api/call", "", auth)).status);
        assertEquals(2, calls.get());
    }

    @Test public void hostileOriginsCannotLoginOrControl() throws Exception {
        Auth auth = auth();
        for (String origin : Arrays.asList("null", "http://evil.example", "http://localhost:9999", "https://localhost:18081",
                "http://localhost:18081/", "http://localhost:18081?x=1", "http://localhost:18081#x",
                "http://user@localhost:18081", "http://localhost:18081.evil.example")) {
            Exchange exchange = authorized("POST", "/api/call", "", auth);
            exchange.request.set("Origin", origin);
            assertEquals(origin, 403, run(exchange).status);
        }
    }

    @Test public void sameSiteSiblingOriginIsRejectedEvenWithValidCredentials() throws Exception {
        Auth auth = auth();
        Exchange exchange = authorized("POST", "/api/call", "", auth);
        exchange.request.set("Sec-Fetch-Site", "same-site");
        assertEquals(403, run(exchange).status);
    }

    @Test public void crossSiteAndPreflightAreRejectedWithoutCorsHeaders() throws Exception {
        Exchange cross = loginExchange(SECRET);
        cross.request.set("Sec-Fetch-Site", "cross-site");
        assertEquals(403, run(cross).status);
        Auth auth = auth();
        Exchange preflight = authorized("OPTIONS", "/api/send", "", auth);
        assertEquals(405, run(preflight).status);
        assertNull(preflight.response.getFirst("Access-Control-Allow-Origin"));
    }

    @Test public void exactHostAllowlistBlocksRebindingAndForwardingSpoof() throws Exception {
        Auth auth = auth();
        Exchange exchange = authorized("POST", "/api/call", "", auth);
        exchange.request.set("Host", "attacker.example:18081");
        exchange.request.set("X-Forwarded-Host", "localhost:18081");
        exchange.request.set("X-Forwarded-For", "127.0.0.1");
        assertEquals(403, run(exchange).status);
        exchange = authorized("GET", "/api/state", "", auth);
        exchange.request.set("Host", "127.0.0.1:18081");
        exchange.request.set("Origin", "http://localhost:18081");
        assertEquals(403, run(exchange).status);
    }

    @Test public void duplicateHostAndOriginAreRejected() throws Exception {
        Exchange host = new Exchange("GET", "/", "");
        host.request.add("Host", "localhost:18081");
        assertEquals(400, run(host).status);
        Exchange origin = loginExchange(SECRET);
        origin.request.add("Origin", "http://localhost:18081");
        origin.request.add("Origin", "http://localhost:18081");
        assertEquals(400, run(origin).status);
    }

    @Test public void nonLoopbackPeerCannotSpoofProxyToEnterLocalMode() throws Exception {
        Exchange exchange = new Exchange("GET", "/", "");
        exchange.remote = new InetSocketAddress("192.0.2.11", 4000);
        exchange.request.set("X-Forwarded-For", "127.0.0.1");
        assertEquals(403, run(exchange).status);
    }

    @Test public void protectedGetAndMethodAllowlistRemainCompatible() throws Exception {
        Auth auth = auth();
        for (String path : Arrays.asList("/api/state", "/api/contacts", "/api/stream", "/api/groups")) {
            assertEquals(200, run(authorized("GET", path, "", auth)).status);
        }
        Exchange exchange = authorized("GET", "/api/call", "", auth);
        assertEquals(405, run(exchange).status);
        assertEquals("POST", exchange.response.getFirst("Allow"));
    }

    @Test public void getBodyAndUnexpectedControlBodyAreRejected() throws Exception {
        Auth auth = auth();
        assertEquals(413, run(authorized("GET", "/api/state", "x", auth)).status);
        for (String path : Arrays.asList("/api/call", "/api/hangup", "/api/record?sec=1",
                "/api/record/start", "/api/record/stop", "/api/setpeer?user=102")) {
            assertEquals(path, 413, run(authorized("POST", path, "x", auth)).status);
            assertEquals(path, 200, run(authorized("POST", path, "", auth)).status);
        }
    }

    @Test public void textExactLimitPassesAndOneByteOverFails() throws Exception {
        Auth auth = auth();
        Exchange exact = authorized("POST", "/api/send", "", auth);
        exact.setBody(new byte[16384]);
        assertEquals(200, run(exact).status);
        Exchange over = authorized("POST", "/api/send", "", auth);
        over.setBody(new byte[16385]);
        assertEquals(413, run(over).status);
    }

    @Test public void textMalformedUtf8FailsBeforeDelegate() throws Exception {
        Exchange exchange = authorized("POST", "/api/send", "", auth());
        exchange.setBody(new byte[]{(byte) 0xff});
        assertEquals(400, run(exchange).status);
        assertEquals(0, calls.get());
    }

    @Test public void fileBodyBoundIsEnforcedFromDeclaredLengthBeforeRead() throws Exception {
        Exchange exchange = authorized("POST", "/api/file?name=a.png", "", auth());
        exchange.request.set("Content-Length", "16777217");
        assertEquals(413, run(exchange).status);
        assertEquals(0, exchange.reads);
    }

    @Test public void unknownLengthBodiesAreCountedBeforeDelegate() throws Exception {
        Auth auth = auth();
        Exchange exact = authorized("POST", "/api/send", "valid", auth);
        exact.request.remove("Content-Length");
        exact.request.set("Transfer-Encoding", "chunked");
        assertEquals(200, run(exact).status);
        Exchange over = authorized("POST", "/api/send", "", auth);
        over.setBody(new byte[16385]);
        over.request.remove("Content-Length");
        over.request.set("Transfer-Encoding", "chunked");
        assertEquals(413, run(over).status);
    }

    @Test public void malformedAndAmbiguousFramingAreRejected() throws Exception {
        Auth auth = auth();
        for (String length : Arrays.asList("-1", "+1", "0,0", "abc", "999999999999999999999")) {
            Exchange exchange = authorized("POST", "/api/send", "", auth);
            exchange.request.set("Content-Length", length);
            assertEquals(length, 400, run(exchange).status);
        }
        Exchange both = authorized("POST", "/api/send", "", auth);
        both.request.set("Transfer-Encoding", "chunked");
        assertEquals(400, run(both).status);
        Exchange duplicate = authorized("POST", "/api/send", "", auth);
        duplicate.request.add("Content-Length", "0");
        assertEquals(400, run(duplicate).status);
    }

    @Test public void declaredAndActualBodyMismatchFails() throws Exception {
        Exchange exchange = authorized("POST", "/api/send", "abc", auth());
        exchange.request.set("Content-Length", "4");
        assertEquals(400, run(exchange).status);
    }

    @Test public void excessiveHeadersAndUrisAreRejected() throws Exception {
        Exchange header = new Exchange("GET", "/", "");
        char[] huge = new char[16385];
        Arrays.fill(huge, 'x');
        header.request.set("X-Noise", new String(huge));
        assertEquals(431, run(header).status);
        Exchange uri = new Exchange("GET", "/?q=" + new String(huge), "");
        assertEquals(400, run(uri).status);
    }

    @Test public void encodedApiAliasesAndAbsoluteUrisAreRejected() throws Exception {
        assertEquals(400, run(new Exchange("GET", "/api/%73tate", "")).status);
        assertEquals(400, run(new Exchange("GET", "http://localhost:18081/api/state", "")).status);
        assertEquals(400, run(new Exchange("GET", "/api/%5cstate", "")).status);
    }

    @Test public void unknownApiFailsClosedAfterAuthentication() throws Exception {
        assertEquals(404, run(authorized("POST", "/api/future-unsafe", "", auth())).status);
        assertEquals(0, calls.get());
    }

    @Test public void futureGroupRoutesRequireSessionCsrfAndBoundedBody() throws Exception {
        Auth auth = auth();
        for (String suffix : Arrays.asList("create", "join", "send", "leave", "sync", "retry")) {
            Exchange exchange = authorized("POST", "/api/groups/" + suffix, "{}", auth);
            assertEquals(suffix, 200, run(exchange).status);
        }
        Exchange over = authorized("POST", "/api/groups/send", "", auth);
        over.setBody(new byte[4097]);
        assertEquals(413, run(over).status);
    }

    @Test public void protectedAttachmentGetAndHeadWorkButNestedPathIsDenied() throws Exception {
        Auth auth = auth();
        assertEquals(200, run(authorized("GET", "/files/a%20b.png", "", auth)).status);
        assertEquals(200, run(authorized("HEAD", "/files/a.png", "", auth)).status);
        assertEquals(404, run(authorized("GET", "/files/a/b.png", "", auth)).status);
        assertEquals(404, run(authorized("GET", "/files/..", "", auth)).status);
    }

    @Test public void duplicateSessionCookiesAreRejected() throws Exception {
        Auth auth = auth();
        Exchange exchange = authorized("GET", "/api/state", "", auth);
        exchange.request.set("Cookie", auth.cookie + "; " + auth.cookie);
        assertEquals(401, run(exchange).status);
    }

    @Test public void browserCookiesForIndependentClientPortsDoNotCollide() throws Exception {
        Auth first = auth();
        WebAccessControl other = guard(18082);
        Exchange otherLogin = loginExchange(SECRET);
        otherLogin.request.set("Host", "localhost:18082");
        other.wrap(ex -> fail()).handle(otherLogin);
        assertEquals(200, otherLogin.status);
        String otherCookie = otherLogin.response.getFirst("Set-Cookie").split(";", 2)[0];
        assertTrue(otherCookie.startsWith("SIPIMSESSION_18082="));
        String browserJar = first.cookie + "; " + otherCookie;
        Exchange one = authorized("GET", "/api/state", "", first);
        one.request.set("Cookie", browserJar);
        assertEquals(200, run(one).status);
        Exchange two = new Exchange("GET", "/api/state", "");
        two.request.set("Host", "localhost:18082");
        two.request.set("Cookie", browserJar);
        other.wrap(ex -> { ex.sendResponseHeaders(200, -1); ex.close(); }).handle(two);
        assertEquals(200, two.status);
    }

    @Test public void logoutInvalidatesExistingSseAndClearsCookie() throws Exception {
        Auth auth = auth();
        Exchange sse = authorized("GET", "/api/stream", "", auth);
        assertTrue(access.isAuthenticated(sse));
        Exchange logout = authorized("POST", "/api/session/logout", "", auth);
        assertEquals(200, run(logout).status);
        assertTrue(logout.response.getFirst("Set-Cookie").contains("Max-Age=0"));
        assertFalse(access.isAuthenticated(sse));
        assertEquals(401, run(authorized("GET", "/api/state", "", auth)).status);
    }

    @Test public void sseHeartbeatCannotExtendIdleExpiry() throws Exception {
        Auth auth = auth();
        Exchange sse = authorized("GET", "/api/stream", "", auth);
        now.addAndGet(29L * 60 * 1000);
        assertTrue(access.isAuthenticated(sse));
        now.addAndGet(60 * 1000);
        assertFalse(access.isAuthenticated(sse));
        assertEquals(401, run(authorized("GET", "/api/state", "", auth)).status);
    }

    @Test public void authenticatedActivityExtendsIdleButNotAbsoluteExpiry() throws Exception {
        Auth auth = auth();
        for (int i = 0; i < 16; i++) {
            now.addAndGet(29L * 60 * 1000);
            assertEquals(200, run(authorized("GET", "/api/state", "", auth)).status);
        }
        now.addAndGet(16L * 60 * 1000);
        assertEquals(401, run(authorized("GET", "/api/state", "", auth)).status);
    }

    @Test public void restartInvalidatesOldSession() throws Exception {
        Auth auth = auth();
        WebAccessControl fresh = guard(18081);
        Exchange exchange = authorized("GET", "/api/state", "", auth);
        fresh.wrap(ex -> fail()).handle(exchange);
        assertEquals(401, exchange.status);
    }

    @Test public void sessionCapacityRejectsWithoutEvictingCurrentUsers() throws Exception {
        Auth first = auth();
        for (int i = 1; i < 8; i++) assertEquals(200, run(loginExchange(SECRET)).status);
        assertEquals(429, run(loginExchange(SECRET)).status);
        assertEquals(200, run(authorized("GET", "/api/state", "", first)).status);
    }

    @Test public void failedLoginHasBoundedPerPeerRateLimitAndRecovers() throws Exception {
        for (int i = 0; i < 10; i++) assertEquals(401, run(loginExchange("bad")).status);
        Exchange limited = run(loginExchange(SECRET));
        assertEquals(429, limited.status);
        assertEquals("300", limited.response.getFirst("Retry-After"));
        now.addAndGet(5L * 60 * 1000);
        assertEquals(200, run(loginExchange(SECRET)).status);
    }

    @Test public void globalLoginRateLimitCoversChangingPeerAddresses() throws Exception {
        WebAccessControl remote = new WebAccessControl(SECRET, "https", Collections.singleton("localhost:18081"), false, now::get);
        HttpHandler handler = remote.wrap(ex -> fail());
        for (int i = 1; i <= 50; i++) {
            Exchange exchange = loginExchange("bad");
            exchange.remote = new InetSocketAddress("192.0.2." + i, 1000);
            handler.handle(exchange);
            assertEquals(401, exchange.status);
        }
        Exchange limited = loginExchange(SECRET);
        limited.remote = new InetSocketAddress("192.0.2.99", 1000);
        handler.handle(limited);
        assertEquals(429, limited.status);
    }

    private Exchange run(Exchange exchange) throws IOException { wrapped.handle(exchange); return exchange; }
    private Exchange loginExchange(String secret) {
        Exchange exchange = new Exchange("POST", "/api/session/login", "{\"token\":\"" + secret + "\"}");
        exchange.request.set("X-SIP-Login", "1");
        exchange.request.set("Content-Type", "application/json");
        return exchange;
    }
    private Auth auth() throws Exception {
        Exchange exchange = run(loginExchange(SECRET));
        assertEquals(200, exchange.status);
        return new Auth(exchange.response.getFirst("Set-Cookie").split(";", 2)[0], csrf(exchange));
    }
    private static String csrf(Exchange exchange) {
        return JsonParser.parseString(exchange.text()).getAsJsonObject().get("csrfToken").getAsString();
    }
    private Exchange authorized(String method, String uri, String body, Auth auth) {
        Exchange exchange = new Exchange(method, uri, body);
        exchange.request.set("Cookie", auth.cookie);
        exchange.request.set("X-SIP-CSRF", auth.csrf);
        return exchange;
    }
    private static final class Auth {
        final String cookie, csrf;
        Auth(String cookie, String csrf) { this.cookie = cookie; this.csrf = csrf; }
    }
    private static final class Exchange extends HttpExchange {
        final Headers request = new Headers(), response = new Headers();
        final String method;
        URI uri;
        InetSocketAddress remote = new InetSocketAddress("127.0.0.1", 4567);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Map<String, Object> attributes = new HashMap<String, Object>();
        byte[] bytes;
        int status = -1, reads;
        boolean closed;
        InputStream input;
        Exchange(String method, String uri, String body) {
            this.method = method; this.uri = URI.create(uri);
            request.set("Host", "localhost:18081");
            setBody(body.getBytes(StandardCharsets.UTF_8));
        }
        void setBody(byte[] body) {
            bytes = body;
            request.set("Content-Length", Integer.toString(bytes.length));
            input = new ByteArrayInputStream(bytes) {
                @Override public synchronized int read() { reads++; return super.read(); }
                @Override public synchronized int read(byte[] b, int off, int len) { reads++; return super.read(b, off, len); }
            };
        }
        String text() { return new String(output.toByteArray(), StandardCharsets.UTF_8); }
        public Headers getRequestHeaders() { return request; }
        public Headers getResponseHeaders() { return response; }
        public URI getRequestURI() { return uri; }
        public String getRequestMethod() { return method; }
        public HttpContext getHttpContext() { return null; }
        public void close() { closed = true; }
        public InputStream getRequestBody() { return input; }
        public OutputStream getResponseBody() { return output; }
        public void sendResponseHeaders(int status, long length) { this.status = status; }
        public InetSocketAddress getRemoteAddress() { return remote; }
        public int getResponseCode() { return status; }
        public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 18081); }
        public String getProtocol() { return "HTTP/1.1"; }
        public Object getAttribute(String name) { return attributes.get(name); }
        public void setAttribute(String name, Object value) { attributes.put(name, value); }
        public void setStreams(InputStream input, OutputStream output) { this.input = input; }
        public HttpPrincipal getPrincipal() { return null; }
    }
}

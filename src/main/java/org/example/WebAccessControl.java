package org.example;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** One fail-closed boundary for the browser control server; constructing it opens no sockets. */
public final class WebAccessControl {
    private static final int MAX_HEADERS = 16 * 1024;
    private static final int MAX_URI = 2048;
    private static final int MAX_FILE_BODY = 16 * 1024 * 1024;
    private static final long IDLE_MS = 30L * 60 * 1000;
    private static final long ABSOLUTE_MS = 8L * 60 * 60 * 1000;
    private static final long LOGIN_WINDOW_MS = 5L * 60 * 1000;
    private static final int MAX_SESSIONS = 8;
    private static final int MAX_LOGIN_PEERS = 64;
    private final byte[] secret;
    private final String scheme;
    private final Set<String> authorities;
    private final boolean localOnly;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new LinkedHashMap<String, Session>();
    private final Map<String, Attempts> attempts = new HashMap<String, Attempts>();
    private long globalWindow = Long.MIN_VALUE;
    private int globalAttempts;
    private final String cookieName;

    public WebAccessControl(String secret, String scheme, Set<String> allowedAuthorities, boolean localOnly) {
        this(secret, scheme, allowedAuthorities, localOnly, System::currentTimeMillis);
    }

    WebAccessControl(String secret, String scheme, Set<String> allowedAuthorities,
                     boolean localOnly, LongSupplier clock) {
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}")
                || Base64.getUrlDecoder().decode(secret).length != 32) {
            throw new IllegalArgumentException("A 256-bit access token is required");
        }
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("Invalid scheme");
        }
        if (!localOnly && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Remote access requires HTTPS");
        }
        if (clock == null || allowedAuthorities == null || allowedAuthorities.isEmpty()) {
            throw new IllegalArgumentException("Authorities and clock are required");
        }
        this.scheme = scheme;
        this.authorities = new HashSet<String>();
        int port = -1;
        for (String authority : allowedAuthorities) {
            URI uri = authorityUri(authority, scheme);
            if (uri == null) throw new IllegalArgumentException("Invalid authority");
            int currentPort = effectivePort(uri, scheme);
            if (port != -1 && port != currentPort) throw new IllegalArgumentException("Authorities need one port");
            port = currentPort;
            this.authorities.add(authority.toLowerCase(Locale.ROOT));
        }
        this.cookieName = "SIPIMSESSION_" + port;
        this.secret = secret.getBytes(StandardCharsets.US_ASCII);
        this.localOnly = localOnly;
        this.clock = clock;
    }

    /** Apply this to the single root dispatcher so newly merged routes inherit the boundary. */
    public HttpHandler wrap(final HttpHandler delegate) {
        if (delegate == null) throw new IllegalArgumentException("Handler required");
        return exchange -> {
            securityHeaders(exchange.getResponseHeaders());
            try {
                boundary(exchange);
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/api/session")) {
                    builtin(exchange, path);
                    return;
                }
                boolean publicResource = "/".equals(path) || "/web-access.js".equals(path)
                        || "/group-chat.js".equals(path) || "/favicon.ico".equals(path);
                Session session = publicResource ? null : requireSession(exchange, true);
                Policy policy = policy(path);
                if (policy == null) throw new Rejection(404, "NOT_FOUND");
                requireMethod(exchange, policy.methods);
                if ("POST".equals(exchange.getRequestMethod())) requireCsrf(exchange, session);
                byte[] body = body(exchange, policy.limit);
                if ("/api/send".equals(path) || path.startsWith("/api/groups")) strictUtf8(body);
                delegate.handle(new BufferedExchange(exchange, body));
            } catch (Rejection rejection) {
                if (rejection.allow != null) exchange.getResponseHeaders().set("Allow", rejection.allow);
                reply(exchange, rejection.status, "{\"ok\":false,\"error\":\"" + rejection.code + "\"}");
            } catch (IOException failure) {
                if (exchange.getResponseCode() < 0) reply(exchange, 400, "{\"ok\":false,\"error\":\"INVALID_REQUEST\"}");
                else exchange.close();
            }
        };
    }

    /** Recheck established SSE sessions without allowing heartbeats to extend the idle timeout. */
    public boolean isAuthenticated(HttpExchange exchange) {
        try {
            boundary(exchange);
            return findSession(exchange, false) != null;
        } catch (Rejection failure) {
            return false;
        }
    }

    private void builtin(HttpExchange exchange, String path) throws IOException, Rejection {
        if (exchange.getRequestURI().getRawQuery() != null) throw new Rejection(400, "SESSION_QUERY_FORBIDDEN");
        if ("/api/session/login".equals(path)) {
            requireMethod(exchange, "POST");
            if (!"1".equals(single(exchange.getRequestHeaders(), "X-SIP-Login", false))) {
                throw new Rejection(403, "LOGIN_HEADER_REQUIRED");
            }
            String contentType = single(exchange.getRequestHeaders(), "Content-Type", true);
            if (contentType == null || !contentType.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")) {
                throw new Rejection(415, "JSON_REQUIRED");
            }
            reserveLogin(exchange);
            String token = loginToken(body(exchange, 1024));
            if (!constant(secret, token)) throw new Rejection(401, "INVALID_CREDENTIAL");
            Session session;
            synchronized (sessions) {
                purgeSessions(clock.getAsLong());
                if (sessions.size() >= MAX_SESSIONS) throw new Rejection(429, "SESSION_LIMIT");
                long now = clock.getAsLong();
                session = new Session(randomToken(), randomToken(), now);
                sessions.put(session.id, session);
            }
            exchange.getResponseHeaders().add("Set-Cookie", cookie(session.id, false));
            reply(exchange, 200, metadata(session));
            return;
        }
        Session session = requireSession(exchange, true);
        if ("/api/session".equals(path)) {
            requireMethod(exchange, "GET");
            body(exchange, 0);
            reply(exchange, 200, metadata(session));
        } else if ("/api/session/logout".equals(path)) {
            requireMethod(exchange, "POST");
            requireCsrf(exchange, session);
            body(exchange, 0);
            synchronized (sessions) { sessions.remove(session.id); }
            exchange.getResponseHeaders().add("Set-Cookie", cookie("", true));
            reply(exchange, 200, "{\"ok\":true,\"authenticated\":false}");
        } else {
            throw new Rejection(404, "NOT_FOUND");
        }
    }

    private void boundary(HttpExchange exchange) throws Rejection {
        URI request = exchange.getRequestURI();
        if (request == null || request.isAbsolute() || request.getRawAuthority() != null
                || request.getRawFragment() != null || request.toString().length() > MAX_URI) {
            throw new Rejection(400, "INVALID_URI");
        }
        String path = request.getPath();
        if (path == null || !path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
                || (path.startsWith("/api/") && !path.equals(request.getRawPath()))) {
            throw new Rejection(400, "INVALID_PATH");
        }
        int total = 0;
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            if (header.getKey() == null || header.getValue() == null) throw new Rejection(400, "INVALID_HEADER");
            total += header.getKey().getBytes(StandardCharsets.UTF_8).length;
            for (String value : header.getValue()) {
                if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                    throw new Rejection(400, "INVALID_HEADER");
                }
                total += value.getBytes(StandardCharsets.UTF_8).length;
                if (total > MAX_HEADERS) throw new Rejection(431, "HEADERS_TOO_LARGE");
            }
        }
        String host = single(exchange.getRequestHeaders(), "Host", true);
        if (authorityUri(host, scheme) == null || !authorities.contains(host.toLowerCase(Locale.ROOT))) {
            throw new Rejection(403, "HOST_DENIED");
        }
        if (localOnly && (exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null
                || !exchange.getRemoteAddress().getAddress().isLoopbackAddress())) {
            throw new Rejection(403, "LOCAL_ACCESS_ONLY");
        }
        String origin = single(exchange.getRequestHeaders(), "Origin", false);
        if (origin != null && !origin.equalsIgnoreCase(scheme + "://" + host)) {
            throw new Rejection(403, "ORIGIN_DENIED");
        }
        if (origin != null) {
            try {
                URI parsed = URI.create(origin);
                if (!scheme.equals(parsed.getScheme()) || parsed.getRawUserInfo() != null
                        || parsed.getRawPath() == null || !parsed.getRawPath().isEmpty()
                        || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
                    throw new Rejection(403, "ORIGIN_DENIED");
                }
            } catch (IllegalArgumentException failure) { throw new Rejection(403, "ORIGIN_DENIED"); }
        }
        String fetchSite = single(exchange.getRequestHeaders(), "Sec-Fetch-Site", false);
        if (fetchSite != null && !("same-origin".equals(fetchSite) || "none".equals(fetchSite))) {
            throw new Rejection(403, "CROSS_SITE_DENIED");
        }
        framing(exchange.getRequestHeaders());
    }

    private static URI authorityUri(String authority, String scheme) {
        if (authority == null || authority.isEmpty() || !authority.matches("[A-Za-z0-9.\\[\\]:-]+")) return null;
        try {
            URI uri = URI.create(scheme + "://" + authority);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                    || uri.getPort() == 0 || uri.getPort() > 65535) return null;
            return uri;
        } catch (IllegalArgumentException failure) { return null; }
    }

    private static int effectivePort(URI uri, String scheme) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
    }

    private static String single(Headers headers, String name, boolean required) throws Rejection {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            if (required) throw new Rejection(400, "MISSING_HEADER");
            return null;
        }
        if (values.size() != 1 || values.get(0) == null || values.get(0).indexOf(',') >= 0) {
            throw new Rejection(400, "DUPLICATE_HEADER");
        }
        return values.get(0);
    }

    private static long framing(Headers headers) throws Rejection {
        String length = single(headers, "Content-Length", false);
        String transfer = single(headers, "Transfer-Encoding", false);
        if (length != null && transfer != null) throw new Rejection(400, "AMBIGUOUS_FRAMING");
        if (transfer != null && !"chunked".equalsIgnoreCase(transfer)) throw new Rejection(400, "INVALID_FRAMING");
        if (length == null) return -1;
        if (!length.matches("[0-9]{1,18}")) throw new Rejection(400, "INVALID_LENGTH");
        try { return Long.parseLong(length); }
        catch (NumberFormatException failure) { throw new Rejection(400, "INVALID_LENGTH"); }
    }

    private static byte[] body(HttpExchange exchange, int limit) throws IOException, Rejection {
        long declared = framing(exchange.getRequestHeaders());
        if (declared > limit) throw new Rejection(413, "BODY_TOO_LARGE");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 1024));
        byte[] buffer = new byte[Math.min(limit + 1, 8192)];
        int total = 0;
        InputStream input = exchange.getRequestBody();
        int read;
        while ((read = input.read(buffer, 0, Math.min(buffer.length, limit - total + 1))) != -1) {
            total += read;
            if (total > limit) throw new Rejection(413, "BODY_TOO_LARGE");
            output.write(buffer, 0, read);
        }
        if (declared >= 0 && total != declared) throw new Rejection(400, "LENGTH_MISMATCH");
        return output.toByteArray();
    }

    private static String strictUtf8(byte[] bytes) throws Rejection {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) { throw new Rejection(400, "INVALID_UTF8"); }
    }

    private static String loginToken(byte[] bytes) throws Rejection {
        String source = strictUtf8(bytes);
        // Gson's reader otherwise tolerates raw control characters inside quoted strings.
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char character = source.charAt(i);
            if (character < 0x20 && (quoted || (character != '\r' && character != '\n' && character != '\t'))) {
                throw new Rejection(400, "INVALID_JSON");
            }
            if (escaped) escaped = false;
            else if (quoted && character == '\\') escaped = true;
            else if (character == '"') quoted = !quoted;
        }
        try (JsonReader reader = new JsonReader(new StringReader(source))) {
            reader.setLenient(false);
            reader.beginObject();
            if (!reader.hasNext() || !"token".equals(reader.nextName()) || reader.peek() != JsonToken.STRING) {
                throw new Rejection(400, "INVALID_JSON");
            }
            String token = reader.nextString();
            if (reader.hasNext()) throw new Rejection(400, "INVALID_JSON");
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || token.length() > 128) throw new Rejection(400, "INVALID_JSON");
            return token;
        } catch (IOException | IllegalStateException failure) { throw new Rejection(400, "INVALID_JSON"); }
    }

    private Session requireSession(HttpExchange exchange, boolean touch) throws Rejection {
        Session session = findSession(exchange, touch);
        if (session == null) throw new Rejection(401, "AUTHENTICATION_REQUIRED");
        return session;
    }

    private Session findSession(HttpExchange exchange, boolean touch) throws Rejection {
        String id = null;
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) return null;
        for (String line : cookies) {
            for (String cookie : line.split(";")) {
                String item = cookie.trim();
                int separator = item.indexOf('=');
                if (separator < 0 || !cookieName.equals(item.substring(0, separator))) continue;
                if (id != null) throw new Rejection(401, "AMBIGUOUS_COOKIE");
                id = item.substring(separator + 1);
            }
        }
        if (id == null || !id.matches("[A-Za-z0-9_-]{43}")) return null;
        synchronized (sessions) {
            long now = clock.getAsLong();
            purgeSessions(now);
            Session session = sessions.get(id);
            if (session != null && touch) session.lastUsed = now;
            return session;
        }
    }

    private void purgeSessions(long now) {
        Iterator<Session> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            if (now - session.lastUsed >= IDLE_MS || now - session.created >= ABSOLUTE_MS || now < session.created) iterator.remove();
        }
    }

    private void requireCsrf(HttpExchange exchange, Session session) throws Rejection {
        String csrf = single(exchange.getRequestHeaders(), "X-SIP-CSRF", false);
        if (session == null || !constant(session.csrf.getBytes(StandardCharsets.US_ASCII), csrf)) {
            throw new Rejection(403, "CSRF_REQUIRED");
        }
    }

    private void reserveLogin(HttpExchange exchange) throws Rejection {
        String address = exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null
                ? "unknown" : exchange.getRemoteAddress().getAddress().getHostAddress();
        synchronized (attempts) {
            long now = clock.getAsLong();
            if (globalWindow == Long.MIN_VALUE || now - globalWindow >= LOGIN_WINDOW_MS || now < globalWindow) {
                globalWindow = now;
                globalAttempts = 0;
            }
            Iterator<Attempts> iterator = attempts.values().iterator();
            while (iterator.hasNext()) if (now - iterator.next().start >= LOGIN_WINDOW_MS) iterator.remove();
            Attempts peer = attempts.get(address);
            if (globalAttempts >= 50 || (peer != null && peer.count >= 10)
                    || (peer == null && attempts.size() >= MAX_LOGIN_PEERS)) {
                exchange.getResponseHeaders().set("Retry-After", "300");
                throw new Rejection(429, "LOGIN_RATE_LIMIT");
            }
            if (peer == null) { peer = new Attempts(now); attempts.put(address, peer); }
            peer.count++;
            globalAttempts++;
        }
    }

    private String cookie(String value, boolean clear) {
        return cookieName + "=" + value + "; Path=/; HttpOnly; SameSite=Strict"
                + ("https".equals(scheme) ? "; Secure" : "") + (clear ? "; Max-Age=0" : "");
    }

    private static String metadata(Session session) {
        return "{\"ok\":true,\"authenticated\":true,\"csrfToken\":\"" + session.csrf + "\"}";
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constant(byte[] expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected, actual.getBytes(StandardCharsets.UTF_8));
    }

    private static Policy policy(String path) {
        if ("/".equals(path) || "/web-access.js".equals(path) || "/group-chat.js".equals(path)
                || "/favicon.ico".equals(path)) return new Policy("GET", 0);
        if ("/api/state".equals(path) || "/api/contacts".equals(path) || "/api/stream".equals(path)
                || "/api/groups".equals(path)) return new Policy("GET", 0);
        if ("/api/send".equals(path)) return new Policy("POST", 16 * 1024);
        if ("/api/file".equals(path)) return new Policy("POST", MAX_FILE_BODY);
        if ("/api/call".equals(path) || "/api/hangup".equals(path) || "/api/record".equals(path)
                || "/api/record/start".equals(path) || "/api/record/stop".equals(path)
                || "/api/setpeer".equals(path)) return new Policy("POST", 0);
        if (path.matches("/api/groups/(create|join|send|leave|sync|retry)")) return new Policy("POST", 4096);
        if (path.startsWith("/files/") && path.length() > 7) {
            String name = path.substring(7);
            if (name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !".".equals(name) && !"..".equals(name)) {
                return new Policy("GET, HEAD", 0);
            }
        }
        return null;
    }

    private static void requireMethod(HttpExchange exchange, String allowed) throws Rejection {
        for (String method : allowed.split(", ")) if (method.equals(exchange.getRequestMethod())) return;
        Rejection rejection = new Rejection(405, "METHOD_NOT_ALLOWED");
        rejection.allow = allowed;
        throw rejection;
    }

    private static void securityHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' data: blob:; "
                + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        finally { exchange.close(); }
    }

    private static final class Session {
        final String id;
        final String csrf;
        final long created;
        long lastUsed;
        Session(String id, String csrf, long now) { this.id = id; this.csrf = csrf; created = now; lastUsed = now; }
    }
    private static final class Attempts {
        final long start;
        int count;
        Attempts(long start) { this.start = start; }
    }
    private static final class Policy {
        final String methods;
        final int limit;
        Policy(String methods, int limit) { this.methods = methods; this.limit = limit; }
    }
    private static final class Rejection extends Exception {
        final int status;
        final String code;
        String allow;
        Rejection(int status, String code) { this.status = status; this.code = code; }
    }

    private static final class BufferedExchange extends HttpExchange {
        private final HttpExchange delegate;
        private InputStream input;
        BufferedExchange(HttpExchange delegate, byte[] body) { this.delegate = delegate; input = new ByteArrayInputStream(body); }
        public Headers getRequestHeaders() { return delegate.getRequestHeaders(); }
        public Headers getResponseHeaders() { return delegate.getResponseHeaders(); }
        public URI getRequestURI() { return delegate.getRequestURI(); }
        public String getRequestMethod() { return delegate.getRequestMethod(); }
        public HttpContext getHttpContext() { return delegate.getHttpContext(); }
        public void close() { delegate.close(); }
        public InputStream getRequestBody() { return input; }
        public OutputStream getResponseBody() { return delegate.getResponseBody(); }
        public void sendResponseHeaders(int status, long length) throws IOException { delegate.sendResponseHeaders(status, length); }
        public InetSocketAddress getRemoteAddress() { return delegate.getRemoteAddress(); }
        public int getResponseCode() { return delegate.getResponseCode(); }
        public InetSocketAddress getLocalAddress() { return delegate.getLocalAddress(); }
        public String getProtocol() { return delegate.getProtocol(); }
        public Object getAttribute(String name) { return delegate.getAttribute(name); }
        public void setAttribute(String name, Object value) { delegate.setAttribute(name, value); }
        public void setStreams(InputStream input, OutputStream output) { this.input = input; delegate.setStreams(input, output); }
        public HttpPrincipal getPrincipal() { return delegate.getPrincipal(); }
    }
}

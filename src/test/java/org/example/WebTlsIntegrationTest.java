package org.example;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Real, temporary TLS listeners on loopback port 0; no SIP engine or remote service is started. */
public class WebTlsIntegrationTest {
    @ClassRule public static TemporaryFolder temporary = new TemporaryFolder();
    private static final String CAPABILITY = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static WebServerSettings settings;
    private static SSLContext serverTls;
    private static SSLContext clientTls;
    private HttpServer server;
    private ExecutorService executor;
    private String origin;

    @BeforeClass public static void generatedPrivateTlsFixture() throws Exception {
        Path directory = temporary.getRoot().toPath();
        Path store = directory.resolve("test-only.p12");
        Path passwordFile = directory.resolve("test-only.password");
        String password = "tls-fixture-only-854917";
        PrivateWebToken.loadOrCreate(passwordFile);
        Files.write(passwordFile, password.getBytes(StandardCharsets.UTF_8));
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool";
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path keytool = javaHome.resolve("bin").resolve(executable);
        if (!Files.isRegularFile(keytool)) keytool = javaHome.getParent().resolve("bin").resolve(executable);
        assertTrue("The existing JDK keytool is required", Files.isRegularFile(keytool));
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "tls-fixture-only",
                "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", password, "-keypass", password, "-dname", "CN=localhost", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool-fixture.log").toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Temporary TLS fixture key generation exceeded its timeout");
        }
        assertEquals("Temporary TLS fixture generation failed", 0, process.exitValue());
        PrivateWebTokenTest.protect(store);
        Properties properties = new Properties();
        properties.setProperty("web.bind.address", "127.0.0.1");
        properties.setProperty("web.remote.enabled", "true");
        properties.setProperty("web.tls.keystore", store.toString());
        properties.setProperty("web.tls.password.file", passwordFile.toString());
        settings = WebServerSettings.from(properties);
        serverTls = settings.loadSslContext();
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(store)) { trust.load(input, password.toCharArray()); }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        clientTls = SSLContext.getInstance("TLSv1.2");
        clientTls.init(null, trustManagers.getTrustManagers(), null);
    }

    @Before public void startIsolatedListener() throws Exception {
        server = WebClientServer.createListener(settings, serverTls, 0);
        int port = server.getAddress().getPort();
        assertTrue(server.getAddress().getAddress().isLoopbackAddress());
        assertTrue(port > 0);
        WebAccessControl access = new WebAccessControl(CAPABILITY, "https", settings.authorities(port), false);
        WebClientServer.registerRoutes(server, access);
        executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        server.start();
        origin = "https://127.0.0.1:" + port;
    }

    @After public void releaseListener() throws Exception {
        if (server != null) server.stop(0);
        if (executor != null) {
            executor.shutdownNow();
            assertTrue("Temporary TLS worker release", executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void realHandshakeUsesTls12AndHostnameVerifiedCertificate() throws Exception {
        try (SSLSocket socket = (SSLSocket) clientTls.getSocketFactory().createSocket()) {
            socket.connect(server.getAddress(), 3000);
            socket.setSoTimeout(3000);
            socket.setEnabledProtocols(new String[]{"TLSv1.2"});
            socket.startHandshake();
            assertEquals("TLSv1.2", socket.getSession().getProtocol());
            assertTrue(socket.getSession().getCipherSuite().contains("TLS_"));
        }
        Response page = request("GET", "/", null, null, null);
        assertEquals(200, page.status);
        assertTrue(page.body.contains("<html"));
        assertFalse(page.body.contains(CAPABILITY));
    }

    @Test public void authenticatedHttpsLoginUsesSecureCookieAndProtectedState() throws Exception {
        assertEquals(401, request("GET", "/api/state", null, null, null).status);
        Response login = request("POST", "/api/session/login", "{\"token\":\"" + CAPABILITY + "\"}", null, null);
        assertEquals(200, login.status);
        assertNotNull(login.cookie);
        assertTrue(login.cookie.contains("; Secure"));
        assertTrue(login.cookie.contains("HttpOnly"));
        assertTrue(login.cookie.contains("SameSite=Strict"));
        assertFalse(login.body.contains(CAPABILITY));
        String cookie = login.cookie.split(";", 2)[0];
        String csrf = JsonParser.parseString(login.body).getAsJsonObject().get("csrfToken").getAsString();
        assertEquals(43, csrf.length());
        Response state = request("GET", "/api/state", null, cookie, null);
        assertEquals(200, state.status);
        assertTrue(JsonParser.parseString(state.body).getAsJsonObject().has("registered"));
        assertFalse(JsonParser.parseString(state.body).getAsJsonObject().get("registered").getAsBoolean());
        assertEquals(403, request("POST", "/api/hangup", "", cookie, null).status);
        Response logout = request("POST", "/api/session/logout", "", cookie, csrf);
        assertEquals(200, logout.status);
        assertTrue(logout.cookie.contains("Max-Age=0"));
        assertEquals(401, request("GET", "/api/state", null, cookie, null).status);
    }

    @Test public void invalidCredentialIsRejectedOverActualTls() throws Exception {
        Response denied = request("POST", "/api/session/login", "{\"token\":\"invalid-test-token\"}", null, null);
        assertEquals(401, denied.status);
        assertNull(denied.cookie);
        assertFalse(denied.body.contains("invalid-test-token"));
    }

    @Test public void plaintextCannotReachTlsControlListener() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(server.getAddress(), 3000);
            socket.setSoTimeout(3000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /api/state HTTP/1.1\r\nHost: 127.0.0.1:" + server.getAddress().getPort()
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            try {
                int first = socket.getInputStream().read();
                assertEquals("Plain HTTP must not be served by a TLS listener", -1, first);
            } catch (java.net.SocketException rejected) {
                // A TCP reset is also an explicit TLS protocol rejection.
            }
        }
    }

    @Test public void inconsistentTlsConfigurationRejectsBeforeAnyBindAttempt() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            WebServerSettings http = WebServerSettings.from(new Properties());
            try {
                WebClientServer.createListener(http, serverTls, occupied.getLocalPort());
                fail("HTTP settings with TLS must fail before binding");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("TLS"));
            }
            try {
                WebClientServer.createListener(settings, null, occupied.getLocalPort());
                fail("HTTPS settings without TLS must fail before binding");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("TLS"));
            }
        }
    }

    private Response request(String method, String path, String body, String cookie, String csrf) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(origin + path).openConnection();
        connection.setSSLSocketFactory(clientTls.getSocketFactory());
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Origin", origin);
        if (cookie != null) connection.setRequestProperty("Cookie", cookie);
        if (csrf != null) connection.setRequestProperty("X-SIP-CSRF", csrf);
        if ("/api/session/login".equals(path)) {
            connection.setRequestProperty("X-SIP-Login", "1");
            connection.setRequestProperty("Content-Type", "application/json");
        }
        try {
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = "";
            if (input != null) {
                try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] block = new byte[1024];
                    int count;
                    while ((count = stream.read(block)) != -1) output.write(block, 0, count);
                    responseBody = new String(output.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            return new Response(status, responseBody, connection.getHeaderField("Set-Cookie"));
        } finally { connection.disconnect(); }
    }

    private static final class Response {
        final int status;
        final String body, cookie;
        Response(int status, String body, String cookie) { this.status = status; this.body = body; this.cookie = cookie; }
    }
}

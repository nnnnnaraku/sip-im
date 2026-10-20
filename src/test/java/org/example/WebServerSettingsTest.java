package org.example;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.Assert.*;

public class WebServerSettingsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void defaultsToIpv4LoopbackWithoutTlsOrBindSideEffects() throws Exception {
        WebServerSettings settings = WebServerSettings.from(new Properties());
        assertEquals("127.0.0.1", settings.getBindAddress().getHostAddress());
        assertEquals(8081, settings.getPort());
        assertFalse(settings.isRemoteEnabled());
        assertEquals("http", settings.getScheme());
        assertEquals(Paths.get("web-access.token").toAbsolutePath().normalize(), settings.getTokenPath());
        assertEquals(new java.util.HashSet<String>(Arrays.asList("127.0.0.1:8081", "localhost:8081")), settings.authorities(8081));
        assertNull(settings.loadSslContext());
    }

    @Test public void ipv6LoopbackHasBracketedHost() {
        Properties properties = props("web.bind.address", "::1");
        WebServerSettings settings = WebServerSettings.from(properties);
        assertTrue(settings.getBindAddress().isLoopbackAddress());
        assertTrue(settings.authorities(18181).contains("[::1]:18181"));
        assertTrue(settings.authorities(18181).contains("localhost:18181"));
    }

    @Test public void exactHostAuthoritiesAreImmutable() {
        WebServerSettings settings = WebServerSettings.from(new Properties());
        try { settings.authorities(8081).add("evil:8081"); fail(); } catch (UnsupportedOperationException expected) { }
        try { settings.getAllowedHosts().add("evil"); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void defaultPortsAllowCanonicalHostWithoutPort() {
        WebServerSettings settings = WebServerSettings.from(props("web.port", "80"));
        assertTrue(settings.authorities(80).contains("localhost"));
        assertTrue(settings.authorities(8081).contains("localhost:8081"));
        assertFalse(settings.authorities(8081).contains("localhost"));
    }

    @Test public void listenerPortIsStrictlyBounded() {
        for (String value : Arrays.asList("0", "-1", "65536", "8081x", "0x1f91", "", "+8081")) {
            invalid(props("web.port", value));
        }
        assertEquals(65535, WebServerSettings.from(props("web.port", "65535")).getPort());
    }

    @Test public void remoteBooleanIsStrict() {
        for (String value : Arrays.asList("TRUE", "yes", "1", "", " true ")) invalid(props("web.remote.enabled", value));
    }

    @Test public void rejectsWildcardDnsLegacyAndAmbiguousBindAddresses() {
        for (String address : Arrays.asList("0.0.0.0", "::", "localhost", "example.invalid", "127.1", "2130706433", "0177.0.0.1",
                "127.0.0.01", "256.0.0.1", "[::1]", "::ffff:127.0.0.1", "224.0.0.1", "127.0.0.1:8081", "")) {
            invalid(props("web.bind.address", address));
        }
    }

    @Test public void rejectsImplicitRemoteBinding() {
        invalid(props("web.bind.address", "192.0.2.10"));
    }

    @Test public void remoteModeRequiresTlsAndBothFiles() {
        Properties properties = props("web.bind.address", "192.0.2.10");
        properties.setProperty("web.remote.enabled", "true");
        invalid(properties);
        properties.setProperty("web.tls.keystore", "only-keystore.p12");
        invalid(properties);
    }

    @Test public void partialTlsConfigurationFailsEvenLocally() {
        invalid(props("web.tls.keystore", "test.p12"));
        invalid(props("web.tls.password.file", "test.password"));
    }

    @Test public void remoteHostNamesMustBeExplicitAndCanonical() {
        Properties properties = remoteProps();
        properties.setProperty("web.allowed.hosts", "sip-client.example,192.0.2.20");
        WebServerSettings settings = WebServerSettings.from(properties);
        assertTrue(settings.authorities(8081).contains("192.0.2.10:8081"));
        assertTrue(settings.authorities(8081).contains("sip-client.example:8081"));
        assertFalse(settings.authorities(8081).contains("unlisted.example:8081"));
        assertEquals("https", settings.getScheme());
    }

    @Test public void allowedHostsRejectPortsUrlsWildcardsAndMalformedDns() {
        for (String host : Arrays.asList("*", "*.example", "host:8081", "http://host", "user@host", "example..org", "-bad.org",
                "bad-.org", "host/path", "host#fragment", "host?query", "host,", "0.0.0.0", "127.1", "[::]", "host\n.other")) {
            Properties properties = remoteProps();
            properties.setProperty("web.allowed.hosts", host);
            invalid(properties);
        }
    }

    @Test public void loopbackModeCannotAuthorizeRemoteDns() {
        invalid(props("web.allowed.hosts", "untrusted.example"));
    }

    @Test public void tokenPathCanBeExplicitWithoutCreatingIt() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("private").resolve("web.token");
        WebServerSettings settings = WebServerSettings.from(props("web.auth.token.file", path.toString()));
        assertEquals(path.toAbsolutePath().normalize(), settings.getTokenPath());
        assertFalse(Files.exists(path));
    }

    @Test public void rejectsUnknownKeystoreType() {
        invalid(props("web.tls.keystore.type", "not-a-keystore"));
    }

    @Test public void loadsGeneratedPrivatePkcs12WithTls12() throws Exception {
        Properties properties = generatedKeystore();
        SSLContext context = WebServerSettings.from(properties).loadSslContext();
        assertNotNull(context);
        assertEquals("TLSv1.2", context.getProtocol());
        assertTrue(Arrays.asList(context.getSupportedSSLParameters().getProtocols()).contains("TLSv1.2"));
    }

    @Test public void wrongKeystorePasswordFailsWithoutSecretDisclosure() throws Exception {
        Properties properties = generatedKeystore();
        Path passwordFile = Paths.get(properties.getProperty("web.tls.password.file"));
        Files.write(passwordFile, "wrong-test-only-password".getBytes(StandardCharsets.UTF_8));
        try {
            WebServerSettings.from(properties).loadSslContext();
            fail("Wrong password must fail");
        } catch (IOException expected) {
            assertFalse(String.valueOf(expected.getMessage()).contains("wrong-test-only-password"));
        }
    }

    @Test public void missingKeystoreFailsBeforeAnyListenerExists() throws Exception {
        Properties properties = remoteProps();
        properties.setProperty("web.tls.keystore", temporary.getRoot().toPath().resolve("missing.p12").toString());
        try { WebServerSettings.from(properties).loadSslContext(); fail(); } catch (IOException expected) { }
    }

    private Properties generatedKeystore() throws Exception {
        Path keyStore = temporary.getRoot().toPath().resolve(java.util.UUID.randomUUID().toString() + ".p12");
        Path passwordFile = temporary.getRoot().toPath().resolve(java.util.UUID.randomUUID().toString() + ".password");
        String fakePassword = "test-only-73951";
        PrivateWebToken.loadOrCreate(passwordFile);
        Files.write(passwordFile, fakePassword.getBytes(StandardCharsets.UTF_8));
        Path javaHome = Paths.get(System.getProperty("java.home"));
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "keytool.exe" : "keytool";
        Path keytool = javaHome.resolve("bin").resolve(executable);
        if (!Files.isRegularFile(keytool)) keytool = javaHome.getParent().resolve("bin").resolve(executable);
        assertTrue("JDK keytool is required for isolated TLS test", Files.isRegularFile(keytool));
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "test-only", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", fakePassword, "-keypass", fakePassword,
                "-dname", "CN=localhost", "-validity", "1", "-noprompt").redirectErrorStream(true).start();
        PrivateWebTokenTest.drain(process.getInputStream());
        assertEquals("Isolated fixture keystore generation failed", 0, process.waitFor());
        PrivateWebTokenTest.protect(keyStore);
        Properties properties = remoteProps();
        properties.setProperty("web.tls.keystore", keyStore.toString());
        properties.setProperty("web.tls.password.file", passwordFile.toString());
        return properties;
    }

    private static Properties props(String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return properties;
    }

    private static Properties remoteProps() {
        Properties properties = props("web.bind.address", "192.0.2.10");
        properties.setProperty("web.remote.enabled", "true");
        properties.setProperty("web.tls.keystore", "test-only.p12");
        properties.setProperty("web.tls.password.file", "test-only.password");
        return properties;
    }

    private static void invalid(Properties properties) {
        try { WebServerSettings.from(properties); fail("Invalid configuration must fail closed"); }
        catch (IllegalArgumentException expected) { }
    }
}

package org.example;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Validated Web listener settings. Construction does not bind sockets or initialize SIP. */
public final class WebServerSettings {
    private final InetAddress bindAddress;
    private final int port;
    private final boolean remote;
    private final Path tokenPath;
    private final Path keyStorePath;
    private final Path keyStorePasswordPath;
    private final String keyStoreType;
    private final Set<String> allowedHosts;

    private WebServerSettings(Properties properties) {
        try {
            remote = strictBoolean(properties, "web.remote.enabled", false);
            String bind = value(properties, "web.bind.address", "127.0.0.1");
            bindAddress = numericAddress(bind);
            if (bindAddress.isAnyLocalAddress() || bindAddress.isMulticastAddress()) throw bad("Web bind address must identify one interface");
            if (!bindAddress.isLoopbackAddress() && !remote) throw bad("Non-loopback Web binding requires web.remote.enabled=true and TLS");
            String portValue = value(properties, "web.port", "8081");
            if (!portValue.matches("[0-9]{1,5}")) throw bad("Invalid web.port");
            port = Integer.parseInt(portValue);
            if (port < 1 || port > 65535) throw bad("Invalid web.port");
            tokenPath = Paths.get(value(properties, "web.auth.token.file", "web-access.token")).toAbsolutePath().normalize();
            String keyPath = value(properties, "web.tls.keystore", "");
            String passwordPath = value(properties, "web.tls.password.file", "");
            if (keyPath.isEmpty() != passwordPath.isEmpty()) throw bad("Both TLS keystore and private password file are required");
            keyStorePath = keyPath.isEmpty() ? null : Paths.get(keyPath).toAbsolutePath().normalize();
            keyStorePasswordPath = passwordPath.isEmpty() ? null : Paths.get(passwordPath).toAbsolutePath().normalize();
            keyStoreType = value(properties, "web.tls.keystore.type", "PKCS12").toUpperCase(Locale.ROOT);
            if (!keyStoreType.equals("PKCS12") && !keyStoreType.equals("JKS")) throw bad("Unsupported TLS keystore type");
            if (remote && keyStorePath == null) throw bad("Remote Web mode requires TLS and a private keystore password file");
            LinkedHashSet<String> hosts = new LinkedHashSet<String>();
            if (bindAddress.isLoopbackAddress()) {
                hosts.add(bind.equals("::1") ? "[::1]" : bindAddress.getHostAddress());
                hosts.add("localhost");
            } else {
                hosts.add(bindAddress.getHostAddress());
            }
            String extra = value(properties, "web.allowed.hosts", "");
            if (!extra.isEmpty()) {
                for (String entry : extra.split(",", -1)) {
                    String host = validHost(entry.trim());
                    if (!remote && !(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"))) {
                        throw bad("Local-only mode cannot authorize remote Web hosts");
                    }
                    hosts.add(host);
                }
            }
            allowedHosts = Collections.unmodifiableSet(hosts);
        } catch (java.nio.file.InvalidPathException e) {
            throw bad("Invalid Web security file path");
        }
    }

    public static WebServerSettings from(Properties properties) {
        if (properties == null) throw bad("Web settings are required");
        return new WebServerSettings(properties);
    }

    public InetAddress getBindAddress() { return bindAddress; }
    public int getPort() { return port; }
    public boolean isRemoteEnabled() { return remote; }
    public String getScheme() { return keyStorePath == null ? "http" : "https"; }
    public Path getTokenPath() { return tokenPath; }
    public Set<String> getAllowedHosts() { return allowedHosts; }

    /** Exact Host values and Origins include the active listener port, including test ephemeral ports. */
    public Set<String> authorities(int actualPort) {
        if (actualPort < 1 || actualPort > 65535) throw bad("Invalid listener port");
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        int defaultPort = getScheme().equals("https") ? 443 : 80;
        for (String host : allowedHosts) {
            result.add(host + ":" + actualPort);
            if (actualPort == defaultPort) result.add(host);
        }
        return Collections.unmodifiableSet(result);
    }

    /** A private keystore is required. TLS initialization is completed before any listener is bound. */
    public SSLContext loadSslContext() throws IOException, GeneralSecurityException {
        if (keyStorePath == null) return null;
        byte[] storeBytes = PrivateWebToken.readPrivateFile(keyStorePath, 1024 * 1024);
        char[] password = PrivateWebToken.readPrivatePassword(keyStorePasswordPath);
        try {
            KeyStore keyStore = KeyStore.getInstance(keyStoreType);
            try (ByteArrayInputStream input = new ByteArrayInputStream(storeBytes)) {
                keyStore.load(input, password);
            }
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keyStore, password);
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(storeBytes, (byte) 0);
        }
    }

    private static InetAddress numericAddress(String text) {
        try {
            if (text.equals("::1")) return InetAddress.getByAddress(new byte[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1});
            String[] parts = text.split("\\.", -1);
            if (parts.length != 4) throw bad("web.bind.address must be a concrete numeric IPv4 address or ::1");
            byte[] bytes = new byte[4];
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw bad("Invalid numeric Web bind address");
                int n = Integer.parseInt(parts[i]);
                if (n > 255) throw bad("Invalid numeric Web bind address");
                bytes[i] = (byte) n;
            }
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException impossibleLength) {
            throw bad("Invalid numeric Web bind address");
        }
    }

    private static String validHost(String text) {
        String host = text.toLowerCase(Locale.ROOT);
        if (host.equals("[::1]")) return host;
        if (host.length() > 253 || host.isEmpty() || !host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) {
            throw bad("Invalid web.allowed.hosts entry");
        }
        if (host.matches("[0-9.]+")) {
            InetAddress address = numericAddress(host);
            if (address.isAnyLocalAddress() || address.isMulticastAddress()) throw bad("Invalid web.allowed.hosts address");
        } else {
            for (String label : host.split("\\.", -1)) {
                if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                    throw bad("Invalid web.allowed.hosts entry");
                }
            }
        }
        return host;
    }

    private static String value(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null ? defaultValue : value.trim();
    }

    private static boolean strictBoolean(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) return defaultValue;
        if (!value.equals("true") && !value.equals("false")) throw bad("Invalid " + key + "; expected true or false");
        return Boolean.parseBoolean(value);
    }

    private static IllegalArgumentException bad(String text) { return new IllegalArgumentException(text); }
}

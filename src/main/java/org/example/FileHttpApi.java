package org.example;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Bounded raw-file upload and read-only attachment delivery; constructing it opens no sockets. */
public final class FileHttpApi implements HttpHandler {
    @FunctionalInterface
    public interface FileSender {
        void send(ReceivedFileStore.StoredFile file, String type) throws Exception;
    }

    private static final class Response {
        final HttpExchange exchange;
        boolean started;
        Response(HttpExchange exchange) { this.exchange = exchange; }
    }

    private final ReceivedFileStore store;
    private final BooleanSupplier registered;
    private final FileSender sender;

    public FileHttpApi(ReceivedFileStore store, BooleanSupplier registered, FileSender sender) {
        if (store == null || registered == null || sender == null) throw new NullPointerException();
        this.store = store;
        this.registered = registered;
        this.sender = sender;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Response response = new Response(exchange);
        try {
            String path = exchange.getRequestURI().getRawPath();
            if ("/api/file".equals(path)) upload(exchange, response);
            else if (path != null && path.startsWith("/files/")) download(exchange, path, response);
            else failure(response, 404, "NOT_FOUND");
        } catch (ReceivedFileStore.SizeLimitException e) {
            failure(response, 413, "FILE_OR_STORAGE_LIMIT");
        } catch (IllegalArgumentException e) {
            failure(response, 400, "INVALID_FILE_REQUEST");
        } catch (NoSuchFileException e) {
            failure(response, 404, "FILE_NOT_FOUND");
        } catch (SecurityException e) {
            failure(response, 403, "UNSAFE_FILE_PATH");
        } catch (IOException e) {
            failure(response, e.getMessage() != null && e.getMessage().startsWith("UNSAFE_PATH:") ? 403 : 500,
                    "FILE_IO_FAILED");
        } catch (Exception e) {
            failure(response, 500, "FILE_REQUEST_FAILED");
        } finally {
            exchange.close();
        }
    }

    private void upload(HttpExchange exchange, Response response) throws Exception {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            failure(response, 405, "METHOD_NOT_ALLOWED");
            return;
        }
        Map<String, String> params = query(exchange.getRequestURI().getRawQuery());
        String original = params.get("name");
        if (original == null) throw new IllegalArgumentException("Missing filename");
        ReceivedFileStore.validateOriginalName(original);
        String type = params.containsKey("type") ? params.get("type") : "IMG";
        if (!"IMG".equals(type) && !"VOICE".equals(type) && !"VIDEO".equals(type)) {
            throw new IllegalArgumentException("Unsupported message type");
        }
        long expected = contentLength(exchange);
        if (expected > store.getMaxFileBytes()) {
            failure(response, 413, "FILE_OR_STORAGE_LIMIT");
            return;
        }
        if (!registered.getAsBoolean()) {
            failure(response, 503, "SIP_NOT_REGISTERED");
            return;
        }
        ReceivedFileStore.StoredFile file = null;
        boolean submitted = false;
        try {
            file = store.save(original, exchange.getRequestBody());
            if (expected >= 0 && expected != file.getSize()) {
                failure(response, 400, "CONTENT_LENGTH_MISMATCH");
                return;
            }
            sender.send(file, type);
            submitted = true;
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("status", "SUBMITTED");
            result.addProperty("acceptedForSending", true);
            result.addProperty("name", file.getOriginalName());
            result.addProperty("url", fileUrl(file.getStorageName()));
            result.addProperty("size", file.getSize());
            result.addProperty("sha256", file.getSha256());
            json(response, 200, result);
        } finally {
            // A partially sent SIP transfer is not a successful local upload; receiver TTL handles partial chunks.
            if (!submitted && file != null) store.deleteOwned(file);
        }
    }

    private void download(HttpExchange exchange, String rawPath, Response response) throws Exception {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            failure(response, 405, "METHOD_NOT_ALLOWED");
            return;
        }
        if (exchange.getRequestURI().getRawQuery() != null) throw new IllegalArgumentException("Unexpected query");
        String rawName = rawPath.substring("/files/".length());
        if (rawName.isEmpty() || rawName.indexOf('/') >= 0 || rawName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Not one filename");
        }
        String name = decode(rawName, false);
        // Reject ambiguous double-encoding rather than relying on another component never decoding twice.
        if (hasEscapedOctet(name)) throw new IllegalArgumentException("Double encoding");
        ReceivedFileStore.validateOriginalName(name);
        long size = store.sizeOf(name);
        try (InputStream input = store.openForRead(name)) {
            secureHeaders(exchange);
            String mime = mime(name);
            exchange.getResponseHeaders().set("Content-Type", mime);
            exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
            if ("application/octet-stream".equals(mime)) {
                exchange.getResponseHeaders().set("Content-Disposition", "attachment");
            }
            if ("HEAD".equals(method)) {
                begin(response, 200, -1);
                return;
            }
            begin(response, 200, size == 0 ? -1 : size);
            if (size > 0) {
                try (OutputStream output = exchange.getResponseBody()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    long remaining = size;
                    while (remaining > 0) {
                        count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (count < 0) throw new IOException("Attachment truncated while reading");
                        if (count != 0) { output.write(buffer, 0, count); remaining -= count; }
                    }
                    if (input.read() != -1) throw new IOException("Attachment changed while reading");
                }
            }
        }
    }

    public static String fileUrl(String storageName) {
        ReceivedFileStore.validateOriginalName(storageName);
        try {
            return "/files/" + URLEncoder.encode(storageName, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private static long contentLength(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get("Content-Length");
        if (values == null) return -1;
        if (values.size() != 1 || !values.get(0).matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid Content-Length");
        }
        try { return Long.parseLong(values.get(0)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid Content-Length"); }
    }

    private static Map<String, String> query(String raw) {
        if (raw == null || raw.length() > 2048) throw new IllegalArgumentException("Invalid query");
        Map<String, String> result = new HashMap<>();
        for (String pair : raw.split("&", -1)) {
            int equal = pair.indexOf('=');
            if (equal < 1) throw new IllegalArgumentException("Invalid query item");
            String key = decode(pair.substring(0, equal), true);
            String value = decode(pair.substring(equal + 1), true);
            if ((!"name".equals(key) && !"type".equals(key)) || result.put(key, value) != null) {
                throw new IllegalArgumentException("Unexpected or duplicate query item");
            }
        }
        return result;
    }

    private static String decode(String raw, boolean plusAsSpace) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < raw.length();) {
            char ch = raw.charAt(i);
            if (ch == '%') {
                if (i + 2 >= raw.length()) throw new IllegalArgumentException("Invalid escape");
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid escape");
                bytes.write((hi << 4) | lo);
                i += 3;
            } else {
                if (ch == '+' && plusAsSpace) ch = ' ';
                if (ch > 127) throw new IllegalArgumentException("URI must encode non-ASCII");
                bytes.write(ch);
                i++;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8", e);
        }
    }

    private static boolean hasEscapedOctet(String name) {
        for (int i = 0; i + 2 < name.length(); i++) {
            if (name.charAt(i) == '%' && Character.digit(name.charAt(i + 1), 16) >= 0
                    && Character.digit(name.charAt(i + 2), 16) >= 0) return true;
        }
        return false;
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }

    private static void secureHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "sandbox; default-src 'none'");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    private static void failure(Response response, int code, String error) throws IOException {
        if (response.started) {
            throw new IOException("File response interrupted after headers; response closed");
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", error);
        json(response, code, result);
    }

    private static void begin(Response response, int code, long length) throws IOException {
        // A failed header write may already be visible on the wire; never attempt a second response.
        response.started = true;
        response.exchange.sendResponseHeaders(code, length);
    }

    private static void json(Response response, int code, JsonObject result) throws IOException {
        HttpExchange exchange = response.exchange;
        byte[] body = result.toString().getBytes(StandardCharsets.UTF_8);
        secureHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            begin(response, code, -1);
        } else {
            begin(response, code, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        }
    }
}

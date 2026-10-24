package org.example;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Separate HTTP boundary; construction opens no listener and never initializes SIP. */
public final class GroupWebApi implements HttpHandler {
    static final int MAX_BODY_BYTES = 4096;
    private final GroupService groups;
    private final BooleanSupplier registered;

    public GroupWebApi(GroupService groups, BooleanSupplier registered) {
        this.groups = java.util.Objects.requireNonNull(groups, "groups");
        this.registered = java.util.Objects.requireNonNull(registered, "registered");
    }

    public static JsonObject event(GroupService groups) {
        JsonObject event = groups.snapshotEnvelope(); event.addProperty("t", "group-state"); return event;
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if ("/api/groups".equals(path)) {
                if (!"GET".equals(exchange.getRequestMethod())) { error(exchange, 405, "METHOD_NOT_ALLOWED"); return; }
                respond(exchange, 200, result()); return;
            }
            String action = path.startsWith("/api/groups/") ? path.substring("/api/groups/".length()) : "";
            if (!Arrays.asList("create", "join", "send", "leave", "sync", "retry").contains(action)) { error(exchange, 404, "UNKNOWN_GROUP_ACTION"); return; }
            if (!"POST".equals(exchange.getRequestMethod())) { error(exchange, 405, "METHOD_NOT_ALLOWED"); return; }
            if (!sameOrigin(exchange)) { error(exchange, 403, "CROSS_ORIGIN_REJECTED"); return; }
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !"application/json".equalsIgnoreCase(type.split(";", 2)[0].trim())) {
                error(exchange, 415, "JSON_REQUIRED"); return;
            }
            if (!registered.getAsBoolean()) { error(exchange, 503, "SIP_NOT_REGISTERED"); return; }
            Input body = parse(exchange.getRequestBody());
            if ("create".equals(action)) {
                body.keys("name", "members");
                groups.create(body.string("name"), body.members);
            } else {
                if ("send".equals(action)) body.keys("groupId", "text");
                else if ("retry".equals(action)) body.keys("groupId", "messageId");
                else body.keys("groupId");
                String id = body.string("groupId");
                switch (action) {
                    case "join": groups.join(id); break;
                    case "send": groups.send(id, body.string("text")); break;
                    case "leave": groups.leave(id); break;
                    case "sync": groups.sync(id); break;
                    case "retry": groups.retry(id, body.string("messageId")); break;
                    default: throw new IllegalArgumentException("UNKNOWN_GROUP_ACTION");
                }
            }
            respond(exchange, 200, result());
        } catch (BodyTooLarge tooLarge) { error(exchange, 413, "GROUP_BODY_TOO_LARGE"); }
        catch (IllegalArgumentException invalid) { error(exchange, 400, safe(invalid.getMessage())); }
        catch (IllegalStateException invalidState) { error(exchange, 409, safe(invalidState.getMessage())); }
        finally { exchange.close(); }
    }

    private JsonObject result() { JsonObject result = groups.snapshotEnvelope(); result.addProperty("ok", true); return result; }
    private static String safe(String error) { return error == null ? "GROUP_OPERATION_FAILED" : error; }
    private static void error(HttpExchange exchange, int code, String error) throws IOException {
        JsonObject result = new JsonObject(); result.addProperty("ok", false); result.addProperty("error", error); respond(exchange, code, result);
    }
    private static void respond(HttpExchange exchange, int code, JsonObject result) throws IOException {
        byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
    private static boolean sameOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true; // native same-host tools; existing app uses trusted LAN deployment
        try {
            URI uri = URI.create(origin);
            String host = exchange.getRequestHeaders().getFirst("Host");
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && host != null
                    && host.equalsIgnoreCase(uri.getRawAuthority()) && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static Input parse(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[512]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > MAX_BODY_BYTES) throw new BodyTooLarge();
            out.write(buffer, 0, count);
        }
        final String json;
        try { json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString(); }
        catch (java.nio.charset.CharacterCodingException badEncoding) { throw new IllegalArgumentException("INVALID_UTF8"); }
        // Gson JsonReader still accepts raw string controls: reject them before token parsing.
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (c < 0x20) throw new IllegalArgumentException("INVALID_JSON_CONTROL");
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
        }        Input result = new Input();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IllegalArgumentException("JSON_OBJECT_REQUIRED");
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!result.fields.add(key)) throw new IllegalArgumentException("DUPLICATE_FIELD");
                if ("members".equals(key)) {
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) throw new IllegalArgumentException("MEMBERS_ARRAY_REQUIRED");
                    result.members = new ArrayList<>(); reader.beginArray();
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.STRING || result.members.size() >= GroupProtocol.MAX_MEMBERS) throw new IllegalArgumentException("INVALID_MEMBERS");
                        result.members.add(reader.nextString());
                    }
                    reader.endArray();
                } else {
                    if (!Arrays.asList("groupId", "messageId", "name", "text").contains(key) || reader.peek() != JsonToken.STRING) throw new IllegalArgumentException("INVALID_FIELD");
                    result.values.put(key, reader.nextString());
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("TRAILING_JSON");
        } catch (IOException | IllegalStateException invalidJson) { throw new IllegalArgumentException("INVALID_JSON"); }
        return result;
    }
    private static final class Input {
        final Set<String> fields = new HashSet<>(); final Map<String, String> values = new HashMap<>(); List<String> members;
        void keys(String... expected) { if (!fields.equals(new HashSet<>(Arrays.asList(expected)))) throw new IllegalArgumentException("INVALID_FIELDS"); }
        String string(String key) { String value = values.get(key); if (value == null) throw new IllegalArgumentException("MISSING_FIELD"); return value; }
    }
    private static final class BodyTooLarge extends IOException { }
}

package org.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict parsing and size/identity checks shared by the SIP and HTTP boundaries. */
public final class GroupProtocol {
    /**
     * 群信封的原始 MIME，仅用于兼容旧版本识别。
     *
     * <p>miniSIPServer 会对自定义 {@code application/*+json} 返回
     * {@code 406 Unsupported Content-Type}，导致群消息根本无法路由，
     * 因此线上发送改为 {@link #WIRE_MEDIA_TYPE}，并用 {@link #HEADER} 标记。
     */
    public static final String MEDIA_TYPE = "application/vnd.sip-im.group+json";
    /** 实际发送使用的 MIME，必须是被 SIP 服务器接受的通用类型。 */
    public static final String WIRE_MEDIA_TYPE = "text/plain";
    /** 群信封标记头，用于在 text/plain 中区分群消息与普通文字消息。 */
    public static final String HEADER = "X-SIP-IM-Group";
    public static final String HEADER_VALUE = "1";
    /**
     * 群信封的正文前缀（{@link GroupMessage} 字段顺序决定）。
     *
     * <p>miniSIPServer 在转发 MESSAGE 时会**剥离自定义头**，接收端拿不到
     * {@link #HEADER}，因此再用正文前缀做一次兜底识别。
     */
    public static final String ENVELOPE_PREFIX = "{\"version\":1,\"type\":\"";
    public static final int MAX_MEMBERS = 8;
    public static final int MAX_TEXT_BYTES = 400;
    public static final int MAX_WIRE_BYTES = 800;
    public static final int MAX_NAME_BYTES = 48;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> TYPES = new HashSet<String>(Arrays.asList(
            "INVITE", "STATE", "TEXT", "ACK", "ACCEPT", "LEAVE", "SYNC"));
    private static final Set<String> STATES = new HashSet<String>(Arrays.asList(
            "ACTIVE", "INVITED", "LEFT"));

    private GroupProtocol() { }

    public static String user(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,16}")) {
            throw invalid("Invalid SIP user");
        }
        return value;
    }

    public static String text(String value) {
        scalar(value, MAX_TEXT_BYTES, "text");
        if (value.trim().isEmpty()) throw invalid("Empty text");
        return value;
    }

    public static String name(String value) {
        if (value == null) throw invalid("Missing name");
        // Check before trim so that disallowed controls cannot be silently removed.
        unicode(value);
        controls(value);
        String result = value.trim();
        scalar(result, MAX_NAME_BYTES, "name");
        if (result.isEmpty()) throw invalid("Empty name");
        return result;
    }

    public static String encode(GroupMessage message) {
        validate(message);
        String result = GSON.toJson(message);
        wire(result);
        return result;
    }

    /** 正文是否以群信封前缀开头（服务器可能剥离标记头，用正文兜底识别）。 */
    public static boolean looksLikeEnvelope(byte[] raw) {
        if (raw == null || raw.length < ENVELOPE_PREFIX.length() || raw.length > MAX_WIRE_BYTES) return false;
        String head = new String(raw, 0, ENVELOPE_PREFIX.length(), StandardCharsets.UTF_8);
        return ENVELOPE_PREFIX.equals(head);
    }

    public static GroupMessage decode(String wire) {
        wire(wire);
        GroupMessage result = new GroupMessage();
        Set<String> seen = new HashSet<String>();
        try (JsonReader reader = new JsonReader(new StringReader(wire))) {
            reader.setLenient(false);
            expect(reader, JsonToken.BEGIN_OBJECT);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!seen.add(key)) throw invalid("Duplicate field");
                switch (key) {
                    case "version": result.version = numberInt(reader); break;
                    case "type": result.type = string(reader); break;
                    case "groupId": result.groupId = string(reader); break;
                    case "messageId": result.messageId = string(reader); break;
                    case "senderId": result.senderId = string(reader); break;
                    case "ownerId": result.ownerId = string(reader); break;
                    case "token": result.token = string(reader); break;
                    case "revision": result.revision = numberInt(reader); break;
                    case "timestamp": result.timestamp = numberLong(reader); break;
                    case "name": result.name = string(reader); break;
                    case "text": result.text = string(reader); break;
                    case "ackForId": result.ackForId = string(reader); break;
                    case "ackStatus": result.ackStatus = string(reader); break;
                    case "ackDetail": result.ackDetail = string(reader); break;
                    case "members": result.members = members(reader); break;
                    case "closed":
                        expect(reader, JsonToken.BOOLEAN);
                        result.closed = reader.nextBoolean();
                        break;
                    default: throw invalid("Unknown field");
                }
            }
            reader.endObject();
            expect(reader, JsonToken.END_DOCUMENT);
        } catch (IOException | IllegalStateException ex) {
            // Errors intentionally contain no payload, bearer token or credentials.
            throw invalid("Malformed group envelope");
        }
        if (!seen.containsAll(Arrays.asList("version", "type", "groupId", "messageId",
                "senderId", "ownerId", "token", "revision", "timestamp"))) {
            throw invalid("Missing required field");
        }
        validate(result);
        return result;
    }

    private static Map<String, String> members(JsonReader reader) throws IOException {
        expect(reader, JsonToken.BEGIN_OBJECT);
        reader.beginObject();
        Map<String, String> result = new LinkedHashMap<String, String>();
        while (reader.hasNext()) {
            String key = user(reader.nextName());
            if (result.containsKey(key)) throw invalid("Duplicate member");
            result.put(key, string(reader));
            if (result.size() > MAX_MEMBERS) throw invalid("Too many members");
        }
        reader.endObject();
        return result;
    }

    private static void validate(GroupMessage value) {
        if (value == null || value.version != 1 || !TYPES.contains(value.type)) {
            throw invalid("Unsupported envelope version or type");
        }
        uuid(value.groupId);
        uuid(value.messageId);
        uuid(value.token);
        user(value.senderId);
        user(value.ownerId);
        if (value.revision < 1 || value.revision > 1000000000 || value.timestamp < 1) {
            throw invalid("Invalid revision or timestamp");
        }
        boolean state = "INVITE".equals(value.type) || "STATE".equals(value.type);
        boolean text = "TEXT".equals(value.type);
        boolean ack = "ACK".equals(value.type);
        if (state) {
            if (value.name == null || !value.name.equals(name(value.name))) {
                throw invalid("Noncanonical group name");
            }
            if (value.members == null || value.members.size() < 3 || value.members.size() > MAX_MEMBERS
                    || value.closed == null || !value.members.containsKey(value.ownerId)) {
                throw invalid("Invalid membership snapshot");
            }
            for (Map.Entry<String, String> member : value.members.entrySet()) {
                user(member.getKey());
                if (!STATES.contains(member.getValue())) throw invalid("Invalid member state");
            }
            if (!value.closed && !"ACTIVE".equals(value.members.get(value.ownerId))) {
                throw invalid("Inactive owner");
            }
            if ("INVITE".equals(value.type) && value.closed) throw invalid("Closed invitation");
        } else if (value.name != null || value.members != null || value.closed != null) {
            throw invalid("Unexpected membership fields");
        }
        if (text) text(value.text);
        else if (value.text != null) throw invalid("Unexpected text");
        if (ack) {
            uuid(value.ackForId);
            if (!"DELIVERED".equals(value.ackStatus) && !"REJECTED".equals(value.ackStatus)) {
                throw invalid("Invalid acknowledgement status");
            }
            if (value.ackDetail != null && !value.ackDetail.matches("[A-Z][A-Z0-9_]{0,47}")) {
                throw invalid("Invalid acknowledgement detail");
            }
        } else if (value.ackForId != null || value.ackStatus != null || value.ackDetail != null) {
            throw invalid("Unexpected acknowledgement fields");
        }
    }

    private static void uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw invalid("Invalid canonical UUID");
        }
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw invalid("Invalid canonical UUID");
        } catch (IllegalArgumentException ex) { throw invalid("Invalid canonical UUID"); }
    }

    private static void wire(String value) {
        if (value == null) throw invalid("Missing envelope");
        unicode(value);
        // JsonReader 2.x permits raw control characters in quoted strings even
        // with lenient=false. RFC JSON requires them to be escaped explicitly.
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (quoted) {
                if (character < 0x20) throw invalid("Unescaped JSON control character");
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') quoted = true;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_WIRE_BYTES) throw invalid("Envelope too large");
    }

    private static void scalar(String value, int maxBytes, String label) {
        if (value == null) throw invalid("Missing " + label);
        unicode(value);
        controls(value);
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw invalid("Oversized " + label);
    }

    private static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw invalid("Invalid Unicode");
                }
            } else if (Character.isLowSurrogate(character)) throw invalid("Invalid Unicode");
        }
    }

    private static void controls(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((Character.isISOControl(character) && character != '\n' && character != '\t')
                    || character == '\u202a' || character == '\u202b' || character == '\u202c'
                    || character == '\u202d' || character == '\u202e'
                    || (character >= '\u2066' && character <= '\u2069')) {
                throw invalid("Unsafe control character");
            }
        }
    }

    private static void expect(JsonReader reader, JsonToken token) throws IOException {
        if (reader.peek() != token) throw invalid("Invalid JSON type");
    }

    private static String string(JsonReader reader) throws IOException {
        expect(reader, JsonToken.STRING);
        return reader.nextString();
    }

    private static int numberInt(JsonReader reader) throws IOException {
        long value = numberLong(reader);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) throw invalid("Invalid integer");
        return (int) value;
    }

    private static long numberLong(JsonReader reader) throws IOException {
        expect(reader, JsonToken.NUMBER);
        String value = reader.nextString();
        if (!value.matches("0|[1-9][0-9]*")) throw invalid("Invalid integer");
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw invalid("Invalid integer"); }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}

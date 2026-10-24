package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** HTTP exchanges and inter-user messages are entirely in memory; no listener or SIP stack. */
public class GroupWebApiTest {
    private Network network;
    private final Map<String, GroupWebApi> apis = new LinkedHashMap<>();
    private final Map<String, AtomicBoolean> registrations = new LinkedHashMap<>();

    @Before public void prepare() {
        network = new Network();
        apis.clear(); registrations.clear();
        for (String user : Arrays.asList("100", "101", "102")) {
            GroupService service = network.add(user);
            AtomicBoolean registered = new AtomicBoolean(true);
            registrations.put(user, registered);
            apis.put(user, new GroupWebApi(service, registered::get));
        }
    }

    @Test public void getIsDetachedAndNeverExposesProtocolToken() throws Exception {
        String id = create();
        String token = GroupProtocol.decode(network.sent.get(0).body).token;
        Exchange first = request("100", "GET", "/api/groups", "");
        assertEquals(200, first.status);
        assertFalse(first.response().contains("\"token\""));
        assertFalse(first.response().contains(token));
        first.json().getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("name", "mutated");
        JsonObject again = group("100", id);
        assertEquals("测试群", again.get("name").getAsString());
        assertEquals("application/json; charset=utf-8", first.responseHeaders.getFirst("Content-Type"));
        assertEquals("no-store", first.responseHeaders.getFirst("Cache-Control"));
        registrations.get("100").set(false);
        assertEquals(200, request("100", "GET", "/api/groups", "").status);
    }

    @Test public void snapshotOrderingSharesEpochAndIncreasesAcrossHttpAndSse() throws Exception {
        JsonObject first = request("100", "GET", "/api/groups", "").json();
        JsonObject event = GroupWebApi.event(network.users.get("100"));
        JsonObject second = request("100", "GET", "/api/groups", "").json();
        String epoch = first.get("snapshotEpoch").getAsString();
        assertFalse(epoch.isEmpty());
        assertEquals(epoch, event.get("snapshotEpoch").getAsString());
        assertEquals(epoch, second.get("snapshotEpoch").getAsString());
        assertTrue(first.get("snapshotSequence").getAsLong() < event.get("snapshotSequence").getAsLong());
        assertTrue(event.get("snapshotSequence").getAsLong() < second.get("snapshotSequence").getAsLong());
        assertNotEquals(epoch, request("101", "GET", "/api/groups", "").json().get("snapshotEpoch").getAsString());
    }

    @Test public void createInvitesThreeUserGroupThroughActualTransport() throws Exception {
        String id = create();
        assertEquals(2, network.sent.size());
        Set<String> recipients = new HashSet<>();
        for (Packet packet : network.sent) {
            GroupMessage invitation = GroupProtocol.decode(packet.body);
            assertEquals("INVITE", invitation.type);
            assertEquals("100", invitation.senderId);
            assertEquals(id, invitation.groupId);
            assertEquals(3, invitation.members.size());
            recipients.add(packet.recipient);
        }
        assertEquals(new HashSet<>(Arrays.asList("101", "102")), recipients);
        network.pump();
        for (String user : Arrays.asList("100", "101", "102")) {
            JsonObject snapshot = group(user, id);
            assertEquals(3, snapshot.getAsJsonArray("members").size());
            assertEquals("100", snapshot.get("ownerId").getAsString());
        }
        assertEquals("INVITED", group("101", id).get("localStatus").getAsString());
    }

    @Test public void joinSendAndLeaveEndpointsUseThreeIndependentServices() throws Exception {
        String id = joined();
        String text = "中文 English <>&\" 😀";
        JsonObject send = new JsonObject(); send.addProperty("groupId", id); send.addProperty("text", text);
        int sentBefore = network.sent.size();
        assertEquals(200, post("101", "send", send.toString()).status);
        List<Packet> dispatched = network.sent.subList(sentBefore, network.sent.size());
        assertEquals(2, dispatched.size());
        Set<String> destinations = new HashSet<>();
        for (Packet packet : dispatched) {
            assertEquals("TEXT", GroupProtocol.decode(packet.body).type);
            destinations.add(packet.recipient);
        }
        assertEquals(new HashSet<>(Arrays.asList("100", "102")), destinations);
        network.pump();
        for (String user : Arrays.asList("100", "101", "102")) {
            JsonArray history = group(user, id).getAsJsonArray("messages");
            assertEquals(1, history.size());
            assertEquals(text, history.get(0).getAsJsonObject().get("text").getAsString());
            assertEquals("101", history.get(0).getAsJsonObject().get("senderId").getAsString());
        }
        assertEquals("DELIVERED", group("101", id).getAsJsonArray("messages").get(0).getAsJsonObject()
                .getAsJsonObject("deliveries").getAsJsonObject("100").get("status").getAsString());
        assertEquals(200, post("101", "leave", idBody(id)).status);
        network.pump();
        assertEquals("LEFT", group("101", id).get("localStatus").getAsString());
        assertEquals(409, post("101", "send", send.toString()).status);
        JsonObject next = new JsonObject(); next.addProperty("groupId", id); next.addProperty("text", "after leave");
        assertEquals(200, post("100", "send", next.toString()).status);
        network.pump();
        assertEquals(1, group("101", id).getAsJsonArray("messages").size());
        assertEquals(2, group("102", id).getAsJsonArray("messages").size());
    }

    @Test public void syncRequestsAuthorityStateAndDoesNotInventMessages() throws Exception {
        String id = joined();
        int before = network.sent.size();
        assertEquals(200, post("102", "sync", idBody(id)).status);
        assertEquals("SYNC", GroupProtocol.decode(network.sent.get(before).body).type);
        assertEquals("100", network.sent.get(before).recipient);
        network.pump();
        assertEquals("ACTIVE", group("102", id).get("localStatus").getAsString());
        assertEquals(0, group("102", id).getAsJsonArray("messages").size());
        assertEquals(200, post("100", "sync", idBody(id)).status);
        network.pump();
        assertEquals(0, group("100", id).getAsJsonArray("messages").size());
    }

    @Test public void retryPreservesMessageIdAndDoesNotDuplicateSuccessfulRecipient() throws Exception {
        String id = joined();
        network.blocked.add("102");
        assertEquals(200, post("100", "send", "{\"groupId\":\"" + id + "\",\"text\":\"retry me\"}").status);
        network.pump();
        JsonObject original = group("100", id).getAsJsonArray("messages").get(0).getAsJsonObject();
        String messageId = original.get("id").getAsString();
        assertEquals("FAILED", original.getAsJsonObject("deliveries").getAsJsonObject("102").get("status").getAsString());
        assertEquals(1, group("101", id).getAsJsonArray("messages").size());
        assertEquals(0, group("102", id).getAsJsonArray("messages").size());
        network.blocked.clear();
        assertEquals(200, post("100", "retry", "{\"groupId\":\"" + id + "\",\"messageId\":\"" + messageId + "\"}").status);
        network.pump();
        for (String user : Arrays.asList("100", "101", "102")) {
            JsonArray history = group(user, id).getAsJsonArray("messages");
            assertEquals(1, history.size());
            assertEquals(messageId, history.get(0).getAsJsonObject().get("id").getAsString());
        }
        assertEquals("DELIVERED", group("100", id).getAsJsonArray("messages").get(0).getAsJsonObject()
                .getAsJsonObject("deliveries").getAsJsonObject("102").get("status").getAsString());
    }

    @Test public void groupEventContainsOnlyPublicGroupSnapshot() throws Exception {
        GroupService service = network.users.get("100");
        List<JsonObject> events = new ArrayList<>();
        service.setListener(() -> events.add(GroupWebApi.event(service)));
        create();
        assertFalse(events.isEmpty());
        String token = GroupProtocol.decode(network.sent.get(0).body).token;
        for (JsonObject event : events) {
            assertEquals("group-state", event.get("t").getAsString());
            assertTrue(event.has("groups"));
            assertFalse(event.has("peer")); assertFalse(event.has("text")); assertFalse(event.has("out"));
            assertFalse(event.toString().contains("\"token\""));
            assertFalse(event.toString().contains(token));
        }
        events.get(0).getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("name", "external mutation");
        assertEquals("测试群", service.snapshot().get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test public void methodsAndUnknownActionsAreRejectedWithoutSending() throws Exception {
        assertEquals(405, request("100", "POST", "/api/groups", "{}").status);
        assertEquals(405, request("100", "GET", "/api/groups/create", "{}").status);
        assertEquals(405, request("100", "PUT", "/api/groups/send", "{}").status);
        assertEquals(404, post("100", "unknown", "{}").status);
        assertEquals(404, post("100", "create/extra", "{}").status);
        assertTrue(network.sent.isEmpty());
    }

    @Test public void oldPrivateFileCallAndStreamPathsAreNotGroupActions() throws Exception {
        for (String path : Arrays.asList("/api/send", "/api/file", "/api/setpeer", "/api/call", "/api/hangup", "/api/stream")) {
            Exchange exchange = request("100", "POST", path, "{}");
            assertEquals(path, 404, exchange.status);
            assertEquals("UNKNOWN_GROUP_ACTION", exchange.json().get("error").getAsString());
        }
        assertTrue(network.sent.isEmpty());
        assertEquals(0, network.users.get("100").snapshot().size());
    }

    @Test public void mutationRequiresRegisteredSipButGetDoesNot() throws Exception {
        registrations.get("100").set(false);
        Exchange result = post("100", "create", validCreate());
        assertEquals(503, result.status);
        assertEquals("SIP_NOT_REGISTERED", result.json().get("error").getAsString());
        assertEquals(200, request("100", "GET", "/api/groups", "").status);
        assertTrue(network.sent.isEmpty());
        assertEquals(0, network.users.get("100").snapshot().size());
    }

    @Test public void contentTypeMustBeJsonAndCharsetParameterIsAccepted() throws Exception {
        for (String type : Arrays.asList("text/plain", "application/x-www-form-urlencoded", "application/jsonp")) {
            Exchange exchange = base("POST", "/api/groups/create", validCreate());
            exchange.requestHeaders.set("Content-Type", type);
            invoke("100", exchange);
            assertEquals(415, exchange.status);
        }
        Exchange missing = base("POST", "/api/groups/create", validCreate());
        missing.requestHeaders.remove("Content-Type"); invoke("100", missing);
        assertEquals(415, missing.status);
        Exchange valid = base("POST", "/api/groups/create", validCreate());
        valid.requestHeaders.set("Content-Type", "application/json; charset=UTF-8"); invoke("100", valid);
        assertEquals(200, valid.status);
        assertEquals("application/json; charset=utf-8", valid.responseHeaders.getFirst("Content-Type"));
    }

    @Test public void crossOriginMutationIsRejectedButSameHostOrNativeToolsAreAllowed() throws Exception {
        for (String origin : Arrays.asList("http://evil.invalid", "http://127.0.0.1:9999", "null", "http://user@127.0.0.1:8081")) {
            Exchange exchange = base("POST", "/api/groups/create", validCreate());
            exchange.requestHeaders.set("Origin", origin); invoke("100", exchange);
            assertEquals(origin, 403, exchange.status);
            assertEquals("CROSS_ORIGIN_REJECTED", exchange.json().get("error").getAsString());
        }
        assertTrue(network.sent.isEmpty());
        Exchange same = base("POST", "/api/groups/create", validCreate());
        invoke("100", same); assertEquals(200, same.status);
        Exchange nativeTool = base("POST", "/api/groups/create", validCreate());
        nativeTool.requestHeaders.remove("Origin"); invoke("100", nativeTool); assertEquals(200, nativeTool.status);
    }

    @Test public void missingAndUnexpectedFieldsAreRejected() throws Exception {
        for (String body : Arrays.asList(
                "{}", "{\"name\":\"g\"}", "{\"members\":[\"101\",\"102\"]}",
                "{\"name\":\"g\",\"members\":[\"101\",\"102\"],\"extra\":\"x\"}",
                "{\"name\":\"g\",\"members\":[\"101\",\"102\"],\"groupId\":\"x\"}")) {
            assertEquals(body, 400, post("100", "create", body).status);
        }
        assertEquals(400, post("100", "send", "{\"groupId\":\"x\",\"text\":\"hi\",\"name\":\"extra\"}").status);
        assertTrue(network.sent.isEmpty());
    }

    @Test public void wrongJsonTypesAreRejectedWithoutCoercion() throws Exception {
        for (String body : Arrays.asList(
                "[]", "null", "\"scalar\"", "1",
                "{\"name\":1,\"members\":[\"101\",\"102\"]}",
                "{\"name\":true,\"members\":[\"101\",\"102\"]}",
                "{\"name\":null,\"members\":[\"101\",\"102\"]}",
                "{\"name\":{},\"members\":[\"101\",\"102\"]}",
                "{\"name\":\"g\",\"members\":\"101,102\"}",
                "{\"name\":\"g\",\"members\":null}",
                "{\"name\":\"g\",\"members\":[101,102]}",
                "{\"name\":\"g\",\"members\":[\"101\",null]}")) {
            assertEquals(body, 400, post("100", "create", body).status);
        }
        assertEquals(400, post("100", "send", "{\"groupId\":\"x\",\"text\":42}").status);
        assertTrue(network.sent.isEmpty());
    }

    @Test public void duplicateFieldsMalformedAndTrailingJsonAreRejected() throws Exception {
        for (String body : Arrays.asList(
                "{\"name\":\"g\",\"name\":\"h\",\"members\":[\"101\",\"102\"]}",
                "{\"name\":\"g\",\"members\":[\"101\",\"102\"],\"members\":[\"101\",\"102\"]}",
                validCreate() + " {}", "{", "{name:'g',members:['101','102']}", "/*comment*/" + validCreate())) {
            assertEquals(body, 400, post("100", "create", body).status);
        }
        assertTrue(network.sent.isEmpty());
    }

    @Test public void rawNewlineAndTabInsideJsonStringsAreRejected() throws Exception {
        for (String control : Arrays.asList("\n", "\t")) {
            String body = "{\"name\":\"raw" + control + "control\",\"members\":[\"101\",\"102\"]}";
            assertEquals(400, post("100", "create", body).status);
        }
        assertTrue(network.sent.isEmpty());
    }

    @Test public void escapedNewlineAndTabInTextRemainValidJson() throws Exception {
        String id = joined();
        String body = "{\"groupId\":\"" + id + "\",\"text\":\"line1\\nline2\\tvalue\"}";
        assertEquals(200, post("100", "send", body).status);
        network.pump();
        assertEquals("line1\nline2\tvalue", group("101", id).getAsJsonArray("messages")
                .get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test public void bodyAbove4096BytesIsRejectedBeforeParsing() throws Exception {
        char[] chars = new char[4097]; Arrays.fill(chars, ' ');
        Exchange exchange = post("100", "create", new String(chars));
        assertEquals(413, exchange.status);
        assertEquals("GROUP_BODY_TOO_LARGE", exchange.json().get("error").getAsString());
        assertTrue(network.sent.isEmpty());
    }

    @Test public void exactly4096BytesOfValidJsonAndWhitespaceAreAccepted() throws Exception {
        String body = "{\"name\":\"group\",\"members\":[\"101\",\"102\"]}";
        StringBuilder padded = new StringBuilder(body);
        while (padded.length() < 4096) padded.append(' ');
        assertEquals(4096, padded.toString().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(200, post("100", "create", padded.toString()).status);
    }

    @Test public void malformedUtf8IsRejectedWithoutReplacementCharacters() throws Exception {
        Exchange exchange = base("POST", "/api/groups/create", "");
        exchange.requestBody = new ByteArrayInputStream(new byte[] { '{', '"', 'n', 'a', 'm', 'e', '"', ':', '"', (byte) 0xc3, '(', '"', '}' });
        invoke("100", exchange);
        assertEquals(400, exchange.status);
        assertEquals("INVALID_UTF8", exchange.json().get("error").getAsString());
        assertTrue(network.sent.isEmpty());
    }

    @Test public void unknownGroupsAndInvalidStateHaveExplicitErrors() throws Exception {
        for (String action : Arrays.asList("join", "leave", "sync")) {
            assertEquals(action, 400, post("100", action, idBody("unknown")).status);
        }
        assertEquals(400, post("100", "send", "{\"groupId\":\"unknown\",\"text\":\"hi\"}").status);
        assertEquals(400, post("100", "retry", "{\"groupId\":\"unknown\",\"messageId\":\"unknown\"}").status);
        String id = create();
        assertEquals(409, post("100", "send", "{\"groupId\":\"" + id + "\",\"text\":\"not yet\"}").status);
        assertEquals(409, post("100", "join", idBody(id)).status);
        assertEquals(400, post("100", "retry", "{\"groupId\":\"" + id + "\",\"messageId\":\"unknown\"}").status);
    }

    @Test public void invalidMemberCountAndOversizedTextDoNotChangeHistory() throws Exception {
        assertEquals(400, post("100", "create", "{\"name\":\"g\",\"members\":[\"101\"]}").status);
        assertEquals(400, post("100", "create", "{\"name\":\"g\",\"members\":[\"101\",\"101\"]}").status);
        String id = joined();
        JsonObject body = new JsonObject(); body.addProperty("groupId", id); body.addProperty("text", "   ");
        assertEquals(400, post("100", "send", body.toString()).status);
        char[] tooLong = new char[401]; Arrays.fill(tooLong, 'x'); body.addProperty("text", new String(tooLong));
        assertEquals(400, post("100", "send", body.toString()).status);
        assertEquals(0, group("100", id).getAsJsonArray("messages").size());
    }

    private String create() throws Exception {
        Exchange exchange = post("100", "create", validCreate());
        assertEquals(exchange.response(), 200, exchange.status);
        return exchange.json().getAsJsonArray("groups").get(0).getAsJsonObject().get("id").getAsString();
    }
    private String joined() throws Exception {
        String id = create(); network.pump();
        assertEquals(200, post("101", "join", idBody(id)).status); network.pump();
        assertEquals(200, post("102", "join", idBody(id)).status); network.pump();
        for (String user : Arrays.asList("100", "101", "102")) assertEquals("ACTIVE", group(user, id).get("localStatus").getAsString());
        return id;
    }
    private static String validCreate() { return "{\"name\":\"测试群\",\"members\":[\"101\",\"102\"]}"; }
    private static String idBody(String id) { return "{\"groupId\":\"" + id + "\"}"; }
    private JsonObject group(String user, String id) throws Exception {
        JsonArray groups = request(user, "GET", "/api/groups", "").json().getAsJsonArray("groups");
        for (int i = 0; i < groups.size(); i++) {
            JsonObject group = groups.get(i).getAsJsonObject();
            if (id.equals(group.get("id").getAsString())) return group;
        }
        throw new AssertionError("Missing group " + id + " at " + user);
    }
    private Exchange post(String user, String action, String body) throws Exception { return request(user, "POST", "/api/groups/" + action, body); }
    private Exchange request(String user, String method, String path, String body) throws Exception {
        Exchange exchange = base(method, path, body); invoke(user, exchange); return exchange;
    }
    private Exchange base(String method, String path, String body) {
        Exchange exchange = new Exchange(method, path, body.getBytes(StandardCharsets.UTF_8));
        exchange.requestHeaders.set("Host", "127.0.0.1:8081");
        exchange.requestHeaders.set("Origin", "http://127.0.0.1:8081");
        exchange.requestHeaders.set("Content-Type", "application/json");
        return exchange;
    }
    private void invoke(String user, Exchange exchange) throws Exception {
        apis.get(user).handle(exchange);
        assertTrue("response status not set", exchange.status >= 200);
        assertEquals(exchange.response().getBytes(StandardCharsets.UTF_8).length, exchange.responseLength);
        assertEquals("application/json; charset=utf-8", exchange.responseHeaders.getFirst("Content-Type"));
        assertTrue(exchange.json().has("ok"));
    }

    private static final class Packet {
        final String sender, recipient, body; final GroupTransport.Completion completion;
        Packet(String sender, String recipient, String body, GroupTransport.Completion completion) {
            this.sender = sender; this.recipient = recipient; this.body = body; this.completion = completion;
        }
    }
    private static final class Network {
        final Map<String, GroupService> users = new LinkedHashMap<>();
        final Queue<Packet> queue = new ArrayDeque<>();
        final List<Packet> sent = new ArrayList<>();
        final Set<String> blocked = new HashSet<>();
        GroupService add(String user) {
            GroupService service = new GroupService(user, (recipient, body, completion) -> {
                Packet packet = new Packet(user, recipient, body, completion); sent.add(packet); queue.add(packet);
            }, () -> 100000L);
            users.put(user, service); return service;
        }
        void pump() {
            int remaining = 1000;
            while (!queue.isEmpty()) {
                if (--remaining == 0) throw new AssertionError("unbounded protocol exchange");
                Packet packet = queue.remove();
                if (blocked.contains(packet.recipient)) { packet.completion.completed(false, "OFFLINE"); continue; }
                GroupService recipient = users.get(packet.recipient);
                if (recipient == null) throw new AssertionError("unknown recipient " + packet.recipient);
                int status = recipient.receive(packet.sender, packet.body);
                packet.completion.completed(status >= 200 && status < 300, "SIP_STATUS_" + status);
            }
        }
    }
    private static final class Exchange extends HttpExchange {
        final Headers requestHeaders = new Headers(); final Headers responseHeaders = new Headers();
        final String method; final URI uri; InputStream requestBody; final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        final Map<String, Object> attributes = new HashMap<>(); int status; long responseLength;
        Exchange(String method, String path, byte[] body) { this.method = method; this.uri = URI.create(path); this.requestBody = new ByteArrayInputStream(body); }
        String response() { return new String(responseBody.toByteArray(), StandardCharsets.UTF_8); }
        JsonObject json() { return JsonParser.parseString(response()).getAsJsonObject(); }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return requestBody; }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int status, long length) { this.status = status; this.responseLength = length; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 40000); }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8081); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
        @Override public void setStreams(InputStream input, OutputStream output) { requestBody = input; }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}

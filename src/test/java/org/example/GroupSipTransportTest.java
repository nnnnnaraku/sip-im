package org.example;

import org.junit.Before;
import org.junit.Test;

import javax.sip.*;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.*;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Uses request/header factories and proxy transactions only: no SipStack, sockets or server. */
public class GroupSipTransportTest {
    private SipVideoCallClient client;
    private AddressFactory addresses;
    private HeaderFactory headers;
    private MessageFactory messages;
    private final List<Request> sent = new ArrayList<>();
    private final List<Tx> transactions = new ArrayList<>();
    private RecordingUi ui;
    private boolean failSend;
    private String switchOnAddress;

    @Before public void prepare() throws Exception {
        SipFactory factory = SipFactory.getInstance();
        factory.setPathName("gov.nist");
        addresses = factory.createAddressFactory();
        headers = factory.createHeaderFactory();
        messages = factory.createMessageFactory();
        client = new SipVideoCallClient();
        ui = new RecordingUi();
        client.setUi(ui);
        set("username", "100");
        set("serverIp", "example.invalid");
        set("serverPort", 5060);
        set("localIp", "127.0.0.1");
        set("localPort", 5061);
        set("addressFactory", proxy(AddressFactory.class, (p, m, a) -> {
            if ("createAddress".equals(m.getName()) && a != null && a.length == 1
                    && a[0].equals(switchOnAddress)) client.setTargetUser("103");
            try { return m.invoke(addresses, a); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        }));
        set("headerFactory", headers);
        set("messageFactory", messages);
        set("sipProvider", proxy(SipProvider.class, (p, m, a) -> {
            if ("getNewCallId".equals(m.getName())) return headers.createCallIdHeader("offline-" + sent.size());
            if ("getNewClientTransaction".equals(m.getName())) {
                Request request = (Request) a[0];
                sent.add(request);
                Tx tx = new Tx(request);
                transactions.add(tx);
                return tx.proxy;
            }
            return defaultValue(m.getReturnType());
        }));
    }

    @Test public void explicitRecipientDoesNotFollowSelectedContact() throws Exception {
        client.setTargetUser("101");
        switchOnAddress = "sip:102@example.invalid";
        client.sendGroupEnvelope("102", "{\"text\":\"中文 <>&\\\"\"}", (ok, detail) -> { });
        Request request = sent.get(0);
        assertEquals("102", ((SipURI) request.getRequestURI()).getUser());
        assertEquals("102", ((SipURI) ((ToHeader) request.getHeader(ToHeader.NAME)).getAddress().getURI()).getUser());
        assertEquals("103", client.getTargetUser());
        assertEquals(0, ui.messages);
    }

    @Test public void groupMimeFromAndUtf8AreCorrect() throws Exception {
        String envelope = "{\"text\":\"中文 English 😀 & <tag> \\\"\"}";
        client.sendGroupEnvelope("101", envelope, (ok, detail) -> { });
        Request request = sent.get(0);
        assertTrue(SipVideoCallClient.isGroupMessage(request));
        ContentTypeHeader type = (ContentTypeHeader) request.getHeader(ContentTypeHeader.NAME);
        assertEquals(GroupProtocol.MEDIA_TYPE, type.getContentType() + "/" + type.getContentSubType());
        AcceptHeader accepted = (AcceptHeader) request.getHeader(AcceptHeader.NAME);
        assertEquals(GroupProtocol.MEDIA_TYPE, accepted.getContentType() + "/" + accepted.getContentSubType());
        assertEquals("100", ((SipURI) ((FromHeader) request.getHeader(FromHeader.NAME)).getAddress().getURI()).getUser());
        assertArrayEquals(envelope.getBytes(StandardCharsets.UTF_8), request.getRawContent());
        assertEquals(5060, ((SipURI) request.getRequestURI()).getPort());
    }

    @Test public void successIsSipAcceptanceAndCompletesOnlyOnce() throws Exception {
        List<String> results = new ArrayList<>();
        client.sendGroupEnvelope("101", "{}", (ok, detail) -> results.add(ok + ":" + detail));
        respond(0, 180);
        assertTrue(results.isEmpty());
        respond(0, 200);
        respond(0, 200);
        timeout(0);
        assertEquals(1, results.size());
        assertEquals("true:SIP_ACCEPTED_200", results.get(0));
    }

    @Test public void nonSuccessAndAuthenticationResponsesAreExplicitFailures() throws Exception {
        for (int status : new int[] { 400, 401, 403, 407, 480, 500 }) {
            final List<String> results = new ArrayList<>();
            client.sendGroupEnvelope("101", "{}", (ok, detail) -> results.add(ok + ":" + detail));
            int index = transactions.size() - 1;
            respond(index, status);
            timeout(index);
            assertEquals(1, results.size());
            assertEquals("false:SIP_REJECTED_" + status, results.get(0));
        }
    }

    @Test public void timeoutCannotLaterTurnIntoAcceptance() throws Exception {
        List<String> results = new ArrayList<>();
        client.sendGroupEnvelope("101", "{}", (ok, detail) -> results.add(ok + ":" + detail));
        timeout(0);
        timeout(0);
        respond(0, 200);
        assertEquals(1, results.size());
        assertEquals("false:SIP_TIMEOUT", results.get(0));
    }

    @Test public void retransmissionNotificationDoesNotFinalizeMessage() throws Exception {
        List<String> results = new ArrayList<>();
        client.sendGroupEnvelope("101", "{}", (ok, detail) -> results.add(ok + ":" + detail));
        client.processTimeout(new TimeoutEvent(this, transactions.get(0).proxy, Timeout.RETRANSMIT));
        assertTrue(results.isEmpty());
        respond(0, 200);
        assertEquals("true:SIP_ACCEPTED_200", results.get(0));
    }

    @Test public void localSendFailureIsReportedAndExceptionPreserved() throws Exception {
        failSend = true;
        List<String> results = new ArrayList<>();
        try {
            client.sendGroupEnvelope("101", "{}", (ok, detail) -> results.add(ok + ":" + detail));
            fail("send failure must propagate");
        } catch (SipException expected) {
            assertEquals("offline send failure", expected.getMessage());
        }
        timeout(0);
        assertEquals(1, results.size());
        assertEquals("false:LOCAL_SEND_ERROR", results.get(0));
    }

    @Test public void incomingGroupUsesSipFromAndDedicatedHandler() throws Exception {
        client.sendGroupEnvelope("101", "{}", (ok, detail) -> { });
        Request request = sent.get(0);
        ((ContentTypeHeader) request.getHeader(ContentTypeHeader.NAME)).setParameter("charset", "UTF-8");
        AtomicInteger groups = new AtomicInteger();
        client.setGroupMessageHandler((peer, body) -> {
            assertEquals("100", peer);
            assertEquals("{}", body);
            groups.incrementAndGet();
            return 200;
        });
        assertEquals(200, receive(request));
        assertEquals(1, groups.get());
        assertEquals(0, ui.messages);
    }

    @Test public void unavailableRejectedOrBrokenGroupHandlerNeverFallsBackToPrivateChat() throws Exception {
        client.sendGroupEnvelope("101", "#FILE|not-a-valid-group", (ok, detail) -> { });
        Request request = sent.get(0);
        assertEquals(503, receive(request));
        client.setGroupMessageHandler((peer, body) -> 403);
        assertEquals(403, receive(request));
        client.setGroupMessageHandler((peer, body) -> { throw new IllegalArgumentException("invalid"); });
        assertEquals(500, receive(request));
        client.setGroupMessageHandler((peer, body) -> null);
        assertEquals(500, receive(request));
        client.setGroupMessageHandler((peer, body) -> 100);
        assertEquals(500, receive(request));
        assertEquals(0, ui.messages);
        assertEquals(0, ui.files);
    }

    @Test public void malformedGroupUtf8IsRejectedBeforeHandlerOrPrivateCallback() throws Exception {
        AtomicInteger groups = new AtomicInteger();
        client.setGroupMessageHandler((peer, body) -> { groups.incrementAndGet(); return 200; });
        client.sendGroupEnvelope("101", "{}", (ok, detail) -> { });
        Request request = sent.get(0);
        request.setContent(new byte[] { (byte) 0xc3, 0x28 },
                (ContentTypeHeader) request.getHeader(ContentTypeHeader.NAME));
        assertEquals(400, receive(request));
        assertEquals(0, groups.get());
        assertEquals(0, ui.messages);
        assertEquals(0, ui.files);
    }

    @Test public void oversizedGroupBodyIsRejectedBeforeHandlerAndExactBoundaryIsAllowed() throws Exception {
        AtomicInteger groups = new AtomicInteger();
        client.setGroupMessageHandler((peer, body) -> { groups.incrementAndGet(); return 200; });
        client.sendGroupEnvelope("101", "{}", (ok, detail) -> { });
        Request request = sent.get(0);
        byte[] oversized = new byte[GroupProtocol.MAX_WIRE_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) 'x');
        request.setContent(oversized, (ContentTypeHeader) request.getHeader(ContentTypeHeader.NAME));
        assertEquals(413, receive(request));
        assertEquals(0, groups.get());
        assertEquals(0, ui.messages);
        byte[] boundary = new byte[GroupProtocol.MAX_WIRE_BYTES];
        java.util.Arrays.fill(boundary, (byte) 'x');
        request.setContent(boundary, (ContentTypeHeader) request.getHeader(ContentTypeHeader.NAME));
        assertEquals(200, receive(request));
        assertEquals(1, groups.get());
        assertEquals(0, ui.messages);
        assertEquals(0, ui.files);
    }

    @Test public void privateTextApiRetainsPlainMimeAndRecipientSnapshot() throws Exception {
        client.setTargetUser("101");
        switchOnAddress = "sip:101@example.invalid";
        client.sendMessage("private 中文");
        Request request = sent.get(0);
        assertFalse(SipVideoCallClient.isGroupMessage(request));
        assertEquals("101", ((SipURI) request.getRequestURI()).getUser());
        assertEquals("101", ui.peer);
        assertEquals("private 中文", ui.text);
        assertTrue(ui.outgoing);
        assertEquals(1, ui.messages);
        assertEquals("103", client.getTargetUser());
    }

    @Test public void plainJsonIsStillPrivateAndOldInvalidFileFrameIsNotGroupChat() throws Exception {
        AtomicInteger groups = new AtomicInteger();
        client.setGroupMessageHandler((peer, body) -> { groups.incrementAndGet(); return 200; });
        client.sendMessage("{\"text\":\"private\"}");
        assertEquals(200, receive(sent.get(0)));
        assertEquals(2, ui.messages);
        assertFalse(ui.outgoing);
        assertEquals(0, groups.get());
        client.sendMessage("#FILE|invalid");
        int messageCount = ui.messages;
        int status = receive(sent.get(1));
        assertTrue("malformed file frame must be rejected: " + status, status >= 400 && status <= 499);
        assertEquals(messageCount, ui.messages);
        assertEquals(0, groups.get());
    }

    @Test public void invalidRecipientCannotInjectSipUriOrHeaders() throws Exception {
        for (String recipient : new String[] { "", "101@example.invalid", "101\r\nX:evil", "sip:101", "101 102", "*6*1" }) {
            try {
                client.sendGroupEnvelope(recipient, "{}", (ok, detail) -> { });
                fail("invalid recipient accepted: " + recipient);
            } catch (IllegalArgumentException expected) { }
        }
        assertTrue(sent.isEmpty());
    }

    @Test public void independentMessagesUseIncreasingCseqAndReceipts() throws Exception {
        List<String> results = new ArrayList<>();
        client.sendGroupEnvelope("101", "{}", (ok, detail) -> results.add("101:" + ok));
        client.sendGroupEnvelope("102", "{}", (ok, detail) -> results.add("102:" + ok));
        assertTrue(((CSeqHeader) sent.get(1).getHeader(CSeqHeader.NAME)).getSeqNumber()
                > ((CSeqHeader) sent.get(0).getHeader(CSeqHeader.NAME)).getSeqNumber());
        respond(1, 200);
        respond(0, 480);
        assertEquals("102:true", results.get(0));
        assertEquals("101:false", results.get(1));
    }

    private int receive(Request request) throws Exception {
        final int[] status = { 0 };
        ServerTransaction tx = proxy(ServerTransaction.class, (p, m, a) -> {
            if ("sendResponse".equals(m.getName())) status[0] = ((Response) a[0]).getStatusCode();
            return defaultValue(m.getReturnType());
        });
        client.processRequest(new RequestEvent(this, tx, null, request));
        assertNotEquals("receiver must send a response", 0, status[0]);
        return status[0];
    }

    private void respond(int index, int status) throws Exception {
        Tx tx = transactions.get(index);
        Response response = messages.createResponse(status, tx.request);
        client.processResponse(new ResponseEvent(this, tx.proxy, null, response));
    }

    private void timeout(int index) {
        client.processTimeout(new TimeoutEvent(this, transactions.get(index).proxy, Timeout.TRANSACTION));
    }

    private void set(String name, Object value) throws Exception {
        Field field = SipVideoCallClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    private final class Tx implements InvocationHandler {
        private final Request request;
        private Object applicationData;
        private final ClientTransaction proxy;
        private Tx(Request request) {
            this.request = request;
            this.proxy = proxy(ClientTransaction.class, this);
        }
        @Override public Object invoke(Object p, Method method, Object[] args) throws Exception {
            String name = method.getName();
            if ("getRequest".equals(name)) return request;
            if ("getApplicationData".equals(name)) return applicationData;
            if ("setApplicationData".equals(name)) { applicationData = args[0]; return null; }
            if ("sendRequest".equals(name) && failSend) throw new SipException("offline send failure");
            return defaultValue(method.getReturnType());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == float.class) return 0F;
        return 0D;
    }

    private static final class RecordingUi implements SipVideoCallClient.Ui {
        int messages;
        int files;
        String peer;
        String text;
        boolean outgoing;
        @Override public void onMessage(String peer, String text, boolean outgoing) {
            messages++; this.peer = peer; this.text = text; this.outgoing = outgoing;
        }
        @Override public void onFile(String peer, String type, String name, String path, boolean outgoing) { files++; }
        @Override public void onCallState(String state, String peer) { }
        @Override public void onVideoFrame(byte[] jpeg, boolean local) { }
    }
}

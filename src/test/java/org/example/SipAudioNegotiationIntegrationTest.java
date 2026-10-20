package org.example;

import org.junit.Before;
import org.junit.Test;

import javax.sip.*;
import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;

import static org.junit.Assert.*;

/** SIP factories/parsers and recording interface proxies; no SIP stack or audio device. */
public class SipAudioNegotiationIntegrationTest {
    private MessageFactory messages;
    private HeaderFactory headers;
    private RecordingClient client;
    private final List<Response> replies = new ArrayList<>();
    private final List<String> order = new ArrayList<>();

    @Before public void setup() throws Exception {
        SipFactory factory = SipFactory.getInstance();
        factory.setPathName("gov.nist");
        messages = factory.createMessageFactory();
        headers = factory.createHeaderFactory();
        AddressFactory addresses = factory.createAddressFactory();
        client = new RecordingClient();
        set("messageFactory", messages);
        set("headerFactory", headers);
        set("addressFactory", addresses);
        set("localIp", "127.0.0.1");
        SipProvider provider = (SipProvider) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SipProvider.class}, (p, m, a) -> {
                    if ("getNewClientTransaction".equals(m.getName())) {
                        Request sent = (Request) a[0];
                        assertEquals(Request.BYE, sent.getMethod());
                        return Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{ClientTransaction.class}, (proxy, method, args) ->
                                        "getRequest".equals(method.getName()) ? sent : defaultValue(method.getReturnType()));
                    }
                    return defaultValue(m.getReturnType());
                });
        set("sipProvider", provider);
        client.setUi(new SipVideoCallClient.Ui() {
            public void onMessage(String p, String t, boolean o) { }
            public void onFile(String p, String t, String n, String f, boolean o) { }
            public void onVideoFrame(byte[] j, boolean l) { }
            public void onCallState(String s, String p) { if ("active".equals(s)) client.active++; }
        });
    }

    @Test public void offerContainsImplementedAudioAndPreservesVideo() throws Exception {
        set("videoCall", true);
        Method build = SipVideoCallClient.class.getDeclaredMethod("buildLocalSdp");
        build.setAccessible(true);
        String sdp = (String) build.invoke(client);
        assertTrue(sdp.contains("m=audio 8000 RTP/AVP 0 8\r\n"));
        assertTrue(sdp.contains("a=rtpmap:0 PCMU/8000"));
        assertTrue(sdp.contains("a=rtpmap:8 PCMA/8000"));
        assertFalse(sdp.contains("101"));
        assertTrue(sdp.contains("m=video 8020 RTP/AVP 26"));
    }

    @Test public void incomingPcmaOfferAnswersOnlyPcma() throws Exception {
        incoming(sdp("8 101"));
        assertEquals(Arrays.asList(100, 180, 200), statuses());
        String answer = new String(replies.get(2).getRawContent(), StandardCharsets.UTF_8);
        assertTrue(answer.contains("m=audio 8000 RTP/AVP 8\r\n"));
        assertFalse(answer.contains("PCMU"));
        assertFalse(answer.contains("101"));
        assertEquals(G711Codec.PCMA, client.audio.getCodec());
        assertEquals(8, client.audio.getPayloadType());
        assertEquals(1, client.starts);
        assertEquals(1, client.active);
        assertEquals(1, client.timers);
    }

    @Test public void incomingPcmuOfferStartsPcmu() throws Exception {
        incoming(sdp("0"));
        assertEquals(G711Codec.PCMU, client.audio.getCodec());
        assertEquals(0, client.audio.getPayloadType());
        assertEquals(1, client.starts);
    }

    @Test public void unsupportedIncomingOfferReturns488Without200OrMedia() throws Exception {
        incoming(sdp("111") + "a=rtpmap:111 opus/48000/2\r\n");
        assertEquals(Arrays.asList(100, 488), statuses());
        assertEquals(0, client.starts);
        assertEquals(0, client.active);
        assertEquals(0, client.timers);
    }

    @Test public void missingIncomingOfferReturns488WithoutMedia() throws Exception {
        incoming("");
        assertEquals(Arrays.asList(100, 488), statuses());
        assertEquals(0, client.starts);
    }

    @Test public void incomingDeviceFailureRejectsBefore200WithoutPrematureBye() throws Exception {
        client.startSucceeds = false;
        incoming(sdp("8"));
        assertEquals(Arrays.asList(100, 180, 480), statuses());
        assertEquals(1, client.starts);
        assertEquals(0, client.hangups);
        assertEquals(0, client.active);
        assertEquals(0, client.timers);
    }

    @Test public void conferenceAnswer8And101IsAckedThenStartsPcma() throws Exception {
        set("conferenceRoom", "1");
        outgoing(sdp("8 101") + "a=ptime:20\r\n");
        assertEquals(Arrays.asList("ACK", "MEDIA"), order);
        assertEquals(G711Codec.PCMA, client.audio.getCodec());
        assertEquals(8, client.audio.getPayloadType());
        assertFalse(client.audio.getReceiveCodecs().containsKey(101));
        assertEquals("1", get("conferenceRoom"));
        assertEquals(1, client.active);
    }

    @Test public void unsupportedAnswerIsAckedAndEndedWithoutOpeningAudio() throws Exception {
        outgoing(sdp("111") + "a=rtpmap:111 opus/48000/2\r\n");
        assertEquals(Arrays.asList("ACK", "HANGUP"), order);
        assertEquals(0, client.starts);
        assertEquals(1, client.hangups);
        assertEquals(0, client.active);
        assertEquals(0, client.timers);
    }

    @Test public void missingAnswerDoesNotFallBackToPcmu() throws Exception {
        outgoing("");
        assertEquals(Arrays.asList("ACK", "HANGUP"), order);
        assertEquals(0, client.starts);
    }

    @Test public void deviceFailureDoesNotReportActiveOrScheduleTimeout() throws Exception {
        client.startSucceeds = false;
        outgoing(sdp("0"));
        assertEquals(Arrays.asList("ACK", "MEDIA", "HANGUP"), order);
        assertEquals(0, client.active);
        assertEquals(0, client.timers);
    }

    @Test public void incompatibleAnswerActuallySendsByeAndClosesOldAudioSocket() throws Exception {
        assertRealByeCleanup(false);
    }

    @Test public void failedByeStillClosesOldAudioSocket() throws Exception {
        assertRealByeCleanup(true);
    }

    private void assertRealByeCleanup(boolean failBye) throws Exception {
        client.realHangup = true;
        client.failBye = failBye;
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            RTPAudioReceiver receiver = new RTPAudioReceiver(socket,
                    Collections.singletonMap(0, G711Codec.PCMU), pcm -> fail("No playback expected"));
            set("rtpReceiver", receiver);
            outgoing(sdp("111") + "a=rtpmap:111 opus/48000/2\r\n");
            assertEquals(Arrays.asList("ACK", "HANGUP", "BYE"), order);
            assertTrue(socket.isClosed());
            assertNull(get("rtpReceiver"));
            assertEquals(0, client.starts);
            assertEquals(0, client.active);
        }
    }

    private void incoming(String body) throws Exception {
        Request request = request(body);
        ServerTransaction tx = (ServerTransaction) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ServerTransaction.class}, (p, m, a) -> {
                    if ("sendResponse".equals(m.getName())) replies.add((Response) a[0]);
                    return defaultValue(m.getReturnType());
                });
        client.processRequest(new RequestEvent(this, tx, null, request));
    }

    private void outgoing(String body) throws Exception {
        Dialog dialog = (Dialog) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Dialog.class}, (p, m, a) -> {
                    if ("createAck".equals(m.getName())) {
                        Request ack = request("");
                        ack.setMethod(Request.ACK);
                        ack.setHeader(headers.createCSeqHeader((Long) a[0], Request.ACK));
                        return ack;
                    }
                    if ("sendAck".equals(m.getName())) {
                        assertEquals(Request.ACK, ((Request) a[0]).getMethod());
                        order.add("ACK");
                    }
                    if ("createRequest".equals(m.getName())) {
                        assertEquals(Request.BYE, a[0]);
                        Request bye = request("");
                        bye.setMethod(Request.BYE);
                        bye.setHeader(headers.createCSeqHeader(2L, Request.BYE));
                        return bye;
                    }
                    if ("sendRequest".equals(m.getName())) {
                        order.add("BYE");
                        assertEquals(Request.BYE, ((ClientTransaction) a[0]).getRequest().getMethod());
                        if (client.failBye) throw new SipException("Simulated transaction failure");
                    }
                    return defaultValue(m.getReturnType());
                });
        Response response = messages.createResponse(200, request(""));
        if (!body.isEmpty()) response.setContent(body.getBytes(StandardCharsets.UTF_8),
                headers.createContentTypeHeader("application", "sdp"));
        client.processResponse(new ResponseEvent(this, null, dialog, response));
    }

    private Request request(String body) throws Exception {
        String text = "INVITE sip:100@127.0.0.1 SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:5099;branch=z9hG4bK-test\r\n"
                + "From: <sip:101@127.0.0.1>;tag=test\r\n"
                + "To: <sip:100@127.0.0.1>\r\n"
                + "Call-ID: audio-unit-test@localhost\r\nCSeq: 1 INVITE\r\n"
                + "Max-Forwards: 70\r\nContact: <sip:101@127.0.0.1:5099>\r\n"
                + (body.isEmpty() ? "" : "Content-Type: application/sdp\r\n")
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
        return messages.createRequest(text);
    }

    private static String sdp(String formats) {
        return "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=test\r\n"
                + "c=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio 10008 RTP/AVP " + formats + "\r\n";
    }

    private List<Integer> statuses() {
        List<Integer> values = new ArrayList<>();
        for (Response r : replies) values.add(r.getStatusCode());
        return values;
    }

    private void set(String name, Object value) throws Exception {
        Field field = SipVideoCallClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    private Object get(String name) throws Exception {
        Field field = SipVideoCallClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(client);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        throw new AssertionError("Unexpected proxy method return type " + type);
    }

    private final class RecordingClient extends SipVideoCallClient {
        AudioSdp.NegotiatedAudio audio;
        int starts, hangups, active, timers;
        boolean startSucceeds = true, realHangup, failBye;
        @Override protected boolean startMedia(String sdp, AudioSdp.NegotiatedAudio audio) {
            order.add("MEDIA");
            starts++;
            this.audio = audio;
            return startSucceeds;
        }
        @Override protected void scheduleAutoHangup(String name) { timers++; }
        @Override public void hangup() throws Exception {
            hangups++;
            order.add("HANGUP");
            if (realHangup) super.hangup();
        }
    }
}

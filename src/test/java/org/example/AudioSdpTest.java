package org.example;

import org.junit.Test;

import java.util.Arrays;
import java.util.ArrayList;

import static org.junit.Assert.*;

public class AudioSdpTest {
    private static final String BASE = "v=0\r\nc=IN IP4 192.0.2.10\r\n";

    private static String sdp(String payloads, String attributes) {
        return BASE + "m=audio 10008 RTP/AVP " + payloads + "\r\n" + attributes;
    }

    @Test public void acceptsActualMiniSipServerPcmaAndDtmfAnswer() {
        String actual = "v=0\r\n"
                + "o=- 123456 654321 IN IP4 192.0.2.10\r\n"
                + "s=miniSIPServer\r\n"
                + "c=IN IP4 192.0.2.10\r\n"
                + "t=0 0\r\n"
                + "m=audio 10008 RTP/AVP 8 101\r\n"
                + "a=rtpmap:8 PCMA/8000\r\n"
                + "a=rtpmap:101 telephone-event/8000\r\n"
                + "a=fmtp:101 0-16\r\n";
        AudioSdp.NegotiatedAudio result = AudioSdp.negotiateAnswer(actual);
        assertEquals(G711Codec.PCMA, result.getCodec());
        assertEquals(8, result.getPayloadType());
        assertEquals("192.0.2.10", result.getRemoteAddress());
        assertEquals(10008, result.getRemotePort());
        assertEquals(1, result.getReceiveCodecs().size());
        assertEquals(G711Codec.PCMA, result.getReceiveCodecs().get(8));
        assertFalse(result.getReceiveCodecs().containsKey(101));
    }

    @Test public void supportsStaticPcmuWithoutRtpmap() {
        assertEquals(G711Codec.PCMU, AudioSdp.negotiateOffer(sdp("0", "")).getCodec());
        assertEquals(G711Codec.PCMU, AudioSdp.negotiateAnswer(sdp("0", "")).getCodec());
    }

    @Test public void supportsStaticPcmaWithoutRtpmap() {
        assertEquals(G711Codec.PCMA, AudioSdp.negotiateOffer(sdp("8", "")).getCodec());
        assertEquals(G711Codec.PCMA, AudioSdp.negotiateAnswer(sdp("8", "")).getCodec());
    }

    @Test public void usesMediaOrderRatherThanRtpmapOrder() {
        AudioSdp.NegotiatedAudio result = AudioSdp.negotiateOffer(sdp("8 0 101",
                "a=rtpmap:0 PCMU/8000\r\na=rtpmap:8 pcma/8000/1\r\n"));
        assertEquals(8, result.getPayloadType());
        assertEquals(Arrays.asList(8, 0), new ArrayList<>(result.getReceiveCodecs().keySet()));
        assertTrue(AudioSdp.answerMedia(8000, result).startsWith("m=audio 8000 RTP/AVP 8 0\r\n"));
    }

    @Test public void skipsUnsupportedFormatsBeforeChoosingFirstCommonCodec() {
        AudioSdp.NegotiatedAudio result = AudioSdp.negotiateAnswer(sdp("111 101 0 8",
                "a=rtpmap:111 opus/48000/2\r\na=rtpmap:101 telephone-event/8000\r\n"));
        assertEquals(0, result.getPayloadType());
        assertEquals(Arrays.asList(0, 8), new ArrayList<>(result.getReceiveCodecs().keySet()));
    }

    @Test public void inboundDynamicG711UsesTheOfferedPayloadNumbersInAnswer() {
        AudioSdp.NegotiatedAudio result = AudioSdp.negotiateOffer(sdp("110 111 0",
                "a=rtpmap:110 PCMA/8000\r\na=rtpmap:111 PCMU/8000/1\r\n"));
        assertEquals(G711Codec.PCMA, result.getCodec());
        assertEquals(110, result.getPayloadType());
        assertEquals(Arrays.asList(110, 111, 0), new ArrayList<>(result.getReceiveCodecs().keySet()));
        String answer = AudioSdp.answerMedia(8000, result);
        assertTrue(answer.contains("m=audio 8000 RTP/AVP 110 111 0\r\n"));
        assertTrue(answer.contains("a=rtpmap:110 PCMA/8000\r\n"));
        assertTrue(answer.contains("a=rtpmap:111 PCMU/8000\r\n"));
    }

    @Test public void outgoingAnswerCannotIntroduceDynamicG711EvenAlongsideStaticCodec() {
        rejectAnswer(sdp("8 110", "a=rtpmap:110 PCMU/8000\r\n"));
        rejectAnswer(sdp("110", "a=rtpmap:110 PCMA/8000\r\n"));
    }

    @Test public void rejectsNoCommonFormatsAndDoesNotTreatDtmfAsSpeech() {
        rejectOffer(sdp("101", "a=rtpmap:101 telephone-event/8000\r\n"));
        rejectOffer(sdp("101", "a=rtpmap:101 PCMA/8000\r\n"));
        rejectAnswer(sdp("101", "a=rtpmap:101 PCMA/8000\r\n"));
        rejectOffer(sdp("111", "a=rtpmap:111 opus/48000/2\r\n"));
        rejectOffer(sdp("96", ""));
        assertEquals(8, AudioSdp.negotiateAnswer(sdp("101 8",
                "a=rtpmap:101 PCMA/8000\r\n")).getPayloadType());
    }

    @Test public void staticPayloadCannotBeRedefinedByExplicitMapping() {
        rejectOffer(sdp("0", "a=rtpmap:0 PCMA/8000\r\n"));
        rejectAnswer(sdp("8", "a=rtpmap:8 PCMU/8000\r\n"));
        rejectOffer(sdp("8", "a=rtpmap:8 PCMA/16000\r\n"));
        rejectOffer(sdp("0", "a=rtpmap:0 PCMU/8000/2\r\n"));
        assertEquals(8, AudioSdp.negotiateOffer(sdp("0 8",
                "a=rtpmap:0 opus/48000/2\r\n")).getPayloadType());
    }

    @Test public void dynamicG711MustBeMonoAtEightKhzAndUseDynamicRange() {
        rejectOffer(sdp("110", "a=rtpmap:110 PCMA/8000/2\r\n"));
        rejectOffer(sdp("110", "a=rtpmap:110 PCMA/16000\r\n"));
        rejectOffer(sdp("9", "a=rtpmap:9 PCMA/8000\r\n"));
    }

    @Test public void rejectsConflictingOrMalformedMappings() {
        rejectOffer(sdp("8", "a=rtpmap:8 PCMA/8000\r\na=rtpmap:8 PCMU/8000\r\n"));
        rejectOffer(sdp("8", "a=rtpmap:8 PCMA\r\n"));
        rejectOffer(sdp("8", "a=rtpmap:8 PCMA/8000/0\r\n"));
    }

    @Test public void audioConnectionOverridesSessionAndNeverUsesVideoConnection() {
        String input = BASE + "m=video 8020 RTP/AVP 26\r\nc=IN IP4 203.0.113.99\r\n"
                + "m=audio 10008 RTP/AVP 8\r\nc=IN IP4 192.0.2.22\r\n"
                + "m=video 8022 RTP/AVP 26\r\nc=IN IP4 198.51.100.88\r\n";
        assertEquals("192.0.2.22", AudioSdp.negotiateOffer(input).getRemoteAddress());
        assertEquals("192.0.2.10", AudioSdp.negotiateOffer(sdp("8", "")
                + "m=video 8020 RTP/AVP 26\r\nc=IN IP4 203.0.113.99\r\n").getRemoteAddress());
    }

    @Test public void videoConnectionCannotFillInMissingAudioEndpoint() {
        rejectOffer("v=0\r\nm=audio 10008 RTP/AVP 8\r\nm=video 8020 RTP/AVP 26\r\n"
                + "c=IN IP4 192.0.2.10\r\n");
    }

    @Test public void audioMappingIsNotTakenFromVideoSection() {
        rejectOffer(sdp("110", "") + "m=video 8020 RTP/AVP 110\r\na=rtpmap:110 PCMA/8000\r\n");
    }

    @Test public void rejectsInvalidIpv4AndUnsupportedAddressFamilies() {
        for (String connection : Arrays.asList("IN IP6 ::1", "IN IP4 server.local", "IN IP4 256.1.2.3",
                "IN IP4 0.0.0.0", "IN IP4 224.1.2.3", "IN IP4 255.255.255.255",
                "IN IP4 192.0.2.1/127")) {
            rejectOffer("v=0\r\nc=" + connection + "\r\nm=audio 10008 RTP/AVP 8\r\n");
        }
    }

    @Test public void validatesRemoteAndLocalPorts() {
        for (String port : Arrays.asList("0", "-1", "65536", "abc", "8000/2", "8000/", "8000/1/1")) {
            rejectOffer(BASE + "m=audio " + port + " RTP/AVP 8\r\n");
        }
        assertEquals(65535, AudioSdp.negotiateOffer(BASE + "m=audio 65535/1 RTP/AVP 8\r\n").getRemotePort());
        for (int port : new int[]{0, -1, 65536}) {
            try {
                AudioSdp.offerMedia(port);
                fail("Invalid local port was accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void rejectsDisabledOneWayAndMultipleAudioStreams() {
        for (String direction : Arrays.asList("sendonly", "recvonly", "inactive")) {
            rejectOffer(sdp("8", "a=" + direction + "\r\n"));
            rejectAnswer("v=0\r\nc=IN IP4 192.0.2.1\r\na=" + direction
                    + "\r\nm=audio 10008 RTP/AVP 8\r\n");
        }
        rejectOffer(sdp("8", "") + "m=audio 10010 RTP/AVP 0\r\n");
        rejectOffer(sdp("8", "a=sendrecv\r\na=inactive\r\n"));
    }

    @Test public void audioDirectionOverridesSessionDirection() {
        String input = "v=0\r\nc=IN IP4 192.0.2.1\r\na=sendonly\r\n"
                + "m=audio 10008 RTP/AVP 8\r\na=sendrecv\r\n";
        assertEquals(8, AudioSdp.negotiateOffer(input).getPayloadType());
    }

    @Test public void rejectsEmptyMalformedUnsupportedAndDuplicateAudioDefinitions() {
        rejectOffer(null);
        rejectOffer(" ");
        rejectOffer(BASE + "m=video 8020 RTP/AVP 26\r\n");
        rejectOffer(BASE + "m=audio 10008 RTP/AVP\r\n");
        rejectOffer(BASE + "m=audio 10008 RTP/SAVP 8\r\n");
        rejectOffer(sdp("128", ""));
        rejectOffer(sdp("-1", ""));
        rejectOffer(sdp("8 8", ""));
        rejectOffer(sdp("8", "c=IN IP4 192.0.2.1\r\nc=IN IP4 192.0.2.2\r\n"));
    }

    @Test public void localOfferDeclaresOnlyImplementedCodecsAndRoundTrips() {
        String media = AudioSdp.offerMedia(8000);
        assertEquals("m=audio 8000 RTP/AVP 0 8\r\na=rtpmap:0 PCMU/8000\r\n"
                + "a=rtpmap:8 PCMA/8000\r\na=sendrecv\r\n", media);
        assertFalse(media.contains("101"));
        assertEquals(0, AudioSdp.negotiateOffer(BASE + media).getPayloadType());
        AudioSdp.NegotiatedAudio accepted = AudioSdp.negotiateOffer(sdp("8 101", ""));
        String answer = AudioSdp.answerMedia(8000, accepted);
        assertFalse(answer.contains("101"));
        assertEquals(G711Codec.PCMA, AudioSdp.negotiateAnswer(BASE + answer).getCodec());
    }

    @Test public void negotiationResultIsImmutableComparableAndSafeToPrint() {
        AudioSdp.NegotiatedAudio first = AudioSdp.negotiateOffer(sdp("8 0", ""));
        AudioSdp.NegotiatedAudio same = AudioSdp.negotiateOffer(sdp("8 0", ""));
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, AudioSdp.negotiateOffer(sdp("0 8", "")));
        assertNotEquals(first, AudioSdp.negotiateOffer(sdp("8 0", "").replace("10008", "10010")));
        assertNotEquals(first, AudioSdp.negotiateOffer(sdp("8 0", "").replace("192.0.2.10", "192.0.2.5")));
        try {
            first.getReceiveCodecs().put(101, G711Codec.PCMA);
            fail("Negotiated receive map must be immutable");
        } catch (UnsupportedOperationException expected) { }
        assertTrue(first.toString().contains("PCMA"));
        assertTrue(first.toString().contains("PT=8"));
        assertTrue(first.toString().contains("192.0.2.10:10008"));
    }

    private static void rejectOffer(String sdp) {
        try {
            AudioSdp.negotiateOffer(sdp);
            fail("Unsupported SDP offer was accepted");
        } catch (IllegalArgumentException expected) { }
    }

    private static void rejectAnswer(String sdp) {
        try {
            AudioSdp.negotiateAnswer(sdp);
            fail("Unsupported SDP answer was accepted");
        } catch (IllegalArgumentException expected) { }
    }
}

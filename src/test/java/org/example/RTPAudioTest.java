package org.example;

import org.junit.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Only memory and explicitly loopback-bound UDP sockets; never opens an audio device. */
public class RTPAudioTest {
    private static final InetAddress LOOPBACK;
    static {
        try { LOOPBACK = InetAddress.getByName("127.0.0.1"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @Test
    public void shortPcmReadsAccumulateWithoutEncodingStaleBufferTail() throws Exception {
        try (DatagramSocket source = localSocket(); DatagramSocket destination = localSocket()) {
            destination.setSoTimeout(1000);
            RTPAudioSender sender = new RTPAudioSender(source, G711Codec.PCMA, 8);
            sender.setRemote("127.0.0.1", destination.getLocalPort());
            byte[] captureBuffer = new byte[512];
            Arrays.fill(captureBuffer, (byte) 0x7f);
            Arrays.fill(captureBuffer, 0, 160, (byte) 0);
            sender.sendAudio(captureBuffer, 160);
            assertEquals(0, sender.getPacketsSent());
            sender.sendAudio(captureBuffer, 160);
            byte[] first = receive(destination);
            assertEquals(172, first.length);
            assertEquals(8, first[1] & 0x7f);
            assertEquals(0, readSequence(first));
            assertEquals(0, readTimestamp(first));
            for (int i = 12; i < first.length; i++) assertEquals(0xd5, first[i] & 0xff);

            sender.sendAudio(new byte[640], 640);
            byte[] second = receive(destination), third = receive(destination);
            assertEquals(1, readSequence(second));
            assertEquals(2, readSequence(third));
            assertEquals(160, readTimestamp(second));
            assertEquals(320, readTimestamp(third));
            assertArrayEquals(Arrays.copyOfRange(first, 8, 12), Arrays.copyOfRange(third, 8, 12));
            assertEquals(3, sender.getPacketsSent());
            sender.close();
            assertFalse("The receiver owns a shared socket", source.isClosed());
        }
    }

    @Test
    public void offsetInputAndDefaultConstructorUsePcmuPayloadZero() throws Exception {
        try (DatagramSocket source = localSocket(); DatagramSocket destination = localSocket()) {
            destination.setSoTimeout(1000);
            RTPAudioSender sender = new RTPAudioSender(source);
            sender.setRemote("127.0.0.1", destination.getLocalPort());
            byte[] input = new byte[324];
            input[0] = input[1] = input[322] = input[323] = 100;
            sender.sendAudio(input, 2, 320);
            byte[] packet = receive(destination);
            assertEquals(0, packet[1] & 0x7f);
            for (int i = 12; i < packet.length; i++) assertEquals(0xff, packet[i] & 0xff);
        }
    }

    @Test
    public void packetHeaderWrapsSequenceAndTimestampAtWireWidths() {
        byte[] before = RTPAudioSender.packetize(new byte[]{(byte) 0xff}, 0, 65535, 0xfffffff0L, 0x12345678);
        byte[] after = RTPAudioSender.packetize(new byte[]{(byte) 0xff}, 0, 65536, 0x100000090L, 0x12345678);
        assertEquals(0x80, before[0] & 0xff);
        assertEquals(65535, readSequence(before));
        assertEquals(0xfffffff0L, readTimestamp(before));
        assertEquals(0, readSequence(after));
        assertEquals(144, readTimestamp(after));
        assertEquals(0x12345678, ByteBuffer.wrap(after).getInt(8));
    }

    @Test
    public void invalidPcmAndUnconfiguredDestinationFailExplicitly() throws Exception {
        try (DatagramSocket source = localSocket()) {
            RTPAudioSender sender = new RTPAudioSender(source);
            assertThrows(IllegalArgumentException.class, () -> sender.sendAudio(null, 0));
            assertThrows(IllegalArgumentException.class, () -> sender.sendAudio(new byte[320], 321));
            assertThrows(IllegalArgumentException.class, () -> sender.sendAudio(new byte[320], 319));
            assertThrows(IllegalArgumentException.class, () -> sender.sendAudio(new byte[320], -2, 2));
            assertThrows(IllegalArgumentException.class, () -> sender.sendAudio(new byte[320], 1, 2));
            assertThrows(IllegalStateException.class, () -> sender.sendAudio(new byte[320], 320));
            assertThrows(IllegalArgumentException.class, () -> sender.setRemote("127.0.0.1", 0));
            assertThrows(IllegalArgumentException.class, () -> new RTPAudioSender(source, G711Codec.PCMA, 0));
            assertThrows(IllegalArgumentException.class, () -> new RTPAudioSender(source, G711Codec.PCMU, 101));
            sender.close();
            sender.close();
            assertThrows(IllegalStateException.class, () -> sender.sendAudio(new byte[320], 320));
        }
    }

    @Test
    public void failedUdpSendIsCountedAndExposedInsteadOfReportedAsSuccess() throws Exception {
        try (DatagramSocket source = new DatagramSocket(0, LOOPBACK) {
            @Override public void send(DatagramPacket packet) throws IOException {
                throw new IOException("Deliberate local test failure");
            }
        }) {
            RTPAudioSender sender = new RTPAudioSender(source);
            sender.setRemote("127.0.0.1", 50000);
            sender.sendAudio(new byte[640], 640);
            assertEquals(0, sender.getPacketsSent());
            assertEquals(2, sender.getSendFailures());
            assertNotNull(sender.getLastSendFailure());
            assertEquals("Deliberate local test failure", sender.getLastSendFailure().getMessage());
        }
    }

    @Test
    public void parserHonorsPayloadMapMarkerCsrcExtensionPaddingAndPacketOffset() {
        byte[] packet = extendedSilencePacket();
        byte[] wrapped = new byte[packet.length + 9];
        System.arraycopy(packet, 0, wrapped, 5, packet.length);
        RTPAudioReceiver.DecodedAudio audio = RTPAudioReceiver.decodePacket(wrapped, 5, packet.length,
                Collections.singletonMap(8, G711Codec.PCMA));
        assertEquals(8, audio.payloadType);
        assertArrayEquals(silencePcm(G711Codec.PCMA), audio.pcm);
        assertTrue("Exercise reception beyond the old 512-byte buffer", packet.length > 512);
    }

    @Test
    public void unknownAndDtmfPayloadsNeverFallBackToPcmu() {
        Map<Integer, G711Codec> pcmu = Collections.singletonMap(0, G711Codec.PCMU);
        byte[] wrongCodec = silencePacket(G711Codec.PCMA);
        assertNull(RTPAudioReceiver.decodePacket(wrongCodec, 0, wrongCodec.length, pcmu));
        byte[] unknown = RTPAudioSender.packetize(new byte[]{1, 2, 3, 4}, 96, 0, 0, 1);
        byte[] dtmf = RTPAudioSender.packetize(new byte[]{1, 2, 3, 4}, 101, 0, 0, 1);
        assertNull(RTPAudioReceiver.decodePacket(unknown, 0, unknown.length, pcmu));
        assertNull(RTPAudioReceiver.decodePacket(dtmf, 0, dtmf.length, pcmu));
        // Even a mistakenly supplied test map cannot turn the reserved telephone-event PT into audio.
        assertNull(RTPAudioReceiver.decodePacket(dtmf, 0, dtmf.length,
                Collections.singletonMap(101, G711Codec.PCMU)));
    }

    @Test
    public void parserUsesTheExplicitCodecMappingForEachSupportedPayload() {
        Map<Integer, G711Codec> both = new HashMap<>();
        both.put(0, G711Codec.PCMU);
        both.put(8, G711Codec.PCMA);
        for (G711Codec codec : G711Codec.values()) {
            byte[] packet = silencePacket(codec);
            RTPAudioReceiver.DecodedAudio decoded = RTPAudioReceiver.decodePacket(packet, 0, packet.length, both);
            assertArrayEquals(silencePcm(codec), decoded.pcm);
        }
        byte[] dynamic = silencePacket(G711Codec.PCMA);
        dynamic[1] = 96;
        assertNull(RTPAudioReceiver.decodePacket(dynamic, 0, dynamic.length, both));
        RTPAudioReceiver.DecodedAudio decoded = RTPAudioReceiver.decodePacket(dynamic, 0, dynamic.length,
                Collections.singletonMap(96, G711Codec.PCMA));
        assertEquals(96, decoded.payloadType);
        assertArrayEquals(silencePcm(G711Codec.PCMA), decoded.pcm);
    }

    @Test
    public void parserRejectsMalformedRtpRatherThanReadingOutsideBounds() {
        Map<Integer, G711Codec> map = Collections.singletonMap(0, G711Codec.PCMU);
        assertMalformed(new byte[11], map);
        byte[] wrongVersion = silencePacket(G711Codec.PCMU);
        wrongVersion[0] = 0x40;
        assertMalformed(wrongVersion, map);
        byte[] csrc = new byte[13]; csrc[0] = (byte) 0x8f;
        assertMalformed(csrc, map);
        byte[] extensionHeader = new byte[13]; extensionHeader[0] = (byte) 0x90;
        assertMalformed(extensionHeader, map);
        byte[] extensionBody = new byte[17]; extensionBody[0] = (byte) 0x90;
        extensionBody[14] = extensionBody[15] = (byte) 0xff;
        assertMalformed(extensionBody, map);
        byte[] zeroPadding = silencePacket(G711Codec.PCMU);
        zeroPadding[0] |= 0x20; zeroPadding[zeroPadding.length - 1] = 0;
        assertMalformed(zeroPadding, map);
        byte[] excessivePadding = new byte[14]; excessivePadding[0] = (byte) 0xa0;
        excessivePadding[13] = 3;
        assertMalformed(excessivePadding, map);
        byte[] allPadding = new byte[16]; allPadding[0] = (byte) 0xa0; allPadding[15] = 4;
        assertMalformed(allPadding, map);
        assertThrows(IllegalArgumentException.class,
                () -> RTPAudioReceiver.decodePacket(new byte[12], 1, 12, map));
        assertThrows(IllegalArgumentException.class,
                () -> RTPAudioReceiver.decodePacket(null, 0, 12, map));
        assertThrows(IllegalArgumentException.class,
                () -> RTPAudioReceiver.decodePacket(new byte[65508], 0, 65508, map));
    }

    @Test
    public void loopbackReceiverIgnoresUnknownDtmfAndMalformedBeforePlayingNegotiatedAudio() throws Exception {
        LinkedBlockingQueue<byte[]> played = new LinkedBlockingQueue<>();
        try (DatagramSocket destination = localSocket(); DatagramSocket source = localSocket()) {
            Map<Integer, G711Codec> codecs = new HashMap<>(); codecs.put(8, G711Codec.PCMA);
            RTPAudioReceiver receiver = new RTPAudioReceiver(destination, codecs, played::add);
            // Mutation of the caller's map must not change the negotiated receiver mapping.
            codecs.put(0, G711Codec.PCMU);
            try {
                receiver.start();
                receiver.start();
                send(source, destination, RTPAudioSender.packetize(new byte[]{1, 2, 3, 4}, 101, 0, 0, 1));
                send(source, destination, silencePacket(G711Codec.PCMU));
                send(source, destination, new byte[]{0, 1, 2});
                send(source, destination, extendedSilencePacket());
                assertArrayEquals(silencePcm(G711Codec.PCMA), played.poll(2, TimeUnit.SECONDS));
                assertEquals(1, receiver.getPacketsReceived());
                assertEquals(2, receiver.getIgnoredPackets());
                assertEquals(1, receiver.getMalformedPackets());
                assertEquals(0, receiver.getReceiveFailures());
                assertTrue(played.isEmpty());
            } finally {
                receiver.stop();
                receiver.stop();
            }
            assertTrue(destination.isClosed());
            assertThrows(IllegalStateException.class, receiver::start);
        }
    }

    @Test
    public void invalidNegotiationIsRejectedWithoutOpeningAnyAudioDevice() throws Exception {
        try (DatagramSocket socket = localSocket()) {
            assertThrows(IllegalArgumentException.class, () -> new RTPAudioReceiver(socket,
                    Collections.singletonMap(101, G711Codec.PCMU), pcm -> fail("Must never play")));
            assertThrows(IllegalArgumentException.class, () -> new RTPAudioReceiver(socket,
                    Collections.singletonMap(8, G711Codec.PCMU), pcm -> fail("Must never play")));
            assertThrows(IllegalArgumentException.class, () -> new RTPAudioReceiver(socket,
                    Collections.emptyMap(), pcm -> fail("Must never play")));
        }
    }

    private static void assertMalformed(byte[] packet, Map<Integer, G711Codec> map) {
        assertThrows(IllegalArgumentException.class,
                () -> RTPAudioReceiver.decodePacket(packet, 0, packet.length, map));
    }

    private static DatagramSocket localSocket() throws Exception { return new DatagramSocket(0, LOOPBACK); }

    private static void send(DatagramSocket source, DatagramSocket destination, byte[] bytes) throws Exception {
        source.send(new DatagramPacket(bytes, bytes.length, LOOPBACK, destination.getLocalPort()));
    }

    private static byte[] receive(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
        socket.receive(packet);
        return Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
    }

    private static int readSequence(byte[] packet) { return ByteBuffer.wrap(packet).getShort(2) & 0xffff; }
    private static long readTimestamp(byte[] packet) { return ByteBuffer.wrap(packet).getInt(4) & 0xffffffffL; }

    private static byte[] silencePacket(G711Codec codec) {
        byte[] encoded = new byte[160];
        Arrays.fill(encoded, codec == G711Codec.PCMU ? (byte) 0xff : (byte) 0xd5);
        return RTPAudioSender.packetize(encoded, codec.getPayloadType(), 10, 1000, 42);
    }

    private static byte[] silencePcm(G711Codec codec) {
        byte[] pcm = new byte[320];
        if (codec == G711Codec.PCMA) for (int i = 0; i < pcm.length; i += 2) pcm[i] = 8;
        return pcm;
    }

    private static byte[] extendedSilencePacket() {
        int header = 12 + 8 + 4 + 512;
        byte[] packet = new byte[header + 160 + 4];
        packet[0] = (byte) 0xb2; // V2, padding, extension, two CSRC entries.
        packet[1] = (byte) 0x88; // Marker bit does not change PT 8.
        packet[20] = (byte) 0xbe; packet[21] = (byte) 0xde;
        packet[22] = 0; packet[23] = (byte) 128; // Extension length in 32-bit words.
        Arrays.fill(packet, header, header + 160, (byte) 0xd5);
        packet[packet.length - 1] = 4;
        return packet;
    }
}

package org.example;

import org.junit.Test;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real loopback UDP, negotiated SDP and codecs; fixed reference PCM, no audio hardware. */
public class AudioNegotiationRtpIntegrationTest {
    @Test public void miniSipServerPcmaAnswerUsesPcmaEndToEnd() throws Exception {
        check("8 101", G711Codec.PCMA, 1008);
    }

    @Test public void pcmuOnlyPeerUsesPcmuEndToEnd() throws Exception {
        check("0", G711Codec.PCMU, 988);
    }

    private void check(String payloads, G711Codec codec, int expectedSample) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket destination = new DatagramSocket(0, loopback);
             DatagramSocket source = new DatagramSocket(0, loopback)) {
            String sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=mss\r\n"
                    + "c=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio "
                    + destination.getLocalPort() + " RTP/AVP " + payloads + "\r\na=ptime:20\r\n";
            AudioSdp.NegotiatedAudio selected = AudioSdp.negotiateAnswer(sdp);
            assertEquals(codec, selected.getCodec());
            assertEquals(codec.getPayloadType(), selected.getPayloadType());
            BlockingQueue<byte[]> played = new ArrayBlockingQueue<>(1);
            RTPAudioReceiver receiver = new RTPAudioReceiver(destination, selected.getReceiveCodecs(),
                    pcm -> assertTrue(played.offer(pcm)));
            RTPAudioSender sender = new RTPAudioSender(source, selected.getCodec(), selected.getPayloadType());
            try {
                receiver.start();
                sender.setRemote(selected.getRemoteAddress(), selected.getRemotePort());
                byte[] input = new byte[320];
                for (int i = 0; i < 160; i++) {
                    input[2 * i] = (byte) 1000;
                    input[2 * i + 1] = (byte) (1000 >> 8);
                }
                sender.sendAudio(input, 160);
                assertEquals(0, sender.getPacketsSent());
                sender.sendAudio(input, 160, 160);
                byte[] pcm = played.poll(2, TimeUnit.SECONDS);
                assertNotNull("Negotiated RTP was not decoded", pcm);
                assertEquals(320, pcm.length);
                for (int i = 0; i < 160; i++) {
                    int sample = (short) ((pcm[2 * i] & 255) | (pcm[2 * i + 1] << 8));
                    assertEquals(expectedSample, sample);
                }
                assertEquals(1, sender.getPacketsSent());
                assertEquals(0, sender.getSendFailures());
                assertEquals(1, receiver.getPacketsReceived());
                assertEquals(0, receiver.getReceiveFailures());
            } finally {
                sender.close();
                receiver.stop();
            }
        }
    }
}

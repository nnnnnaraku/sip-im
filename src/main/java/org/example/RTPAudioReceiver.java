package org.example;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Receives only negotiated G.711 RTP; unknown/DTMF payloads are never played as audio. */
public class RTPAudioReceiver {
    private final DatagramSocket socket;
    private final Map<Integer, G711Codec> codecs;
    private SourceDataLine speaker;
    private PcmSink sink;
    private volatile boolean running;
    private boolean stopped;
    private Thread receiveThread;
    private volatile long packetsReceived;
    private volatile long ignoredPackets;
    private volatile long malformedPackets;
    private volatile long receiveFailures;
    private volatile int recvLevel;

    interface PcmSink { void play(byte[] pcm); }

    /** Compatibility default: PT 0 is PCMU; other PTs are ignored. */
    public RTPAudioReceiver(int localPort) throws Exception {
        this(localPort, Collections.singletonMap(0, G711Codec.PCMU));
    }

    public RTPAudioReceiver(int localPort, Map<Integer, G711Codec> negotiatedCodecs) throws Exception {
        codecs = checkedCodecs(negotiatedCodecs);
        socket = new DatagramSocket(localPort);
        SourceDataLine opened = null;
        try {
            socket.setSoTimeout(1000);
            AudioFormat format = new AudioFormat(8000.0f, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            opened = (SourceDataLine) AudioSystem.getLine(info);
            opened.open(format, 3200);
            opened.start();
            speaker = opened;
            sink = pcm -> {
                if (running) speaker.write(pcm, 0, pcm.length);
            };
        } catch (Exception failure) {
            socket.close();
            if (opened != null) {
                try { opened.close(); } catch (Exception ignored) { }
            }
            throw failure;
        }
        System.out.println("RTP receiver: local UDP " + socket.getLocalPort()
                + ", negotiated PTs=" + codecs.keySet());
    }

    /** Device-free local test seam. The receiver owns this socket once constructed. */
    RTPAudioReceiver(DatagramSocket socket, Map<Integer, G711Codec> negotiatedCodecs,
                     PcmSink sink) throws SocketException {
        if (socket == null || socket.isClosed() || sink == null) {
            throw new IllegalArgumentException("An open RTP socket and PCM sink are required");
        }
        this.codecs = checkedCodecs(negotiatedCodecs);
        this.socket = socket;
        this.sink = sink;
        socket.setSoTimeout(1000);
    }

    private static Map<Integer, G711Codec> checkedCodecs(Map<Integer, G711Codec> codecs) {
        if (codecs == null || codecs.isEmpty()) {
            throw new IllegalArgumentException("At least one negotiated G.711 payload is required");
        }
        Map<Integer, G711Codec> copy = new HashMap<>();
        for (Map.Entry<Integer, G711Codec> entry : codecs.entrySet()) {
            Integer pt = entry.getKey();
            if (pt == null || pt < 0 || pt > 127 || pt == 101 || entry.getValue() == null) {
                throw new IllegalArgumentException("Invalid negotiated RTP audio payload mapping");
            }
            if ((pt == 0 && entry.getValue() != G711Codec.PCMU)
                    || (pt == 8 && entry.getValue() != G711Codec.PCMA)
                    || (pt != 0 && pt != 8 && pt < 96)) {
                throw new IllegalArgumentException("The codec does not match the static RTP payload type");
            }
            copy.put(pt, entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    public DatagramSocket getSocket() { return socket; }
    /** Decoded negotiated audio packets; excludes DTMF, unknown and malformed RTP. */
    public long getPacketsReceived() { return packetsReceived; }
    public long getIgnoredPackets() { return ignoredPackets; }
    public long getMalformedPackets() { return malformedPackets; }
    public long getReceiveFailures() { return receiveFailures; }
    public int getRecvLevel() { return recvLevel; }

    public synchronized void start() {
        if (stopped) throw new IllegalStateException("RTP receiver is stopped");
        if (running) return;
        running = true;
        receiveThread = new Thread(this::receiveLoop, "RTPReceiver");
        receiveThread.start();
    }

    private void receiveLoop() {
        // Avoid silent truncation when packets include long extensions or padding.
        byte[] buffer = new byte[65535];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                DecodedAudio decoded;
                try {
                    decoded = decodePacket(packet.getData(), packet.getOffset(), packet.getLength(), codecs);
                } catch (IllegalArgumentException malformed) {
                    limitedWarning("Malformed RTP ignored", ++malformedPackets, malformed.getMessage());
                    continue;
                }
                if (decoded == null) {
                    int pt = packet.getData()[packet.getOffset() + 1] & 0x7f;
                    limitedWarning("Unnegotiated RTP ignored", ++ignoredPackets, "PT=" + pt);
                    continue;
                }
                packetsReceived++;
                if (packetsReceived == 1) {
                    System.out.println("First negotiated RTP audio: " + packet.getAddress().getHostAddress()
                            + ":" + packet.getPort() + ", PT=" + decoded.payloadType);
                }
                updateLevel(decoded.pcm);
                if (running) sink.play(decoded.pcm);
            } catch (SocketTimeoutException timeout) {
                // An idle receiver rechecks the shutdown flag.
            } catch (Exception failure) {
                if (running) {
                    limitedWarning("RTP receive/playback failed", ++receiveFailures, failure.getMessage());
                    if (socket.isClosed()) break;
                }
            }
        }
    }

    private void updateLevel(byte[] pcm) {
        long sum = 0;
        int n = pcm.length / 2;
        for (int i = 0; i < pcm.length; i += 2) {
            int sample = (short) ((pcm[i + 1] << 8) | (pcm[i] & 0xff));
            sum += Math.abs(sample);
        }
        recvLevel = (int) (recvLevel * 0.85 + (n == 0 ? 0 : sum / n) * 0.15);
    }

    private static void limitedWarning(String label, long count, String detail) {
        if (count <= 3 || count % 100 == 0) {
            System.err.println(label + " (" + count + "): " + detail);
        }
    }

    /** Pure parser: malformed packets throw, well-formed unnegotiated PTs return null. */
    static DecodedAudio decodePacket(byte[] data, int offset, int length,
                                     Map<Integer, G711Codec> codecs) {
        if (data == null || offset < 0 || length < 12 || length > 65507
                || offset > data.length - length || codecs == null) {
            throw new IllegalArgumentException("RTP packet bounds/length are invalid");
        }
        int flags = data[offset] & 0xff;
        if ((flags >>> 6) != 2) throw new IllegalArgumentException("RTP version must be 2");
        int headerLength = 12 + (flags & 0x0f) * 4;
        if (length < headerLength) throw new IllegalArgumentException("Truncated RTP CSRC list");
        if ((flags & 0x10) != 0) {
            if (length - headerLength < 4) throw new IllegalArgumentException("Truncated RTP extension header");
            int extOffset = offset + headerLength;
            int words = ((data[extOffset + 2] & 0xff) << 8) | (data[extOffset + 3] & 0xff);
            headerLength += 4 + words * 4;
            if (headerLength > length) throw new IllegalArgumentException("Truncated RTP extension body");
        }
        int payloadEnd = length;
        if ((flags & 0x20) != 0) {
            int padding = data[offset + length - 1] & 0xff;
            if (padding == 0 || padding > length - headerLength) {
                throw new IllegalArgumentException("Invalid RTP padding");
            }
            payloadEnd -= padding;
        }
        if (payloadEnd <= headerLength) throw new IllegalArgumentException("RTP contains no payload");
        int payloadType = data[offset + 1] & 0x7f;
        if (payloadType == 101) return null;
        G711Codec codec = codecs.get(payloadType);
        if (codec == null) return null;
        byte[] encoded = Arrays.copyOfRange(data, offset + headerLength, offset + payloadEnd);
        return new DecodedAudio(payloadType, codec.decode(encoded));
    }

    static final class DecodedAudio {
        final int payloadType;
        final byte[] pcm;
        DecodedAudio(int payloadType, byte[] pcm) {
            this.payloadType = payloadType;
            this.pcm = pcm;
        }
    }

    /** No drain: a stalled device must not make hangup wait indefinitely. */
    public void stop() {
        synchronized (this) {
            if (stopped) return;
            stopped = true;
            running = false;
        }
        socket.close();
        SourceDataLine line = speaker;
        if (line != null) {
            try { line.stop(); } finally { line.close(); }
        }
        System.out.println("RTP receiver stopped: audio=" + packetsReceived + ", unknown="
                + ignoredPackets + ", malformed=" + malformedPackets + ", failures=" + receiveFailures);
    }
}

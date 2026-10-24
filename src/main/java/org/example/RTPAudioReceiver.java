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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    /** 本机发送流的 SSRC；收到同 SSRC 的包说明被服务器回环，必须丢弃。 */
    private volatile int localSsrc;
    private volatile long ignoredSelf;
    private volatile long duplicatePackets;
    /** 按 SSRC 统计来源，便于判断对端音频是否真的到达。 */
    private final Map<Integer, SourceStat> sources = new ConcurrentHashMap<>();
    private volatile long lastSourceReport;
    private volatile int sourceReports;

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
    public long getIgnoredSelf() { return ignoredSelf; }
    public long getDuplicatePackets() { return duplicatePackets; }
    /** 必须在发送器创建后调用；收到相同 SSRC 的包会被当作回环丢弃。 */
    public void setLocalSsrc(int ssrc) { this.localSsrc = ssrc; }

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
            reportSources();
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
                int off = packet.getOffset();
                int ssrc = ((packet.getData()[off + 8] & 0xFF) << 24)
                        | ((packet.getData()[off + 9] & 0xFF) << 16)
                        | ((packet.getData()[off + 10] & 0xFF) << 8)
                        | (packet.getData()[off + 11] & 0xFF);
                int seq = ((packet.getData()[off + 2] & 0xFF) << 8) | (packet.getData()[off + 3] & 0xFF);
                SourceStat stat = sources.computeIfAbsent(ssrc, k -> new SourceStat());
                if (localSsrc != 0 && ssrc == localSsrc) {
                    // 服务器把本机发出的流回环回来：绝不能播放，否则就是“自听回声”。
                    ignoredSelf++;
                    packetsReceived--;
                    continue;
                }
                if (stat.duplicate(seq)) {
                    duplicatePackets++;
                    packetsReceived--;
                    continue;
                }
                stat.packets.incrementAndGet();
                if (packetsReceived == 1) {
                    System.out.println("First negotiated RTP audio: " + packet.getAddress().getHostAddress()
                            + ":" + packet.getPort() + ", PT=" + decoded.payloadType);
                }
                updateLevel(decoded.pcm);
                if (running) sink.play(decoded.pcm);
            } catch (SocketTimeoutException timeout) {
                // An idle receiver rechecks the shutdown flag.
                reportSources();
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

    /**
     * 每个 SSRC 的序列号窗口：重复包直接丢弃。
     * RTP over UDP 可能重复送达，若不去重会表现为“复读”。
     */
    private static final class SourceStat {
        final AtomicLong packets = new AtomicLong();
        private long highest = -1;
        private long bitmap;

        synchronized boolean duplicate(int seq16) {
            long seq;
            if (highest < 0) {
                seq = seq16;
            } else {
                long base = highest & ~0xFFFFL;
                long candidate = base | (seq16 & 0xFFFF);
                if (candidate < highest - 32768) candidate += 65536;
                else if (candidate > highest + 32768) candidate -= 65536;
                seq = candidate;
            }
            if (seq <= highest - 64) return true;
            if (seq > highest) {
                long shift = seq - highest;
                bitmap = shift >= 64 ? 0 : (bitmap << shift);
                highest = seq;
                bitmap |= 1L;
                return false;
            }
            long mask = 1L << (highest - seq);
            if ((bitmap & mask) != 0) return true;
            bitmap |= mask;
            return false;
        }
    }

    /** 前 120 秒每 10 秒打印一次来源统计，用于判断对端音频是否真的到达。 */
    private void reportSources() {
        if (sources.isEmpty() || sourceReports >= 12) return;
        long now = System.currentTimeMillis();
        if (lastSourceReport != 0 && now - lastSourceReport < 10000) return;
        lastSourceReport = now;
        sourceReports++;
        StringBuilder line = new StringBuilder("[RTP 源统计] ");
        for (Map.Entry<Integer, SourceStat> entry : sources.entrySet()) {
            line.append("SSRC=").append(Integer.toHexString(entry.getKey()));
            if (entry.getKey() == localSsrc) line.append("(本机回环)");
            line.append(" 包数=").append(entry.getValue().packets.get()).append("；");
        }
        line.append("丢弃回环=").append(ignoredSelf).append("，重复=").append(duplicatePackets);
        System.out.println(line);
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

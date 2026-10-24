package org.example;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;

/** Packetizes 16-bit little-endian, mono, 8 kHz PCM into 20 ms G.711 RTP. */
public class RTPAudioSender {
    static final int PCM_FRAME_BYTES = 320;
    private static final int SAMPLES_PER_FRAME = 160;
    private final DatagramSocket socket;
    private final boolean ownsSocket;
    private final G711Codec codec;
    private final int payloadType;
    private final int ssrc = ThreadLocalRandom.current().nextInt();
    private final byte[] pendingPcm = new byte[PCM_FRAME_BYTES];
    private int pendingBytes;
    private int sequenceNumber;
    private long timestamp;
    private InetAddress remoteAddress;
    private int remotePort;
    private boolean closed;
    private volatile long packetsSent;
    private volatile long sendFailures;
    private volatile IOException lastSendFailure;
    private volatile int micLevel;

    /** Compatibility default: only PCMU, payload type 0. */
    public RTPAudioSender(DatagramSocket sharedSocket) {
        this(sharedSocket, G711Codec.PCMU, 0);
    }

    public RTPAudioSender(DatagramSocket sharedSocket, G711Codec codec, int payloadType) {
        this(sharedSocket, false, codec, payloadType);
    }

    public RTPAudioSender(int localPort) throws SocketException {
        this(new DatagramSocket(localPort), true, G711Codec.PCMU, 0);
    }

    private RTPAudioSender(DatagramSocket socket, boolean ownsSocket,
                           G711Codec codec, int payloadType) {
        if (socket == null || socket.isClosed()) {
            throw new IllegalArgumentException("An open RTP socket is required");
        }
        if (codec == null || payloadType < 0 || payloadType > 127 || payloadType == 101) {
            throw new IllegalArgumentException("A codec and an audio payload type are required");
        }
        if ((payloadType == 0 && codec != G711Codec.PCMU)
                || (payloadType == 8 && codec != G711Codec.PCMA)
                || (payloadType != 0 && payloadType != 8 && payloadType < 96)) {
            throw new IllegalArgumentException("The codec does not match the static RTP payload type");
        }
        this.socket = socket;
        this.ownsSocket = ownsSocket;
        this.codec = codec;
        this.payloadType = payloadType;
        System.out.println("RTP sender: local UDP " + socket.getLocalPort()
                + ", codec=" + codec.getRtpName() + ", PT=" + payloadType);
    }

    public synchronized void setRemote(String ip, int port) throws UnknownHostException {
        if (ip == null || ip.trim().isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("A destination address and valid UDP port are required");
        }
        if (closed) throw new IllegalStateException("RTP sender is closed");
        remoteAddress = InetAddress.getByName(ip);
        remotePort = port;
        System.out.println("RTP target: " + remoteAddress.getHostAddress() + ":" + port);
    }

    public void sendAudio(byte[] pcmData, int length) {
        sendAudio(pcmData, 0, length);
    }

    /** Short reads accumulate; only complete 20 ms frames become RTP packets. */
    public synchronized void sendAudio(byte[] pcmData, int offset, int length) {
        if (pcmData == null || offset < 0 || length < 0
                || offset > pcmData.length - length || (offset & 1) != 0 || (length & 1) != 0) {
            throw new IllegalArgumentException("PCM must contain complete 16-bit samples within bounds");
        }
        if (closed) throw new IllegalStateException("RTP sender is closed");
        if (remoteAddress == null) throw new IllegalStateException("RTP destination is not configured");
        int end = offset + length;
        while (offset < end) {
            int count = Math.min(PCM_FRAME_BYTES - pendingBytes, end - offset);
            System.arraycopy(pcmData, offset, pendingPcm, pendingBytes, count);
            pendingBytes += count;
            offset += count;
            if (pendingBytes == PCM_FRAME_BYTES) {
                try {
                    sendFrame();
                } finally {
                    pendingBytes = 0;
                }
            }
        }
    }

    private void sendFrame() {
        long sum = 0;
        for (int i = 0; i < PCM_FRAME_BYTES; i += 2) {
            int sample = (short) ((pendingPcm[i + 1] << 8) | (pendingPcm[i] & 0xff));
            sum += Math.abs(sample);
        }
        micLevel = (int) (micLevel * 0.85 + (sum / SAMPLES_PER_FRAME) * 0.15);
        byte[] encoded = codec.encode(pendingPcm, 0, pendingPcm.length);
        byte[] packet = packetize(encoded, payloadType, sequenceNumber, timestamp, ssrc);
        // Advance the media clock even on UDP failure; never retry stale audio.
        sequenceNumber = (sequenceNumber + 1) & 0xffff;
        timestamp = (timestamp + SAMPLES_PER_FRAME) & 0xffffffffL;
        try {
            socket.send(new DatagramPacket(packet, packet.length, remoteAddress, remotePort));
            packetsSent++;
        } catch (IOException failure) {
            lastSendFailure = failure;
            long count = ++sendFailures;
            if (count <= 3 || count % 100 == 0) {
                System.err.println("RTP audio send failed (" + count + "): " + failure.getMessage());
            }
        }
    }

    /** Pure packet construction, including sequence/timestamp wrap-around. */
    static byte[] packetize(byte[] payload, int payloadType, int sequence,
                            long timestamp, int ssrc) {
        if (payload == null || payload.length == 0 || payload.length > 65507 - 12
                || payloadType < 0 || payloadType > 127) {
            throw new IllegalArgumentException("Invalid RTP audio payload");
        }
        ByteBuffer out = ByteBuffer.allocate(12 + payload.length);
        out.put((byte) 0x80).put((byte) payloadType);
        out.putShort((short) sequence).putInt((int) timestamp).putInt(ssrc).put(payload);
        return out.array();
    }

    public long getPacketsSent() { return packetsSent; }
    public long getSendFailures() { return sendFailures; }
    public IOException getLastSendFailure() { return lastSendFailure; }
    public int getMicLevel() { return micLevel; }
    /** 本机发送流的 SSRC，接收端据此识别并丢弃被服务器回环回来的自己的包。 */
    public int getSsrc() { return ssrc; }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        pendingBytes = 0;
        // Shared sockets remain the receiver's responsibility.
        if (ownsSocket) socket.close();
    }
}

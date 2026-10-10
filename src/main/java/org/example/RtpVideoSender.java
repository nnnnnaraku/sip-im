package org.example;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Random;

/**
 * RTP 视频发送：把一帧 JPEG 拆成多个 RTP 包发出。
 *
 * <p>载荷格式（RFC 2435 的简化版）：
 * <pre>
 *   RTP 头（12 字节）：V=2, PT=26(JPEG), 序列号, 时间戳, SSRC
 *   载荷： [2 字节 帧内偏移] + JPEG 片段
 * </pre>
 * 一帧的最后一个包把 RTP 的 <b>Marker 位</b>置 1——这是标准 RTP 语义，
 * 接收端据此判断"这一帧收完了"。同一帧的所有分片共用同一个时间戳。
 *
 * <p>之所以简化掉 RFC 2435 里的量化表等字段：两端都是本项目的客户端，
 * 没有必要为了完全合规而增加复杂度。
 */
public class RtpVideoSender {

    /** 单个 RTP 包的最大载荷（含 3 字节前缀），留足余量避免 IP 分片 */
    private static final int MAX_PAYLOAD = 1200;
    /** RTP 时钟频率：视频固定 90000 Hz */
    private static final int CLOCK_RATE = 90000;
    /** 载荷类型 26 = JPEG */
    private static final int PAYLOAD_TYPE = 26;

    private final DatagramSocket socket;
    private final Random random = new Random();

    private InetAddress remoteAddr;
    private int remotePort;

    private int sequence = 0;
    private long timestamp = 0;
    private final int ssrc = random.nextInt();

    private volatile long framesSent = 0;
    private volatile long lastFrameSize = 0;
    /** 每帧时间戳增量（由帧率决定） */
    private int ticksPerFrame = CLOCK_RATE / 10;

    public RtpVideoSender(DatagramSocket socket) {
        this.socket = socket;
    }

    public void setRemote(InetAddress addr, int port) {
        this.remoteAddr = addr;
        this.remotePort = port;
        System.out.println("视频发送目标: " + addr.getHostAddress() + ":" + port);
    }

    public void setFps(int fps) {
        if (fps > 0) ticksPerFrame = CLOCK_RATE / fps;
    }

    /** 发送一帧 JPEG */
    public void sendFrame(byte[] jpeg) {
        if (remoteAddr == null || jpeg == null || jpeg.length == 0) return;

        int offset = 0;
        while (offset < jpeg.length) {
            int len = Math.min(MAX_PAYLOAD - 2, jpeg.length - offset);
            boolean isLast = (offset + len >= jpeg.length);

            ByteArrayOutputStream payload = new ByteArrayOutputStream(len + 2);
            payload.write((offset >> 8) & 0xFF);
            payload.write(offset & 0xFF);
            payload.write(jpeg, offset, len);

            sendPacket(payload.toByteArray(), isLast);
            offset += len;
        }

        timestamp += ticksPerFrame;
        framesSent++;
        lastFrameSize = jpeg.length;
    }

    private void sendPacket(byte[] payload, boolean marker) {
        try {
            byte[] packet = new byte[12 + payload.length];
            packet[0] = (byte) 0x80;                                    // V=2
            packet[1] = (byte) ((marker ? 0x80 : 0x00) | PAYLOAD_TYPE);  // M + PT
            packet[2] = (byte) ((sequence >> 8) & 0xFF);
            packet[3] = (byte) (sequence & 0xFF);
            packet[4] = (byte) ((timestamp >> 24) & 0xFF);
            packet[5] = (byte) ((timestamp >> 16) & 0xFF);
            packet[6] = (byte) ((timestamp >> 8) & 0xFF);
            packet[7] = (byte) (timestamp & 0xFF);
            packet[8] = (byte) ((ssrc >> 24) & 0xFF);
            packet[9] = (byte) ((ssrc >> 16) & 0xFF);
            packet[10] = (byte) ((ssrc >> 8) & 0xFF);
            packet[11] = (byte) (ssrc & 0xFF);
            System.arraycopy(payload, 0, packet, 12, payload.length);

            socket.send(new DatagramPacket(packet, packet.length, remoteAddr, remotePort));
            sequence = (sequence + 1) & 0xFFFF;
        } catch (Exception e) {
            System.err.println("视频包发送失败: " + e.getMessage());
        }
    }

    public long getFramesSent() {
        return framesSent;
    }

    public long getLastFrameSize() {
        return lastFrameSize;
    }
}

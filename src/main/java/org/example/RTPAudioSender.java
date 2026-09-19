package org.example;

import java.net.*;
import java.nio.ByteBuffer;

public class RTPAudioSender {
    private DatagramSocket socket;
    private boolean ownsSocket;
    private InetAddress remoteAddress;
    private int remotePort;
    private int sequenceNumber = 0;
    private long timestamp = 0;
    private int ssrc;
    private long startTime;

    /**
     * 复用外部已有 socket（一般是 RTPAudioReceiver 的），
     * 使 RTP 收发共用同一个本地端口，满足对称 RTP 要求。
     */
    public RTPAudioSender(DatagramSocket sharedSocket) {
        this.socket = sharedSocket;
        this.ownsSocket = false;
        ssrc = (int) (Math.random() * Integer.MAX_VALUE);
        startTime = System.currentTimeMillis();
        System.out.println("RTP发送器已创建，复用本地端口: " + sharedSocket.getLocalPort());
    }

    public RTPAudioSender(int localPort) throws SocketException {
        socket = new DatagramSocket(localPort);
        ownsSocket = true;
        ssrc = (int) (Math.random() * Integer.MAX_VALUE);
        startTime = System.currentTimeMillis();
        System.out.println("RTP发送器已创建，本地端口: " + localPort);
    }

    public void setRemote(String ip, int port) throws UnknownHostException {
        this.remoteAddress = InetAddress.getByName(ip);
        this.remotePort = port;
        System.out.println("RTP目标设置为: " + ip + ":" + port);
    }

    public void sendAudio(byte[] pcmData, int length) {
        if (remoteAddress == null) return;

        try {
            // 将 16bit PCM 转换为 8bit PCMU
            byte[] mulawData = PCMUEncoder.encode(pcmData);

            // 构建RTP包
            byte[] rtpPacket = buildRTPPacket(mulawData);

            DatagramPacket packet = new DatagramPacket(
                    rtpPacket, rtpPacket.length, remoteAddress, remotePort
            );

            socket.send(packet);

            sequenceNumber++;
            if (sequenceNumber > 65535) {
                sequenceNumber = 0;
            }

            // 时间戳增量 = 样本数 (20ms @ 8kHz = 160 samples)
            timestamp += 160;

        } catch (Exception e) {
            System.err.println("RTP发送失败: " + e.getMessage());
        }
    }

    private byte[] buildRTPPacket(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(12 + payload.length);

        // Byte 0: V(2)=2, P(1)=0, X(1)=0, CC(4)=0
        buffer.put((byte) 0x80);

        // Byte 1: M(1)=0, PT(7)=0 (PCMU)
        buffer.put((byte) 0x00);

        // Bytes 2-3: Sequence Number
        buffer.putShort((short) sequenceNumber);

        // Bytes 4-7: Timestamp
        buffer.putInt((int) timestamp);

        // Bytes 8-11: SSRC
        buffer.putInt(ssrc);

        // Payload
        buffer.put(payload);

        return buffer.array();
    }

    public void close() {
        // 共享 socket 由接收器负责关闭；这里若抢先关闭，接收线程会抛异常
        if (ownsSocket && socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}

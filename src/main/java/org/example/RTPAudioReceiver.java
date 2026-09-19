package org.example;

import javax.sound.sampled.*;
import java.net.*;

public class RTPAudioReceiver {
    private DatagramSocket socket;
    private SourceDataLine speaker;
    private boolean isRunning = false;
    private AudioFormat format;
    /** 已收到的 RTP 包数（用于判断音频是否真的在接收） */
    private volatile long packetsReceived = 0;

    public RTPAudioReceiver(int localPort) throws Exception {
        socket = new DatagramSocket(localPort);
        socket.setSoTimeout(5000); // 5秒超时

        // 16bit PCM 8kHz mono
        format = new AudioFormat(8000.0f, 16, 1, true, false);

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        speaker = (SourceDataLine) AudioSystem.getLine(info);
        speaker.open(format, 3200); // 400ms buffer
        speaker.start();

        System.out.println("✓ RTP接收器已启动，监听端口: " + localPort);
        System.out.println("  格式: " + format);
    }

    /** 供 RTPAudioSender 复用，实现 RTP 收发共用一个本地端口 */
    public DatagramSocket getSocket() {
        return socket;
    }

    /** 已收到的 RTP 包数 */
    public long getPacketsReceived() {
        return packetsReceived;
    }

    public void start() {
        isRunning = true;

        new Thread(() -> {
            byte[] buffer = new byte[512];
            int consecutiveErrors = 0;

            while (isRunning) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    consecutiveErrors = 0; // 重置错误计数

                    // 解析RTP包
                    byte[] rtpData = packet.getData();
                    int packetLength = packet.getLength();

                    if (packetLength < 12) {
                        continue; // RTP头至少12字节
                    }

                    packetsReceived++;
                    if (packetsReceived == 1) {
                        System.out.println("✓ 收到第一个 RTP 包，来自 "
                                + packet.getAddress().getHostAddress() + ":" + packet.getPort()
                                + "，长度 " + packetLength + " 字节");
                    }

                    // RTP头长度
                    int rtpHeaderLength = 12;

                    // 检查是否有扩展头
                    int cc = rtpData[0] & 0x0F; // CSRC count
                    rtpHeaderLength += cc * 4;

                    if ((rtpData[0] & 0x10) != 0) { // 有扩展头
                        if (packetLength < rtpHeaderLength + 4) continue;
                        int extLen = ((rtpData[rtpHeaderLength + 2] & 0xFF) << 8) |
                                (rtpData[rtpHeaderLength + 3] & 0xFF);
                        rtpHeaderLength += 4 + extLen * 4;
                    }

                    if (packetLength <= rtpHeaderLength) {
                        continue;
                    }

                    // 提取音频数据 (PCMU格式)
                    int payloadLength = packetLength - rtpHeaderLength;
                    byte[] mulawData = new byte[payloadLength];
                    System.arraycopy(rtpData, rtpHeaderLength, mulawData, 0, payloadLength);

                    // 解码 PCMU 为 PCM
                    byte[] pcmData = PCMUEncoder.decode(mulawData);

                    // 播放音频
                    speaker.write(pcmData, 0, pcmData.length);

                } catch (SocketTimeoutException e) {
                    // 超时是正常的，继续等待
                } catch (Exception e) {
                    if (isRunning) {
                        consecutiveErrors++;
                        if (consecutiveErrors < 5) {
                            System.err.println("RTP接收错误: " + e.getMessage());
                        }
                        if (consecutiveErrors >= 10) {
                            System.err.println("连续错误过多，停止接收");
                            break;
                        }
                    }
                }
            }
        }, "RTPReceiver").start();
    }

    public void stop() {
        isRunning = false;
        if (speaker != null) {
            speaker.drain();
            speaker.stop();
            speaker.close();
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        System.out.println("RTP接收器已停止");
    }
}
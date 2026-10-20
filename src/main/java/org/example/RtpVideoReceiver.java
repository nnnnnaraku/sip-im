package org.example;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Map;
import java.util.TreeMap;

/**
 * RTP 视频接收：收包 -> 按偏移重组一帧 JPEG -> 回调。
 *
 * <p>与 {@link RtpVideoSender} 对应。同一帧的分片共用同一个 RTP 时间戳，
 * 最后一个包的 Marker 位为 1。收到 Marker 后按偏移拼接，
 * 拼出的数据从 0 开始且连续，才算一帧完整（丢包则丢弃该帧，宁可花屏也不卡死）。
 */
public class RtpVideoReceiver {

    /** 一帧重组完成的回调 */
    public interface FrameListener {
        void onFrame(byte[] jpeg);
    }

    private final DatagramSocket socket;
    private final FrameListener listener;

    private volatile boolean running = false;
    private Thread thread;
    private boolean stopped;

    /** 当前正在重组的帧：偏移 -> 数据 */
    private final TreeMap<Integer, byte[]> fragments = new TreeMap<>();
    private long currentTimestamp = -1;

    private volatile long framesReceived = 0;
    private volatile long lastFrameSize = 0;
    private volatile long droppedFrames = 0;

    public RtpVideoReceiver(DatagramSocket socket, FrameListener listener) {
        if (socket == null || socket.isClosed()) throw new IllegalArgumentException("Open video socket required");
        this.socket = socket;
        this.listener = listener;
    }

    public synchronized void start() {
        if (stopped || socket.isClosed()) throw new IllegalStateException("Video receiver has stopped");
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "RtpVideoReceiver");
        thread.setDaemon(true);
        try { thread.start(); }
        catch (RuntimeException | Error failure) { running = false; stopped = true; socket.close(); throw failure; }
    }

    private void loop() {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                if (!running) return;

                int len = packet.getLength();
                if (len < 14) continue; // 12 字节 RTP 头 + 2 字节偏移

                int b0 = buf[0] & 0xFF;
                if ((b0 >> 6) != 2) continue;         // 版本必须是 2
                boolean marker = (buf[1] & 0x80) != 0;

                long ts = ((long) (buf[4] & 0xFF) << 24)
                        | ((long) (buf[5] & 0xFF) << 16)
                        | ((long) (buf[6] & 0xFF) << 8)
                        | (buf[7] & 0xFF);

                int offset = ((buf[12] & 0xFF) << 8) | (buf[13] & 0xFF);
                byte[] data = new byte[len - 14];
                System.arraycopy(buf, 14, data, 0, data.length);

                synchronized (fragments) {
                    if (ts != currentTimestamp) {
                        // 新的一帧开始，丢弃上一帧的残留分片
                        if (currentTimestamp != -1) droppedFrames++;
                        fragments.clear();
                        currentTimestamp = ts;
                    }
                    fragments.put(offset, data);

                    if (marker) {
                        byte[] frame = assemble();
                        fragments.clear();
                        currentTimestamp = -1;
                        if (frame != null) {
                            framesReceived++;
                            lastFrameSize = frame.length;
                            if (running && listener != null) listener.onFrame(frame);
                        } else {
                            droppedFrames++;
                        }
                    }
                }
            } catch (Exception e) {
                if (running) System.err.println("视频接收异常: " + e.getMessage());
                if (socket.isClosed()) { running = false; return; }
            }
        }
    }

    /** 按偏移顺序拼接；必须从 0 开始且连续，否则返回 null（丢包则弃帧） */
    private byte[] assemble() {
        int expected = 0;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        for (Map.Entry<Integer, byte[]> e : fragments.entrySet()) {
            if (e.getKey() != expected) return null; // 有空洞，丢弃
            bos.write(e.getValue(), 0, e.getValue().length);
            expected += e.getValue().length;
        }
        return (expected > 0) ? bos.toByteArray() : null;
    }

    public void stop() {
        final Thread receiving;
        synchronized (this) {
            stopped = true;
            running = false;
            receiving = thread;
        }
        // Interrupt alone does not release DatagramSocket.receive().
        socket.close();
        if (receiving != null) receiving.interrupt();
        if (receiving != null && receiving != Thread.currentThread()) {
            try { receiving.join(1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (receiving == null || !receiving.isAlive() || receiving == Thread.currentThread()) {
            synchronized (fragments) {
                fragments.clear();
                currentTimestamp = -1;
            }
        }
    }

    public long getFramesReceived() {
        return framesReceived;
    }

    public long getLastFrameSize() {
        return lastFrameSize;
    }

    public long getDroppedFrames() {
        return droppedFrames;
    }
}

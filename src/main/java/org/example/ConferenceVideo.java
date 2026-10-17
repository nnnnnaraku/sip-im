package org.example;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会议视频通道（客户端侧）：把本机摄像头画面发到 {@link VideoRelay}，
 * 同时接收同一房间里其他人的画面。
 *
 * <p><b>为什么收发用两个 socket：</b>
 * {@link DatagramSocket#receive} 在阻塞等待时会一直持有 socket 的锁，
 * 若用同一个 socket 发送，心跳/离开通知会永远等不到锁（实测会死锁）。
 * 因此接收用 {@code recvSocket}，所有发送走 {@code sendSocket}。
 *
 * <p>报文里始终携带「房间 + 用户名」，中继据此识别发送者，
 * 不依赖发送方的源端口。
 */
public class ConferenceVideo {

    public interface Listener {
        /** 本机摄像头的一帧（用于网页/窗口里的本地预览） */
        void onLocalFrame(byte[] jpeg);

        /** 收到某位参与者的画面 */
        void onRemoteFrame(String user, byte[] jpeg);

        /** 某位参与者离开 */
        void onPeerLeft(String user);
    }

    private static final int TYPE_JOIN = 1;
    private static final int TYPE_FRAME = 2;
    private static final int TYPE_LEAVE = 3;
    private static final int TYPE_FRAME_IN = 4;
    private static final int TYPE_JOINED = 5;
    private static final int TYPE_LEFT = 6;

    private final String relayHost;
    private final int relayPort;
    private final String room;
    private final String user;
    private final int width;
    private final int height;
    private final int fps;
    private final float quality;
    private final Listener listener;

    /** 只负责接收 */
    private DatagramSocket recvSocket;
    /** 只负责发送（JOIN / FRAME / LEAVE） */
    private DatagramSocket sendSocket;
    private InetAddress relayAddr;
    private VideoCapture capture;
    private volatile boolean running;

    /** 统计：便于排障（不依赖界面） */
    private volatile long framesSent = 0;
    private volatile long framesRecv = 0;

    /** 中继下发的 id -> 用户名 */
    private final Map<Integer, String> idToUser = new ConcurrentHashMap<>();

    public ConferenceVideo(String relayHost, int relayPort, String room, String user,
                           int width, int height, int fps, float quality, Listener listener) {
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.room = room;
        this.user = user;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.quality = quality;
        this.listener = listener;
    }

    public void start() throws Exception {
        relayAddr = InetAddress.getByName(relayHost);
        recvSocket = new DatagramSocket();
        sendSocket = new DatagramSocket();

        // 摄像头打不开时降级为“只收不发”，会议里仍能看到别人
        try {
            capture = new VideoCapture(width, height);
            capture.start();
        } catch (Exception e) {
            System.err.println("摄像头不可用（" + e.getMessage() + "），会议中将只收不发");
            capture = null;
        }

        running = true;
        sendJoin();

        Thread recv = new Thread(this::receiveLoop, "ConfVideoRecv");
        recv.setDaemon(true);
        recv.start();

        Thread send = new Thread(this::sendLoop, "ConfVideoSend");
        send.setDaemon(true);
        send.start();

        Thread beat = new Thread(this::heartbeatLoop, "ConfVideoBeat");
        beat.setDaemon(true);
        beat.start();

        System.out.println("✓ 会议视频已启动：房间 " + room + "，中继 "
                + relayHost + ":" + relayPort
                + "，接收端口 " + recvSocket.getLocalPort()
                + (capture != null ? "" : "（只收不发）"));
    }

    public void stop() {
        if (!running) return;
        running = false;
        sendLeave();
        closeQuietly(recvSocket);
        closeQuietly(sendSocket);
        if (capture != null) {
            capture.stop();
            capture = null;
        }
        System.out.println("会议视频已停止（发送 " + framesSent + " 帧 / 接收 " + framesRecv + " 帧）");
    }

    // ---------------- 发送 ----------------

    private void sendLoop() {
        long interval = 1000L / Math.max(1, fps);
        while (running) {
            long t0 = System.currentTimeMillis();
            try {
                if (capture != null) {
                    java.awt.image.BufferedImage img = capture.grab();
                    if (img != null) {
                        byte[] jpeg = VideoCodec.encode(img, quality);
                        byte[] head = frameHeader(TYPE_FRAME);
                        if (head.length + jpeg.length < 65000) {
                            byte[] out = new byte[head.length + jpeg.length];
                            System.arraycopy(head, 0, out, 0, head.length);
                            System.arraycopy(jpeg, 0, out, head.length, jpeg.length);
                            send(out);
                            framesSent++;
                        }
                        if (listener != null) listener.onLocalFrame(jpeg);
                    }
                } else {
                    Thread.sleep(interval);
                }
            } catch (Exception e) {
                if (running) System.err.println("会议视频发送失败: " + e.getMessage());
            }
            long sleep = interval - (System.currentTimeMillis() - t0);
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void sendJoin() {
        byte[] head = frameHeader(TYPE_JOIN);
        byte[] out = new byte[head.length + 2];
        System.arraycopy(head, 0, out, 0, head.length);
        int port = (recvSocket != null) ? recvSocket.getLocalPort() : 0;
        out[head.length] = (byte) (port >>> 8);
        out[head.length + 1] = (byte) port;
        send(out);
    }

    private void sendLeave() {
        send(frameHeader(TYPE_LEAVE));
    }

    /** 构造「类型 + 房间 + 用户名」头部 */
    private byte[] frameHeader(int type) {
        byte[] r = room.getBytes(StandardCharsets.UTF_8);
        byte[] u = user.getBytes(StandardCharsets.UTF_8);
        byte[] h = new byte[1 + 1 + r.length + 1 + u.length];
        int off = 0;
        h[off++] = (byte) type;
        h[off++] = (byte) r.length;
        System.arraycopy(r, 0, h, off, r.length); off += r.length;
        h[off++] = (byte) u.length;
        System.arraycopy(u, 0, h, off, u.length);
        return h;
    }

    private void send(byte[] data) {
        try {
            DatagramSocket s = sendSocket;
            if (s != null && !s.isClosed()) {
                s.send(new DatagramPacket(data, data.length, relayAddr, relayPort));
            }
        } catch (Exception ignore) {
        }
    }

    /** 定期重发 JOIN 当心跳，中继 30 秒收不到就认为该成员已离开 */
    private void heartbeatLoop() {
        while (running) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                return;
            }
            if (running) sendJoin();
        }
    }

    // ---------------- 接收 ----------------

    private void receiveLoop() {
        byte[] buf = new byte[65535];
        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                recvSocket.receive(pkt);
                handlePacket(pkt.getData(), pkt.getLength());
            } catch (Exception e) {
                if (!running) return;
                if (recvSocket == null || recvSocket.isClosed()) return;
            }
        }
    }

    private void handlePacket(byte[] b, int len) {
        if (len < 2) return;
        int type = b[0] & 0xFF;
        if (type == TYPE_FRAME_IN && len > 5) {
            int id = readInt(b, 1);
            String name = idToUser.get(id);
            if (name == null) name = "#" + id;
            byte[] jpeg = new byte[len - 5];
            System.arraycopy(b, 5, jpeg, 0, jpeg.length);
            framesRecv++;
            if (listener != null) listener.onRemoteFrame(name, jpeg);
        } else if (type == TYPE_JOINED && len > 6) {
            int id = readInt(b, 1);
            int nameLen = b[5] & 0xFF;
            if (6 + nameLen <= len) {
                idToUser.put(id, new String(b, 6, nameLen, StandardCharsets.UTF_8));
            }
        } else if (type == TYPE_LEFT && len >= 5) {
            int id = readInt(b, 1);
            String name = idToUser.remove(id);
            if (name != null && listener != null) listener.onPeerLeft(name);
        }
    }

    private static void closeQuietly(DatagramSocket s) {
        try {
            if (s != null) s.close();
        } catch (Exception ignore) {
        }
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}

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
    interface ResourceFactory {
        DatagramSocket socket() throws Exception;
        VideoCapture capture(int width, int height);
    }
    private final ResourceFactory resources;
    private boolean started;
    private boolean stopped;
    private Thread receiveThread;
    private Thread sendThread;
    private Thread heartbeatThread;

    /** 只负责接收 */
    private DatagramSocket recvSocket;
    /** 只负责发送（JOIN / FRAME / LEAVE） */
    private DatagramSocket sendSocket;
    private InetAddress relayAddr;
    private volatile VideoCapture capture;
    private volatile boolean running;

    /** 统计：便于排障（不依赖界面） */
    private volatile long framesSent = 0;
    private volatile long framesRecv = 0;

    /** 中继下发的 id -> 用户名 */
    private final Map<Integer, String> idToUser = new ConcurrentHashMap<>();

    public ConferenceVideo(String relayHost, int relayPort, String room, String user,
                           int width, int height, int fps, float quality, Listener listener) {
        this(relayHost, relayPort, room, user, width, height, fps, quality, listener,
                new ResourceFactory() {
                    public DatagramSocket socket() throws Exception { return new DatagramSocket(); }
                    public VideoCapture capture(int w, int h) { return new VideoCapture(w, h); }
                });
    }

    ConferenceVideo(String relayHost, int relayPort, String room, String user,
                    int width, int height, int fps, float quality, Listener listener,
                    ResourceFactory resources) {
        if (resources == null) throw new IllegalArgumentException("Conference media factory required");
        this.resources = resources;
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

    public synchronized void start() throws Exception {
        if (started || stopped) throw new IllegalStateException("Conference video cannot be restarted");
        started = true;
        try {
            relayAddr = InetAddress.getByName(relayHost);
            recvSocket = resources.socket();
            sendSocket = resources.socket();

            // Camera failure preserves receive-only mode, but releases partial camera acquisition.
            try {
                capture = resources.capture(width, height);
                capture.start();
            } catch (Exception cameraFailure) {
                if (capture != null) {
                    try { capture.stop(); } catch (RuntimeException ignored) { }
                    capture = null;
                }
                System.err.println("Camera unavailable; conference video is receive-only");
            }

            running = true;
            sendJoin();
            receiveThread = new Thread(this::receiveLoop, "ConfVideoRecv");
            sendThread = new Thread(this::sendLoop, "ConfVideoSend");
            heartbeatThread = new Thread(this::heartbeatLoop, "ConfVideoBeat");
            receiveThread.setDaemon(true);
            sendThread.setDaemon(true);
            heartbeatThread.setDaemon(true);
            receiveThread.start();
            sendThread.start();
            heartbeatThread.start();
            System.out.println("Conference video started: room " + room + ", UDP receive "
                    + recvSocket.getLocalPort() + (capture != null ? "" : " (receive-only)"));
        } catch (Exception | Error startupFailure) {
            // This path also owns resources acquired before running became true.
            stop();
            throw startupFailure;
        }
    }

    public void stop() {
        final boolean notifyLeave;
        final VideoCapture ownedCapture;
        final Thread receive;
        final Thread send;
        final Thread heartbeat;
        synchronized (this) {
            if (stopped) return;
            stopped = true;
            notifyLeave = running;
            running = false;
            ownedCapture = capture;
            capture = null;
            receive = receiveThread;
            send = sendThread;
            heartbeat = heartbeatThread;
        }
        try {
            if (notifyLeave) sendLeave();
        } finally {
            closeQuietly(recvSocket);
            closeQuietly(sendSocket);
            if (receive != null) receive.interrupt();
            if (send != null) send.interrupt();
            if (heartbeat != null) heartbeat.interrupt();
            if (ownedCapture != null) {
                try { ownedCapture.stop(); } catch (RuntimeException ignored) { }
            }
            join(receive);
            join(send);
            join(heartbeat);
            idToUser.clear();
        }
        System.out.println("Conference video stopped");
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(1000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    // ---------------- 发送 ----------------

    private void sendLoop() {
        long interval = 1000L / Math.max(1, fps);
        while (running) {
            long t0 = System.currentTimeMillis();
            try {
                VideoCapture activeCapture = capture;
                if (activeCapture != null) {
                    java.awt.image.BufferedImage img = activeCapture.grab();
                    if (!running) return;
                    if (img != null) {
                        byte[] jpeg = VideoCodec.encode(img, quality);
                        if (!running) return;
                        byte[] head = frameHeader(TYPE_FRAME);
                        if (head.length + jpeg.length < 65000) {
                            byte[] out = new byte[head.length + jpeg.length];
                            System.arraycopy(head, 0, out, 0, head.length);
                            System.arraycopy(jpeg, 0, out, head.length, jpeg.length);
                            send(out);
                            framesSent++;
                        }
                        if (running && listener != null) listener.onLocalFrame(jpeg);
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
                if (!running) return;
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
            if (running && listener != null) listener.onRemoteFrame(name, jpeg);
        } else if (type == TYPE_JOINED && len > 6) {
            int id = readInt(b, 1);
            int nameLen = b[5] & 0xFF;
            if (6 + nameLen <= len) {
                idToUser.put(id, new String(b, 6, nameLen, StandardCharsets.UTF_8));
            }
        } else if (type == TYPE_LEFT && len >= 5) {
            int id = readInt(b, 1);
            String name = idToUser.remove(id);
            if (running && name != null && listener != null) listener.onPeerLeft(name);
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

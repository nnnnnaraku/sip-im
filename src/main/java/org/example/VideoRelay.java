package org.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多人视频中继（应用层，UDP）。
 *
 * <p>为什么需要它：miniSIPServer 的会议室只做音频混音（其 SDP 里只有 {@code m=audio}），
 * 不转发视频。因此多人视频另走一条轻量中继：
 * 每个参与者把自己的 JPEG 帧发给中继，中继按「房间」扇出给同房间的其他参与者。
 *
 * <p>协议（第 1 字节为类型，字符串均为 UTF-8，长度各占 1 字节）：
 * <pre>
 *   1 JOIN    [1][roomLen][room][userLen][user][recvPortHi][recvPortLo]
 *   2 FRAME   [2][roomLen][room][userLen][user][jpeg...]
 *   3 LEAVE   [3][roomLen][room][userLen][user]
 *   4 FRAME   [4][senderId:4][jpeg...]              中继 -> 客户端
 *   5 JOINED  [5][senderId:4][nameLen][name]        中继 -> 客户端
 *   6 LEFT    [6][senderId:4]                       中继 -> 客户端
 * </pre>
 *
 * <p>加入时客户端会告知自己的「接收端口」，中继据此把画面发到该端口；
 * 客户端发帧用的可能是另一个端口，所以帧里必须携带房间与用户名。
 */
public class VideoRelay {

    private static final int DEFAULT_PORT = 9100;
    /** 参与者超过该时间没有任何数据（含心跳 JOIN）就认为已离开 */
    private static final long TIMEOUT_MS = 30_000;

    private static final class Participant {
        InetSocketAddress addr;
        String room;
        String user;
        int id;
        volatile long lastSeen;
    }

    /** key = room + '\u0000' + user */
    private final Map<String, Participant> byKey = new ConcurrentHashMap<>();
    private final int port;
    private DatagramSocket socket;
    private int nextId = 1;
    private volatile long relayedFrames = 0;

    public VideoRelay(int port) {
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        Properties cfg = loadConfig();
        int port = intCfg(cfg, "video.relay.port", DEFAULT_PORT);
        String p = System.getProperty("relay.port");
        if (p != null && !p.trim().isEmpty()) {
            try { port = Integer.parseInt(p.trim()); } catch (Exception ignore) { }
        }

        System.out.println("=====================================================");
        System.out.println("  多人视频中继已启动");
        System.out.println("  监听端口: UDP " + port);
        System.out.println("  客户端配置 video.relay.host/port 指向本机即可");
        System.out.println("  按 Ctrl+C 退出");
        System.out.println("=====================================================");

        new VideoRelay(port).run();
    }

    private void run() throws Exception {
        socket = new DatagramSocket(port);
        Thread reaper = new Thread(this::housekeepingLoop, "RelayHousekeeping");
        reaper.setDaemon(true);
        reaper.start();

        byte[] buf = new byte[65535];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (true) {
            socket.receive(pkt);
            try {
                handle(pkt);
            } catch (Exception e) {
                System.err.println("处理数据包失败: " + e.getMessage());
            }
        }
    }

    private void handle(DatagramPacket pkt) throws Exception {
        byte[] b = pkt.getData();
        int len = pkt.getLength();
        if (len < 1) return;

        int type = b[0] & 0xFF;
        switch (type) {
            case 1: join(pkt, b, len); break;
            case 2: frame(pkt, b, len); break;
            case 3: leave(b, len); break;
            default: break; // 未知类型忽略
        }
    }

    private void join(DatagramPacket pkt, byte[] b, int len) throws Exception {
        String[] ru = readRoomUser(b, len, 1);
        if (ru == null) return;
        int off = Integer.parseInt(ru[2]);
        if (off + 2 > len) return;
        int recvPort = ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
        String room = ru[0];
        String user = ru[1];

        String key = key(room, user);
        Participant p = byKey.get(key);
        boolean isNew = (p == null);
        if (isNew) {
            p = new Participant();
            p.id = nextId++;
            p.room = room;
            p.user = user;
            byKey.put(key, p);
        }
        p.addr = new InetSocketAddress(pkt.getAddress(), recvPort);
        p.lastSeen = System.currentTimeMillis();

        if (isNew) {
            System.out.println("[加入] " + user + " -> 房间 " + room
                    + "  收流端口 " + recvPort + "  (" + pkt.getAddress() + ")");
            byte[] ub = user.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[1 + 4 + 1 + ub.length];
            out[0] = 5;
            writeInt(out, 1, p.id);
            out[5] = (byte) ub.length;
            System.arraycopy(ub, 0, out, 6, ub.length);
            broadcast(room, key, out);
        }
    }

    private void frame(DatagramPacket pkt, byte[] b, int len) throws Exception {
        String[] ru = readRoomUser(b, len, 1);
        if (ru == null) return;
        int off = Integer.parseInt(ru[2]);
        if (off >= len) return;
        String room = ru[0];
        String key = key(room, ru[1]);
        Participant p = byKey.get(key);
        if (p == null) return;
        p.lastSeen = System.currentTimeMillis();

        // 中继帧：类型 4 + 发送者 id + 原始 JPEG
        int jpegLen = len - off;
        byte[] out = new byte[1 + 4 + jpegLen];
        out[0] = 4;
        writeInt(out, 1, p.id);
        System.arraycopy(b, off, out, 5, jpegLen);
        broadcast(room, key, out);
        relayedFrames++;
    }

    private void leave(byte[] b, int len) {
        String[] ru = readRoomUser(b, len, 1);
        if (ru == null) return;
        String key = key(ru[0], ru[1]);
        Participant p = byKey.remove(key);
        if (p == null) return;
        System.out.println("[离开] " + p.user + " <- 房间 " + p.room);
        notifyLeft(p);
    }

    /** 把数据发给同房间的“其他人”（不回发给发送者本人） */
    private void broadcast(String room, String fromKey, byte[] data) {
        for (Map.Entry<String, Participant> e : byKey.entrySet()) {
            if (e.getKey().equals(fromKey)) continue;
            Participant p = e.getValue();
            if (!room.equals(p.room)) continue;
            try {
                socket.send(new DatagramPacket(data, data.length, p.addr));
            } catch (Exception ignore) {
            }
        }
    }

    private void notifyLeft(Participant gone) {
        byte[] out = new byte[5];
        out[0] = 6;
        writeInt(out, 1, gone.id);
        for (Participant p : byKey.values()) {
            if (!gone.room.equals(p.room)) continue;
            try {
                socket.send(new DatagramPacket(out, out.length, p.addr));
            } catch (Exception ignore) {
            }
        }
    }

    /** 清理超时成员 + 定期打印中继统计 */
    private void housekeepingLoop() {
        long lastReport = System.currentTimeMillis();
        while (true) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            List<Participant> dead = new ArrayList<>();
            for (Participant p : byKey.values()) {
                if (now - p.lastSeen > TIMEOUT_MS) dead.add(p);
            }
            for (Participant p : dead) {
                byKey.remove(key(p.room, p.user));
                System.out.println("[超时] " + p.user + " 已移出房间 " + p.room);
                notifyLeft(p);
            }
            if (now - lastReport >= 30000) {
                if (relayedFrames > 0 && !byKey.isEmpty()) {
                    System.out.println("[中继] 在线 " + byKey.size() + " 人，近 30 秒转发 " + relayedFrames + " 帧");
                }
                relayedFrames = 0;
                lastReport = now;
            }
        }
    }

    /**
     * 解析 [type][roomLen][room][userLen][user]，返回 {room, user, nextOffset}
     * 失败返回 null。nextOffset 以字符串形式返回，便于复用。
     */
    private static String[] readRoomUser(byte[] b, int len, int start) {
        int off = start;
        if (off >= len) return null;
        int roomLen = b[off++] & 0xFF;
        if (off + roomLen > len) return null;
        String room = new String(b, off, roomLen, StandardCharsets.UTF_8);
        off += roomLen;
        if (off >= len) return null;
        int userLen = b[off++] & 0xFF;
        if (off + userLen > len) return null;
        String user = new String(b, off, userLen, StandardCharsets.UTF_8);
        off += userLen;
        return new String[]{room, user, String.valueOf(off)};
    }

    private static String key(String room, String user) {
        return room + '\u0000' + user;
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        File f = new File("config.properties");
        if (!f.exists()) return p;
        try (InputStream in = new FileInputStream(f);
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (Exception e) {
            System.err.println("读取 config.properties 失败: " + e.getMessage());
        }
        return p;
    }

    private static int intCfg(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}

package org.example;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会议音频通道（客户端侧）：与点对点通话使用同一套 20ms / 16bit PCM 帧，
 * 但媒体走 {@link VideoRelay} 的应用层扇出，而不是 miniSIPServer 会议室混音。
 *
 * <p><b>为什么不用服务器会议室混音：</b>
 * 会议室会把所有参与者的声音混在一起再回传给每个人，<b>包括发言者自己</b>。
 * 在免提场景下这会形成「自听回声」，听起来又闷又延迟，实测无法使用。
 * 应用层中继按房间扇出原始帧且<b>不回传发送者</b>，从源头上消除了这个问题。
 *
 * <p><b>抖动缓冲：</b>跨网段时帧到达节奏不稳。每个参与者维护独立队列，
 * 预缓冲 60ms 后按 20ms 的节奏取帧；丢包时补静音而不是加速播放。
 *
 * <p>本类只负责采集、发送、接收、混音、播放；房间与 JOIN 复用 {@link ConferenceVideo} 的通道。
 */
public class ConferenceAudio {

    private static final int SAMPLE_RATE = 8000;
    /** 20ms @ 8kHz / 16bit / mono */
    private static final int FRAME_BYTES = 320;
    /** 每个来源先缓冲 3 帧（60ms）再开始播放 */
    private static final int PREBUFFER_FRAMES = 3;
    /** 队列上限，超过说明对端发送过快或本机卡顿，丢弃最旧的帧 */
    private static final int MAX_QUEUE_FRAMES = 10;
    /** 缺帧超过该数量（约 100ms）就跳到最早可用的帧，避免永久等待 */
    private static final int MAX_RESYNC_FRAMES = 5;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    private final ConferenceVideo channel;
    private final String user;
    private final Map<Integer, PeerBuffer> peers = new ConcurrentHashMap<>();
    private final AtomicLong sentFrames = new AtomicLong();
    private final AtomicLong recvFrames = new AtomicLong();
    private final AtomicLong mixPeak = new AtomicLong();

    private AudioCapture capture;
    private SourceDataLine speaker;
    private volatile boolean running;
    private int seq;

    public ConferenceAudio(ConferenceVideo channel, String user) {
        this.channel = channel;
        this.user = user;
    }

    public void start() {
        if (running) return;
        running = true;

        try {
            openSpeaker();
        } catch (Exception e) {
            System.err.println("会议音频：扬声器不可用（" + e.getMessage() + "），本次只发不收");
        }
        try {
            startCapture();
        } catch (Exception e) {
            System.err.println("会议音频：麦克风不可用（" + e.getMessage() + "），本次只收不发");
        }

        // 音频帧由 ConferenceVideo 的接收线程分发过来，避免与视频抢同一个 JOIN 地址
        channel.setAudioSink(this::onAudioFrame);

        if (speaker != null) startPlayback();
        if (capture != null || speaker != null) {
            System.out.println("✓ 会议音频已启动（应用层中继，混音播放，不回传自己）");
        }
    }

    public void stop() {
        if (!running) return;
        running = false;
        channel.setAudioSink(null);

        if (capture != null) {
            capture.stop();
            capture = null;
        }
        SourceDataLine line = speaker;
        speaker = null;
        if (line != null) {
            try { line.stop(); } catch (Exception ignore) { }
            try { line.close(); } catch (Exception ignore) { }
        }
        peers.clear();
        System.out.println("会议音频已停止（发送 " + sentFrames.get() + " 帧 / 接收 " + recvFrames.get() + " 帧）");
    }

    /** 供 {@link ConferenceVideo} 在收到中继音频帧时回调。 */
    public void onAudioFrame(int senderId, int seq16, byte[] pcm) {
        if (!running || pcm == null || pcm.length == 0) return;
        recvFrames.incrementAndGet();
        peers.computeIfAbsent(senderId, id -> new PeerBuffer()).offer(seq16, pcm);
    }

    // ---------------- 设备 ----------------

    private void openSpeaker() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(FORMAT, FRAME_BYTES * 4);
        line.start();
        speaker = line;
    }

    private void startCapture() throws LineUnavailableException {
        capture = new AudioCapture();
        capture.start((data, length) -> {
            if (!running) return;
            byte[] pcm = new byte[FRAME_BYTES];
            int n = Math.min(length, FRAME_BYTES);
            System.arraycopy(data, 0, pcm, 0, n);
            int s = seq++ & 0xFFFF;
            if (channel.sendAudio(pcm, s)) sentFrames.incrementAndGet();
        });
    }

    // ---------------- 播放 ----------------

    private void startPlayback() {
        Thread t = new Thread(() -> {
            long lastReport = System.currentTimeMillis();
            while (running) {
                byte[] frame = mix();
                SourceDataLine line = speaker;
                if (line == null) return;
                try {
                    // 由 SourceDataLine 的阻塞写入控制 20ms 节奏：
                    // 不要额外 sleep，否则会与声卡缓冲叠加，越播越慢。
                    line.write(frame, 0, frame.length);
                } catch (Exception e) {
                    return;
                }

                long now = System.currentTimeMillis();
                if (now - lastReport >= 30_000) {
                    System.out.println("[会议音频] 房间 " + channel.getRoom() + " 接收 "
                            + recvFrames.get() + " 帧 / 发送 " + sentFrames.get()
                            + " 帧 / 混音峰值 " + mixPeak.getAndSet(0));
                    lastReport = now;
                }
            }
        }, "ConfAudioPlay");
        t.setDaemon(true);
        t.start();
    }

    /** 把每个来源当前时间片的帧取出来混合；缺帧的来源当静音处理。 */
    private byte[] mix() {
        int[] acc = new int[FRAME_BYTES / 2];
        int sources = 0;
        for (PeerBuffer p : peers.values()) {
            byte[] frame = p.next();
            if (frame == null) continue;
            sources++;
            for (int i = 0; i < acc.length && i * 2 + 1 < frame.length; i++) {
                acc[i] += (short) ((frame[i * 2] & 0xFF) | (frame[i * 2 + 1] << 8));
            }
        }
        if (sources == 0) return new byte[FRAME_BYTES];

        byte[] out = new byte[FRAME_BYTES];
        int peak = 0;
        for (int i = 0; i < acc.length; i++) {
            int v = acc[i] / sources;
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            peak = Math.max(peak, Math.abs(v));
            out[i * 2] = (byte) v;
            out[i * 2 + 1] = (byte) (v >> 8);
        }
        if (peak > mixPeak.get()) mixPeak.set(peak);
        return out;
    }

    /** 单个来源的抖动队列：按 20ms 时间片消费，丢包补静音。 */
    static final class PeerBuffer {
        private final TreeMap<Long, byte[]> frames = new TreeMap<>();
        private long lastSeq = -1;
        private long playSeq = -1;

        synchronized void offer(int seq16, byte[] pcm) {
            long s = unwrap(seq16);
            if (playSeq >= 0 && s < playSeq) return; // 已经播过的时间片，丢弃
            frames.put(s, pcm);
            while (frames.size() > MAX_QUEUE_FRAMES) {
                frames.pollFirstEntry();
            }
            if (playSeq < 0 && frames.size() >= PREBUFFER_FRAMES) {
                playSeq = frames.firstKey();
            }
        }

        synchronized byte[] next() {
            if (playSeq < 0) {
                if (frames.size() < PREBUFFER_FRAMES) return null;
                playSeq = frames.firstKey();
            }
            // 丢弃已经播过或队列上限导致的旧帧
            while (!frames.isEmpty() && frames.firstKey() < playSeq) {
                frames.pollFirstEntry();
            }
            byte[] frame = frames.remove(playSeq);
            if (frame != null) {
                playSeq++;
                return frame;
            }
            if (frames.isEmpty()) {
                // 欠载：不要推进 playSeq，否则下一帧到达时会被误判为“已播过”而永久静音
                return null;
            }
            if (frames.firstKey() - playSeq > MAX_RESYNC_FRAMES) {
                // 中间确实丢了较多帧，直接跳到最早可用的帧
                playSeq = frames.firstKey();
                frame = frames.remove(playSeq);
                playSeq++;
                return frame;
            }
            return null; // 短暂乱序：本时间片补静音，等缺失帧
        }

        /** 把 16 位序号展开成单调递增的 long，处理回绕。 */
        private long unwrap(int seq16) {
            if (lastSeq < 0) {
                lastSeq = seq16;
                return seq16;
            }
            long base = lastSeq & ~0xFFFFL;
            long candidate = base | (seq16 & 0xFFFF);
            if (candidate < lastSeq - 32768) candidate += 65536;
            else if (candidate > lastSeq + 32768) candidate -= 65536;
            if (candidate > lastSeq) lastSeq = candidate;
            return candidate;
        }
    }
}

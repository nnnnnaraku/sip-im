package org.example;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * 语音消息的录制与播放。
 *
 * <p>录制：复用 {@link AudioCapture} 采集 8kHz/16bit/单声道 PCM，录够时长后写成标准 WAV 文件。
 * <p>播放：用 SourceDataLine 播放 WAV。
 *
 * <p>之所以用 WAV 而不是 PCMU 原始数据：WAV 是标准格式，随手双击就能播放/验证，
 * 而且在局域网上多占的这点带宽无所谓。
 */
public class VoiceIO {

    /** 音频格式：8kHz / 16bit / 单声道 / 有符号 / 小端（与通话媒体保持一致） */
    public static final AudioFormat FORMAT = new AudioFormat(8000f, 16, 1, true, false);

    /**
     * 录制指定秒数的语音并写成 WAV 文件。
     *
     * @param seconds 录制时长（秒）
     * @param outFile 输出的 WAV 文件
     * @return 成功返回文件本身，失败返回 null
     */
    public static File record(int seconds, File outFile) {
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        final AudioCapture capture = new AudioCapture();

        System.out.println("[录音] 开始录制 " + seconds + " 秒，请对着麦克风说话...");
        try {
            capture.start((data, length) -> pcm.write(data, 0, length));
        } catch (Exception e) {
            System.err.println("录音启动失败: " + e.getMessage());
            return null;
        }

        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        capture.stop();

        byte[] data = pcm.toByteArray();
        if (data.length == 0) {
            System.err.println("录音失败：没有采集到数据（请检查系统默认录音设备）");
            return null;
        }

        return writeWav(data, outFile);
    }

    /** 把 PCM 数据写成标准 WAV 文件 */
    public static File writeWav(byte[] pcm, File outFile) {
        try {
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            int frames = pcm.length / FORMAT.getFrameSize();
            AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), FORMAT, frames);
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outFile);
            ais.close();

            System.out.println("[录音] 完成：" + outFile.getName()
                    + "  (" + pcm.length + " 字节, "
                    + String.format("%.1f", pcm.length / 2.0 / 8000.0) + " 秒)");
            return outFile;
        } catch (Exception e) {
            System.err.println("写 WAV 失败: " + e.getMessage());
            return null;
        }
    }

    /** 播放 WAV（阻塞直到播放完毕） */
    public static void play(File wav) {
        if (wav == null || !wav.exists()) {
            System.err.println("语音文件不存在");
            return;
        }
        AudioInputStream ais = null;
        SourceDataLine line = null;
        try {
            ais = AudioSystem.getAudioInputStream(wav);
            AudioFormat fmt = ais.getFormat();
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt);
            line.start();

            byte[] buf = new byte[1600];
            int n;
            while ((n = ais.read(buf)) > 0) {
                line.write(buf, 0, n);
            }
            line.drain();
            System.out.println("[播放] 语音消息播放完毕");
        } catch (Exception e) {
            System.err.println("播放失败: " + e.getMessage());
        } finally {
            try { if (line != null) { line.stop(); line.close(); } } catch (Exception ignored) { }
            try { if (ais != null) ais.close(); } catch (Exception ignored) { }
        }
    }
}

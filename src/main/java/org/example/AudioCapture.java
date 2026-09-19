package org.example;

import javax.sound.sampled.*;

public class AudioCapture {
    private TargetDataLine microphone;
    private AudioFormat format;
    private boolean isRunning = false;
    private AudioCaptureListener listener;

    public interface AudioCaptureListener {
        void onAudioData(byte[] data, int length);
    }

    public AudioCapture() {
        // 8kHz, 16bit, mono, signed, little-endian
        format = new AudioFormat(8000.0f, 16, 1, true, false);
    }

    public void start(AudioCaptureListener listener) throws LineUnavailableException {
        this.listener = listener;

        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            System.err.println("麦克风不支持该音频格式，尝试使用默认格式");
            // 尝试默认格式
            format = new AudioFormat(8000.0f, 16, 1, true, false);
            info = new DataLine.Info(TargetDataLine.class, format);
        }

        microphone = (TargetDataLine) AudioSystem.getLine(info);
        microphone.open(format, 1600); // 200ms buffer
        microphone.start();

        isRunning = true;

        System.out.println("✓ 麦克风已启动");
        System.out.println("  格式: " + format);

        // 采集线程
        new Thread(() -> {
            byte[] buffer = new byte[320]; // 20ms @ 8kHz, 16bit = 160 samples * 2 bytes

            while (isRunning) {
                try {
                    int bytesRead = microphone.read(buffer, 0, buffer.length);
                    if (bytesRead > 0 && listener != null) {
                        listener.onAudioData(buffer, bytesRead);
                    }
                } catch (Exception e) {
                    if (isRunning) {
                        System.err.println("音频采集错误: " + e.getMessage());
                    }
                }
            }
        }, "AudioCapture").start();
    }

    public void stop() {
        isRunning = false;
        if (microphone != null) {
            microphone.stop();
            microphone.close();
        }
        System.out.println("麦克风已停止");
    }
}
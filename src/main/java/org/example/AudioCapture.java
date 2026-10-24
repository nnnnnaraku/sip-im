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
        microphone = openMicrophone();
        microphone.start();

        isRunning = true;

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

    /**
     * 选择真实麦克风，跳过「立体声混音 / Stereo Mix / Loopback」等环回设备。
     *
     * <p>若系统默认录音设备是环回，采集到的会是扬声器输出：
     * 对端播放你的声音 → 被环回采集 → 再发回给你，表现为「实时返听」，
     * 而且静音麦克风也无法消除（因为根本没用麦克风）。
     */
    private TargetDataLine openMicrophone() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            String name = (mixerInfo.getName() + " " + mixerInfo.getDescription()).toLowerCase(java.util.Locale.ROOT);
            if (isLoopbackDevice(name)) continue;
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (!mixer.isLineSupported(info)) continue;
            try {
                TargetDataLine line = (TargetDataLine) mixer.getLine(info);
                line.open(format, 1600);
                System.out.println("✓ 麦克风已启动: " + mixerInfo.getName());
                return line;
            } catch (Exception ignore) {
                // 该设备不支持所需格式或已被占用，继续尝试下一个
            }
        }
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format, 1600);
        System.out.println("✓ 麦克风已启动（系统默认设备）");
        return line;
    }

    private static boolean isLoopbackDevice(String name) {
        return name.contains("stereo mix") || name.contains("立体声混音")
                || name.contains("loopback") || name.contains("what u hear")
                || name.contains("wave out mix") || name.contains("waveout mix");
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
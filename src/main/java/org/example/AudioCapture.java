package org.example;

import javax.sound.sampled.*;

/** Owns one microphone run at a time; a stopped worker never adopts a new line. */
public class AudioCapture {
    interface LineFactory {
        TargetDataLine create(AudioFormat format) throws LineUnavailableException;
    }

    private final AudioFormat format = new AudioFormat(8000.0f, 16, 1, true, false);
    private final LineFactory lineFactory;
    private Run current;

    private static final class Run {
        final TargetDataLine line;
        final AudioCaptureListener listener;
        volatile boolean active = true;
        Thread worker;
        private boolean lineClosed;

        Run(TargetDataLine line, AudioCaptureListener listener) {
            this.line = line;
            this.listener = listener;
        }

        synchronized void close() {
            if (lineClosed) return;
            lineClosed = true;
            closeLine(line);
        }
    }

    public interface AudioCaptureListener {
        void onAudioData(byte[] data, int length);
    }

    public AudioCapture() {
        this(format -> (TargetDataLine) AudioSystem.getLine(
                new DataLine.Info(TargetDataLine.class, format)));
    }

    /** Device-free test seam. A returned line is owned even if open/start fails. */
    AudioCapture(LineFactory lineFactory) {
        if (lineFactory == null) throw new IllegalArgumentException("Microphone factory required");
        this.lineFactory = lineFactory;
    }

    public synchronized void start(AudioCaptureListener listener) throws LineUnavailableException {
        if (current != null) throw new IllegalStateException("Microphone capture already started");
        TargetDataLine opened = null;
        try {
            opened = lineFactory.create(format);
            opened.open(format, 1600);
            opened.start();
            final Run run = new Run(opened, listener);
            run.worker = new Thread(() -> capture(run), "AudioCapture");
            run.worker.setDaemon(true);
            current = run;
            run.worker.start();
        } catch (LineUnavailableException | RuntimeException | Error failure) {
            current = null;
            closeLine(opened);
            throw failure;
        }
        System.out.println("Microphone started: " + format);
    }

    private void capture(Run run) {
        byte[] buffer = new byte[320]; // 20 ms: 160 signed PCM16 samples.
        try {
            while (run.active) {
                int length = run.line.read(buffer, 0, buffer.length);
                if (length > 0 && run.active && run.listener != null) {
                    run.listener.onAudioData(buffer, length);
                }
            }
        } catch (RuntimeException failure) {
            if (run.active) System.err.println("Microphone capture stopped after a device error");
        } finally {
            run.active = false;
            run.close();
            synchronized (this) {
                if (current == run) current = null;
            }
        }
    }

    public void stop() {
        final Run run;
        synchronized (this) {
            run = current;
            if (run == null) return;
            run.active = false;
        }
        run.close(); // Closing releases a driver-blocked read before joining.
        join(run.worker);
        synchronized (this) {
            if (current == run && !run.worker.isAlive()) current = null;
        }
        System.out.println("Microphone stopped");
    }

    private static void closeLine(TargetDataLine line) {
        if (line == null) return;
        try { line.stop(); } catch (RuntimeException ignored) { }
        try { line.flush(); } catch (RuntimeException ignored) { }
        try { line.close(); } catch (RuntimeException ignored) { }
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(1000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}

package org.example;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Realtek 在中文 Windows 上把 Stereo Mix 命名为「主声音捕获器」，必须与英文名一样被过滤。 */
public class AudioCaptureTest {
    @Test
    public void loopbackDevicesAreRejected() {
        assertTrue(AudioCapture.isLoopbackDevice("主声音捕获器 (Realtek(R) Audio)"));
        assertTrue(AudioCapture.isLoopbackDevice("立体声混音 (Realtek High Definition Audio)"));
        assertTrue(AudioCapture.isLoopbackDevice("stereo mix (realtek(r) audio)"));
        assertTrue(AudioCapture.isLoopbackDevice("what u hear"));
        assertTrue(AudioCapture.isLoopbackDevice("wave out mix"));
    }

    @Test
    public void realMicrophonesAreAccepted() {
        assertFalse(AudioCapture.isLoopbackDevice("麦克风 (Realtek(R) Audio)"));
        assertFalse(AudioCapture.isLoopbackDevice("Microphone Array (Intel® Smart Sound)"));
        assertFalse(AudioCapture.isLoopbackDevice("麦克风 (USB2.0 PC CAMERA)"));
    }
}

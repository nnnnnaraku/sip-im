package org.example;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** 会议音频抖动缓冲：欠载时不能推进播放序号，否则会永久静音。 */
public class ConferenceAudioTest {

    private static byte[] frame(int seq) {
        byte[] pcm = new byte[320];
        pcm[0] = (byte) seq;
        return pcm;
    }

    @Test
    public void prebuffersThreeFramesBeforePlaying() {
        ConferenceAudio.PeerBuffer buffer = new ConferenceAudio.PeerBuffer();
        buffer.offer(0, frame(0));
        buffer.offer(1, frame(1));
        assertNull(buffer.next());
        buffer.offer(2, frame(2));
        for (int i = 0; i < 3; i++) {
            byte[] played = buffer.next();
            assertEquals((byte) i, played[0]);
        }
        assertNull(buffer.next());
    }

    @Test
    public void underrunDoesNotAdvancePlaybackSequence() {
        ConferenceAudio.PeerBuffer buffer = new ConferenceAudio.PeerBuffer();
        for (int i = 0; i < 3; i++) buffer.offer(i, frame(i));
        for (int i = 0; i < 3; i++) buffer.next();

        // 队列空了：多个播放时间片都补静音，但不能把序号推到下一帧之前
        assertNull(buffer.next());
        assertNull(buffer.next());
        assertNull(buffer.next());

        buffer.offer(3, frame(3));
        byte[] played = buffer.next();
        assertEquals((byte) 3, played[0]);
    }

    @Test
    public void shortGapWaitsForMissingFrameThenRecovers() {
        ConferenceAudio.PeerBuffer buffer = new ConferenceAudio.PeerBuffer();
        for (int i = 0; i < 3; i++) buffer.offer(i, frame(i));
        for (int i = 0; i < 3; i++) buffer.next();

        buffer.offer(5, frame(5));
        assertNull(buffer.next());

        buffer.offer(3, frame(3));
        buffer.offer(4, frame(4));
        assertEquals((byte) 3, buffer.next()[0]);
        assertEquals((byte) 4, buffer.next()[0]);
        assertEquals((byte) 5, buffer.next()[0]);
    }

    @Test
    public void longGapResyncsToEarliestAvailableFrame() {
        ConferenceAudio.PeerBuffer buffer = new ConferenceAudio.PeerBuffer();
        for (int i = 0; i < 3; i++) buffer.offer(i, frame(i));
        for (int i = 0; i < 3; i++) buffer.next();

        buffer.offer(10, frame(10));
        byte[] played = buffer.next();
        assertNotNull(played);
        assertEquals((byte) 10, played[0]);
        assertNull(buffer.next());
    }
}

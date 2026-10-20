package org.example;

import org.junit.Test;

import static org.junit.Assert.*;

public class G711CodecTest {
    @Test
    public void mapsOnlyImplementedStaticPayloadTypes() {
        assertSame(G711Codec.PCMU, G711Codec.fromPayloadType(0));
        assertSame(G711Codec.PCMA, G711Codec.fromPayloadType(8));
        assertEquals(0, G711Codec.PCMU.getPayloadType());
        assertEquals(8, G711Codec.PCMA.getPayloadType());
        assertEquals("PCMU", G711Codec.PCMU.getRtpName());
        assertEquals("PCMA", G711Codec.PCMA.getRtpName());
        for (int payload = -1; payload <= 128; payload++) {
            if (payload != 0 && payload != 8) assertNull(G711Codec.fromPayloadType(payload));
        }
    }

    @Test
    public void encodesOnlyRequestedSliceIncludingOddByteOffset() {
        byte[] surrounding = {99, (byte) 0xE8, 3, 77, 88}; // sample 1000 starts at byte 1
        assertArrayEquals(new byte[]{(byte) 0xCE}, G711Codec.PCMU.encode(surrounding, 1, 2));
        assertArrayEquals(new byte[]{(byte) 0xFA}, G711Codec.PCMA.encode(surrounding, 1, 2));
        assertArrayEquals(new byte[]{(byte) 0xCE}, PCMUEncoder.encode(surrounding, 1, 2));
        assertArrayEquals(new byte[]{(byte) 0xFA}, PCMAEncoder.encode(surrounding, 1, 2));
        assertArrayEquals(new byte[]{99, (byte) 0xE8, 3, 77, 88}, surrounding);
    }

    @Test
    public void dispatchesBothEncodeAndDecode() {
        assertArrayEquals(new byte[]{(byte) 0xCE}, G711Codec.PCMU.encode(G711Reference.pcm(1000)));
        assertArrayEquals(new byte[]{(byte) 0xFA}, G711Codec.PCMA.encode(G711Reference.pcm(1000)));
        assertArrayEquals(G711Reference.pcm(988), G711Codec.PCMU.decode(new byte[]{(byte) 0xCE}));
        assertArrayEquals(G711Reference.pcm(1008), G711Codec.PCMA.decode(new byte[]{(byte) 0xFA}));
    }

    @Test
    public void acceptsEmptyBuffersAndEmptySliceAtEnd() {
        for (G711Codec codec : G711Codec.values()) {
            assertArrayEquals(new byte[0], codec.encode(new byte[0]));
            assertArrayEquals(new byte[0], codec.decode(new byte[0]));
            assertArrayEquals(new byte[0], codec.encode(new byte[2], 2, 0));
        }
    }

    @Test
    public void rejectsNullIncompleteSamplesAndInvalidRanges() {
        for (G711Codec codec : G711Codec.values()) {
            assertThrows(NullPointerException.class, () -> codec.encode(null));
            assertThrows(NullPointerException.class, () -> codec.encode(null, 0, 0));
            assertThrows(NullPointerException.class, () -> codec.decode(null));
            assertThrows(IllegalArgumentException.class, () -> codec.encode(new byte[3]));
            assertThrows(IllegalArgumentException.class, () -> codec.encode(new byte[4], 0, 3));
            assertThrows(IndexOutOfBoundsException.class, () -> codec.encode(new byte[4], -1, 2));
            assertThrows(IndexOutOfBoundsException.class, () -> codec.encode(new byte[4], 0, -2));
            assertThrows(IndexOutOfBoundsException.class, () -> codec.encode(new byte[4], 4, 2));
            assertThrows(IndexOutOfBoundsException.class, () -> codec.encode(new byte[4], Integer.MAX_VALUE, 2));
            assertThrows(IndexOutOfBoundsException.class, () -> codec.encode(new byte[4], 2, Integer.MAX_VALUE));
        }
        assertThrows(NullPointerException.class, () -> PCMUEncoder.encode(null));
        assertThrows(NullPointerException.class, () -> PCMAEncoder.encode(null));
    }
}

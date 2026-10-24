package org.example;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class PCMAEncoderTest {
    @Test
    public void knownEncodingVectorsIncludeSignAndSegmentBoundaries() {
        int[] samples = {-32768, -1000, -17, -16, -1, 0, 15, 16, 1000, 32767};
        byte[] expected = {0x2A, 0x7A, 0x54, 0x55, 0x55, (byte) 0xD5,
                (byte) 0xD5, (byte) 0xD4, (byte) 0xFA, (byte) 0xAA};
        assertArrayEquals(expected, PCMAEncoder.encode(G711Reference.pcm(samples)));
    }

    @Test
    public void knownDecodeVectorsDoNotDependOnEncoder() {
        byte[] codes = {0, (byte) 0x80, (byte) 0xD5, 0x55, (byte) 0xFA, 0x7A, (byte) 0xAA, 0x2A};
        assertArrayEquals(G711Reference.pcm(-5504, 5504, 8, -8, 1008, -1008, 32256, -32256),
                PCMAEncoder.decode(codes));
    }

    @Test
    public void independentDecodeOracleAlsoMatchesFixedVectors() {
        int[] codes = {0x00, 0x80, 0xD5, 0x55, 0xD4, 0x54, 0xFA, 0x7A, 0xAA, 0x2A};
        int[] samples = {-5504, 5504, 8, -8, 24, -24, 1008, -1008, 32256, -32256};
        for (int i = 0; i < codes.length; i++) {
            assertEquals("Independent A-law oracle at code " + codes[i],
                    samples[i], G711Reference.decodeA(codes[i]));
        }
    }

    @Test
    public void allPcm16ValuesMatchIndependentReference() {
        byte[] pcm = new byte[65536 * 2];
        byte[] expected = new byte[65536];
        for (int i = 0; i < expected.length; i++) {
            int sample = i - 32768;
            pcm[2 * i] = (byte) sample;
            pcm[2 * i + 1] = (byte) (sample >> 8);
            expected[i] = (byte) G711Reference.encodeA(sample);
        }
        assertArrayEquals(expected, PCMAEncoder.encode(pcm));
    }

    @Test
    public void allWireCodesMatchIndependentReference() {
        byte[] codes = new byte[256];
        int[] expected = new int[256];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = (byte) i;
            expected[i] = G711Reference.decodeA(i);
        }
        assertArrayEquals(G711Reference.pcm(expected), PCMAEncoder.decode(codes));
    }
}

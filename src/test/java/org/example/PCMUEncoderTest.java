package org.example;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class PCMUEncoderTest {
    @Test
    public void knownEncodingVectorsIncludeQuietAndFullScaleSamples() {
        int[] samples = {-32768, -32636, -1000, -5, -4, -1, 0, 1, 3, 4, 1000, 32635, 32767};
        byte[] expected = {0, 0, 0x4E, 0x7E, 0x7F, 0x7F, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFE, (byte) 0xCE, (byte) 0x80, (byte) 0x80};
        assertArrayEquals(expected, PCMUEncoder.encode(G711Reference.pcm(samples)));
    }

    @Test
    public void knownDecodeVectorsDoNotDependOnEncoder() {
        byte[] codes = {0, (byte) 0x80, 0x7F, (byte) 0xFF, (byte) 0xCE, 0x4E};
        assertArrayEquals(G711Reference.pcm(-32124, 32124, 0, 0, 988, -988), PCMUEncoder.decode(codes));
    }

    @Test
    public void allPcm16ValuesMatchIndependentReference() {
        byte[] pcm = new byte[65536 * 2];
        byte[] expected = new byte[65536];
        for (int i = 0; i < expected.length; i++) {
            int sample = i - 32768;
            pcm[2 * i] = (byte) sample;
            pcm[2 * i + 1] = (byte) (sample >> 8);
            expected[i] = (byte) G711Reference.encodeU(sample);
        }
        assertArrayEquals(expected, PCMUEncoder.encode(pcm));
    }

    @Test
    public void allWireCodesMatchIndependentReference() {
        byte[] codes = new byte[256];
        int[] expected = new int[256];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = (byte) i;
            expected[i] = G711Reference.decodeU(i);
        }
        assertArrayEquals(G711Reference.pcm(expected), PCMUEncoder.decode(codes));
    }
}

package org.example;

import java.util.Objects;

/** G.711 mu-law (PCMU), using signed 16-bit little-endian PCM. */
public final class PCMUEncoder {
    private static final int BIAS = 0x84;

    private PCMUEncoder() {
    }

    public static byte[] encode(byte[] pcmData) {
        Objects.requireNonNull(pcmData, "pcmData");
        return encode(pcmData, 0, pcmData.length);
    }

    /** Encodes exactly the requested PCM slice; length must contain complete samples. */
    public static byte[] encode(byte[] pcmData, int offset, int length) {
        G711Codec.checkPcmRange(pcmData, offset, length);
        byte[] result = new byte[length / 2];
        for (int i = 0; i < result.length; i++) {
            int at = offset + i * 2;
            int sample = (short) ((pcmData[at] & 0xFF) | (pcmData[at + 1] << 8));
            int mask = sample < 0 ? 0x7F : 0xFF;
            // The -1 on negative samples follows the ITU bit-exact convention.
            int magnitude = sample < 0 ? BIAS - sample - 1 : BIAS + sample;
            magnitude = Math.min(magnitude, 0x7FFF);
            int segment = 0;
            for (int limit = 0xFF; magnitude > limit; limit = (limit << 1) | 1) {
                segment++;
            }
            int quantization = (magnitude >> (segment + 3)) & 0x0F;
            result[i] = (byte) (((segment << 4) | quantization) ^ mask);
        }
        return result;
    }

    public static byte[] decode(byte[] mulawData) {
        Objects.requireNonNull(mulawData, "mulawData");
        byte[] pcm = new byte[mulawData.length * 2];
        for (int i = 0; i < mulawData.length; i++) {
            int code = (~mulawData[i]) & 0xFF;
            int magnitude = (((code & 0x0F) << 3) + BIAS) << ((code >> 4) & 7);
            int sample = (code & 0x80) != 0 ? BIAS - magnitude : magnitude - BIAS;
            pcm[i * 2] = (byte) sample;
            pcm[i * 2 + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }
}

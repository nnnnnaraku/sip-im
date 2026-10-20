package org.example;

import java.util.Objects;

/** G.711 A-law (PCMA), using signed 16-bit little-endian PCM. */
public final class PCMAEncoder {
    private PCMAEncoder() {
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
            int mask = sample >= 0 ? 0xD5 : 0x55;
            int magnitude = sample >= 0 ? sample : -sample - 1;
            int segment = 0;
            for (int limit = 0xFF; magnitude > limit; limit = (limit << 1) | 1) {
                segment++;
            }
            int shift = segment == 0 ? 4 : segment + 3;
            int quantization = (magnitude >> shift) & 0x0F;
            result[i] = (byte) (((segment << 4) | quantization) ^ mask);
        }
        return result;
    }

    public static byte[] decode(byte[] alawData) {
        Objects.requireNonNull(alawData, "alawData");
        byte[] pcm = new byte[alawData.length * 2];
        for (int i = 0; i < alawData.length; i++) {
            int code = (alawData[i] & 0xFF) ^ 0x55;
            int segment = (code >> 4) & 7;
            int magnitude = (code & 0x0F) << 4;
            magnitude = segment == 0 ? magnitude + 8 : (magnitude + 0x108) << (segment - 1);
            int sample = (code & 0x80) != 0 ? magnitude : -magnitude;
            pcm[i * 2] = (byte) sample;
            pcm[i * 2 + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }
}

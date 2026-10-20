package org.example;

/**
 * Independent arithmetic oracle, never calling the production codecs.
 * Reference: Steve Underwood's public-domain SpanDSP G.711 formulae, including
 * WebRTC's ITU bit-exact negative-sample corrections, as published at:
 * https://chromium.googlesource.com/external/webrtc/stable/webrtc/+/master/modules/audio_coding/codecs/g711/g711.h
 * This Java oracle expresses the formulae using leading-bit position and
 * multiplication, instead of the production implementation's segment search.
 * It is checked against explicit wire/sample vectors in the tests as well.
 */
final class G711Reference {
    private G711Reference() {
    }

    static int encodeU(int sample) {
        int biased = sample < 0 ? 131 - sample : 132 + sample;
        int segment = 24 - Integer.numberOfLeadingZeros(biased | 255);
        if (segment >= 8) return sample < 0 ? 0 : 128;
        int compressed = 16 * segment + ((biased / (1 << (segment + 3))) % 16);
        return compressed ^ (sample < 0 ? 127 : 255);
    }

    static int encodeA(int sample) {
        int magnitude = sample < 0 ? -sample - 1 : sample;
        int segment = 24 - Integer.numberOfLeadingZeros(magnitude | 255);
        int step = segment == 0 ? 16 : 1 << (segment + 3);
        return (16 * segment + ((magnitude / step) % 16)) ^ (sample < 0 ? 85 : 213);
    }

    static int decodeU(int encoded) {
        int compressed = 255 - encoded;
        int magnitude = (132 + 8 * (compressed % 16)) * (1 << ((compressed / 16) % 8)) - 132;
        return compressed >= 128 ? -magnitude : magnitude;
    }

    static int decodeA(int encoded) {
        int compressed = encoded ^ 85;
        int segment = (compressed / 16) % 8;
        int magnitude = 16 * (compressed % 16) + 8;
        // Start at 16*q + 8, then add 256 to obtain the nonzero-segment bias 264.
        if (segment != 0) magnitude = (magnitude + 256) * (1 << (segment - 1));
        return compressed >= 128 ? magnitude : -magnitude;
    }

    static byte[] pcm(int... samples) {
        byte[] result = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            result[2 * i] = (byte) samples[i];
            result[2 * i + 1] = (byte) (samples[i] >> 8);
        }
        return result;
    }
}

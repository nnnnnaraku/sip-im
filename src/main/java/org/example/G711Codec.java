package org.example;

import java.util.Objects;

/** The G.711 codecs implemented by this client (RFC 3551 static RTP mappings). */
public enum G711Codec {
    PCMU(0, "PCMU"),
    PCMA(8, "PCMA");

    private final int payloadType;
    private final String rtpName;

    G711Codec(int payloadType, String rtpName) {
        this.payloadType = payloadType;
        this.rtpName = rtpName;
    }

    public int getPayloadType() {
        return payloadType;
    }

    public String getRtpName() {
        return rtpName;
    }

    public byte[] encode(byte[] pcm) {
        Objects.requireNonNull(pcm, "pcm");
        return encode(pcm, 0, pcm.length);
    }

    public byte[] encode(byte[] pcm, int offset, int length) {
        return this == PCMU ? PCMUEncoder.encode(pcm, offset, length)
                : PCMAEncoder.encode(pcm, offset, length);
    }

    public byte[] decode(byte[] encoded) {
        return this == PCMU ? PCMUEncoder.decode(encoded) : PCMAEncoder.decode(encoded);
    }

    /** Unknown payload types must be rejected by the caller rather than decoded as audio. */
    public static G711Codec fromPayloadType(int payloadType) {
        for (G711Codec codec : values()) {
            if (codec.payloadType == payloadType) return codec;
        }
        return null;
    }

    static void checkPcmRange(byte[] pcm, int offset, int length) {
        Objects.requireNonNull(pcm, "pcm");
        if (offset < 0 || length < 0 || offset > pcm.length - length) {
            throw new IndexOutOfBoundsException("Invalid PCM slice: offset=" + offset + ", length=" + length);
        }
        if ((length & 1) != 0) {
            throw new IllegalArgumentException("PCM16 length must be even");
        }
    }
}

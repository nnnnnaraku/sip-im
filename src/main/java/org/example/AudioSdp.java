package org.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Negotiates the single, bidirectional IPv4 RTP/AVP audio stream supported here.
 * Only G.711 at 8000 Hz, mono is implemented. Payload 101 is reserved for the
 * project's legacy telephone-event convention and is never treated as speech.
 */
public final class AudioSdp {
    private static final Pattern RTPMAP = Pattern.compile(
            "^a=rtpmap:(\\d+)\\s+([^\\s/]+)/([0-9]+)(?:/([0-9]+))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile(
            "([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})");

    private AudioSdp() { }

    /** Accepts static G.711 payloads and explicitly mapped dynamic payloads. */
    public static NegotiatedAudio negotiateOffer(String sdp) {
        return negotiate(sdp, false);
    }

    /**
     * Our offer contains only static payloads 0 and 8. Extra unknown formats and
     * legacy DTMF 101 are ignored, but an answer cannot introduce dynamic G.711.
     */
    public static NegotiatedAudio negotiateAnswer(String sdp) {
        return negotiate(sdp, true);
    }

    public static String offerMedia(int localPort) {
        Map<Integer, G711Codec> codecs = new LinkedHashMap<>();
        codecs.put(0, G711Codec.PCMU);
        codecs.put(8, G711Codec.PCMA);
        return media(localPort, codecs);
    }

    /** Answers with every supported format, in the peer's preference order. */
    public static String answerMedia(int localPort, NegotiatedAudio result) {
        if (result == null) throw new IllegalArgumentException("Audio negotiation is required");
        return media(localPort, result.getReceiveCodecs());
    }

    private static NegotiatedAudio negotiate(String sdp, boolean answer) {
        ParsedAudio parsed = parse(sdp);
        Map<Integer, G711Codec> supported = new LinkedHashMap<>();
        for (int payload : parsed.payloads) {
            if (payload == 101) continue;
            G711Codec codec = codecFor(payload, parsed.mappings);
            if (codec == null) continue;
            if (answer && payload != 0 && payload != 8) {
                throw new IllegalArgumentException("Answer introduced an unoffered G.711 payload: " + payload);
            }
            supported.put(payload, codec);
        }
        if (supported.isEmpty()) {
            throw new IllegalArgumentException("No common G.711/8000 mono audio format");
        }
        return new NegotiatedAudio(parsed.address, parsed.port, supported);
    }

    private static G711Codec codecFor(int payload, Map<Integer, Mapping> mappings) {
        Mapping explicit = mappings.get(payload);
        if (payload == 0 || payload == 8) {
            G711Codec expected = payload == 0 ? G711Codec.PCMU : G711Codec.PCMA;
            // An explicit conflicting/unsupported mapping must not fall back to static PT semantics.
            return explicit == null ? expected : (explicit.codec == expected ? expected : null);
        }
        return payload >= 96 && payload <= 127 && explicit != null ? explicit.codec : null;
    }

    private static ParsedAudio parse(String sdp) {
        if (sdp == null || sdp.trim().isEmpty()) {
            throw new IllegalArgumentException("Remote SDP is empty");
        }
        String sessionConnection = null;
        String audioConnection = null;
        String sessionDirection = null;
        String audioDirection = null;
        String currentMedia = null;
        ParsedAudio audio = null;
        for (String raw : sdp.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("m=")) {
                String[] fields = line.substring(2).trim().split("\\s+");
                currentMedia = fields[0];
                if ("audio".equals(currentMedia)) {
                    if (audio != null) throw new IllegalArgumentException("Multiple audio streams are unsupported");
                    if (fields.length < 4) throw new IllegalArgumentException("Malformed audio media line");
                    if (!"RTP/AVP".equalsIgnoreCase(fields[2])) {
                        throw new IllegalArgumentException("Only RTP/AVP audio is supported");
                    }
                    audio = new ParsedAudio();
                    audio.port = port(fields[1]);
                    for (int i = 3; i < fields.length; i++) {
                        int payload = number(fields[i], 0, 127, "payload type");
                        if (audio.payloads.contains(payload)) {
                            throw new IllegalArgumentException("Duplicate audio payload type: " + payload);
                        }
                        audio.payloads.add(payload);
                    }
                }
            } else if (line.startsWith("c=")) {
                if (currentMedia == null) {
                    if (sessionConnection != null) throw new IllegalArgumentException("Multiple session connections");
                    sessionConnection = line.substring(2).trim();
                } else if ("audio".equals(currentMedia)) {
                    if (audioConnection != null) throw new IllegalArgumentException("Multiple audio connections");
                    audioConnection = line.substring(2).trim();
                }
            } else if (isDirection(line)) {
                String direction = line.substring(2).toLowerCase(Locale.ROOT);
                if (currentMedia == null) {
                    sessionDirection = direction(sessionDirection, direction);
                } else if ("audio".equals(currentMedia)) {
                    audioDirection = direction(audioDirection, direction);
                }
            } else if ("audio".equals(currentMedia)
                    && line.toLowerCase(Locale.ROOT).startsWith("a=rtpmap:")) {
                Matcher matcher = RTPMAP.matcher(line);
                if (!matcher.matches()) throw new IllegalArgumentException("Malformed audio rtpmap");
                int payload = number(matcher.group(1), 0, 127, "rtpmap payload type");
                int rate = number(matcher.group(3), 1, Integer.MAX_VALUE, "clock rate");
                int channels = matcher.group(4) == null ? 1
                        : number(matcher.group(4), 1, Integer.MAX_VALUE, "channel count");
                Mapping mapping = new Mapping(matcher.group(2), rate, channels);
                Mapping previous = audio.mappings.get(payload);
                if (previous != null && !previous.equals(mapping)) {
                    throw new IllegalArgumentException("Conflicting rtpmap entries for payload: " + payload);
                }
                audio.mappings.put(payload, mapping);
            }
        }
        if (audio == null) throw new IllegalArgumentException("Remote SDP has no audio stream");
        String effectiveDirection = audioDirection == null ? sessionDirection : audioDirection;
        if (effectiveDirection != null && !"sendrecv".equals(effectiveDirection)) {
            throw new IllegalArgumentException("Only bidirectional sendrecv audio is supported");
        }
        audio.address = address(audioConnection == null ? sessionConnection : audioConnection);
        return audio;
    }

    private static boolean isDirection(String line) {
        String value = line.toLowerCase(Locale.ROOT);
        return "a=sendrecv".equals(value) || "a=sendonly".equals(value)
                || "a=recvonly".equals(value) || "a=inactive".equals(value);
    }

    private static String direction(String previous, String value) {
        if (previous != null && !previous.equals(value)) {
            throw new IllegalArgumentException("Conflicting audio directions");
        }
        return value;
    }

    private static String address(String connection) {
        if (connection == null) throw new IllegalArgumentException("Audio connection address is missing");
        String[] fields = connection.split("\\s+");
        if (fields.length != 3 || !"IN".equalsIgnoreCase(fields[0])
                || !"IP4".equalsIgnoreCase(fields[1])) {
            throw new IllegalArgumentException("Only a single IPv4 audio connection is supported");
        }
        Matcher match = IPV4.matcher(fields[2]);
        if (!match.matches()) throw new IllegalArgumentException("Audio address must be an IPv4 literal");
        int[] octets = new int[4];
        for (int i = 0; i < octets.length; i++) octets[i] = number(match.group(i + 1), 0, 255, "IPv4 octet");
        if (octets[0] == 0 || octets[0] >= 224) {
            throw new IllegalArgumentException("Audio address must be a unicast IPv4 endpoint");
        }
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }

    private static int port(String value) {
        String[] fields = value.split("/", -1);
        if (fields.length > 2 || (fields.length == 2 && !"1".equals(fields[1]))) {
            throw new IllegalArgumentException("Multiple audio ports are unsupported");
        }
        return number(fields[0], 1, 65535, "audio port");
    }

    private static int number(String text, int min, int max, String label) {
        if (!text.matches("[0-9]+")) throw new IllegalArgumentException("Invalid " + label);
        try {
            int value = Integer.parseInt(text);
            if (value < min || value > max) throw new IllegalArgumentException("Invalid " + label);
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label, e);
        }
    }

    private static String media(int localPort, Map<Integer, G711Codec> codecs) {
        number(String.valueOf(localPort), 1, 65535, "local audio port");
        StringBuilder out = new StringBuilder("m=audio ").append(localPort).append(" RTP/AVP");
        for (Integer payload : codecs.keySet()) out.append(' ').append(payload);
        out.append("\r\n");
        for (Map.Entry<Integer, G711Codec> entry : codecs.entrySet()) {
            out.append("a=rtpmap:").append(entry.getKey()).append(' ')
                    .append(entry.getValue().getRtpName()).append("/8000\r\n");
        }
        return out.append("a=sendrecv\r\n").toString();
    }

    private static final class ParsedAudio {
        String address;
        int port;
        final List<Integer> payloads = new ArrayList<>();
        final Map<Integer, Mapping> mappings = new LinkedHashMap<>();
    }

    private static final class Mapping {
        final String name;
        final int rate;
        final int channels;
        final G711Codec codec;

        Mapping(String name, int rate, int channels) {
            this.name = name.toUpperCase(Locale.ROOT);
            this.rate = rate;
            this.channels = channels;
            this.codec = rate != 8000 || channels != 1 ? null
                    : ("PCMU".equals(this.name) ? G711Codec.PCMU
                    : ("PCMA".equals(this.name) ? G711Codec.PCMA : null));
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Mapping)) return false;
            Mapping mapping = (Mapping) other;
            return rate == mapping.rate && channels == mapping.channels && name.equals(mapping.name);
        }

        @Override public int hashCode() { return Objects.hash(name, rate, channels); }
    }

    public static final class NegotiatedAudio {
        private final G711Codec codec;
        private final int payloadType;
        private final String remoteAddress;
        private final int remotePort;
        private final Map<Integer, G711Codec> receiveCodecs;

        private NegotiatedAudio(String address, int port, Map<Integer, G711Codec> codecs) {
            Map.Entry<Integer, G711Codec> first = codecs.entrySet().iterator().next();
            codec = first.getValue();
            payloadType = first.getKey();
            remoteAddress = address;
            remotePort = port;
            receiveCodecs = Collections.unmodifiableMap(new LinkedHashMap<>(codecs));
        }

        public G711Codec getCodec() { return codec; }
        public int getPayloadType() { return payloadType; }
        public String getRemoteAddress() { return remoteAddress; }
        public int getRemotePort() { return remotePort; }
        public Map<Integer, G711Codec> getReceiveCodecs() { return receiveCodecs; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof NegotiatedAudio)) return false;
            NegotiatedAudio audio = (NegotiatedAudio) other;
            return codec == audio.codec && payloadType == audio.payloadType
                    && remotePort == audio.remotePort && remoteAddress.equals(audio.remoteAddress)
                    && receiveCodecs.equals(audio.receiveCodecs);
        }

        @Override public int hashCode() {
            return Objects.hash(codec, payloadType, remoteAddress, remotePort, receiveCodecs);
        }

        @Override public String toString() {
            return "Audio[" + codec.getRtpName() + "/8000, PT=" + payloadType + ", remote="
                    + remoteAddress + ':' + remotePort + ", receive=" + receiveCodecs + ']';
        }
    }
}

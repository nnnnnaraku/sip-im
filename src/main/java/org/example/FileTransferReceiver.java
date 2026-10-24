package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded, sender-isolated assembler for the existing #FILE wire format. No I/O until complete. */
public final class FileTransferReceiver implements AutoCloseable {
    public static final int MAX_BODY_BYTES = 8192;
    public static final int MAX_CHUNK_CHARACTERS = 4096;
    public static final int MAX_CHUNKS = 32768;
    public static final int MAX_ACTIVE_TRANSFERS = 8;
    /** Actual UTF-16 character storage budget; ASCII Base64 uses two bytes per Java char. */
    public static final long MAX_BUFFER_BYTES = 64L * 1024 * 1024;
    public static final long MAX_TERMINAL_BYTES = 16L * 1024 * 1024;
    private static final long CHUNK_OVERHEAD = 128L;
    private static final long HASH_OVERHEAD = 160L;
    private static final long TERMINAL_OVERHEAD = 512L;
    public static final long IDLE_TIMEOUT_MILLIS = 120000L;
    public static final long ABSOLUTE_TIMEOUT_MILLIS = 3600000L;
    public static final int MAX_TERMINAL_IDS = 256;

    static final class Limits {
        final long fileBytes, bufferBytes, terminalBytes, idleMillis, absoluteMillis;
        final int chunks, chunkChars, active, terminal;
        Limits(long fileBytes, int chunks, int chunkChars, int active, long bufferBytes,
               int terminal, long terminalBytes, long idleMillis, long absoluteMillis) {
            if (fileBytes < 0 || fileBytes > ReceivedFileStore.MAX_FILE_BYTES || chunks < 1
                    || chunks > MAX_CHUNKS || chunkChars < 4 || chunkChars > MAX_CHUNK_CHARACTERS
                    || active < 1 || terminal < active || bufferBytes < 0 || terminalBytes < TERMINAL_OVERHEAD || idleMillis < 1
                    || absoluteMillis < idleMillis) throw new IllegalArgumentException("Invalid receiver limits");
            this.fileBytes = fileBytes; this.chunks = chunks; this.chunkChars = chunkChars;
            this.active = active; this.bufferBytes = bufferBytes; this.terminal = terminal; this.terminalBytes = terminalBytes;
            this.idleMillis = idleMillis; this.absoluteMillis = absoluteMillis;
        }
    }

    public static final class Result {
        private final int sipStatus;
        private final boolean complete;
        private final ReceivedFileStore.StoredFile storedFile;
        private final String type, error;
        private Result(int status, boolean complete, ReceivedFileStore.StoredFile file, String type, String error) {
            this.sipStatus = status; this.complete = complete; this.storedFile = file;
            this.type = type; this.error = error;
        }
        public int getSipStatus() { return sipStatus; }
        public boolean isComplete() { return complete; }
        public ReceivedFileStore.StoredFile getStoredFile() { return storedFile; }
        public String getType() { return type; }
        public String getError() { return error; }
    }

    private static final class Key {
        final String peer, id;
        Key(String peer, String id) { this.peer = peer; this.id = id; }
        @Override public boolean equals(Object o) {
            return o instanceof Key && peer.equals(((Key) o).peer) && id.equals(((Key) o).id);
        }
        @Override public int hashCode() { return 31 * peer.hashCode() + id.hashCode(); }
    }
    private static final class Transfer {
        final String type, name, name64;
        final int total;
        final long created;
        long lastActivity, characters, chargedBytes;
        final Map<Integer, String> chunks = new HashMap<Integer, String>();
        final Map<Integer, byte[]> fingerprints = new HashMap<Integer, byte[]>();
        long reservation() { return TERMINAL_OVERHEAD + (long) total * HASH_OVERHEAD; }
        Transfer(String type, String name, String name64, int total, long now) {
            this.type = type; this.name = name; this.name64 = name64; this.total = total;
            this.created = now; this.lastActivity = now;
        }
        boolean metadata(String type, String name64, int total) {
            return this.type.equals(type) && this.name64.equals(name64) && this.total == total;
        }
    }
    private static final class Terminal {
        final int status;
        final String error, type, name64;
        final int total;
        final long created;
        final long reservation;
        final Map<Integer, byte[]> fingerprints;
        Terminal(int status, String error, Transfer transfer, long now) {
            this.status = status; this.error = error; this.created = now;
            this.fingerprints = status == 200 ? transfer.fingerprints : null;
            this.reservation = status == 200 ? transfer.reservation() : TERMINAL_OVERHEAD;
            this.type = transfer == null ? null : transfer.type;
            this.name64 = transfer == null ? null : transfer.name64;
            this.total = transfer == null ? 0 : transfer.total;
        }
    }

    private final ReceivedFileStore store;
    private final Limits limits;
    private final LongSupplier clock;
    private final Map<Key, Transfer> active = new LinkedHashMap<Key, Transfer>();
    private final Map<Key, Terminal> terminal = new LinkedHashMap<Key, Terminal>();
    private long bufferBytes, reservedTerminalBytes;
    private boolean closed;

    public FileTransferReceiver(ReceivedFileStore store) {
        this(store, new Limits(ReceivedFileStore.MAX_FILE_BYTES, MAX_CHUNKS, MAX_CHUNK_CHARACTERS,
                MAX_ACTIVE_TRANSFERS, MAX_BUFFER_BYTES, MAX_TERMINAL_IDS, MAX_TERMINAL_BYTES,
                IDLE_TIMEOUT_MILLIS, ABSOLUTE_TIMEOUT_MILLIS), System::currentTimeMillis);
    }
    FileTransferReceiver(ReceivedFileStore store, Limits limits, LongSupplier clock) {
        if (store == null || limits == null || clock == null) throw new IllegalArgumentException("Missing receiver dependency");
        this.store = store; this.limits = limits; this.clock = clock;
    }

    public synchronized Result accept(String peer, String body) {
        if (closed) return failure(503, "RECEIVER_CLOSED");
        long now = clock.getAsLong();
        expireAt(now);
        if (!identifier(peer)) return failure(400, "INVALID_SENDER");
        if (body == null || body.length() > MAX_BODY_BYTES
                || body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return rejectOversized(peer, body);
        }
        String[] parts = body.split("\\|", 7);
        Key key = parts.length >= 3 && identifier(parts[2]) ? new Key(peer, parts[2]) : null;
        if (parts.length != 7 || !"#FILE".equals(parts[0]) || key == null)
            return reject(key, 400, "INVALID_FILE_FRAME", now);
        String type = parts[1], name64 = parts[3], chunk = parts[6];
        int index, total;
        try {
            if (!parts[4].matches("0|[1-9][0-9]{0,9}") || !parts[5].matches("[1-9][0-9]{0,9}"))
                return reject(key, 400, "INVALID_CHUNK_INDEX", now);
            index = Integer.parseInt(parts[4]); total = Integer.parseInt(parts[5]);
        } catch (NumberFormatException ex) { return reject(key, 400, "INVALID_CHUNK_INDEX", now); }
        if (!"IMG".equals(type) && !"VOICE".equals(type) && !"VIDEO".equals(type))
            return reject(key, 400, "UNSUPPORTED_FILE_TYPE", now);
        if (total > limits.chunks) return reject(key, 413, "TOO_MANY_CHUNKS", now);
        if (index >= total) return reject(key, 400, "INVALID_CHUNK_INDEX", now);
        if (chunk.length() > limits.chunkChars) return reject(key, 413, "CHUNK_TOO_LARGE", now);
        if (chunk.isEmpty() && (total != 1 || index != 0)) return reject(key, 400, "EMPTY_NONFINAL_FILE_CHUNK", now);
        if (!validChunk(chunk, index == total - 1)) return reject(key, 400, "INVALID_BASE64_DATA", now);
        String name;
        try {
            if (name64.length() > 2048) return reject(key, 413, "FILENAME_TOO_LARGE", now);
            byte[] nameBytes = decodeCanonical(name64);
            name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(nameBytes)).toString();
            ReceivedFileStore.validateOriginalName(name);
        } catch (CharacterCodingException | IllegalArgumentException ex) {
            return reject(key, 400, "INVALID_FILENAME", now);
        }
        Terminal ended = terminal.get(key);
        if (ended != null) {
            if (ended.status == 200 && (!ended.type.equals(type) || !ended.name64.equals(name64) || ended.total != total))
                return failure(409, "COMPLETED_METADATA_CONFLICT");
            if (ended.status == 200 && !MessageDigest.isEqual(ended.fingerprints.get(index), fingerprint(chunk)))
                return failure(409, "COMPLETED_CHUNK_CONFLICT");
            return ended.status == 200 ? accepted(type) : failure(ended.status, ended.error);
        }
        Transfer transfer = active.get(key);
        if (transfer == null) {
            // Reserve one terminal slot for every active transfer. Never evict unexpired IDs.
            if (active.size() >= limits.active || active.size() + terminal.size() >= limits.terminal)
                return failure(507, "TRANSFER_CAPACITY_EXCEEDED");
            long reservation = TERMINAL_OVERHEAD + (long) total * HASH_OVERHEAD;
            if (reservedTerminalBytes + reservation > limits.terminalBytes)
                return failure(507, "DEDUP_CAPACITY_EXCEEDED");
            transfer = new Transfer(type, name, name64, total, now);
            reservedTerminalBytes += reservation;
            active.put(key, transfer);
        } else if (!transfer.metadata(type, name64, total)) {
            return reject(key, 409, "FILE_METADATA_CONFLICT", now);
        }
        String previous = transfer.chunks.get(index);
        if (previous != null) {
            if (!previous.equals(chunk)) return reject(key, 409, "CHUNK_CONTENT_CONFLICT", now);
            // Duplicate packets do not extend the idle or absolute lifetime.
            return accepted(type);
        }
        long nextCharacters = transfer.characters + chunk.length();
        long maxCharacters = ((limits.fileBytes + 2L) / 3L) * 4L;
        if (nextCharacters > maxCharacters) return reject(key, 413, "FILE_TOO_LARGE", now);
        long charge = 2L * chunk.length() + CHUNK_OVERHEAD;
        if (bufferBytes + charge > limits.bufferBytes)
            return reject(key, 507, "BUFFER_CAPACITY_EXCEEDED", now);
        transfer.chunks.put(index, chunk);
        transfer.fingerprints.put(index, fingerprint(chunk));
        transfer.chargedBytes += charge;
        transfer.characters = nextCharacters;
        transfer.lastActivity = now;
        bufferBytes += charge;
        if (transfer.chunks.size() != transfer.total) return accepted(type);
        try {
            long decodedLength = decodedLength(transfer);
            if (decodedLength > limits.fileBytes) return reject(key, 413, "FILE_TOO_LARGE", now);
            try (InputStream source = new ChunkInputStream(transfer);
                 InputStream decoded = Base64.getDecoder().wrap(source)) {
                ReceivedFileStore.StoredFile saved = store.save(transfer.name, decoded);
                removeActive(key);
                Terminal done = new Terminal(200, null, transfer, now);
                terminal.put(key, done);
                reservedTerminalBytes += done.reservation;
                return new Result(200, true, saved, type, null);
            }
        } catch (IllegalArgumentException ex) {
            return reject(key, 400, "INVALID_BASE64_DATA", now);
        } catch (ReceivedFileStore.SizeLimitException ex) {
            return reject(key, 413, "FILE_TOO_LARGE", now);
        } catch (IOException ex) {
            return reject(key, 507, "FILE_STORAGE_FAILED", now);
        } catch (RuntimeException ex) {
            return reject(key, 500, "FILE_RECEIVE_FAILED", now);
        }
    }

    /** Use only a bounded prefix to release an identified transfer before decoding an oversized body. */
    public synchronized Result rejectOversized(String peer, String boundedPrefix) {
        return rejectPrefix(peer, boundedPrefix, 413, "BODY_TOO_LARGE");
    }
    /** Invalid UTF-8 must never be repaired into a filename or private message. */
    public synchronized Result rejectMalformed(String peer, String boundedPrefix) {
        return rejectPrefix(peer, boundedPrefix, 400, "INVALID_UTF8_BODY");
    }
    private Result rejectPrefix(String peer, String prefix, int status, String error) {
        if (closed) return failure(503, "RECEIVER_CLOSED");
        long now = clock.getAsLong();
        expireAt(now);
        if (identifier(peer) && prefix != null) {
            String bounded = prefix.substring(0, Math.min(256, prefix.length()));
            String[] fields = bounded.split("\\|", 4);
            if (fields.length == 4 && "#FILE".equals(fields[0]) && identifier(fields[2]))
                return reject(new Key(peer, fields[2]), status, error, now);
        }
        return failure(status, error);
    }

    public synchronized void expire() { if (!closed) expireAt(clock.getAsLong()); }
    private void expireAt(long now) {
        Iterator<Map.Entry<Key, Terminal>> ended = terminal.entrySet().iterator();
        while (ended.hasNext()) {
            Terminal item = ended.next().getValue();
            if (now - item.created >= limits.absoluteMillis) { reservedTerminalBytes -= item.reservation; ended.remove(); }
        }
        Iterator<Map.Entry<Key, Transfer>> live = active.entrySet().iterator();
        while (live.hasNext()) {
            Map.Entry<Key, Transfer> entry = live.next(); Transfer transfer = entry.getValue();
            if (now - transfer.lastActivity >= limits.idleMillis || now - transfer.created >= limits.absoluteMillis) {
                bufferBytes -= transfer.chargedBytes;
                reservedTerminalBytes -= transfer.reservation();
                live.remove();
                Terminal expired = new Terminal(408, "TRANSFER_EXPIRED", transfer, now);
                terminal.put(entry.getKey(), expired);
                reservedTerminalBytes += expired.reservation;
            }
        }
    }
    private Result reject(Key key, int status, String error, long now) {
        if (key != null) {
            Transfer transfer = removeActive(key);
            Terminal existing = terminal.get(key);
            if (existing == null && terminal.size() + active.size() < limits.terminal
                    && reservedTerminalBytes + TERMINAL_OVERHEAD <= limits.terminalBytes) {
                Terminal rejected = new Terminal(status, error, transfer, now);
                terminal.put(key, rejected);
                reservedTerminalBytes += rejected.reservation;
            }
        }
        return failure(status, error);
    }
    private Transfer removeActive(Key key) {
        Transfer removed = active.remove(key);
        if (removed != null) { bufferBytes -= removed.chargedBytes; reservedTerminalBytes -= removed.reservation(); }
        return removed;
    }
    private static byte[] fingerprint(String chunk) {
        try { return MessageDigest.getInstance("SHA-256").digest(chunk.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
    private static Result accepted(String type) { return new Result(200, false, null, type, null); }
    private static Result failure(int status, String error) { return new Result(status, false, null, null, error); }
    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_.+\\-]{1,64}");
    }
    private static boolean validChunk(String value, boolean last) {
        int padding = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '=') { if (++padding > 2) return false; }
            else if (padding != 0 || base64Value(c) < 0) return false;
        }
        return last || padding < 2;
    }
    private static int base64Value(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        return c == '+' ? 62 : c == '/' ? 63 : -1;
    }
    private static byte[] decodeCanonical(String value) {
        byte[] decoded = Base64.getDecoder().decode(value);
        if (!Base64.getEncoder().encodeToString(decoded).equals(value)) throw new IllegalArgumentException("Noncanonical Base64");
        return decoded;
    }
    private static long decodedLength(Transfer transfer) {
        if (transfer.characters == 0) return 0;
        if (transfer.characters % 4 != 0) throw new IllegalArgumentException("Incomplete Base64 quartet");
        boolean paddingStarted = false;
        int allPadding = 0;
        for (int i = 0; i < transfer.total; i++) {
            String chunk = transfer.chunks.get(i);
            for (int j = 0; j < chunk.length(); j++) {
                if (chunk.charAt(j) == '=') { paddingStarted = true; if (++allPadding > 2) throw new IllegalArgumentException("Excess padding"); }
                else if (paddingStarted) throw new IllegalArgumentException("Data after padding");
            }
        }
        int padding = charAt(transfer, transfer.characters - 1) == '=' ? 1 : 0;
        if (padding == 1 && charAt(transfer, transfer.characters - 2) == '=') padding = 2;
        if (padding == 1 && (base64Value(charAt(transfer, transfer.characters - 2)) & 3) != 0)
            throw new IllegalArgumentException("Noncanonical Base64 pad bits");
        if (padding == 2 && (base64Value(charAt(transfer, transfer.characters - 3)) & 15) != 0)
            throw new IllegalArgumentException("Noncanonical Base64 pad bits");
        return transfer.characters / 4 * 3 - padding;
    }
    private static char charAt(Transfer transfer, long position) {
        for (int i = 0; i < transfer.total; i++) {
            String chunk = transfer.chunks.get(i);
            if (position < chunk.length()) return chunk.charAt((int) position);
            position -= chunk.length();
        }
        throw new IllegalArgumentException("Missing Base64 character");
    }
    private static final class ChunkInputStream extends InputStream {
        private final Transfer transfer;
        private int chunkIndex, offset;
        ChunkInputStream(Transfer transfer) { this.transfer = transfer; }
        @Override public int read() {
            while (chunkIndex < transfer.total) {
                String chunk = transfer.chunks.get(chunkIndex);
                if (offset < chunk.length()) return chunk.charAt(offset++);
                chunkIndex++; offset = 0;
            }
            return -1;
        }
        @Override public int read(byte[] target, int from, int length) {
            if (target == null) throw new NullPointerException("target");
            if (from < 0 || length < 0 || length > target.length - from) throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            int count = 0;
            while (count < length && chunkIndex < transfer.total) {
                String chunk = transfer.chunks.get(chunkIndex);
                if (offset == chunk.length()) { chunkIndex++; offset = 0; continue; }
                int take = Math.min(length - count, chunk.length() - offset);
                for (int i = 0; i < take; i++) target[from + count + i] = (byte) chunk.charAt(offset + i);
                count += take; offset += take;
            }
            return count == 0 ? -1 : count;
        }
    }
    @Override public synchronized void close() { active.clear(); terminal.clear(); bufferBytes = 0; reservedTerminalBytes = 0; closed = true; }
    synchronized int activeTransferCount() { return active.size(); }
    synchronized int terminalIdCount() { return terminal.size(); }
    synchronized long bufferedBytes() { return bufferBytes; }
    synchronized long reservedTerminalBytes() { return reservedTerminalBytes; }
}

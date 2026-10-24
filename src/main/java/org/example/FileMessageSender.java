package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.UUID;

/** Streams the existing #FILE protocol without loading a whole file into memory. */
public final class FileMessageSender {
    public static final int MAX_CHUNK_CHARS = 4096;
    public static final int MAX_CHUNKS = 32768;
    private FileMessageSender() { }

    @FunctionalInterface public interface FrameSender {
        void send(String recipient, String frame) throws Exception;
    }
    public static final class SentFile {
        private final long size;
        private final int chunks;
        private final String id;
        private SentFile(long size, int chunks, String id) { this.size = size; this.chunks = chunks; this.id = id; }
        public long getSize() { return size; }
        public int getChunks() { return chunks; }
        public String getId() { return id; }
    }

    public static SentFile send(Path path, String originalName, String type, int chunkChars,
                                String recipient, FrameSender transport) throws Exception {
        ReceivedFileStore.validateOriginalName(originalName);
        if (!"IMG".equals(type) && !"VOICE".equals(type) && !"VIDEO".equals(type)) {
            throw new IllegalArgumentException("INVALID_FILE_TYPE");
        }
        if (path == null || transport == null || recipient == null || recipient.isEmpty()
                || recipient.indexOf('\r') >= 0 || recipient.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("INVALID_FILE_TARGET");
        }
        if (chunkChars < 1 || chunkChars > MAX_CHUNK_CHARS) throw new IllegalArgumentException("INVALID_CHUNK_SIZE");
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.isOther()) throw new IOException("FILE_NOT_REGULAR");
        long expected = before.size();
        if (expected > ReceivedFileStore.MAX_FILE_BYTES) throw new ReceivedFileStore.SizeLimitException("outgoing file too large");
        long encoded = ((expected + 2) / 3) * 4;
        long count = Math.max(1L, (encoded + chunkChars - 1) / chunkChars);
        if (count > MAX_CHUNKS) throw new IllegalArgumentException("TOO_MANY_FILE_CHUNKS");
        String id = UUID.randomUUID().toString();
        String name = Base64.getEncoder().encodeToString(originalName.getBytes(StandardCharsets.UTF_8));
        Fragments sink = new Fragments(recipient, type, id, name, (int) count, chunkChars, transport);
        long read = 0;
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             OutputStream encoder = Base64.getEncoder().wrap(sink)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (length == 0) continue;
                read += length;
                if (read > expected || read > ReceivedFileStore.MAX_FILE_BYTES) throw new IOException("FILE_CHANGED_DURING_SEND");
                encoder.write(buffer, 0, length);
            }
            if (read != expected) throw new IOException("FILE_CHANGED_DURING_SEND");
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || after.isSymbolicLink() || after.size() != expected
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException("FILE_CHANGED_DURING_SEND");
        }
        sink.finish();
        return new SentFile(read, sink.index, id);
    }

    private static final class Fragments extends OutputStream {
        private final String recipient, prefix;
        private final int total;
        private final byte[] buffer;
        private final FrameSender transport;
        private int used, index;
        Fragments(String recipient, String type, String id, String name, int total,
                  int chunkChars, FrameSender transport) {
            this.recipient = recipient; this.total = total; this.transport = transport;
            this.buffer = new byte[chunkChars];
            this.prefix = "#FILE|" + type + "|" + id + "|" + name + "|";
        }
        @Override public void write(int value) throws IOException {
            if (used == buffer.length) throw new IOException("FILE_FRAME_COUNT_CHANGED");
            buffer[used++] = (byte) value;
            if (used == buffer.length && index < total - 1) emit();
        }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            while (length > 0) {
                if (used == buffer.length) throw new IOException("FILE_FRAME_COUNT_CHANGED");
                int take = Math.min(length, buffer.length - used);
                System.arraycopy(data, offset, buffer, used, take);
                used += take; offset += take; length -= take;
                if (used == buffer.length && index < total - 1) emit();
            }
        }
        private void emit() throws IOException {
            if (index >= total) throw new IOException("FILE_FRAME_COUNT_CHANGED");
            String frame = prefix + index + "|" + total + "|" + new String(buffer, 0, used, StandardCharsets.US_ASCII);
            try { transport.send(recipient, frame); }
            catch (Exception failure) { throw new IOException("FILE_FRAME_SEND_FAILED", failure); }
            index++; used = 0;
        }
        void finish() throws IOException {
            if (used > 0 || index == 0) emit();
            if (index != total) throw new IOException("FILE_FRAME_COUNT_CHANGED");
        }
        // Base64's close only completes padding. Publication of a final full/short/empty
        // frame is explicit, after source length/identity validation succeeded.
        @Override public void close() { }
    }
}

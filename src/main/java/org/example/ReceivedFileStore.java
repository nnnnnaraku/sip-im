package org.example;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded receive/upload storage. Untrusted display names never become paths.
 * All roots and entries are checked without following links, before and after IO.
 * Java 8 on Windows does not expose an atomic directory-handle-relative rename or
 * open. These checks fail closed when changes are observed, but cannot eliminate
 * a malicious local process replacing directories between system calls. The root
 * and its parents must be protected by OS permissions from such local writers.
 */
public final class ReceivedFileStore {
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    public static final long MAX_DIRECTORY_BYTES = 256L * 1024 * 1024;
    public static final int MAX_FILES = 512;
    private static final int MAX_NAME_UTF8_BYTES = 240;
    private final Path root;
    private final Limits limits;
    private final Object owner = new Object();
    private Identity rootIdentity;
    private Path rootRealPath;

    public ReceivedFileStore(Path root) {
        this(root, new Limits(MAX_FILE_BYTES, MAX_DIRECTORY_BYTES, MAX_FILES));
    }

    public ReceivedFileStore(Path root, long maxFileBytes, long maxDirectoryBytes, int maxFiles) {
        this(root, new Limits(maxFileBytes, maxDirectoryBytes, maxFiles));
    }

    /** Constructing a store does not create directories or touch the filesystem. */
    public ReceivedFileStore(Path root, Limits limits) {
        if (root == null || limits == null) throw new IllegalArgumentException("root and limits are required");
        this.root = root.toAbsolutePath().normalize();
        this.limits = limits;
    }

    public Path getRoot() { return root; }
    public long getMaxFileBytes() { return limits.maxFileBytes; }

    public static final class Limits {
        public final long maxFileBytes;
        public final long maxDirectoryBytes;
        public final int maxFiles;
        public Limits(long maxFileBytes, long maxDirectoryBytes, int maxFiles) {
            if (maxFileBytes < 0 || maxFileBytes > MAX_FILE_BYTES || maxDirectoryBytes < 0 || maxDirectoryBytes > MAX_DIRECTORY_BYTES || maxFiles < 1 || maxFiles > MAX_FILES) {
                throw new IllegalArgumentException("invalid receive limits");
            }
            this.maxFileBytes = maxFileBytes;
            this.maxDirectoryBytes = maxDirectoryBytes;
            this.maxFiles = maxFiles;
        }
    }

    public static final class SizeLimitException extends IOException {
        public SizeLimitException(String message) { super("SIZE_LIMIT: " + message); }
    }

    public static final class StoredFile {
        private final Object owner;
        private final String originalName;
        private final String storageName;
        private final Path path;
        private final long size;
        private final String sha256;
        private final Identity identity;
        private StoredFile(Object owner, String originalName, String storageName, Path path,
                           long size, String sha256, Identity identity) {
            this.owner = owner;
            this.originalName = originalName;
            this.storageName = storageName;
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
            this.identity = identity;
        }
        public String getOriginalName() { return originalName; }
        public String getStorageName() { return storageName; }
        public Path getPath() { return path; }
        public long getSize() { return size; }
        public String getSha256() { return sha256; }
    }

    /** Validate a display filename using Windows rules even when tests run elsewhere. */
    public static String validateOriginalName(String name) {
        if (name == null || name.length() == 0) throw badName();
        if (name.equals(".") || name.equals("..") || name.endsWith(".") || name.endsWith(" ")) throw badName();
        for (int i = 0; i < name.length();) {
            char c = name.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(i + 1))) throw badName();
            } else if (Character.isLowSurrogate(c)) {
                throw badName();
            }
            int cp = name.codePointAt(i);
            int kind = Character.getType(cp);
            if (kind == Character.CONTROL || kind == Character.FORMAT || "<>:\"/\\|?*".indexOf(cp) >= 0) throw badName();
            i += Character.charCount(cp);
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_UTF8_BYTES) throw badName();
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.matches(".*%(?:00|2E|2F|3A|5C|25).*")) throw badName();
        String base = upper;
        int dot = base.indexOf('.');
        if (dot >= 0) base = base.substring(0, dot);
        base = base.trim();
        if (base.matches("CON|PRN|AUX|NUL|CLOCK\\$|CONIN\\$|CONOUT\\$|COM[1-9\u00b9\u00b2\u00b3]|LPT[1-9\u00b9\u00b2\u00b3]")) throw badName();
        return name;
    }

    private static IllegalArgumentException badName() {
        return new IllegalArgumentException("UNSAFE_FILENAME: invalid file name");
    }

    /** Stream to a unique incomplete file, then publish without replacing anything. */
    public synchronized StoredFile save(String originalName, InputStream source) throws IOException {
        validateOriginalName(originalName);
        if (source == null) throw new IllegalArgumentException("source is required");
        checkRoot(true);
        Usage usage = usage();
        if (usage.files >= limits.maxFiles) throw new SizeLimitException("receive file count exceeded");
        long available = Math.min(limits.maxFileBytes, limits.maxDirectoryBytes - usage.bytes);
        if (available < 0) throw new SizeLimitException("receive directory is full");
        String id = UUID.randomUUID().toString();
        Path part = root.resolve(".part-" + id);
        String storageName = id + safeExtension(originalName);
        Path destination = root.resolve(storageName);
        Identity partIdentity = null;
        Identity finalIdentity = null;
        boolean published = false;
        try {
            Set<OpenOption> options = new HashSet<OpenOption>(Arrays.<OpenOption>asList(
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
            long written = 0;
            MessageDigest digest = sha256();
            try (SeekableByteChannel channel = Files.newByteChannel(part, options);
                 OutputStream output = Channels.newOutputStream(channel)) {
                partIdentity = regularIdentity(part);
                checkRoot(false);
                byte[] buffer = new byte[8192];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (count == 0) {
                        int one = source.read();
                        if (one < 0) break;
                        if (written >= available) throw new SizeLimitException("file or directory byte limit exceeded");
                        output.write(one);
                        digest.update((byte) one);
                        written++;
                    } else {
                        if (count > available - written) throw new SizeLimitException("file or directory byte limit exceeded");
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        written += count;
                    }
                }
            }
            checkRoot(false);
            requireIdentity(part, partIdentity);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw unsafe("destination unexpectedly exists");
            // Intentionally omit REPLACE_EXISTING and ATOMIC_MOVE: the latter may
            // replace an existing destination on some providers despite that flag.
            Files.move(part, destination);
            published = true;
            finalIdentity = regularIdentity(destination);
            if (!partIdentity.sameObject(finalIdentity)) throw unsafe("published file changed");
            checkRoot(false);
            Usage after = usage();
            if (after.bytes > limits.maxDirectoryBytes || after.files > limits.maxFiles) {
                throw new SizeLimitException("receive directory quota changed during write");
            }
            StoredFile result = new StoredFile(owner, originalName, storageName, destination,
                    written, hex(digest.digest()), finalIdentity);
            return result;
        } catch (IOException e) {
            cleanupOwn(part, partIdentity, e);
            if (published) cleanupOwn(destination, finalIdentity == null ? partIdentity : finalIdentity, e);
            throw e;
        } catch (RuntimeException e) {
            IOException cleanup = new IOException("cleanup after receive failure");
            cleanupOwn(part, partIdentity, cleanup);
            if (published) cleanupOwn(destination, finalIdentity == null ? partIdentity : finalIdentity, cleanup);
            for (Throwable suppressed : cleanup.getSuppressed()) e.addSuppressed(suppressed);
            throw e;
        }
    }

    /** Open only a complete, bounded, regular file immediately beneath this root. */
    public synchronized InputStream openForRead(String storageName) throws IOException {
        Path file = resolveCompleteName(storageName);
        checkRoot(false);
        final Identity expected = regularIdentity(file);
        if (expected.size > limits.maxFileBytes) throw new SizeLimitException("stored file exceeds limit");
        Set<OpenOption> options = new HashSet<OpenOption>(Arrays.<OpenOption>asList(
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        SeekableByteChannel channel = Files.newByteChannel(file, options);
        try {
            requireIdentity(file, expected);
            checkRoot(false);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        return new CheckedInputStream(Channels.newInputStream(channel), file, expected);
    }

    public synchronized long sizeOf(String storageName) throws IOException {
        Path file = resolveCompleteName(storageName);
        checkRoot(false);
        Identity identity = regularIdentity(file);
        if (identity.size > limits.maxFileBytes) throw new SizeLimitException("stored file exceeds limit");
        checkRoot(false);
        return identity.size;
    }

    /** Only this exact store's saved object may authorize cleanup of a complete file. */
    public synchronized void deleteOwned(StoredFile file) throws IOException {
        if (file == null || file.owner != owner
                || !file.path.equals(root.resolve(file.storageName))) throw unsafe("file is not owned by this store");
        checkRoot(false);
        requireIdentity(file.path, file.identity);
        Files.delete(file.path);
        checkRoot(false);
    }

    private Path resolveCompleteName(String storageName) throws IOException {
        try { validateOriginalName(storageName); }
        catch (IllegalArgumentException e) { throw unsafe("invalid stored filename"); }
        String lower = storageName.toLowerCase(Locale.ROOT);
        if (storageName.startsWith(".") || lower.endsWith(".part") || lower.endsWith(".tmp")) {
            throw unsafe("incomplete or hidden file cannot be served");
        }
        Path file = root.resolve(storageName).normalize();
        if (!root.equals(file.getParent())) throw unsafe("file is outside receive directory");
        return file;
    }

    private static String safeExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (extension.matches("[a-z0-9]{1,12}") && !extension.equals("part") && !extension.equals("tmp")) {
                return "." + extension;
            }
        }
        return ".bin";
    }

    private void checkRoot(boolean create) throws IOException {
        Path cursor = root.getRoot();
        if (cursor == null) throw unsafe("receive root is not absolute");
        checkDirectory(cursor);
        for (Path component : root) {
            cursor = cursor.resolve(component);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) throw unsafe("receive root is missing");
                Files.createDirectory(cursor);
            }
            checkDirectory(cursor);
        }
        Identity current = new Identity(Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        Path real = root.toRealPath();
        if (rootIdentity == null) {
            rootIdentity = current;
            rootRealPath = real;
        } else if (!rootIdentity.sameObject(current) || !rootRealPath.equals(real)) {
            throw unsafe("receive directory identity changed");
        }
    }

    private static void checkDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw unsafe("linked or non-directory ancestor");
        }
        Path followed = directory.toRealPath();
        Path noFollow = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!followed.equals(noFollow)) throw unsafe("reparse or symbolic-link ancestor");
    }

    private Usage usage() throws IOException {
        long bytes = 0;
        int files = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                Identity identity = regularIdentity(entry);
                files++;
                if (identity.size > limits.maxDirectoryBytes - bytes) throw new SizeLimitException("receive directory byte quota exceeded");
                bytes += identity.size;
                if (files > limits.maxFiles) throw new SizeLimitException("receive directory file quota exceeded");
            }
        }
        return new Usage(bytes, files);
    }

    private static Identity regularIdentity(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw unsafe("file must be regular and must not be a link");
        }
        if (!path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS))) throw unsafe("linked file");
        return new Identity(attributes);
    }

    private static void requireIdentity(Path path, Identity expected) throws IOException {
        if (expected == null || !expected.sameObject(regularIdentity(path))) throw unsafe("file identity changed");
    }

    private void cleanupOwn(Path path, Identity expected, Throwable failure) {
        if (expected == null) return;
        try {
            checkRoot(false);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                requireIdentity(path, expected);
                Files.delete(path);
            }
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    private static IOException unsafe(String message) { return new IOException("UNSAFE_PATH: " + message); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private static String hex(byte[] digest) {
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private static final class Usage {
        final long bytes; final int files;
        Usage(long bytes, int files) { this.bytes = bytes; this.files = files; }
    }
    private static final class Identity {
        final Object key;
        final long created;
        final long size;
        Identity(BasicFileAttributes attributes) {
            key = attributes.fileKey(); created = attributes.creationTime().toMillis(); size = attributes.size();
        }
        boolean sameObject(Identity other) {
            return key != null && other.key != null ? key.equals(other.key) : key == other.key && created == other.created;
        }
    }

    private final class CheckedInputStream extends FilterInputStream {
        private final Path file;
        private final Identity identity;
        private long count;
        private boolean closed;
        CheckedInputStream(InputStream input, Path file, Identity identity) {
            super(input); this.file = file; this.identity = identity;
        }
        @Override public int read() throws IOException {
            int result = in.read();
            if (result >= 0) increment(1);
            return result;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int result = in.read(buffer, offset, length);
            if (result > 0) increment(result);
            return result;
        }
        @Override public long skip(long amount) throws IOException {
            if (amount <= 0) return 0;
            long skipped = in.skip(Math.min(amount, identity.size - count));
            count += skipped;
            return skipped;
        }
        @Override public boolean markSupported() { return false; }
        @Override public synchronized void mark(int readLimit) { /* Rewinding would invalidate the byte bound. */ }
        @Override public synchronized void reset() throws IOException { throw new IOException("mark/reset is unsupported"); }
        private void increment(int read) throws IOException {
            count += read;
            if (count > limits.maxFileBytes || count > identity.size) throw new SizeLimitException("stored file grew during read");
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException problem = null;
            try { super.close(); } catch (IOException e) { problem = e; }
            try {
                synchronized (ReceivedFileStore.this) {
                    checkRoot(false);
                    requireIdentity(file, identity);
                    if (regularIdentity(file).size != identity.size) throw unsafe("stored file length changed during read");
                }
            } catch (IOException e) {
                if (problem == null) problem = e; else problem.addSuppressed(e);
            }
            if (problem != null) throw problem;
        }
    }
}

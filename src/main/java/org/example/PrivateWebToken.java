package org.example;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Private local authentication files; neither contents nor file paths are logged. */
public final class PrivateWebToken {
    private static final int TOKEN_BYTES = 32;
    private PrivateWebToken() { }

    /** Creates an owner-only file atomically, or reads an existing protected token without rewriting it. */
    public static String loadOrCreate(Path path) throws IOException {
        Path file = checkedPath(path);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] random = new byte[TOKEN_BYTES];
            new SecureRandom().nextBytes(random);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            Arrays.fill(random, (byte) 0);
            createPrivateFile(file, token.getBytes(StandardCharsets.US_ASCII));
        }
        String token = removeOneLineEnding(strictUtf8(readPrivate(file, 128)));
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Invalid private Web token format");
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length != TOKEN_BYTES || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) {
                throw new IOException("Invalid private Web token format");
            }
            Arrays.fill(bytes, (byte) 0);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid private Web token format");
        }
        return token;
    }

    /** Reads a single UTF-8 password line. Meaningful leading/trailing spaces are preserved. */
    public static char[] readPrivatePassword(Path path) throws IOException {
        byte[] bytes = readPrivate(checkedPath(path), 4096);
        try {
            String password = removeOneLineEnding(strictUtf8(bytes));
            if (password.isEmpty() || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0
                    || password.indexOf('\0') >= 0) throw new IOException("Invalid private password file");
            return password.toCharArray();
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public static void verifyPrivateRegularFile(Path path, int maximumBytes) throws IOException {
        inspect(checkedPath(path), maximumBytes);
    }

    public static byte[] readPrivateFile(Path path, int maximumBytes) throws IOException {
        return readPrivate(checkedPath(path), maximumBytes);
    }

    private static byte[] readPrivate(Path path, int maximumBytes) throws IOException {
        BasicFileAttributes before = inspect(path, maximumBytes);
        Set<OpenOption> options = new HashSet<OpenOption>();
        options.add(StandardOpenOption.READ);
        options.add(LinkOption.NOFOLLOW_LINKS);
        ByteBuffer buffer = ByteBuffer.allocate(maximumBytes + 1);
        try (FileIdentity identity = FileIdentity.capture(path, before)) {
            try (SeekableByteChannel in = Files.newByteChannel(path, options)) {
                while (buffer.hasRemaining() && in.read(buffer) != -1) { }
            }
            BasicFileAttributes after = inspect(path, maximumBytes);
            if (buffer.position() > maximumBytes || !identity.matches(path, after)
                    || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || buffer.position() != before.size()) throw new IOException("Private file changed while reading");
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }
    private static void createPrivateFile(Path file, byte[] bytes) throws IOException {
        FileAttribute<?> permissions;
        AclFileAttributeView aclView = Files.getFileAttributeView(file.getParent(), AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (aclView != null) {
            final UserPrincipal current = file.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            final List<AclEntry> acl = Collections.singletonList(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(current).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
            permissions = new FileAttribute<List<AclEntry>>() {
                public String name() { return "acl:acl"; }
                public List<AclEntry> value() { return acl; }
            };
        } else if (Files.getFileAttributeView(file.getParent(), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        } else {
            throw new IOException("File system cannot enforce private file permissions");
        }
        Set<OpenOption> options = new HashSet<OpenOption>();
        options.add(StandardOpenOption.CREATE_NEW);
        options.add(StandardOpenOption.WRITE);
        options.add(LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel out = Files.newByteChannel(file, options, permissions)) {
            restrictAcl(file);
            BasicFileAttributes initial = inspect(file, 128);
            try (FileIdentity identity = FileIdentity.capture(file, initial)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) out.write(data);
                BasicFileAttributes complete = inspect(file, 128);
                if (!identity.matches(file, complete)) throw new IOException("Private file changed while writing");
            }
        } catch (java.nio.file.FileAlreadyExistsException concurrentCreator) {
            // Another process won CREATE_NEW. Never replace it; the caller verifies and reads it.
        } catch (UnsupportedOperationException e) {
            throw new IOException("File system cannot atomically create private files");
        }
    }

    /**
     * Windows 会在新建文件时继承父目录的 ACE（常含 Users / Authenticated Users），
     * 必须在写入前替换为仅 owner 的 ACL，否则后续校验会判定为泄露。
     */
    private static void restrictAcl(Path file) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) return;
        UserPrincipal owner = view.getOwner();
        if (owner == null) throw new IOException("Private file owner is unavailable");
        List<AclEntry> entries = new ArrayList<>();
        entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
        try {
            view.setAcl(entries);
        } catch (IOException | UnsupportedOperationException e) {
            throw new IOException("File system cannot enforce private file permissions", e);
        }
    }

    private static Path checkedPath(Path path) throws IOException {
        if (path == null) throw new IOException("Private file path is required");
        Path file = path.toAbsolutePath().normalize();
        if (file.getParent() == null || file.getFileName() == null) throw new IOException("Invalid private file path");
        rejectLinks(file.getParent());
        return file;
    }

    private static BasicFileAttributes inspect(Path path, int maximumBytes) throws IOException {
        if (maximumBytes < 0 || maximumBytes > 1024 * 1024) throw new IllegalArgumentException("Invalid private file limit");
        rejectLinks(path);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || attributes.size() > maximumBytes) {
            throw new IOException("Private file must be a bounded regular file");
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal owner = acl.getOwner();
            UserPrincipal current = path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            requireCurrentOwner(owner, current);
            boolean ownerRead = false;
            for (AclEntry entry : acl.getAcl()) {
                if (entry.type() == AclEntryType.ALLOW) {
                    // Windows 文件默认继承 SYSTEM / Administrators 的允许项，这两个内置特权主体
                    // 不影响「仅当前用户可读」的语义；普通组（Users / Everyone）仍然拒绝。
                    if (!entry.principal().equals(owner) && !windowsPrivileged(entry.principal())) {
                        throw new IOException("Private file grants access to another principal");
                    }
                    if (entry.permissions().contains(AclEntryPermission.READ_DATA)) ownerRead = true;
                }
            }
            if (!ownerRead) throw new IOException("Private file is not readable by its owner");
        } else {
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix == null) throw new IOException("File system cannot verify private file permissions");
            java.nio.file.attribute.PosixFileAttributes posixAttributes = posix.readAttributes();
            UserPrincipal current = path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            requireCurrentOwner(posixAttributes.owner(), current);
            Set<PosixFilePermission> mode = posixAttributes.permissions();
            if (!mode.contains(PosixFilePermission.OWNER_READ)) throw new IOException("Private file is not readable by its owner");
            for (PosixFilePermission permission : mode) {
                if (permission != PosixFilePermission.OWNER_READ && permission != PosixFilePermission.OWNER_WRITE) {
                    throw new IOException("Private file grants access beyond its owner");
                }
            }
        }
        return attributes;
    }

    static void requireCurrentOwner(UserPrincipal owner, UserPrincipal current) throws IOException {
        if (owner == null || current == null || !owner.equals(current)) {
            throw new IOException("Private file must be owned by the current user");
        }
    }

    /** Windows 内置特权主体：SYSTEM 与 Administrators（含本地化名称）。 */
    private static boolean windowsPrivileged(UserPrincipal principal) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return false;
        String name = principal.getName() == null ? "" : principal.getName().toLowerCase(Locale.ROOT);
        return name.equals("system") || name.endsWith("\\system")
                || name.contains("administrators") || name.contains("管理员");
    }
    private static void rejectLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path component : absolute) {
            cursor = cursor.resolve(component);
            BasicFileAttributes attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Private file path contains a link or reparse point");
        }
        if (!absolute.toRealPath().equals(absolute)) throw new IOException("Private file path resolves through an alias");
    }

    /** Java 8 Windows exposes a null fileKey; a temporary hard link preserves its native file identity. */
    private static final class FileIdentity implements AutoCloseable {
        private final Object key;
        private final Path anchor;
        private FileIdentity(Object key, Path anchor) { this.key = key; this.anchor = anchor; }

        static FileIdentity capture(Path path, BasicFileAttributes attributes) throws IOException {
            if (attributes.fileKey() != null) return new FileIdentity(attributes.fileKey(), null);
            Path anchor = path.resolveSibling(".web-identity-" + java.util.UUID.randomUUID().toString());
            try {
                Files.createLink(anchor, path);
                BasicFileAttributes linked = Files.readAttributes(anchor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!Files.isSameFile(path, anchor) || !linked.creationTime().equals(attributes.creationTime())
                        || !linked.isRegularFile() || linked.isSymbolicLink() || linked.isOther()) {
                    throw new IOException("Private file changed during identity capture");
                }
                return new FileIdentity(null, anchor);
            } catch (IOException | UnsupportedOperationException | SecurityException failure) {
                try { Files.deleteIfExists(anchor); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw new IOException("File system cannot verify private file identity", failure);
            }
        }

        boolean matches(Path path, BasicFileAttributes attributes) throws IOException {
            return anchor != null ? Files.isSameFile(path, anchor) : key.equals(attributes.fileKey());
        }

        public void close() throws IOException {
            if (anchor != null) Files.delete(anchor);
        }
    }
    private static String strictUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Private file is not valid UTF-8");
        }
    }

    private static String removeOneLineEnding(String text) {
        if (text.endsWith("\r\n")) return text.substring(0, text.length() - 2);
        if (text.endsWith("\n") || text.endsWith("\r")) return text.substring(0, text.length() - 1);
        return text;
    }
}

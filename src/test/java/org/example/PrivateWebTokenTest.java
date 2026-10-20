package org.example;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.Assert.*;

public class PrivateWebTokenTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void createsPrivateCanonicalRandomToken() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("access.token");
        String token = PrivateWebToken.loadOrCreate(file);
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(32, Base64.getUrlDecoder().decode(token).length);
        assertEquals(token, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        PrivateWebToken.verifyPrivateRegularFile(file, 128);
    }

    @Test public void existingTokenIsNeverRewritten() throws Exception {
        Path file = tokenFile();
        byte[] first = Files.readAllBytes(file);
        Object key = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        assertEquals(new String(first, StandardCharsets.UTF_8), PrivateWebToken.loadOrCreate(file));
        assertArrayEquals(first, Files.readAllBytes(file));
        assertEquals(key, Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).fileKey());
    }

    @Test public void separateFilesUseDistinctTokens() throws Exception {
        Path first = temporary.getRoot().toPath().resolve("first.token");
        Path second = temporary.getRoot().toPath().resolve("second.token");
        assertNotEquals(PrivateWebToken.loadOrCreate(first), PrivateWebToken.loadOrCreate(second));
    }

    @Test public void acceptsOneFinalNewlineOnly() throws Exception {
        Path file = tokenFile();
        String token = PrivateWebToken.loadOrCreate(file);
        Files.write(file, (token + "\r\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(token, PrivateWebToken.loadOrCreate(file));
        Files.write(file, (token + "\n\n").getBytes(StandardCharsets.UTF_8));
        rejects(() -> PrivateWebToken.loadOrCreate(file));
    }

    @Test public void rejectsBlankAndWhitespaceTokens() throws Exception {
        for (String input : Arrays.asList("", " ", "abc", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA ",
                " AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAA\nAAAAAAAAAAAAAAAAAAAA")) {
            Path file = tokenFile();
            Files.write(file, input.getBytes(StandardCharsets.UTF_8));
            rejects(() -> PrivateWebToken.loadOrCreate(file));
            assertArrayEquals(input.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
        }
    }

    @Test public void rejectsNonCanonicalBase64Tail() throws Exception {
        Path file = tokenFile();
        Files.write(file, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB".getBytes(StandardCharsets.UTF_8));
        rejects(() -> PrivateWebToken.loadOrCreate(file));
    }

    @Test public void rejectsOverlongTokenBeforeDecoding() throws Exception {
        Path file = tokenFile();
        Files.write(file, new byte[129]);
        rejects(() -> PrivateWebToken.loadOrCreate(file));
    }

    @Test public void rejectsMalformedUtf8() throws Exception {
        Path file = tokenFile();
        Files.write(file, new byte[]{(byte) 0xc3, (byte) 0x28});
        rejects(() -> PrivateWebToken.loadOrCreate(file));
        rejects(() -> PrivateWebToken.readPrivatePassword(file));
    }

    @Test public void rejectsDirectoryAndMissingParent() throws Exception {
        Path directory = temporary.newFolder("directory").toPath();
        rejects(() -> PrivateWebToken.loadOrCreate(directory));
        Path child = directory.resolve("missing").resolve("access.token");
        rejects(() -> PrivateWebToken.loadOrCreate(child));
        assertFalse(Files.exists(child));
    }

    @Test public void rejectsPermissionsGrantedToOtherUsers() throws Exception {
        Path file = tokenFile();
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            // Resolve the well-known SID through Windows instead of assuming an English account name.
            String translate = "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false; "
                    + "([System.Security.Principal.SecurityIdentifier]::new('S-1-1-0')).Translate("
                    + "[System.Security.Principal.NTAccount]).Value";
            Process resolver = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", translate)
                    .redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream accountBytes = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream output = resolver.getInputStream()) {
                byte[] buffer = new byte[512];
                int count;
                while ((count = output.read(buffer)) != -1) accountBytes.write(buffer, 0, count);
            }
            assertEquals("Well-known SID translation must succeed", 0, resolver.waitFor());
            String account = new String(accountBytes.toByteArray(), StandardCharsets.UTF_8).trim();
            assertFalse("Localized Everyone account name is required", account.isEmpty());
            UserPrincipal everyone = file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(account);
            java.util.List<AclEntry> entries = new java.util.ArrayList<AclEntry>(acl.getAcl());
            entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                    .setPermissions(AclEntryPermission.READ_DATA).build());
            acl.setAcl(entries);
            assertTrue(acl.getAcl().stream().anyMatch(entry -> entry.type() == AclEntryType.ALLOW
                    && entry.principal().equals(everyone) && entry.permissions().contains(AclEntryPermission.READ_DATA)));
        } else {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
        rejects(() -> PrivateWebToken.loadOrCreate(file));
    }

    @Test public void readsPasswordPreservingSpacesAndUnicode() throws Exception {
        Path file = tokenFile();
        Files.write(file, "  \u6d4b\u8bd5 secret  \r\n".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("  \u6d4b\u8bd5 secret  ".toCharArray(), PrivateWebToken.readPrivatePassword(file));
    }

    @Test public void rejectsEmptyMultilineAndNulPasswords() throws Exception {
        for (String input : Arrays.asList("", "\n", "one\ntwo", "one\rtwo", "one\0two", "one\n\n")) {
            Path file = tokenFile();
            Files.write(file, input.getBytes(StandardCharsets.UTF_8));
            rejects(() -> PrivateWebToken.readPrivatePassword(file));
        }
    }

    @Test public void rejectsOverlongPassword() throws Exception {
        Path file = tokenFile();
        Files.write(file, new byte[4097]);
        rejects(() -> PrivateWebToken.readPrivatePassword(file));
    }

    @Test public void missingPasswordIsNotCreated() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("absent.password");
        rejects(() -> PrivateWebToken.readPrivatePassword(file));
        assertFalse(Files.exists(file));
    }

    @Test public void fileDoesNotContainOtherPrincipalsWhenCreated() throws Exception {
        Path file = tokenFile();
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
            for (AclEntry entry : view.getAcl()) {
                if (entry.type() == AclEntryType.ALLOW) assertEquals(view.getOwner(), entry.principal());
            }
        } else {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
    }

    @Test public void rejectsSymbolicLinkOrWindowsJunctionParent() throws Exception {
        Path destination = temporary.newFolder("destination").toPath();
        Path link = temporary.getRoot().toPath().resolve("alias");
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), destination.toString())
                    .redirectErrorStream(true).start();
            drain(process.getInputStream());
            assertEquals("Test junction creation failed", 0, process.waitFor());
        } else {
            Files.createSymbolicLink(link, destination);
        }
        try {
            rejects(() -> PrivateWebToken.loadOrCreate(link.resolve("access.token")));
            assertFalse(Files.exists(destination.resolve("access.token")));
        } finally {
            Files.delete(link);
        }
    }

    @Test public void boundedPrivateBlobAllowsCertificateChainSize() throws Exception {
        Path file = tokenFile();
        byte[] bytes = new byte[8192];
        java.util.Arrays.fill(bytes, (byte) 7);
        Files.write(file, bytes);
        assertArrayEquals(bytes, PrivateWebToken.readPrivateFile(file, 1024 * 1024));
        rejects(() -> PrivateWebToken.readPrivateFile(file, 4096));
    }
    @Test public void bothPermissionBackendsRequireCurrentOwner() throws Exception {
        UserPrincipal owner = () -> "test-current-owner";
        PrivateWebToken.requireCurrentOwner(owner, owner);
        rejects(() -> PrivateWebToken.requireCurrentOwner(owner, () -> "test-other-owner"));
        rejects(() -> PrivateWebToken.requireCurrentOwner(null, owner));
        rejects(() -> PrivateWebToken.requireCurrentOwner(owner, null));
    }

    @Test public void createdFileOwnerMatchesCurrentUserPrincipal() throws Exception {
        Path file = tokenFile();
        assertEquals(file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name")),
                Files.getOwner(file, LinkOption.NOFOLLOW_LINKS));
        PrivateWebToken.verifyPrivateRegularFile(file, 128);
    }
    private Path tokenFile() throws Exception {
        Path file = temporary.getRoot().toPath().resolve(java.util.UUID.randomUUID().toString() + ".token");
        PrivateWebToken.loadOrCreate(file);
        return file;
    }

    static void protect(Path file) throws Exception {
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            acl.setAcl(Collections.singletonList(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        } else {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    static void drain(java.io.InputStream input) throws IOException {
        byte[] buffer = new byte[1024];
        while (input.read(buffer) != -1) { }
        input.close();
    }

    interface Failure { void run() throws Exception; }
    static void rejects(Failure action) throws Exception {
        try {
            action.run();
            fail("Expected safe rejection");
        } catch (IOException expected) {
            assertFalse("Error must not disclose fixture secrets", expected.getMessage() != null && expected.getMessage().contains("secret"));
        }
    }
}

package org.example;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ReceivedFileStoreTest {
    private Path temporary;
    private Path root;
    private ReceivedFileStore store;

    @Before public void createTemporaryStore() throws IOException {
        Path target = Paths.get("target").toAbsolutePath();
        Files.createDirectories(target);
        temporary = Files.createTempDirectory(target, "file-store-test-");
        root = temporary.resolve("received");
        store = new ReceivedFileStore(root);
    }

    @After public void removeTemporaryStore() throws IOException {
        if (temporary != null && Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(temporary, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(dir); return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Test public void constructionDoesNotCreateDirectory() {
        assertFalse(Files.exists(root));
        assertEquals(root.toAbsolutePath().normalize(), store.getRoot());
        assertEquals(16L * 1024 * 1024, store.getMaxFileBytes());
    }

    @Test public void normalFileHasSafeUniqueNameExactContentAndIndependentHash() throws Exception {
        byte[] contents = "abc".getBytes(StandardCharsets.UTF_8);
        ReceivedFileStore.StoredFile file = store.save("hello.txt", stream(contents));
        assertEquals("hello.txt", file.getOriginalName());
        assertTrue(file.getStorageName().matches("[0-9a-f-]{36}\\.txt"));
        assertEquals(root, file.getPath().getParent());
        assertEquals(3, file.getSize());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", file.getSha256());
        assertArrayEquals(contents, Files.readAllBytes(file.getPath()));
        assertEquals(3, store.sizeOf(file.getStorageName()));
        try (InputStream input = store.openForRead(file.getStorageName())) {
            assertArrayEquals(contents, readAll(input));
        }
    }

    @Test public void sameOriginalNameNeverOverwritesEitherContent() throws Exception {
        ReceivedFileStore.StoredFile first = store.save("same.png", stream(new byte[]{1, 2}));
        ReceivedFileStore.StoredFile second = store.save("same.png", stream(new byte[]{3, 4}));
        assertNotEquals(first.getStorageName(), second.getStorageName());
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(first.getPath()));
        assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(second.getPath()));
        assertEquals(2, countFiles(root));
    }

    @Test public void preexistingFileRemainsUntouched() throws Exception {
        Files.createDirectory(root);
        Path existing = root.resolve("photo.png");
        Files.write(existing, new byte[]{42});
        store.save("photo.png", stream(new byte[]{9}));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing));
    }

    @Test public void rejectsUnixAndWindowsTraversalWithoutCreatingRoot() throws Exception {
        for (String name : Arrays.asList("../x", "..\\x", "a/../x", "a\\..\\x", ".", "..")) rejectName(name);
        assertFalse(Files.exists(root));
    }

    @Test public void rejectsAbsoluteDriveAndUncPaths() throws Exception {
        for (String name : Arrays.asList("/tmp/x", "\\rooted", "C:\\temp\\x", "C:x", "\\\\server\\share\\x", "//server/share/x")) rejectName(name);
    }

    @Test public void rejectsWindowsDevicesCaseInsensitiveWithSuffixesAndSuperscripts() throws Exception {
        for (String name : Arrays.asList("CON", "con.txt", "PRN.png", "aux", "NUL.wav", "COM1.mp4", "com9", "LPT1.txt", "lpt9", "COM\u00b9.txt", "LPT\u00b2", "COM\u00b3", "CON .txt", "CLOCK$", "CONIN$", "CONOUT$")) rejectName(name);
        assertEquals("com10.txt", ReceivedFileStore.validateOriginalName("com10.txt"));
        assertEquals("console.txt", ReceivedFileStore.validateOriginalName("console.txt"));
    }

    @Test public void rejectsInvalidWindowsCharactersAdsControlsAndTrailingNormalization() throws Exception {
        for (String name : Arrays.asList("a:b", "a?b", "a*b", "a<b", "a>b", "a|b", "a\"b", "a\u0000b", "a\r\nb", "a\u0085b", "a\u202eb", "a.", "a ")) rejectName(name);
    }

    @Test public void rejectsEncodedDangerousPathAndInvalidSurrogates() throws Exception {
        for (String name : Arrays.asList("%2e%2e.txt", "%2Fescape", "%5cfoo", "%3Aads", "%00x", "%252e.txt", "x\ud800", "x\udc00")) rejectName(name);
    }

    @Test public void supportsChineseUnicodeAndDisplaySpecialCharacters() throws Exception {
        String name = "中文 资料-\ud83d\ude80_#&+(1).PNG";
        ReceivedFileStore.StoredFile file = store.save(name, stream("中文内容".getBytes(StandardCharsets.UTF_8)));
        assertEquals(name, file.getOriginalName());
        assertTrue(file.getStorageName().endsWith(".png"));
        assertFalse(file.getStorageName().contains("中文"));
        assertFalse(file.getStorageName().contains("#"));
        assertArrayEquals("中文内容".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file.getPath()));
    }

    @Test public void nameBoundCountsUtf8Bytes() throws Exception {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 80; i++) name.append('中');
        assertEquals(name.toString(), ReceivedFileStore.validateOriginalName(name.toString()));
        rejectName(name.append('中').toString());
    }

    @Test public void zeroByteFileIsCompleteAndHasCorrectHash() throws Exception {
        ReceivedFileStore.StoredFile file = store.save("empty.bin", stream(new byte[0]));
        assertEquals(0, file.getSize());
        assertEquals(0, store.sizeOf(file.getStorageName()));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", file.getSha256());
        try (InputStream input = store.openForRead(file.getStorageName())) { assertEquals(-1, input.read()); }
    }

    @Test public void imageWavAndVideoExtensionsAndBytesArePreserved() throws Exception {
        for (String name : Arrays.asList("image.jpg", "photo.png", "voice.wav", "movie.mp4", "clip.webm")) {
            byte[] bytes = new byte[]{0, (byte) 255, 1, 2, 0, 4};
            ReceivedFileStore.StoredFile file = store.save(name, stream(bytes));
            assertTrue(file.getStorageName().endsWith(name.substring(name.lastIndexOf('.'))));
            assertArrayEquals(bytes, Files.readAllBytes(file.getPath()));
        }
    }

    @Test public void dangerousOrUnusualExtensionsDoNotCreateIncompleteNames() throws Exception {
        for (String name : Arrays.asList("source.part", "source.tmp", "noextension", "文件.中文", "long.abcdefghijklmnop")) {
            assertTrue(store.save(name, stream(new byte[0])).getStorageName().endsWith(".bin"));
        }
    }

    @Test public void exactFileSizeLimitSucceeds() throws Exception {
        ReceivedFileStore small = new ReceivedFileStore(root, 4, 10, 3);
        assertEquals(4, small.save("x.bin", stream(new byte[4])).getSize());
    }

    @Test public void fileSizeLimitPlusOneFailsAndCleansPart() throws Exception {
        final ReceivedFileStore small = new ReceivedFileStore(root, 4, 10, 3);
        expectLimit(new IoAction() { public void run() throws Exception { small.save("x.bin", stream(new byte[5])); }});
        assertEquals(0, countFiles(root));
    }

    @Test public void directoryByteQuotaDoesNotOverwriteExistingFile() throws Exception {
        final ReceivedFileStore small = new ReceivedFileStore(root, 8, 5, 4);
        ReceivedFileStore.StoredFile saved = small.save("first.bin", stream(new byte[]{1, 2, 3}));
        expectLimit(new IoAction() { public void run() throws Exception { small.save("too-large.bin", stream(new byte[3])); }});
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(saved.getPath()));
        assertEquals(1, countFiles(root));
        assertEquals(2, small.save("fits.bin", stream(new byte[2])).getSize());
    }

    @Test public void directoryFileCountCountsZeroByteAndPreexistingFiles() throws Exception {
        Files.createDirectory(root);
        Files.write(root.resolve("existing.txt"), new byte[0]);
        final ReceivedFileStore small = new ReceivedFileStore(root, 8, 5, 2);
        small.save("zero.bin", stream(new byte[0]));
        expectLimit(new IoAction() { public void run() throws Exception { small.save("third.bin", stream(new byte[0])); }});
        assertEquals(2, countFiles(root));
    }

    @Test public void interruptedInputLeavesNeitherPartNorCompleteFile() throws Exception {
        try {
            store.save("broken.mp4", new InputStream() {
                private int emitted;
                @Override public int read() throws IOException {
                    if (emitted++ >= 3) throw new IOException("synthetic transfer interruption");
                    return 7;
                }
            });
            fail("interrupted stream must fail");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("synthetic")); }
        assertEquals(0, countFiles(root));
    }

    @Test public void runtimeInputFailureAlsoCleansIncompleteFile() throws Exception {
        try {
            store.save("broken.wav", new InputStream() {
                @Override public int read() { throw new IllegalStateException("synthetic runtime failure"); }
            });
            fail("runtime failure must propagate");
        } catch (IllegalStateException expected) { assertEquals("synthetic runtime failure", expected.getMessage()); }
        assertEquals(0, countFiles(root));
    }

    @Test public void zeroLengthReadCannotCreateAnInfiniteLoop() throws Exception {
        InputStream source = new InputStream() {
            int emitted;
            @Override public int read(byte[] b, int off, int len) { return 0; }
            @Override public int read() { return emitted++ == 0 ? 33 : -1; }
        };
        ReceivedFileStore.StoredFile file = store.save("zero-read.bin", source);
        assertArrayEquals(new byte[]{33}, Files.readAllBytes(file.getPath()));
    }

    @Test public void completeReadNeverExposesPartOrHiddenFiles() throws Exception {
        Files.createDirectory(root);
        for (String name : Arrays.asList(".part-incomplete", ".hidden", "unfinished.part", "unfinished.tmp")) {
            Files.write(root.resolve(name), new byte[]{1});
            expectIo(new IoAction() { public void run() throws Exception { store.openForRead(name); }});
        }
    }

    @Test public void downloadsRejectTraversalAndOversizedLegacyFile() throws Exception {
        Files.createDirectory(root);
        for (final String name : Arrays.asList("../x", "..\\x", "C:x", "%2e%2e", "a/b")) {
            expectIo(new IoAction() { public void run() throws Exception { store.sizeOf(name); }});
        }
        Files.write(root.resolve("large.bin"), new byte[9]);
        final ReceivedFileStore small = new ReceivedFileStore(root, 8, 20, 5);
        expectLimit(new IoAction() { public void run() throws Exception { small.openForRead("large.bin"); }});
    }

    @Test public void readsSafeLegacyNamesWithoutRenamingOrDeletingThem() throws Exception {
        Files.createDirectory(root);
        Files.write(root.resolve("旧照片.png"), new byte[]{5, 6});
        assertEquals(2, store.sizeOf("旧照片.png"));
        try (InputStream input = store.openForRead("旧照片.png")) { assertArrayEquals(new byte[]{5, 6}, readAll(input)); }
    }

    @Test public void deleteOwnedDeletesOnlyExactOwnedObject() throws Exception {
        ReceivedFileStore.StoredFile file = store.save("x.bin", stream(new byte[]{1}));
        final ReceivedFileStore foreign = new ReceivedFileStore(root);
        expectIo(new IoAction() { public void run() throws Exception { foreign.deleteOwned(file); }});
        assertTrue(Files.exists(file.getPath()));
        store.deleteOwned(file);
        assertFalse(Files.exists(file.getPath()));
        assertEquals(0, countFiles(root));
    }

    @Test public void replacingOwnedFileDoesNotAuthorizeDeletionOfReplacement() throws Exception {
        final ReceivedFileStore.StoredFile saved = store.save("x.bin", stream(new byte[]{1}));
        Path original = temporary.resolve("original.bin");
        Files.move(saved.getPath(), original);
        Files.write(saved.getPath(), new byte[]{9});
        expectIo(new IoAction() { public void run() throws Exception { store.deleteOwned(saved); }});
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(saved.getPath()));
    }

    @Test public void directoryIdentityReplacementIsRejected() throws Exception {
        store.save("first.bin", stream(new byte[0]));
        Files.move(root, temporary.resolve("old-root"));
        Files.createDirectory(root);
        expectIo(new IoAction() { public void run() throws Exception { store.save("second.bin", stream(new byte[0])); }});
        assertEquals(0, countFiles(root));
    }

    @Test public void boundedReadTracksSkipAndDisallowsRewind() throws Exception {
        ReceivedFileStore.StoredFile saved = store.save("x.bin", stream(new byte[]{1, 2, 3}));
        try (InputStream input = store.openForRead(saved.getStorageName())) {
            assertEquals(3, input.skip(Long.MAX_VALUE));
            assertEquals(-1, input.read());
            assertFalse(input.markSupported());
            try { input.reset(); fail("rewind must not bypass bounds"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("unsupported")); }
        }
    }

    @Test public void growingStoredFileIsNotSilentlyServed() throws Exception {
        ReceivedFileStore.StoredFile saved = store.save("x.bin", stream(new byte[]{1}));
        InputStream input = store.openForRead(saved.getStorageName());
        try {
            Files.write(saved.getPath(), new byte[]{1, 2});
            try { readAll(input); fail("growth must fail"); }
            catch (IOException expected) { assertTrue(expected.getMessage().startsWith("SIZE_LIMIT:")); }
        } finally {
            try { input.close(); fail("changed file close must fail"); }
            catch (IOException expected) { assertTrue(expected.getMessage().startsWith("UNSAFE_PATH:")); }
        }
    }

    @Test public void nestedUnexpectedDirectoryIsRejectedRatherThanTraversed() throws Exception {
        Files.createDirectories(root.resolve("nested"));
        expectIo(new IoAction() { public void run() throws Exception { store.save("x.bin", stream(new byte[0])); }});
        assertFalse(Files.exists(root.resolve("nested").resolve("x.bin")));
    }

    @Test public void linkedRootOrWindowsJunctionCannotEscapeAuthorizedDirectory() throws Exception {
        Path outside = temporary.resolve("outside");
        Files.createDirectory(outside);
        createDirectoryLink(root, outside);
        try {
            expectIo(new IoAction() { public void run() throws Exception { store.save("x.bin", stream(new byte[]{1})); }});
            assertEquals(0, countFiles(outside));
        } finally {
            Files.delete(root); // Remove only the link itself, never recursively traverse its target.
        }
    }

    @Test public void linkedAncestorOrWindowsJunctionIsRejected() throws Exception {
        Path outside = temporary.resolve("outside");
        Files.createDirectory(outside);
        Path link = temporary.resolve("linked-parent");
        createDirectoryLink(link, outside);
        final ReceivedFileStore linked = new ReceivedFileStore(link.resolve("received"));
        try {
            expectIo(new IoAction() { public void run() throws Exception { linked.save("x.bin", stream(new byte[]{1})); }});
            assertFalse(Files.exists(outside.resolve("received")));
        } finally { Files.delete(link); }
    }

    @Test public void zeroFileLimitAcceptsOnlyEmptyFile() throws Exception {
        final ReceivedFileStore small = new ReceivedFileStore(root, 0, 0, 2);
        assertEquals(0, small.save("empty.bin", stream(new byte[0])).getSize());
        expectLimit(new IoAction() { public void run() throws Exception { small.save("nonempty.bin", stream(new byte[]{1})); }});
        assertEquals(1, countFiles(root));
    }

    private void rejectName(String name) throws Exception {
        try { store.save(name, stream(new byte[]{1})); fail("unsafe filename was accepted: " + name); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().startsWith("UNSAFE_FILENAME:")); }
    }
    private static ByteArrayInputStream stream(byte[] bytes) { return new ByteArrayInputStream(bytes); }
    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
    private static int countFiles(Path directory) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) { for (Path file : files) count++; }
        return count;
    }
    private interface IoAction { void run() throws Exception; }
    private static void expectIo(IoAction action) throws Exception {
        try { action.run(); fail("expected rejection"); }
        catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }
    private static void expectLimit(IoAction action) throws Exception {
        try { action.run(); fail("expected size/quota rejection"); }
        catch (ReceivedFileStore.SizeLimitException expected) { assertTrue(expected.getMessage().startsWith("SIZE_LIMIT:")); }
    }
    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            assertTrue("junction fixture command timed out", process.waitFor(10, TimeUnit.SECONDS));
            byte[] output = readAll(process.getInputStream());
            assertEquals("junction fixture creation failed: " + new String(output, StandardCharsets.UTF_8), 0, process.exitValue());
            assertTrue("junction fixture missing", Files.exists(link, LinkOption.NOFOLLOW_LINKS));
        } else {
            Files.createSymbolicLink(link, target);
        }
    }
}

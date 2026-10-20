package org.example;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import static org.junit.Assert.*;

public class FileTransferReceiverTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final AtomicLong now = new AtomicLong(1000L);
    private Path root;

    private FileTransferReceiver receiver() throws Exception {
        root = temporary.newFolder().toPath().resolve("inbox");
        return new FileTransferReceiver(new ReceivedFileStore(root), defaults(), now::get);
    }
    private FileTransferReceiver.Limits defaults() {
        return new FileTransferReceiver.Limits(1024, 100, 4096, 8, 65536, 32, 65536, 100, 1000);
    }
    private FileTransferReceiver limited(long fileBytes, int chunks, int chunkChars, int active,
                                         long buffer, int terminal, long hashes) throws Exception {
        root = temporary.newFolder().toPath().resolve("inbox");
        return new FileTransferReceiver(new ReceivedFileStore(root), new FileTransferReceiver.Limits(
                fileBytes, chunks, chunkChars, active, buffer, terminal, hashes, 100, 1000), now::get);
    }
    private static String frame(String type, String id, String name, int index, int total, String data) {
        return "#FILE|" + type + "|" + id + "|" + Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8))
                + "|" + index + "|" + total + "|" + data;
    }
    private static String file(String id, String name, byte[] data) {
        return frame("IMG", id, name, 0, 1, Base64.getEncoder().encodeToString(data));
    }
    private static List<String> frames(String type, String id, String name, byte[] data, int size) {
        String encoded = Base64.getEncoder().encodeToString(data);
        int total = Math.max(1, (encoded.length() + size - 1) / size);
        List<String> result = new ArrayList<String>();
        for (int i = 0; i < total; i++) result.add(frame(type, id, name, i, total,
                encoded.substring(i * size, Math.min(encoded.length(), (i + 1) * size))));
        return result;
    }
    private void noFiles() throws Exception {
        if (Files.exists(root)) try (Stream<Path> paths = Files.walk(root)) {
            assertEquals(0, paths.filter(Files::isRegularFile).count());
        }
    }
    private static void status(int expected, FileTransferReceiver.Result result) {
        assertEquals(result.getError(), expected, result.getSipStatus());
        if (expected != 200) { assertFalse(result.isComplete()); assertNull(result.getStoredFile()); assertNotNull(result.getError()); }
    }
    private static byte[] bytes(int length) {
        byte[] data = new byte[length]; for (int i = 0; i < length; i++) data[i] = (byte) (i * 37 + 17); return data;
    }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", b & 255));
        return hex.toString();
    }

    @Test public void constructionDoesNotCreateDirectoriesOrStartAnything() throws Exception {
        FileTransferReceiver r = receiver(); assertFalse(Files.exists(root)); assertEquals(0, r.activeTransferCount());
    }
    @Test public void ordinaryFileCompletesAndSha256Matches() throws Exception {
        FileTransferReceiver r = receiver(); byte[] original = bytes(513);
        FileTransferReceiver.Result result = r.accept("100", file("normal", "photo.png", original));
        status(200, result); assertTrue(result.isComplete()); assertEquals("IMG", result.getType());
        assertArrayEquals(original, Files.readAllBytes(result.getStoredFile().getPath()));
        assertEquals(sha256(original), result.getStoredFile().getSha256());
        assertEquals(original.length, result.getStoredFile().getSize()); assertEquals(0, r.activeTransferCount());
        assertEquals(0, r.bufferedBytes());
    }
    @Test public void outOfOrderChunksAreReassembledByIndex() throws Exception {
        FileTransferReceiver r = receiver(); byte[] original = bytes(17); List<String> f = frames("VIDEO", "oo", "film.mp4", original, 4);
        FileTransferReceiver.Result result = null;
        for (int i = f.size() - 1; i >= 0; i--) { result = r.accept("101", f.get(i)); status(200, result); }
        assertTrue(result.isComplete()); assertArrayEquals(original, Files.readAllBytes(result.getStoredFile().getPath()));
    }
    @Test public void chunkBoundariesNeedNotAlignWithBase64Quartets() throws Exception {
        FileTransferReceiver r = receiver(); byte[] original = bytes(19); List<String> f = frames("VOICE", "unaligned", "voice.wav", original, 3);
        FileTransferReceiver.Result result = null;
        for (String body : f) result = r.accept("101", body);
        status(200, result); assertTrue(result.isComplete()); assertArrayEquals(original, Files.readAllBytes(result.getStoredFile().getPath()));
    }
    @Test public void sameOriginalNameDoesNotOverwrite() throws Exception {
        FileTransferReceiver r = receiver();
        FileTransferReceiver.Result a = r.accept("100", file("a", "same.png", bytes(10)));
        FileTransferReceiver.Result b = r.accept("100", file("b", "same.png", bytes(11)));
        assertTrue(a.isComplete()); assertTrue(b.isComplete()); assertNotEquals(a.getStoredFile().getPath(), b.getStoredFile().getPath());
        assertArrayEquals(bytes(10), Files.readAllBytes(a.getStoredFile().getPath()));
        assertArrayEquals(bytes(11), Files.readAllBytes(b.getStoredFile().getPath()));
    }
    @Test public void senderIsPartOfTransferIdentity() throws Exception {
        FileTransferReceiver r = receiver(); List<String> a = frames("IMG", "shared", "a.png", bytes(5), 4);
        List<String> b = frames("IMG", "shared", "b.png", bytes(6), 4);
        status(200, r.accept("100", a.get(0))); status(200, r.accept("101", b.get(0)));
        FileTransferReceiver.Result ra = r.accept("100", a.get(1)); FileTransferReceiver.Result rb = r.accept("101", b.get(1));
        assertTrue(ra.isComplete()); assertTrue(rb.isComplete());
        assertArrayEquals(bytes(5), Files.readAllBytes(ra.getStoredFile().getPath()));
        assertArrayEquals(bytes(6), Files.readAllBytes(rb.getStoredFile().getPath()));
    }
    @Test public void duplicatePendingChunkIsIdempotentAndDoesNotAddMemory() throws Exception {
        FileTransferReceiver r = receiver(); String body = frame("IMG", "dup", "a.png", 0, 2, "AAAA");
        status(200, r.accept("100", body)); long memory = r.bufferedBytes();
        status(200, r.accept("100", body)); assertEquals(memory, r.bufferedBytes()); assertEquals(1, r.activeTransferCount()); noFiles();
    }
    @Test public void duplicateCompletedChunkNeverPublishesAgain() throws Exception {
        FileTransferReceiver r = receiver(); String body = file("finished", "a.png", bytes(4));
        assertTrue(r.accept("100", body).isComplete()); FileTransferReceiver.Result replay = r.accept("100", body);
        status(200, replay); assertFalse(replay.isComplete()); assertNull(replay.getStoredFile());
        try (Stream<Path> files = Files.walk(root)) { assertEquals(1, files.filter(Files::isRegularFile).count()); }
    }
    @Test public void completedChunkConflictIsRejectedUsingFingerprint() throws Exception {
        FileTransferReceiver r = receiver(); assertTrue(r.accept("100", file("done", "a.png", bytes(4))).isComplete());
        status(409, r.accept("100", file("done", "a.png", bytes(5))));
    }
    @Test public void completedMetadataConflictIsRejected() throws Exception {
        FileTransferReceiver r = receiver(); assertTrue(r.accept("100", file("done", "a.png", bytes(4))).isComplete());
        status(409, r.accept("100", file("done", "b.png", bytes(4))));
    }
    @Test public void conflictingDuplicateClearsAllBufferedState() throws Exception {
        FileTransferReceiver r = receiver(); r.accept("100", frame("IMG", "conflict", "a.png", 0, 2, "AAAA"));
        status(409, r.accept("100", frame("IMG", "conflict", "a.png", 0, 2, "BBBB")));
        assertEquals(0, r.activeTransferCount()); assertEquals(0, r.bufferedBytes()); noFiles();
        status(409, r.accept("100", frame("IMG", "conflict", "a.png", 1, 2, "AAAA")));
    }
    @Test public void typeChangeClearsTransfer() throws Exception {
        FileTransferReceiver r = receiver(); r.accept("100", frame("IMG", "metadata", "a.png", 0, 2, "AAAA"));
        status(409, r.accept("100", frame("VIDEO", "metadata", "a.png", 1, 2, "AAAA"))); assertEquals(0, r.bufferedBytes()); noFiles();
    }
    @Test public void nameChangeClearsTransfer() throws Exception {
        FileTransferReceiver r = receiver(); r.accept("100", frame("IMG", "metadata", "a.png", 0, 2, "AAAA"));
        status(409, r.accept("100", frame("IMG", "metadata", "b.png", 1, 2, "AAAA"))); noFiles();
    }
    @Test public void totalChangeClearsTransfer() throws Exception {
        FileTransferReceiver r = receiver(); r.accept("100", frame("IMG", "metadata", "a.png", 0, 2, "AAAA"));
        status(409, r.accept("100", frame("IMG", "metadata", "a.png", 1, 3, "AAAA"))); noFiles();
    }
    @Test public void chineseUnicodeAndSafePunctuationNamesRoundTrip() throws Exception {
        FileTransferReceiver r = receiver(); String name = "图片 表情😀-_(1).png";
        FileTransferReceiver.Result result = r.accept("100", file("unicode", name, bytes(9)));
        status(200, result); assertEquals(name, result.getStoredFile().getOriginalName());
    }
    @Test public void slashTraversalIsRejected() throws Exception { rejectName("../outside.txt"); }
    @Test public void backslashTraversalIsRejected() throws Exception { rejectName("..\\outside.txt"); }
    @Test public void unixAbsolutePathIsRejected() throws Exception { rejectName("/outside.txt"); }
    @Test public void driveAbsolutePathIsRejected() throws Exception { rejectName("C:\\outside.txt"); }
    @Test public void driveRelativePathIsRejected() throws Exception { rejectName("C:outside.txt"); }
    @Test public void uncPathIsRejected() throws Exception { rejectName("\\\\server\\share\\a.png"); }
    @Test public void reservedDeviceNameIsRejected() throws Exception { rejectName("CON.png"); }
    @Test public void alternateStreamNameIsRejected() throws Exception { rejectName("photo.png:secret"); }
    @Test public void trailingDotIsRejected() throws Exception { rejectName("photo.png."); }
    @Test public void nulNameIsRejected() throws Exception { rejectName("x\u0000.png"); }
    private void rejectName(String name) throws Exception {
        FileTransferReceiver r = receiver(); status(400, r.accept("100", file("badname", name, bytes(3))));
        assertEquals(0, r.activeTransferCount()); noFiles();
    }
    @Test public void invalidUtf8FilenameIsRejected() throws Exception {
        FileTransferReceiver r = receiver();
        status(400, r.accept("100", "#FILE|IMG|badutf|" + Base64.getEncoder().encodeToString(new byte[]{(byte) 0xc3, 0x28}) + "|0|1|AAAA")); noFiles();
    }
    @Test public void noncanonicalFilenameBase64IsRejected() throws Exception {
        FileTransferReceiver r = receiver(); status(400, r.accept("100", "#FILE|IMG|name64|YQ|0|1|AAAA")); noFiles();
    }
    @Test public void zeroByteFileIsExplicitlySupported() throws Exception {
        FileTransferReceiver r = receiver(); FileTransferReceiver.Result result = r.accept("100", file("empty", "empty.bin", new byte[0]));
        status(200, result); assertTrue(result.isComplete()); assertEquals(0, Files.size(result.getStoredFile().getPath()));
        assertEquals(sha256(new byte[0]), result.getStoredFile().getSha256());
    }
    @Test public void incompleteTransfersNeverCreatePublishedOrTemporaryFiles() throws Exception {
        FileTransferReceiver r = receiver(); status(200, r.accept("100", frame("IMG", "partial", "a.png", 0, 3, "AAAA")));
        noFiles(); r.close(); assertEquals(0, r.bufferedBytes()); noFiles();
    }
    @Test public void emptyChunkInMultipartTransferIsRejected() throws Exception {
        FileTransferReceiver r = receiver(); status(400, r.accept("100", frame("IMG", "emptychunk", "a.png", 0, 2, ""))); noFiles();
    }
    @Test public void chunkCountLimitRejectsBeforeSlotAllocation() throws Exception {
        FileTransferReceiver r = limited(10, 2, 4, 1, 1024, 4, 4096);
        status(413, r.accept("100", frame("IMG", "many", "a.png", 0, 3, "AAAA"))); assertEquals(0, r.activeTransferCount()); noFiles();
    }
    @Test public void sparseLargeTotalOnlyAccountsReceivedData() throws Exception {
        root = temporary.newFolder().toPath().resolve("inbox"); FileTransferReceiver r = new FileTransferReceiver(new ReceivedFileStore(root));
        status(200, r.accept("100", frame("IMG", "sparse", "a.png", 0, FileTransferReceiver.MAX_CHUNKS, "AAAA")));
        assertEquals(136, r.bufferedBytes()); assertEquals(1, r.activeTransferCount()); noFiles(); r.close();
    }
    @Test public void chunkCharacterLimitRejects() throws Exception {
        FileTransferReceiver r = limited(10, 10, 4, 1, 1024, 4, 4096);
        status(413, r.accept("100", frame("IMG", "wide", "a.png", 0, 1, "AAAAAAAA"))); noFiles();
    }
    @Test public void exactDecodedFileLimitPasses() throws Exception {
        FileTransferReceiver r = limited(4, 10, 8, 1, 4096, 4, 4096);
        FileTransferReceiver.Result result = r.accept("100", file("exact", "a.png", bytes(4))); status(200, result); assertTrue(result.isComplete());
    }
    @Test public void decodedLimitRejectsEvenWhenBase64LengthHasSameQuartetCount() throws Exception {
        FileTransferReceiver r = limited(4, 10, 8, 1, 4096, 4, 4096);
        status(413, r.accept("100", file("over", "a.png", bytes(5)))); noFiles(); assertEquals(0, r.bufferedBytes());
    }
    @Test public void aggregateBufferIncludesPerChunkOverheadAndReleasesOnFailure() throws Exception {
        FileTransferReceiver r = limited(30, 10, 4, 2, 135, 4, 8192);
        status(507, r.accept("100", frame("IMG", "budget", "a.png", 0, 2, "AAAA")));
        assertEquals(0, r.bufferedBytes()); assertEquals(0, r.activeTransferCount()); noFiles();
    }
    @Test public void activeTransferLimitDoesNotAffectExistingTransfer() throws Exception {
        FileTransferReceiver r = limited(30, 10, 4, 1, 4096, 4, 8192);
        status(200, r.accept("100", frame("IMG", "one", "a.png", 0, 2, "AAAA")));
        status(507, r.accept("100", frame("IMG", "two", "a.png", 0, 2, "AAAA")));
        assertTrue(r.accept("100", frame("IMG", "one", "a.png", 1, 2, "AAAA")).isComplete());
    }
    @Test public void terminalCacheFullFailsExplicitlyWithoutEvictingCompletedIds() throws Exception {
        FileTransferReceiver r = limited(30, 10, 4, 1, 4096, 1, 4096);
        String first = file("one", "a.png", bytes(3)); assertTrue(r.accept("100", first).isComplete());
        status(507, r.accept("100", file("two", "a.png", bytes(3)))); status(200, r.accept("100", first));
        assertEquals(1, r.terminalIdCount());
    }
    @Test public void terminalFingerprintBudgetIsReservedBeforeAcceptingTransfer() throws Exception {
        FileTransferReceiver r = limited(30, 10, 4, 1, 4096, 4, 700);
        status(507, r.accept("100", frame("IMG", "hashbudget", "a.png", 0, 2, "AAAA")));
        assertEquals(0, r.activeTransferCount()); assertEquals(0, r.reservedTerminalBytes()); noFiles();
    }
    @Test public void idleTimeoutClearsBufferAndRejectsLateFragments() throws Exception {
        FileTransferReceiver r = receiver(); r.accept("100", frame("IMG", "idle", "a.png", 0, 2, "AAAA"));
        now.addAndGet(100); r.expire(); assertEquals(0, r.activeTransferCount()); assertEquals(0, r.bufferedBytes());
        status(408, r.accept("100", frame("IMG", "idle", "a.png", 1, 2, "AAAA"))); noFiles();
    }
    @Test public void retransmittedDuplicatesDoNotKeepTransferAlive() throws Exception {
        FileTransferReceiver r = receiver(); String body = frame("IMG", "idledup", "a.png", 0, 2, "AAAA");
        r.accept("100", body); now.addAndGet(80); r.accept("100", body); now.addAndGet(20); r.expire();
        assertEquals(0, r.activeTransferCount()); noFiles();
    }
    @Test public void absoluteLifetimeAppliesEvenWhenNewChunksContinue() throws Exception {
        FileTransferReceiver r = receiver();
        for (int i = 0; i < 12; i++) { now.set(1000 + 90L * i); status(200, r.accept("100", frame("IMG", "absolute", "a.png", i, 20, "AAAA"))); }
        now.set(2000); r.expire(); assertEquals(0, r.activeTransferCount()); assertEquals(0, r.bufferedBytes()); noFiles();
    }
    @Test public void terminalIdsExpireAndCapacityBecomesAvailable() throws Exception {
        FileTransferReceiver r = limited(30, 10, 4, 1, 4096, 1, 4096);
        assertTrue(r.accept("100", file("one", "a.png", bytes(3))).isComplete()); now.addAndGet(1000); r.expire();
        assertEquals(0, r.terminalIdCount()); assertEquals(0, r.reservedTerminalBytes());
        assertTrue(r.accept("100", file("two", "b.png", bytes(3))).isComplete());
    }
    @Test public void closeReleasesEverythingAndRejectsNewData() throws Exception {
        FileTransferReceiver r = receiver(); r.accept("100", frame("IMG", "close", "a.png", 0, 2, "AAAA")); r.close();
        assertEquals(0, r.bufferedBytes()); assertEquals(0, r.activeTransferCount()); assertEquals(0, r.terminalIdCount());
        assertEquals(0, r.reservedTerminalBytes()); status(503, r.accept("100", file("x", "a.png", bytes(3)))); noFiles();
    }
    @Test public void invalidAlphabetClearsState() throws Exception {
        FileTransferReceiver r = receiver(); r.accept("100", frame("IMG", "bad64", "a.png", 0, 2, "AAAA"));
        status(400, r.accept("100", frame("IMG", "bad64", "a.png", 1, 2, "AA!A"))); assertEquals(0, r.bufferedBytes()); noFiles();
    }
    @Test public void paddingBeforeFinalChunkIsRejected() throws Exception {
        FileTransferReceiver r = receiver(); status(400, r.accept("100", frame("IMG", "pad", "a.png", 0, 2, "AA=="))); noFiles();
    }
    @Test public void base64WhitespaceIsNotSilentlyIgnored() throws Exception {
        FileTransferReceiver r = receiver(); status(400, r.accept("100", frame("IMG", "spaces", "a.png", 0, 1, "AA A"))); noFiles();
    }
    @Test public void missingPaddingIsRejected() throws Exception {
        FileTransferReceiver r = receiver(); status(400, r.accept("100", frame("IMG", "missingpad", "a.png", 0, 1, "AA"))); noFiles();
    }
    @Test public void nonzeroPaddingBitsAreRejected() throws Exception {
        FileTransferReceiver r = receiver(); status(400, r.accept("100", frame("IMG", "padbits", "a.png", 0, 1, "AB=="))); noFiles();
    }
    @Test public void invalidSenderAndIdAreRejected() throws Exception {
        FileTransferReceiver r = receiver(); status(400, r.accept("../100", file("id", "a.png", bytes(3))));
        status(400, r.accept("100", file("../id", "a.png", bytes(3)))); noFiles();
    }
    @Test public void invalidTypeIsRejected() throws Exception {
        FileTransferReceiver r = receiver(); status(400, r.accept("100", frame("EXE", "type", "a.png", 0, 1, "AAAA"))); noFiles();
    }
    @Test public void negativeIndexAndIndexEqualTotalAreRejected() throws Exception {
        FileTransferReceiver r = receiver(); status(400, r.accept("100", frame("IMG", "negative", "a.png", -1, 1, "AAAA")));
        status(400, r.accept("100", frame("IMG", "equal", "a.png", 1, 1, "AAAA"))); noFiles();
    }
    @Test public void malformedCountDoesNotOverflowOrAllocate() throws Exception {
        FileTransferReceiver r = receiver(); String body = frame("IMG", "overflow", "a.png", 0, 1, "AAAA").replace("|0|1|", "|0|2147483648|");
        status(400, r.accept("100", body)); assertEquals(0, r.activeTransferCount()); noFiles();
    }
    @Test public void zeroTotalAndIncompleteEnvelopeAreRejected() throws Exception {
        FileTransferReceiver r = receiver(); status(400, r.accept("100", frame("IMG", "zero", "a.png", 0, 0, "AAAA")));
        status(400, r.accept("100", "#FILE|IMG|short")); noFiles();
    }
    @Test public void bodyByteLimitIsCheckedBeforeParsing() throws Exception {
        FileTransferReceiver r = receiver(); char[] large = new char[FileTransferReceiver.MAX_BODY_BYTES + 1]; Arrays.fill(large, 'A');
        status(413, r.accept("100", new String(large))); noFiles();
    }
    @Test public void storeFailureDoesNotClaimSuccessOrLeavePublishedFile() throws Exception {
        root = temporary.newFolder().toPath().resolve("not-directory"); Files.write(root, bytes(2));
        FileTransferReceiver r = new FileTransferReceiver(new ReceivedFileStore(root));
        status(507, r.accept("100", file("storage", "a.png", bytes(3)))); assertEquals(0, r.activeTransferCount()); assertEquals(0, r.bufferedBytes());
        assertArrayEquals(bytes(2), Files.readAllBytes(root));
    }
    @Test public void imageVoiceAndVideoProtocolTypesRemainCompatible() throws Exception {
        FileTransferReceiver r = receiver();
        String[] types = {"IMG", "VOICE", "VIDEO"}; String[] names = {"photo.png", "voice.wav", "film.mp4"};
        byte[][] content = {new byte[]{(byte)137,80,78,71,13,10,26,10}, "RIFF0000WAVEfmt ".getBytes(StandardCharsets.US_ASCII),
                new byte[]{0,0,0,24,102,116,121,112,109,112,52,50}};
        for (int i = 0; i < types.length; i++) {
            List<String> parts = frames(types[i], "compat" + i, names[i], content[i], 4); FileTransferReceiver.Result result = null;
            for (String body : parts) result = r.accept("100", body);
            status(200, result); assertTrue(result.isComplete()); assertEquals(types[i], result.getType());
            assertArrayEquals(content[i], Files.readAllBytes(result.getStoredFile().getPath()));
            assertEquals(sha256(content[i]), result.getStoredFile().getSha256());
        }
    }
    @Test public void defaultBudgetCanAccommodateOneMaximumFileAtLegacyChunkSize() {
        long encoded = ((ReceivedFileStore.MAX_FILE_BYTES + 2L) / 3L) * 4L;
        long chunks = (encoded + 799) / 800;
        assertTrue(chunks <= FileTransferReceiver.MAX_CHUNKS);
        assertTrue(encoded * 2 + chunks * 128 <= FileTransferReceiver.MAX_BUFFER_BYTES);
        assertTrue(chunks * 160 + 512 <= FileTransferReceiver.MAX_TERMINAL_BYTES);
    }
    @Test public void oversizedIdentifiableBodyClearsExistingTransfer() throws Exception {
        FileTransferReceiver r = receiver(); String first = frame("IMG", "oversized", "a.png", 0, 2, "AAAA");
        r.accept("100", first); char[] data = new char[9000]; Arrays.fill(data, 'A');
        status(413, r.accept("100", frame("IMG", "oversized", "a.png", 1, 2, new String(data))));
        assertEquals(0, r.activeTransferCount()); assertEquals(0, r.bufferedBytes()); noFiles();
    }
    @Test public void rawOversizePrefixEntryPointClearsOnlyMatchingSender() throws Exception {
        FileTransferReceiver r = receiver(); String first = frame("IMG", "rawlarge", "a.png", 0, 2, "AAAA");
        r.accept("100", first); r.accept("101", first);
        status(413, r.rejectOversized("100", "#FILE|IMG|rawlarge|bounded-prefix"));
        assertEquals(1, r.activeTransferCount());
        assertTrue(r.accept("101", frame("IMG", "rawlarge", "a.png", 1, 2, "AAAA")).isComplete());
        status(413, r.accept("100", frame("IMG", "rawlarge", "a.png", 1, 2, "AAAA")));
    }
    @Test public void malformedUtf8BodyPrefixClearsPartialTransfer() throws Exception {
        FileTransferReceiver r = receiver(); r.accept("100", frame("IMG", "utfbody", "a.png", 0, 2, "AAAA"));
        status(400, r.rejectMalformed("100", "#FILE|IMG|utfbody|invalid-utf8-follows"));
        assertEquals(0, r.activeTransferCount()); assertEquals(0, r.bufferedBytes()); noFiles();
    }
    @Test public void completedEarlierChunkReplayUsesItsOwnFingerprint() throws Exception {
        FileTransferReceiver r = receiver(); List<String> parts = frames("IMG", "oldchunk", "a.png", bytes(7), 4);
        for (String body : parts) status(200, r.accept("100", body));
        FileTransferReceiver.Result replay = r.accept("100", parts.get(0));
        status(200, replay); assertFalse(replay.isComplete());
        status(409, r.accept("100", frame("IMG", "oldchunk", "a.png", 0, parts.size(), "AAAA")));
    }
    @Test public void base64PaddingMayCrossTheLastTwoChunks() throws Exception {
        FileTransferReceiver r = receiver();
        status(200, r.accept("100", frame("IMG", "splitpad", "a.png", 0, 2, "AA=")));
        FileTransferReceiver.Result result = r.accept("100", frame("IMG", "splitpad", "a.png", 1, 2, "="));
        status(200, result); assertTrue(result.isComplete());
        assertArrayEquals(new byte[]{0}, Files.readAllBytes(result.getStoredFile().getPath()));
    }
    @Test public void alphabetAfterPaddingAcrossChunksIsRejectedBeforeSave() throws Exception {
        FileTransferReceiver r = receiver();
        status(200, r.accept("100", frame("IMG", "midpad", "a.png", 0, 2, "AAA=")));
        status(400, r.accept("100", frame("IMG", "midpad", "a.png", 1, 2, "AAAA")));
        assertEquals(0, r.activeTransferCount()); assertEquals(0, r.bufferedBytes()); noFiles();
    }
    @Test public void activeTransferAlreadyOwnsItsTerminalSlot() throws Exception {
        FileTransferReceiver r = limited(30, 10, 4, 2, 4096, 2, 8192);
        status(200, r.accept("100", frame("IMG", "reserved", "a.png", 0, 2, "AAAA")));
        assertTrue(r.accept("100", file("finishother", "b.png", bytes(3))).isComplete());
        status(507, r.accept("100", file("third", "c.png", bytes(3))));
        assertTrue(r.accept("100", frame("IMG", "reserved", "a.png", 1, 2, "AAAA")).isComplete());
        assertEquals(2, r.terminalIdCount()); assertEquals(0, r.activeTransferCount());
    }
    @Test public void rejectionReleasesUnusedFingerprintReservation() throws Exception {
        FileTransferReceiver r = limited(30, 10, 4, 2, 4096, 4, 1600);
        status(200, r.accept("100", frame("IMG", "hashrelease", "a.png", 0, 2, "AAAA")));
        assertEquals(832, r.reservedTerminalBytes());
        status(409, r.accept("100", frame("IMG", "hashrelease", "a.png", 0, 2, "BBBB")));
        assertEquals(512, r.reservedTerminalBytes());
        status(200, r.accept("100", frame("IMG", "next", "b.png", 0, 2, "AAAA")));
        assertEquals(1344, r.reservedTerminalBytes());
    }
}

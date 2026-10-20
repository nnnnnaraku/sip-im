package org.example;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/** Entirely in memory HTTP exchanges and independent temporary storage; no server is opened. */
public class FileHttpApiTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root;
    private ReceivedFileStore store;
    private FileHttpApi api;
    private boolean registered;
    private final List<ReceivedFileStore.StoredFile> sent = new ArrayList<>();
    private final List<String> types = new ArrayList<>();

    @Before public void setUp() throws Exception {
        root = temporary.newFolder("received").toPath();
        store = new ReceivedFileStore(root, 1024, 8192, 40);
        registered = true;
        api = new FileHttpApi(store, () -> registered, (file, type) -> { sent.add(file); types.add(type); });
    }

    @Test public void uploadPreservesDisplayNameAndSubmitsSafeStoredPath() throws Exception {
        String name = "报告 100% &#+雪.png";
        byte[] bytes = {1, 2, 3, 4};
        Exchange e = upload(name, "IMG", bytes);
        assertEquals(e.response(), 200, e.status);
        assertTrue(e.json().get("ok").getAsBoolean());
        assertEquals("SUBMITTED", e.json().get("status").getAsString());
        assertTrue(e.json().get("acceptedForSending").getAsBoolean());
        assertEquals(name, sent.get(0).getOriginalName());
        assertNotEquals(name, sent.get(0).getStorageName());
        assertArrayEquals(bytes, Files.readAllBytes(sent.get(0).getPath()));
        assertEquals(FileHttpApi.fileUrl(sent.get(0).getStorageName()), e.json().get("url").getAsString());
        assertEquals(64, e.json().get("sha256").getAsString().length());
        assertTrue(e.closed);
    }

    @Test public void zeroByteUploadAndDownloadAreExplicitSuccess() throws Exception {
        Exchange e = upload("empty.wav", "VOICE", new byte[0]);
        assertEquals(200, e.status);
        assertEquals(0, e.json().get("size").getAsLong());
        Exchange get = request("GET", e.json().get("url").getAsString(), new byte[0]);
        assertEquals(200, get.status);
        assertEquals("0", get.responseHeaders.getFirst("Content-Length"));
        assertEquals(0, get.responseBody.size());
    }

    @Test public void originalMediaMessageTypesRemainCompatible() throws Exception {
        for (String[] sample : new String[][] {{"photo.png", "IMG"}, {"sound.wav", "VOICE"}, {"movie.mp4", "VIDEO"}}) {
            Exchange e = upload(sample[0], sample[1], new byte[] {5, 6});
            assertEquals(e.response(), 200, e.status);
            assertEquals(sample[1], types.get(types.size() - 1));
            Exchange get = request("GET", e.json().get("url").getAsString(), new byte[0]);
            assertEquals(200, get.status);
            assertArrayEquals(new byte[] {5, 6}, get.responseBody.toByteArray());
        }
    }

    @Test public void duplicateOriginalNamesDoNotOverwrite() throws Exception {
        upload("same.png", "IMG", new byte[] {1});
        upload("same.png", "IMG", new byte[] {2});
        assertNotEquals(sent.get(0).getPath(), sent.get(1).getPath());
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(sent.get(0).getPath()));
        assertArrayEquals(new byte[] {2}, Files.readAllBytes(sent.get(1).getPath()));
    }

    @Test public void declaredOversizeIsRejectedBeforeReadingBody() throws Exception {
        Exchange e = new Exchange("POST", "/api/file?name=a.png&type=IMG", new byte[0]);
        e.requestHeaders.set("Content-Length", "1025");
        e.requestBody = new InputStream() { @Override public int read() { throw new AssertionError("read oversized body"); } };
        api.handle(e);
        assertEquals(413, e.status);
        assertTrue(sent.isEmpty());
        assertEquals(0, files());
    }

    @Test public void streamingOversizeIsRejectedAndDeleted() throws Exception {
        Exchange e = upload("large.png", "IMG", new byte[1025]);
        assertEquals(413, e.status);
        assertEquals(0, files());
        assertTrue(sent.isEmpty());
    }

    @Test public void interruptedUploadCleansTemporaryAndDoesNotSubmit() throws Exception {
        Exchange e = new Exchange("POST", "/api/file?name=a.png&type=IMG", new byte[0]);
        e.requestBody = new InputStream() {
            int remaining = 100;
            @Override public int read() throws IOException { if (--remaining > 0) return 8; throw new IOException("disconnect"); }
        };
        api.handle(e);
        assertEquals(500, e.status);
        assertEquals(0, files());
        assertTrue(sent.isEmpty());
    }

    @Test public void senderFailureDeletesOnlyThisUploadedFile() throws Exception {
        ReceivedFileStore.StoredFile old = store.save("keep.png", new ByteArrayInputStream(new byte[] {9}));
        api = new FileHttpApi(store, () -> true, (file, type) -> { throw new IOException("SIP unavailable"); });
        Exchange e = upload("fail.png", "IMG", new byte[] {1});
        assertEquals(500, e.status);
        assertFalse(e.json().get("ok").getAsBoolean());
        assertEquals(1, files());
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(old.getPath()));
    }

    @Test public void mismatchedContentLengthCleansFile() throws Exception {
        Exchange e = new Exchange("POST", "/api/file?name=a.png&type=IMG", new byte[] {1, 2});
        e.requestHeaders.set("Content-Length", "3");
        api.handle(e);
        assertEquals(400, e.status);
        assertEquals("CONTENT_LENGTH_MISMATCH", e.json().get("error").getAsString());
        assertEquals(0, files());
        assertTrue(sent.isEmpty());
    }

    @Test public void unregisteredDoesNotReadOrCreateFiles() throws Exception {
        registered = false;
        Exchange e = new Exchange("POST", "/api/file?name=a.png&type=IMG", new byte[0]);
        e.requestBody = new InputStream() { @Override public int read() { throw new AssertionError("read unregistered upload"); } };
        api.handle(e);
        assertEquals(503, e.status);
        assertEquals(0, files());
        assertTrue(sent.isEmpty());
    }

    @Test public void traversalAbsoluteDriveUncAndDeviceNamesAreRejected() throws Exception {
        for (String name : new String[] {"../outside.png", "..\\outside.png", "/absolute.png", "C:\\a.png", "C:a.png",
                "\\\\server\\share\\a.png", "NUL", "con.png", "LPT9.wav", "bad\u0000.png", "name. ", "x:y.png"}) {
            Exchange e = upload(name, "IMG", new byte[] {1});
            assertEquals(name, 400, e.status);
            assertFalse(e.json().get("ok").getAsBoolean());
        }
        assertEquals(0, files());
        assertTrue(sent.isEmpty());
    }

    @Test public void invalidUtf8QueryIsRejectedRatherThanReplacementDecoded() throws Exception {
        for (String value : new String[] {"%C0%AE.png", "%FF.png", "%ED%A0%80.png", "%E2%82.png"}) {
            assertEquals(400, request("POST", "/api/file?name=" + value + "&type=IMG", new byte[] {1}).status);
        }
        assertEquals(0, files());
    }

    @Test public void duplicateUnknownAndMissingQueryArgumentsFailExplicitly() throws Exception {
        for (String query : new String[] {"name=a&name=b", "name=a&type=IMG&type=VIDEO", "name=a&extra=x", "type=IMG",
                "name=", "name=a&", "name=a&flag", "na%6de=a&name=b"}) {
            assertEquals(query, 400, request("POST", "/api/file?" + query, new byte[] {1}).status);
        }
        assertEquals(0, files());
    }

    @Test public void invalidContentLengthValuesAreRejected() throws Exception {
        for (String value : new String[] {"-1", "1,1", "abc", "9223372036854775808", " 1"}) {
            Exchange e = new Exchange("POST", "/api/file?name=a.png&type=IMG", new byte[] {1});
            e.requestHeaders.set("Content-Length", value);
            api.handle(e);
            assertEquals(value, 400, e.status);
        }
        Exchange duplicate = new Exchange("POST", "/api/file?name=a.png&type=IMG", new byte[] {1});
        duplicate.requestHeaders.add("Content-Length", "1"); duplicate.requestHeaders.add("Content-Length", "1");
        api.handle(duplicate); assertEquals(400, duplicate.status);
        assertEquals(0, files());
    }

    @Test public void unsupportedMessageTypeIsRejected() throws Exception {
        for (String type : new String[] {"FILE", "img", "TEXT", "", "IMG\r\n"}) {
            assertEquals(400, upload("x.png", type, new byte[] {1}).status);
        }
        assertEquals(0, files());
    }

    @Test public void absentTypeKeepsLegacyImageDefault() throws Exception {
        Exchange e = request("POST", "/api/file?name=x.png", new byte[] {1});
        assertEquals(200, e.status);
        assertEquals("IMG", types.get(0));
    }

    @Test public void exactUploadPathAndPostMethodAreRequired() throws Exception {
        for (String method : new String[] {"GET", "PUT", "DELETE", "HEAD"}) {
            Exchange e = request(method, "/api/file?name=a.png", new byte[0]);
            assertEquals(405, e.status);
            assertEquals("POST", e.responseHeaders.getFirst("Allow"));
            if ("HEAD".equals(method)) assertEquals(0, e.responseBody.size());
        }
        assertEquals(404, request("POST", "/api/file-extra?name=a.png", new byte[] {1}).status);
        assertEquals(0, files());
    }

    @Test public void imageDownloadHasNoSniffAndSandboxHeaders() throws Exception {
        Exchange up = upload("picture.PNG", "IMG", new byte[] {1, 2, 3});
        Exchange e = request("GET", up.json().get("url").getAsString(), new byte[0]);
        assertEquals(200, e.status);
        assertEquals("image/png", e.responseHeaders.getFirst("Content-Type"));
        assertEquals("nosniff", e.responseHeaders.getFirst("X-Content-Type-Options"));
        assertTrue(e.responseHeaders.getFirst("Content-Security-Policy").contains("sandbox"));
        assertEquals("no-store", e.responseHeaders.getFirst("Cache-Control"));
        assertEquals("3", e.responseHeaders.getFirst("Content-Length"));
        assertArrayEquals(new byte[] {1, 2, 3}, e.responseBody.toByteArray());
    }

    @Test public void unknownAndActiveHtmlFilesDownloadAsAttachment() throws Exception {
        for (String name : new String[] {"document.bin", "script.html", "vector.svg"}) {
            Exchange up = upload(name, "IMG", new byte[] {1});
            Exchange e = request("GET", up.json().get("url").getAsString(), new byte[0]);
            assertEquals(200, e.status);
            assertEquals("application/octet-stream", e.responseHeaders.getFirst("Content-Type"));
            assertEquals("attachment", e.responseHeaders.getFirst("Content-Disposition"));
        }
    }

    @Test public void headChecksFileAndHasHeadersWithoutBody() throws Exception {
        Exchange up = upload("sound.wav", "VOICE", new byte[] {3, 4});
        Exchange e = request("HEAD", up.json().get("url").getAsString(), new byte[0]);
        assertEquals(200, e.status);
        assertEquals("audio/wav", e.responseHeaders.getFirst("Content-Type"));
        assertEquals("2", e.responseHeaders.getFirst("Content-Length"));
        assertEquals(0, e.responseBody.size());
        assertEquals(-1, e.responseLength);
    }

    @Test public void fileEndpointRejectsWriteMethodsAndNestedOrEncodedPaths() throws Exception {
        Exchange e = request("POST", "/files/x.png", new byte[] {1});
        assertEquals(405, e.status);
        assertEquals("GET, HEAD", e.responseHeaders.getFirst("Allow"));
        for (String suffix : new String[] {"../secret", "..%2fsecret", "..%5csecret", "%2Fsecret", "C%3Afile", "%00x", "a/b",
                "%252e%252e%252fsecret", "%255csecret", "%C0%AE", "%FF", "x.png?unused=x"}) {
            assertEquals(suffix, 400, request("GET", "/files/" + suffix, new byte[0]).status);
        }
        assertEquals(400, request("GET", "/files/", new byte[0]).status);
        assertEquals(404, request("GET", "/files", new byte[0]).status);
    }

    @Test public void missingFileAndHeadMissingFileAre404AndNeverEchoInput() throws Exception {
        for (String method : new String[] {"GET", "HEAD"}) {
            Exchange e = request(method, "/files/not-there.png", new byte[0]);
            assertEquals(404, e.status);
            assertFalse(e.response().contains("not-there"));
            if ("HEAD".equals(method)) assertEquals(0, e.responseBody.size());
        }
    }

    @Test public void rawPlusInDownloadIsNotDecodedAsSpace() throws Exception {
        Files.write(root.resolve("old+name.png"), new byte[] {8});
        Exchange e = request("GET", "/files/old+name.png", new byte[0]);
        assertEquals(200, e.status);
        assertArrayEquals(new byte[] {8}, e.responseBody.toByteArray());
    }

    @Test public void fileUrlEncodesSingleComponentWithoutLosingUnicodeOrPlus() throws Exception {
        String name = "old 空+名.png";
        Files.write(root.resolve(name), new byte[] {7});
        String url = FileHttpApi.fileUrl(name);
        assertTrue(url.contains("%20"));
        assertTrue(url.contains("%2B"));
        Exchange e = request("GET", url, new byte[0]);
        assertEquals(200, e.status);
        assertArrayEquals(new byte[] {7}, e.responseBody.toByteArray());
    }

    @Test public void aggregateDiskQuotaIsAnExplicit413() throws Exception {
        store = new ReceivedFileStore(root, 10, 12, 10);
        api = new FileHttpApi(store, () -> true, (file, type) -> { });
        assertEquals(200, upload("one.png", "IMG", new byte[8]).status);
        assertEquals(413, upload("two.png", "IMG", new byte[8]).status);
        assertEquals(1, files());
    }

    @Test public void downloadWriteFailureDoesNotAttemptSecondResponseOrAppendJson() throws Exception {
        Exchange up = upload("picture.png", "IMG", new byte[] {1, 2});
        Exchange e = new Exchange("GET", up.json().get("url").getAsString(), new byte[0]);
        e.responseStream = new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("browser disconnected"); } };
        try { api.handle(e); fail("Expected interrupted response"); } catch (IOException expected) { }
        assertEquals(1, e.headerCalls);
        assertEquals(0, e.responseBody.size());
        assertTrue(e.closed);
    }

    @Test public void lateDownloadCloseFailureDoesNotAppendErrorToFile() throws Exception {
        Exchange up = upload("picture.png", "IMG", new byte[] {1, 2});
        Exchange e = new Exchange("GET", up.json().get("url").getAsString(), new byte[0]);
        e.responseStream = new OutputStream() {
            @Override public void write(int value) { e.responseBody.write(value); }
            @Override public void close() throws IOException { throw new IOException("close failed"); }
        };
        try { api.handle(e); fail("Expected interrupted response"); } catch (IOException expected) { }
        assertEquals(1, e.headerCalls);
        assertArrayEquals(new byte[] {1, 2}, e.responseBody.toByteArray());
        assertTrue(e.closed);
    }

    @Test public void responseStateDoesNotLeakThroughJdk8SharedContextAttributes() throws Exception {
        Map<String, Object> sharedContextAttributes = new HashMap<>();
        Exchange first = new Exchange("POST", "/api/file?name=first.png&type=IMG", new byte[] {1});
        first.attributes = sharedContextAttributes;
        api.handle(first);
        assertEquals(200, first.status);
        Exchange invalid = new Exchange("POST", "/api/file?name=..%2Foutside&type=IMG", new byte[] {1});
        invalid.attributes = sharedContextAttributes;
        api.handle(invalid);
        assertEquals(400, invalid.status);
        assertEquals(1, invalid.headerCalls);
        assertTrue(sharedContextAttributes.isEmpty());
    }

    private Exchange upload(String name, String type, byte[] data) throws Exception {
        return request("POST", "/api/file?name=" + URLEncoder.encode(name, "UTF-8") + "&type=" + URLEncoder.encode(type, "UTF-8"), data);
    }
    private Exchange request(String method, String path, byte[] data) throws Exception {
        Exchange e = new Exchange(method, path, data); api.handle(e); assertTrue(e.closed); return e;
    }
    private long files() throws IOException { try (Stream<Path> paths = Files.walk(root)) { return paths.filter(Files::isRegularFile).count(); } }

    private static final class Exchange extends HttpExchange {
        final Headers requestHeaders = new Headers(), responseHeaders = new Headers();
        final String method; final URI uri; InputStream requestBody;
        final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        OutputStream responseStream = responseBody;
        Map<String, Object> attributes = new HashMap<>();
        int status, headerCalls; long responseLength; boolean closed;
        Exchange(String method, String path, byte[] body) { this.method = method; this.uri = URI.create(path); requestBody = new ByteArrayInputStream(body); }
        String response() { return new String(responseBody.toByteArray(), StandardCharsets.UTF_8); }
        JsonObject json() { return JsonParser.parseString(response()).getAsJsonObject(); }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { closed = true; }
        @Override public InputStream getRequestBody() { return requestBody; }
        @Override public OutputStream getResponseBody() { return responseStream; }
        @Override public void sendResponseHeaders(int code, long length) { headerCalls++; status = code; responseLength = length; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 50000); }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8081); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
        @Override public void setStreams(InputStream in, OutputStream out) { requestBody = in; }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}

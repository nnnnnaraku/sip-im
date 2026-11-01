package org.example;

import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 后台聊天记录：持久化、过滤、排序与损坏行容错。 */
public class ChatLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ChatLog log;
    private Path file;

    @Before
    public void setUp() {
        file = tmp.getRoot().toPath().resolve("logs").resolve("chat.jsonl");
        log = new ChatLog(file);
    }

    @Test
    public void recordsAndReadsUtf8Text() {
        log.record("OUT", "101", "TEXT", "你好\t世界\n第二行");
        ChatLog.Result r = log.query(null, null, null, 100);
        assertEquals(1, r.total);
        assertEquals(1, r.records.size());
        JsonObject o = r.records.get(0);
        assertEquals("OUT", o.get("direction").getAsString());
        assertEquals("101", o.get("peer").getAsString());
        assertEquals("TEXT", o.get("type").getAsString());
        assertTrue(o.get("text").getAsString().contains("第二行"));
        assertTrue(o.get("time").getAsString().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    public void filtersByPeerTypeAndKeyword() {
        log.record("OUT", "101", "TEXT", "明天开会");
        log.record("IN", "102", "TEXT", "收到，明天见");
        log.record("IN", "101", "IMG", "screenshot.png");

        assertEquals(2, log.query("101", null, null, 100).total);
        assertEquals(1, log.query("102", null, null, 100).total);
        assertEquals(2, log.query(null, "明天", null, 100).total);
        assertEquals(1, log.query(null, null, "IMG", 100).total);
        assertEquals(1, log.query("101", "开会", "TEXT", 100).total);
    }

    @Test
    public void returnsNewestFirstAndHonorsLimit() {
        log.record("OUT", "101", "TEXT", "first");
        log.record("IN", "101", "TEXT", "second");
        log.record("OUT", "101", "TEXT", "third");

        ChatLog.Result r = log.query(null, null, null, 2);
        assertEquals(3, r.total);
        assertEquals(2, r.records.size());
        assertEquals("third", r.records.get(0).get("text").getAsString());
        assertEquals("second", r.records.get(1).get("text").getAsString());
    }

    @Test
    public void malformedLinesAreSkipped() throws Exception {
        log.record("OUT", "101", "TEXT", "valid");
        Files.write(file, "not a json line\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.write(file, "{\"peer\":{},\"type\":[],\"text\":{}}\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        log.record("IN", "102", "TEXT", "also valid");

        ChatLog.Result r = log.query(null, null, null, 100);
        assertEquals(3, r.total);
    }

    @Test
    public void groupTextUsesGroupPeerAndGroupType() {
        log.record("IN", "群:课程小组", "GROUP", "101：大家好");
        log.record("OUT", "群:课程小组", "GROUP", "明天见");

        ChatLog.Result r = log.query("群:课程", null, "GROUP", 100);
        assertEquals(2, r.total);
        assertEquals("GROUP", r.records.get(0).get("type").getAsString());
        assertTrue(r.records.get(0).get("peer").getAsString().startsWith("群:"));
    }

    @Test
    public void missingFileReturnsEmptyResult() {
        ChatLog missing = new ChatLog(tmp.getRoot().toPath().resolve("none").resolve("chat.jsonl"));
        ChatLog.Result r = missing.query(null, null, null, 100);
        assertEquals(0, r.total);
        assertEquals(0, r.records.size());
    }

    @Test
    public void longTextIsTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append('x');
        log.record("OUT", "101", "TEXT", sb.toString());
        String stored = log.query(null, null, null, 1).records.get(0).get("text").getAsString();
        assertTrue(stored.length() <= 501);
        assertTrue(stored.endsWith("…"));
    }
}

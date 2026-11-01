package org.example;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 本机聊天记录：把私聊文字/文件消息与群聊文字追加写入 logs/chat.jsonl，供后台管理端查询。
 *
 * <p>每行一个 JSON 对象：{@code {time, direction, peer, type, text}}。
 * 群聊记录的 peer 为「群:群名」，收到的群文字在内容前标注发送者。
 * 只记录本机收发的消息，不做跨机汇总。写文件失败绝不影响通信。
 */
public final class ChatLog {

    public static final ChatLog DEFAULT = new ChatLog(Paths.get("logs", "chat.jsonl"));

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TEXT_CHARS = 500;

    private final Path file;

    ChatLog(Path file) {
        this.file = file;
    }

    /** 追加一条聊天记录；direction 为 OUT/IN，type 为 TEXT/IMG/VOICE/VIDEO/GROUP。 */
    public synchronized void record(String direction, String peer, String type, String text) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            JsonObject o = new JsonObject();
            o.addProperty("time", LocalDateTime.now().format(FMT));
            o.addProperty("direction", "IN".equals(direction) ? "IN" : "OUT");
            o.addProperty("peer", clean(peer, 64));
            o.addProperty("type", clean(type, 16));
            o.addProperty("text", clean(text, MAX_TEXT_CHARS));
            Files.write(file, (o + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // 聊天记录写不进去不影响消息收发
        }
    }

    /** 按对端/关键字/类型过滤，返回最新的 limit 条（时间倒序）。 */
    public Result query(String peer, String keyword, String type, int limit) {
        int max = limit <= 0 ? 100 : Math.min(limit, 500);
        List<JsonObject> matched = new ArrayList<>();
        for (JsonObject o : readAll()) {
            String p = str(o, "peer");
            String t = str(o, "type");
            String x = str(o, "text");
            if (!contains(p, peer)) continue;
            if (!equalsIgnoreCase(t, type)) continue;
            if (!contains(x, keyword) && !contains(p, keyword)) continue;
            matched.add(o);
        }
        int total = matched.size();
        int from = Math.max(0, total - max);
        List<JsonObject> records = new ArrayList<>(matched.subList(from, total));
        Collections.reverse(records);
        return new Result(records, total);
    }

    private List<JsonObject> readAll() {
        List<JsonObject> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) continue;
                try {
                    com.google.gson.JsonElement el = JsonParser.parseString(line);
                    if (el.isJsonObject()) out.add(el.getAsJsonObject());
                } catch (Exception ignore) {
                    // 单行损坏时跳过，不影响其它记录
                }
            }
        } catch (Exception ignore) {
            // 读失败返回已解析的部分
        }
        return out;
    }

    private static boolean contains(String value, String needle) {
        if (needle == null || needle.trim().isEmpty()) return true;
        return value.toLowerCase(Locale.ROOT).contains(needle.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean equalsIgnoreCase(String value, String expected) {
        return expected == null || expected.trim().isEmpty() || value.equalsIgnoreCase(expected.trim());
    }

    private static String clean(String s, int maxChars) {
        if (s == null) return "";
        String v = s.replace('\r', ' ').replace('\u0000', ' ');
        return v.length() <= maxChars ? v : v.substring(0, maxChars) + "…";
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 查询结果：total 为过滤后的总条数，records 为最新 limit 条。 */
    public static final class Result {
        public final List<JsonObject> records;
        public final int total;

        Result(List<JsonObject> records, int total) {
            this.records = records;
            this.total = total;
        }
    }
}

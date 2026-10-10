package org.example;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 客户端事件日志：把通话与消息事件追加写入 logs/events.tsv，供后台管理端统计。
 *
 * <p>每行一个事件，字段用 TAB 分隔：
 * <pre>时间 \t 类型 \t 方向 \t 对端 \t 备注</pre>
 * <ul>
 *   <li>类型：CALL_START / CALL_END / MSG</li>
 *   <li>方向：OUT（我发起/发出） / IN（我收到）</li>
 *   <li>对端：对方分机号</li>
 *   <li>备注：CALL_END 记通话秒数；MSG 记 "类型:字节数"（如 TEXT:12 / IMG:2036）</li>
 * </ul>
 *
 * <p>写日志失败绝不影响主流程（通信优先）。
 */
public class EventLog {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path FILE = Paths.get("logs", "events.tsv");

    private EventLog() {
    }

    /** 追加一条事件 */
    public static synchronized void log(String kind, String direction, String peer, String extra) {
        try {
            if (FILE.getParent() != null) {
                Files.createDirectories(FILE.getParent());
            }
            String line = LocalDateTime.now().format(FMT)
                    + "\t" + nz(kind)
                    + "\t" + nz(direction)
                    + "\t" + nz(peer)
                    + "\t" + nz(extra)
                    + System.lineSeparator();
            Files.write(FILE, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // 日志写不进去就算了，不能影响通信
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}

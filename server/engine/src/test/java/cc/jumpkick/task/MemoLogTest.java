// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The append-only file behind the hash and ABI memos. */
class MemoLogTest {

    private static @Nullable String line(String key, String value) {
        return key + "=" + value;
    }

    private static List<String> read(MemoLog log) throws IOException {
        List<String> out = new ArrayList<>();
        log.load(out::add);
        return out;
    }

    @Test
    void a_flush_appends_the_changed_keys_and_a_load_keeps_the_last_line(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("memo.v1");
        Map<String, String> map = new LinkedHashMap<>();
        MemoLog log = new MemoLog(file);
        map.put("a", "1");
        map.put("b", "2");
        log.changed("a");
        log.changed("b");
        log.flush(map, MemoLogTest::line);
        String first = Files.readString(file);

        map.put("a", "3");
        log.changed("a");
        log.flush(map, MemoLogTest::line);

        assertThat(Files.readString(file)).isEqualTo(first + "a=3\n");
        assertThat(read(new MemoLog(file))).containsExactly("a=1", "b=2", "a=3");
    }

    @Test
    void nothing_changed_writes_nothing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("memo.v1");
        Map<String, String> map = new LinkedHashMap<>(Map.of("a", "1"));
        MemoLog log = new MemoLog(file);
        log.changed("a");
        log.flush(map, MemoLogTest::line);
        String before = Files.readString(file);

        log.flush(map, MemoLogTest::line);

        assertThat(Files.readString(file)).isEqualTo(before);
    }

    /** A crash during an append leaves a line with no newline: never a record, and never appended onto. */
    @Test
    void a_cut_short_last_line_is_dropped_and_the_next_flush_starts_clean(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("memo.v1");
        Files.writeString(file, "a=1\nb=2\nc=par");
        MemoLog log = new MemoLog(file);
        assertThat(read(log)).containsExactly("a=1", "b=2");

        Map<String, String> map = new LinkedHashMap<>(Map.of("a", "1", "b", "2"));
        map.put("d", "4");
        log.changed("d");
        log.flush(map, MemoLogTest::line);

        assertThat(Files.readString(file)).endsWith("\n").doesNotContain("c=par");
        assertThat(read(new MemoLog(file))).containsExactlyInAnyOrder("a=1", "b=2", "d=4");
    }

    @Test
    void superseded_lines_past_twice_the_map_are_compacted_away(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("memo.v1");
        Map<String, String> map = new LinkedHashMap<>(Map.of("k", "0"));
        MemoLog log = new MemoLog(file);
        for (int i = 1; i <= MemoLog.COMPACT_SLACK + 4; i++) {
            map.put("k", Integer.toString(i));
            log.changed("k");
            log.flush(map, MemoLogTest::line);
        }
        assertThat(log.linesOnDisk()).as("rewritten to the live entry").isEqualTo(1);
        assertThat(read(new MemoLog(file))).containsExactly("k=" + (MemoLog.COMPACT_SLACK + 4));
    }

    @Test
    void dropped_entries_leave_the_file_at_the_next_flush(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("memo.v1");
        Map<String, String> map = new LinkedHashMap<>(Map.of("a", "1", "b", "2"));
        MemoLog log = new MemoLog(file);
        log.changed("a");
        log.changed("b");
        log.flush(map, MemoLogTest::line);

        map.remove("a");
        log.dropped();
        log.flush(map, MemoLogTest::line);

        assertThat(read(new MemoLog(file))).containsExactly("b=2");
    }
}

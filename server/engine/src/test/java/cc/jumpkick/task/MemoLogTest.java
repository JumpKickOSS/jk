// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
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

    /**
     * A flush renders a few lines at a time and writes them before rendering more, whether it
     * rewrites the file or appends to it: a store of tens of thousands of long paths must not be
     * built up as one string in the heap of the engine that flushes it.
     */
    @Test
    void a_flush_holds_at_most_a_chunk_of_lines_in_memory(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("memo.v1");
        String value = "v".repeat(1_000);
        Map<String, String> map = new LinkedHashMap<>();
        MemoLog log = new MemoLog(file);
        for (int i = 0; i < 2_000; i++) {
            map.put("k" + i, value);
            log.changed("k" + i);
        }
        long[] rendered = {0};
        long[] start = {0};
        long[] lag = {0};
        BiFunction<String, String, @Nullable String> watched = (k, v) -> {
            long written = bytesIn(dir) - start[0];
            lag[0] = Math.max(lag[0], rendered[0] - written);
            String line = k + "=" + v;
            rendered[0] += line.length() + 1;
            return line;
        };
        long bound = MemoLog.CHUNK_CHARS + value.length() + 16;

        log.flush(map, watched);
        assertThat(lag[0]).as("rendered but unwritten while rewriting").isLessThanOrEqualTo(bound);

        for (String k : map.keySet()) log.changed(k);
        rendered[0] = 0;
        lag[0] = 0;
        start[0] = bytesIn(dir);
        log.flush(map, watched);
        assertThat(lag[0]).as("rendered but unwritten while appending").isLessThanOrEqualTo(bound);
        assertThat(read(new MemoLog(file))).hasSize(4_000);
    }

    /** Bytes in every file of {@code dir}, the memo's staging sibling included. */
    private static long bytesIn(Path dir) {
        try (var files = Files.list(dir)) {
            return files.mapToLong(f -> f.toFile().length()).sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

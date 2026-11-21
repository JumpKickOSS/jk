// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The details tail holds the lines its window needs, not the transcript. */
class McpDetailsTailTest {

    @TempDir
    Path tmp;

    private Path transcript(int errors) throws Exception {
        String body = IntStream.range(0, errors)
                .mapToObj(i -> "{\"schema\":1,\"type\":\"error\",\"message\":\"e" + i + "\"}\n"
                        + "{\"schema\":1,\"type\":\"progress\"}\n")
                .collect(Collectors.joining());
        return Files.writeString(tmp.resolve("details.jsonl"), body);
    }

    @Test
    void reading_keeps_only_the_newest_lines_the_window_needs() throws Exception {
        McpDetails.Matching kept = McpDetails.lastMatching(transcript(5_000), Set.of("error"), 15);

        assertThat(kept.total()).isEqualTo(5_000);
        assertThat(kept.lines()).hasSize(15);
        assertThat(kept.lines().getLast()).contains("e4999");
    }

    @Test
    void a_page_past_the_newest_events_answers_the_same_window_and_cursor() throws Exception {
        Path file = transcript(5_000);
        Map<String, Object> page =
                McpDetails.tail(Map.of("id", "r1"), id -> Optional.of(file), List.of("error"), 5, 10);

        assertThat(page).containsEntry("count", 5_000).containsEntry("truncatedTail", true);
        assertThat(String.valueOf(page.get("nextCursor"))).isEqualTo("15");
        assertThat(String.valueOf(page.get("events")))
                .contains("e4985")
                .contains("e4989")
                .doesNotContain("e4990")
                .doesNotContain("e4984");
    }

    @Test
    void a_deep_cursor_reads_only_its_window_not_every_matching_line() throws Exception {
        Path file = transcript(5_000);

        McpDetails.Matching window = McpDetails.window(file, Set.of("error"), 4_000, 5);

        assertThat(window.total()).isEqualTo(5_000);
        assertThat(window.lines()).hasSize(5);
        assertThat(window.lines().getFirst()).contains("\"e995\"");
        assertThat(window.lines().getLast()).contains("\"e999\"");
        assertThat(McpDetails.window(file, Set.of("error"), Integer.MAX_VALUE, McpDetails.MAX_TAIL)
                        .lines())
                .isEmpty();
    }

    @Test
    void a_cursor_past_every_event_answers_none_and_no_further_cursor() throws Exception {
        Path file = transcript(50);
        Map<String, Object> page =
                McpDetails.tail(Map.of("id", "r1"), id -> Optional.of(file), List.of("error"), 5, Integer.MAX_VALUE);

        assertThat(page).containsEntry("count", 50).containsEntry("truncatedTail", false);
        assertThat(page).doesNotContainKey("nextCursor").doesNotContainKey("error");
        assertThat((List<?>) page.get("events")).isEmpty();
    }

    @Test
    void a_line_cut_short_is_skipped_and_paging_neither_repeats_nor_loses_an_event() throws Exception {
        String line = "{\"schema\":1,\"type\":\"error\",\"message\":\"e%d\"}\n";
        Path file = Files.writeString(
                tmp.resolve("details.jsonl"),
                line.formatted(0) + line.formatted(1) + line.formatted(2)
                        + "{\"schema\":1,\"type\":\"error\",\"message\":\"torn\n"
                        + line.formatted(3) + line.formatted(4)
                        + "{\"schema\":1,\"type\":\"error\",\"message\":\"still being writ");

        Map<String, Object> newest =
                McpDetails.tail(Map.of("id", "r1"), id -> Optional.of(file), List.of("error"), 3, 0);
        assertThat(String.valueOf(newest.get("events")))
                .contains("e3")
                .contains("e4")
                .doesNotContain("e2");
        assertThat(newest).containsEntry("truncatedTail", true).containsEntry("nextCursor", 3);

        Map<String, Object> older =
                McpDetails.tail(Map.of("id", "r1"), id -> Optional.of(file), List.of("error"), 3, 3);
        assertThat(String.valueOf(older.get("events")))
                .contains("e1")
                .contains("e2")
                .doesNotContain("e0")
                .doesNotContain("e3")
                .doesNotContain("torn");
        assertThat(older).containsEntry("truncatedTail", true).containsEntry("nextCursor", 6);

        Map<String, Object> oldest =
                McpDetails.tail(Map.of("id", "r1"), id -> Optional.of(file), List.of("error"), 3, 6);
        assertThat(String.valueOf(oldest.get("events"))).contains("e0").doesNotContain("e1");
        assertThat(oldest).containsEntry("truncatedTail", false);
    }

    @Test
    void each_event_has_the_runs_project_secrets_masked() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), "group = \"g\"\nname = \"a\"\nversion = \"1\"\n");
        Files.writeString(project.resolve(".env"), "TOKEN='details\"s3cret'\n");
        Path file = Files.writeString(
                tmp.resolve("details.jsonl"),
                "{\"schema\":1,\"type\":\"error\",\"message\":\"login with details\\\"s3cret failed\"}\n");

        Map<String, Object> page = McpDetails.tail(
                Map.of("id", "r1", "dir", project.toString()), id -> Optional.of(file), List.of("error"), 5, 0);

        assertThat(String.valueOf(page.get("events")))
                .contains("login with")
                .contains("failed")
                .doesNotContain("s3cret");
    }
}

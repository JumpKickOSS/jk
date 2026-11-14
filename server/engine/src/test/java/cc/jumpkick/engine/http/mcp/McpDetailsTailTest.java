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
}

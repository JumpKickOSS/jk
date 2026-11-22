// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code run(action=logs)}: the last lines of the app's log, or one request's lines with what continues them. */
class McpAppLogTest {

    private static final List<String> LOG = List.of(
            "INFO  [exec-1] GET /api/orders/7 req=a1",
            "INFO  [exec-2] GET /health req=b2",
            "ERROR [exec-1] order 7 failed req=a1",
            "java.lang.IllegalStateException: no stock",
            "\tat com.example.Orders.reserve(Orders.java:42)",
            "Caused by: java.io.IOException: closed",
            "\t... 12 more",
            "INFO  [exec-2] 200 /health req=b2");

    @Test
    void a_tail_is_the_last_lines_in_order() {
        McpAppLog.Tail tail = McpAppLog.tail(LOG, null, 2);

        assertThat(tail.lines()).containsExactly("\t... 12 more", "INFO  [exec-2] 200 /health req=b2");
        assertThat(tail.of()).isEqualTo(LOG.size());
    }

    @Test
    void one_request_is_its_lines_with_the_stack_trace_under_them() {
        McpAppLog.Tail tail = McpAppLog.tail(LOG, "req=a1", 60);

        assertThat(tail.lines())
                .containsExactly(
                        "INFO  [exec-1] GET /api/orders/7 req=a1",
                        "ERROR [exec-1] order 7 failed req=a1",
                        "java.lang.IllegalStateException: no stock",
                        "\tat com.example.Orders.reserve(Orders.java:42)",
                        "Caused by: java.io.IOException: closed",
                        "\t... 12 more")
                .doesNotContain("INFO  [exec-2] GET /health req=b2");
    }

    @Test
    void a_request_between_slashes_is_a_java_regex_and_keeps_the_trace_too() {
        McpAppLog.Tail tail = McpAppLog.tail(LOG, "/ERROR .* req=a\\d/", 60);

        assertThat(tail.lines())
                .containsExactly(
                        "ERROR [exec-1] order 7 failed req=a1",
                        "java.lang.IllegalStateException: no stock",
                        "\tat com.example.Orders.reserve(Orders.java:42)",
                        "Caused by: java.io.IOException: closed",
                        "\t... 12 more");
        assertThat(tail.cut()).isFalse();
        assertThat(McpAppLog.tail(LOG, "req=a\\d", 60).lines())
                .as("without slashes the text is a substring, backslash and all")
                .isEmpty();
    }

    @Test
    void a_regex_that_runs_out_of_time_answers_what_it_found_so_far() {
        List<String> lines = new ArrayList<>();
        lines.add("GET /fast req=a1");
        for (int i = 0; i < 50; i++) lines.add("a".repeat(30) + "!");

        McpAppLog.Tail tail = McpAppLog.tail(lines, "/req=a1|(.*a){12}$/", 60, McpAppLog.BUDGET_CHARS, 50);

        assertThat(tail.cut()).isTrue();
        assertThat(tail.lines()).containsExactly("GET /fast req=a1");
    }

    @Test
    void a_tail_stays_inside_its_character_budget_and_cuts_long_lines() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 400; i++) lines.add("x".repeat(1_000));

        McpAppLog.Tail tail = McpAppLog.tail(lines, null, McpAppLog.MAX_LINES);

        assertThat(tail.lines()).isNotEmpty().allSatisfy(l -> assertThat(l).hasSize(McpAppLog.LINE_CHARS + 1));
        assertThat(String.join("\n", tail.lines()).length()).isLessThanOrEqualTo(McpAppLog.BUDGET_CHARS);
    }

    @Test
    void a_log_larger_than_one_read_starts_at_a_whole_line(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("jk-app.log");
        String line = "y".repeat(99) + "\n";
        Files.writeString(log, line.repeat(McpAppLog.READ_BYTES / 100 + 10) + "last\n", StandardCharsets.UTF_8);

        List<String> read = McpAppLog.read(log);

        assertThat(read.getFirst()).isEqualTo("y".repeat(99));
        assertThat(read.getLast()).isEqualTo("last");
        assertThat(McpAppLog.read(dir.resolve("missing.log"))).isEmpty();
    }
}

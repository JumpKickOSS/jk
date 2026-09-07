// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.run.TestSummary;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MCP and the dashboard read the same test-count field.
 *
 * <p>{@code McpHistoryViews.summarize} used to flatten the journal's nested
 * {@code tests:{total,succeeded,failed,skipped}} into a private scalar {@code testFailed}, so an
 * agent asking {@code jk_history} and a human reading the dashboard were looking at two different
 * spellings of the same number — and the agent could not see how many passed at all.
 */
class McpTestCountShapeTest {

    /**
     * Verbatim from a real journal record — a jk self-host run:
     * {@code ~/.jk/state/builds/projects/a66f86e5…/runs/1/record.json}. Truncated to the fields
     * {@code summarize} reads; the {@code tests} block is byte-for-byte what the engine wrote.
     */
    private static final String REAL_RECORD = """
        {
          "id": "20260824T053110035",
          "buildNumber": 1,
          "kind": "build",
          "dir": "/home/bsant/src/oss/jk",
          "coord": "cc.jumpkick:jk",
          "projectId": "a66f86e5f1e675e17a67c9c36474f968",
          "millis": 98002,
          "success": true,
          "exitCode": 0,
          "requestId": 1,
          "tests": {
            "total": 4323,
            "succeeded": 4314,
            "failed": 0,
            "skipped": 9
          },
          "modules": [],
          "diagnostics": []
        }\
        """;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> record(String json) {
        return (Map<String, Object>) MiniJson.parse(json);
    }

    @Test
    void an_mcp_history_row_carries_the_journals_own_counts_object() {
        Map<String, Object> row = McpHistoryViews.summarize(record(REAL_RECORD));

        assertThat(row).doesNotContainKey("testFailed").doesNotContainKey("testsFailed");
        assertThat(row.get(TestSummary.WIRE_KEY))
                .isEqualTo(Map.of("total", 4323.0, "succeeded", 4314.0, "failed", 0.0, "skipped", 9.0));
    }

    /** No test phase: the key is present and null, never a zeroed object pretending zero tests ran. */
    @Test
    void a_run_with_no_test_phase_summarizes_to_a_null_counts_object() {
        Map<String, Object> row = McpHistoryViews.summarize(record("{\"id\":\"j-1\",\"tests\":null}"));

        assertThat(row).containsKey(TestSummary.WIRE_KEY);
        assertThat(row.get(TestSummary.WIRE_KEY)).isNull();
    }

    /**
     * The engine builds this object from a {@link Map} (history verbs, MCP, journal) while the wire
     * codec builds it by string concatenation ({@code ProtoEvents}). Two encoders for one field is
     * exactly how the three spellings appeared; this pins them to identical bytes, key order
     * included, so {@code jk history show} and a live {@code plan-finish} cannot drift.
     */
    @Test
    void the_map_encoder_and_the_string_encoder_emit_the_same_bytes() {
        String fromMap = JsonOut.object()
                .putObject(TestSummary.WIRE_KEY, TestSummary.countsMap(4323, 4314, 0, 9))
                .toString();

        assertThat(fromMap).isEqualTo("{\"tests\":" + TestSummary.countsJson(4323, 4314, 0, 9) + "}");
        assertThat(TestSummary.readCounts(fromMap)).isNotNull().satisfies(counts -> {
            assertThat(counts.total()).isEqualTo(4323);
            assertThat(counts.succeeded()).isEqualTo(4314);
            assertThat(counts.skipped()).isEqualTo(9);
        });
    }

    /** {@code putObject(key, null)} is how a verb says "no test phase" — it must stay readable. */
    @Test
    void the_map_encoder_writes_a_null_counts_object_for_a_run_with_no_tests() {
        String line = JsonOut.object()
                .put("type", "history-entry")
                .putObject(TestSummary.WIRE_KEY, null)
                .toString();

        assertThat(line).isEqualTo("{\"type\":\"history-entry\",\"tests\":null}");
        assertThat(TestSummary.readCounts(line)).isNull();
    }
}

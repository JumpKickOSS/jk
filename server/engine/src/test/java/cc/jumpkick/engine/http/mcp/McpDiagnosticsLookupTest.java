// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpDiagnosticsLookupTest {

    private static final String FAIL =
            "{\"id\":\"old\",\"kind\":\"test\",\"dir\":\"/ws\",\"success\":false,\"running\":false,"
                    + "\"requestId\":1,\"exitCode\":1}";
    private static final String RUNNING =
            "{\"id\":\"now\",\"kind\":\"format\",\"dir\":\"/ws\",\"success\":false,\"running\":true,"
                    + "\"requestId\":9,\"exitCode\":0}";
    private static final String FORMAT =
            "{\"id\":\"fmt\",\"kind\":\"format\",\"dir\":\"/ws\",\"success\":true,\"running\":false,"
                    + "\"requestId\":9,\"exitCode\":0}";
    private static final String OTHER =
            "{\"id\":\"x\",\"kind\":\"build\",\"dir\":\"/other\",\"success\":false,\"running\":false,"
                    + "\"requestId\":3,\"exitCode\":1}";

    @Test
    void last_fail_skips_running_stubs() {
        Map<String, Object> rec = requireNonNull(McpDiagnostics.findRun(List.of(RUNNING, FAIL), "last-fail", "/ws"));
        assertThat(rec.get("id")).isEqualTo("old");
    }

    @Test
    void find_by_request_id_prefers_finished_row() {
        assertThat(requireNonNull(McpDiagnostics.findByRequestId(List.of(RUNNING, FORMAT), 9))
                        .get("id"))
                .isEqualTo("fmt");
        assertThat(McpDiagnostics.findByRequestId(List.of(RUNNING), 9)).isNull();
    }

    @Test
    void unique_false_keeps_the_normalized_row_shape() {
        Map<String, Object> diag = Map.of(
                "severity", "error",
                "code", "javac",
                "dir", "/ws/core",
                "message", "/ws/A.java:1: error: cannot find symbol\n  foo\n  ^");
        List<Map<String, Object>> deduped = McpDiagnostics.unique(List.of(diag, diag), true);
        List<Map<String, Object>> all = McpDiagnostics.unique(List.of(diag, diag), false);
        assertThat(deduped).hasSize(1);
        assertThat(all).hasSize(2);
        assertThat(all.getFirst().keySet()).isEqualTo(deduped.getFirst().keySet());
        assertThat(all.getFirst().get("module")).isEqualTo("/ws/core");
        assertThat(all.getFirst().get("file")).isEqualTo("/ws/A.java");
        assertThat(all.getFirst().get("message")).isEqualTo("/ws/A.java:1: error: cannot find symbol");
        assertThat(all.getFirst().get("count")).isEqualTo(1);
        assertThat(deduped.getFirst().get("count")).isEqualTo(2);
    }

    /** The resolver's explanation is one diagnostic: the header as the message, the whole chain as its detail. */
    @Test
    void a_resolve_failure_is_one_row_whose_detail_carries_the_whole_explanation() {
        String chain = "  \u2502 ch.qos.logback:logback-classic 1.5.6 depends on org.slf4j:slf4j-api [2.0.13,+\u221e)\n"
                + "  \u2502 The project depends on org.slf4j:slf4j-api 1.7.36\n"
                + "  \u2502 Therefore, the project's requirements cannot be resolved\n\nSuggestions:\n"
                + "  \u2022 Relax or remove the project constraint on org.slf4j:slf4j-api";
        Map<String, Object> diag = Map.of(
                "severity", "error",
                "code", "verbatim",
                "dir", "/ws/app",
                "message", "\u203c Cannot resolve dependencies:\n" + chain);
        List<Map<String, Object>> rows = McpDiagnostics.unique(List.of(diag), true);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("message")).isEqualTo("\u203c Cannot resolve dependencies:");
        assertThat(rows.getFirst().get("detail")).isEqualTo(chain);
    }

    @Test
    void explicit_run_id_bypasses_the_dir_filter() {
        Map<String, Object> rec = requireNonNull(McpDiagnostics.findRun(List.of(FAIL, OTHER), "x", "/ws"));
        assertThat(rec.get("id")).isEqualTo("x");
        assertThat(rec.get("dir")).isEqualTo("/other");
    }

    @Test
    void newest_finished_is_not_last_fail() {
        Map<String, Object> rec = requireNonNull(McpDiagnostics.findNewest(List.of(FORMAT, FAIL, OTHER), "/ws"));
        assertThat(rec.get("id")).isEqualTo("fmt");
        assertThat(rec.get("success")).isEqualTo(true);
    }
}

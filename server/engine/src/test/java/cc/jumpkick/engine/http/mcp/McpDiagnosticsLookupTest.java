// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

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
        Map<String, Object> rec = McpDiagnostics.findRun(List.of(RUNNING, FAIL), "last-fail", "/ws");
        assertThat(rec.get("id")).isEqualTo("old");
    }

    @Test
    void find_by_request_id_prefers_finished_row() {
        assertThat(McpDiagnostics.findByRequestId(List.of(RUNNING, FORMAT), 9).get("id"))
                .isEqualTo("fmt");
        assertThat(McpDiagnostics.findByRequestId(List.of(RUNNING), 9)).isNull();
    }

    @Test
    void newest_finished_is_not_last_fail() {
        Map<String, Object> rec = McpDiagnostics.findNewest(List.of(FORMAT, FAIL, OTHER), "/ws");
        assertThat(rec.get("id")).isEqualTo("fmt");
        assertThat(rec.get("success")).isEqualTo(true);
    }
}

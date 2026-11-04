// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.run.jsonl.JobLine;
import cc.jumpkick.cli.run.jsonl.SessionFinishLine;
import cc.jumpkick.cli.run.jsonl.SlowestTestsLine;
import cc.jumpkick.cli.run.jsonl.WorkspaceStartLine;
import cc.jumpkick.run.SlowestTests;
import cc.jumpkick.wire.transcript.SessionStartLine;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bytes of the lines only the CLI writes, spelled out with a fixed clock (an event the engine
 * streams is its wire record under the envelope; {@code EventCatalogConsoleTest} holds those). {@code details.jsonl} is read by agents and CI; a record that
 * reorders a field, changes a default or spells an optional field the old line omitted is a
 * different line to every reader, and a round trip cannot see that.
 */
class JsonlShapeFrozenBytesTest {
    private static final long TS = 1_700_000_000_000L;
    private static final String ENV = "{\"schema\":1,\"ts\":1700000000000,\"type\":\"";

    @Test
    void session_lines() {
        assertThat(new SessionStartLine(TS, "build", List.of("-r", "a b"), null, null).encode())
                .isEqualTo(ENV + "session-start\",\"command\":\"build\",\"argv\":[\"-r\",\"a b\"]}");
        assertThat(new SessionStartLine(TS, "build", List.of(), null, null).encode())
                .isEqualTo(ENV + "session-start\",\"command\":\"build\",\"argv\":[]}");
        assertThat(new SessionFinishLine(TS, 0, 1234, null, List.of()).encode())
                .isEqualTo(ENV + "session-finish\",\"exit\":0,\"millis\":1234}");
        assertThat(new SessionFinishLine(TS, 2, 5, "compile", List.of("a", "b")).encode())
                .isEqualTo(ENV
                        + "session-finish\",\"exit\":2,\"millis\":5,\"wedge\":\"compile\",\"modules\":[\"a\",\"b\"]}");
        assertThat(new SessionFinishLine(TS, 2, 5, "  ", List.of()).encode())
                .as("a blank wedge is omitted")
                .isEqualTo(ENV + "session-finish\",\"exit\":2,\"millis\":5}");
    }

    @Test
    void the_slowest_tests_line_lists_classes_then_methods_with_module_tier_and_wall() {
        var classes = List.of(new SlowestTests.Row("g:a", "demo.SlowTest", "", "fast", 12_300));
        var methods = List.of(new SlowestTests.Row("g:a", "demo.SlowTest", "boots", "fast", 4_200));
        String line = new SlowestTestsLine(TS, classes, methods).encode();
        assertThat(line)
                .isEqualTo(ENV
                        + "slowest-tests\","
                        + "\"classes\":[{\"module\":\"g:a\",\"testClass\":\"demo.SlowTest\",\"tier\":\"fast\",\"millis\":12300}],"
                        + "\"methods\":[{\"module\":\"g:a\",\"testClass\":\"demo.SlowTest\",\"method\":\"boots\","
                        + "\"tier\":\"fast\",\"millis\":4200}]}");
        assertThat(SlowestTestsLine.decode(line)).isEqualTo(new SlowestTestsLine(TS, classes, methods));
    }

    @Test
    void job_line_carries_only_what_is_known() {
        assertThat(new JobLine(TS, 0, 0, -1, null).encode()).isEqualTo(ENV + "job\"}");
        assertThat(new JobLine(TS, 7, 42, 0, "/runs/42/details.jsonl").encode())
                .isEqualTo(ENV
                        + "job\",\"jid\":7,\"buildNumber\":42,\"etaMs\":0,\"detailsPath\":\"/runs/42/details.jsonl\"}");
        assertThat(new JobLine(TS, 7, 0, -1, " ").encode()).isEqualTo(ENV + "job\",\"jid\":7}");
    }

    @Test
    void the_workspace_start_line_counts_its_modules() {
        assertThat(new WorkspaceStartLine(TS, 5).encode()).isEqualTo(ENV + "workspace-start\",\"modules\":5}");
    }

    @Test
    void the_guard_row_is_spliced_after_the_envelope_and_the_progress_rider_before_the_brace() {
        String guard = JsonlShape.guard(" {\"rule\":\"x\",\"line\":3} ");
        assertThat(guard)
                .startsWith("{\"schema\":1,\"ts\":")
                .endsWith(",\"type\":\"guard\",\"rule\":\"x\",\"line\":3}");
        assertThat(JsonlShape.guard("not json")).endsWith(",\"type\":\"guard\"}");
        assertThat(JsonlShape.withProgress("{\"a\":1}", 12.5)).isEqualTo("{\"a\":1,\"progress\":12.5}");
        assertThat(JsonlShape.withProgress("{\"a\":1}", null)).isEqualTo("{\"a\":1,\"progress\":null}");
        assertThat(JsonlShape.withProgress("{\"a\":1,\"progress\":3}", 9.0)).isEqualTo("{\"a\":1,\"progress\":3}");
        assertThat(JsonlShape.withProgress("plain", 9.0)).isEqualTo("plain");
    }
}

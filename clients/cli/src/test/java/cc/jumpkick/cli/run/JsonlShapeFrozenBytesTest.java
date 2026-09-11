// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.run.jsonl.ErrorLine;
import cc.jumpkick.cli.run.jsonl.EtaLine;
import cc.jumpkick.cli.run.jsonl.JobLine;
import cc.jumpkick.cli.run.jsonl.LabelLine;
import cc.jumpkick.cli.run.jsonl.ModuleFinishLine;
import cc.jumpkick.cli.run.jsonl.ModuleStartLine;
import cc.jumpkick.cli.run.jsonl.OutputLine;
import cc.jumpkick.cli.run.jsonl.PlanFinishLine;
import cc.jumpkick.cli.run.jsonl.PlanStartLine;
import cc.jumpkick.cli.run.jsonl.PreflightLine;
import cc.jumpkick.cli.run.jsonl.ProgressLine;
import cc.jumpkick.cli.run.jsonl.SessionFinishLine;
import cc.jumpkick.cli.run.jsonl.SessionStartLine;
import cc.jumpkick.cli.run.jsonl.TaskFinishLine;
import cc.jumpkick.cli.run.jsonl.TaskStartLine;
import cc.jumpkick.cli.run.jsonl.TestFailureErrorLine;
import cc.jumpkick.cli.run.jsonl.TickUpdateLine;
import cc.jumpkick.cli.run.jsonl.WarnLine;
import cc.jumpkick.cli.run.jsonl.WorkspaceFinishLine;
import cc.jumpkick.cli.run.jsonl.WorkspaceProgressLine;
import cc.jumpkick.cli.run.jsonl.WorkspaceStartLine;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bytes {@code JsonlShape} produced by string concatenation before its lines became records,
 * spelled out with a fixed clock. {@code details.jsonl} is read by agents and CI; a record that
 * reorders a field, changes a default or spells an optional field the old line omitted is a
 * different line to every reader, and a round trip cannot see that.
 */
class JsonlShapeFrozenBytesTest {
    private static final long TS = 1_700_000_000_000L;
    private static final String ENV = "{\"schema\":1,\"ts\":1700000000000,\"type\":\"";

    @Test
    void session_lines() {
        assertThat(new SessionStartLine(TS, "build", List.of("-r", "a b")).encode())
                .isEqualTo(ENV + "session-start\",\"command\":\"build\",\"argv\":[\"-r\",\"a b\"]}");
        assertThat(new SessionStartLine(TS, "build", List.of()).encode())
                .isEqualTo(ENV + "session-start\",\"command\":\"build\",\"argv\":[]}");
        assertThat(new SessionFinishLine(TS, 0, 1234, null, List.of()).encode())
                .isEqualTo(ENV + "session-finish\",\"exit\":0,\"duration_ms\":1234}");
        assertThat(new SessionFinishLine(TS, 2, 5, "compile", List.of("a", "b")).encode())
                .isEqualTo(
                        ENV
                                + "session-finish\",\"exit\":2,\"duration_ms\":5,\"wedge\":\"compile\",\"modules\":[\"a\",\"b\"]}");
        assertThat(new SessionFinishLine(TS, 2, 5, "  ", List.of()).encode())
                .as("a blank wedge is omitted")
                .isEqualTo(ENV + "session-finish\",\"exit\":2,\"duration_ms\":5}");
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
    // The nulls are deliberate: the line normalizes absent fields to empty.
    @SuppressWarnings("NullAway")
    void preflight_and_eta_clamp_at_zero_and_spell_a_missing_string_empty() {
        assertThat(new PreflightLine(TS, "modules", 3, 9, "7 module(s) dirty").encode())
                .isEqualTo(
                        ENV
                                + "preflight\",\"stage\":\"modules\",\"done\":3,\"totalUnits\":9,\"label\":\"7 module(s) dirty\"}");
        assertThat(new PreflightLine(TS, null, -1, -5, null).encode())
                .isEqualTo(ENV + "preflight\",\"stage\":\"\",\"done\":0,\"totalUnits\":0,\"label\":\"\"}");
        assertThat(new EtaLine(TS, 1500).encode()).isEqualTo(ENV + "eta\",\"etaMs\":1500}");
        assertThat(new EtaLine(TS, -9).encode()).isEqualTo(ENV + "eta\",\"etaMs\":0}");
    }

    @Test
    void plan_and_task_lines() {
        assertThat(new PlanStartLine(TS, "build", 200, 9).encode())
                .isEqualTo(ENV + "buildplan-start\",\"plan\":\"build\",\"denominator\":200,\"tasks\":9}");
        assertThat(new TaskStartLine(TS, "compile-main", "compile", 40).encode())
                .isEqualTo(ENV + "task-start\",\"task\":\"compile-main\",\"stage\":\"compile\",\"ticks\":40}");
        assertThat(new ProgressLine(TS, "compile-main", 3, 50, 200).encode())
                .isEqualTo(
                        ENV + "progress\",\"task\":\"compile-main\",\"delta\":3,\"numerator\":50,\"denominator\":200}");
        assertThat(new TickUpdateLine(TS, "run-tests", 1, 200).encode())
                .isEqualTo(ENV + "tick-update\",\"task\":\"run-tests\",\"delta\":1,\"denominator\":200}");
        assertThat(new LabelLine(TS, "run-tests", "3 of 9").encode())
                .isEqualTo(ENV + "label\",\"task\":\"run-tests\",\"label\":\"3 of 9\"}");
        assertThat(new OutputLine(TS, "run-tests", "he said \"hi\"\n").encode())
                .isEqualTo(ENV + "output\",\"task\":\"run-tests\",\"line\":\"he said \\\"hi\\\"\\n\"}");
        assertThat(new TaskFinishLine(TS, "compile-main", "compile", "SUCCESS", 1200, 300).encode())
                .isEqualTo(
                        ENV
                                + "task-finish\",\"task\":\"compile-main\",\"stage\":\"compile\",\"status\":\"SUCCESS\",\"duration_ms\":1200,\"wait_ms\":300}");
        assertThat(new PlanFinishLine(TS, "build", true, 4200, 1, 0).encode())
                .isEqualTo(
                        ENV
                                + "buildplan-finish\",\"plan\":\"build\",\"success\":true,\"duration_ms\":4200,\"warnings\":1,\"errors\":0}");
    }

    @Test
    void diagnostics_ride_their_optional_parts_only_when_non_empty() {
        assertThat(new WarnLine(TS, "compile-main", "W1", "careful").encode())
                .isEqualTo(ENV + "warn\",\"task\":\"compile-main\",\"code\":\"W1\",\"message\":\"careful\"}");
        assertThat(new ErrorLine(TS, "run-tests", "test-failure", "boom", "", "").encode())
                .isEqualTo(ENV + "error\",\"task\":\"run-tests\",\"code\":\"test-failure\",\"message\":\"boom\"}");
        assertThat(new ErrorLine(TS, "run-tests", "test-failure", "boom", "a.T#m", "java.lang.AssertionError").encode())
                .isEqualTo(
                        ENV
                                + "error\",\"task\":\"run-tests\",\"code\":\"test-failure\",\"message\":\"boom\",\"test\":\"a.T#m\",\"exceptionClass\":\"java.lang.AssertionError\"}");
        assertThat(new TestFailureErrorLine(
                                TS, "run-tests", "test-failure", "boom", "", "", "", "", "", 0, "", 0, 0, List.of(), "")
                        .encode())
                .isEqualTo(ENV + "error\",\"task\":\"run-tests\",\"code\":\"test-failure\",\"message\":\"boom\"}");
        assertThat(new TestFailureErrorLine(
                                TS,
                                "run-tests",
                                "test-failure",
                                "boom",
                                "core",
                                "junit-jupiter",
                                "a.T",
                                "m",
                                "java.lang.AssertionError",
                                2,
                                "src/test/java/a/T.java",
                                12,
                                10,
                                List.of("x", "y"),
                                "at a.T.m(T.java:12)")
                        .encode())
                .isEqualTo(
                        ENV
                                + "error\",\"task\":\"run-tests\",\"code\":\"test-failure\",\"message\":\"boom\",\"module\":\"core\",\"engine\":\"junit-jupiter\",\"class\":\"a.T\",\"method\":\"m\",\"exceptionClass\":\"java.lang.AssertionError\",\"worker\":2,\"file\":\"src/test/java/a/T.java\",\"line\":12,\"snippetStart\":10,\"snippet\":[\"x\",\"y\"],\"stack\":\"at a.T.m(T.java:12)\"}");
    }

    @Test
    // The nulls are deliberate: the line normalizes absent fields to empty.
    @SuppressWarnings("NullAway")
    void workspace_and_module_lines() {
        assertThat(new WorkspaceProgressLine(TS, "a/b", 3, 4, "execute", 1, 2).encode())
                .isEqualTo(
                        ENV
                                + "workspace-progress\",\"dir\":\"a/b\",\"numerator\":3,\"denominator\":4,\"phase\":\"execute\",\"modulesComplete\":1,\"modulesTotal\":2}");
        assertThat(new WorkspaceProgressLine(TS, null, 0, 0, null, 0, 0).encode())
                .isEqualTo(
                        ENV
                                + "workspace-progress\",\"dir\":\"\",\"numerator\":0,\"denominator\":0,\"phase\":\"\",\"modulesComplete\":0,\"modulesTotal\":0}");
        assertThat(new WorkspaceStartLine(TS, 5).encode()).isEqualTo(ENV + "workspace-start\",\"modules\":5}");
        assertThat(new WorkspaceFinishLine(TS, false, 99, 5).encode())
                .isEqualTo(ENV + "workspace-finish\",\"success\":false,\"duration_ms\":99,\"modules\":5}");
        assertThat(new ModuleStartLine(TS, "a/b", "g:a").encode())
                .isEqualTo(ENV + "module-start\",\"dir\":\"a/b\",\"coord\":\"g:a\"}");
        assertThat(new ModuleFinishLine(TS, "a/b", "g:a", true, 77).encode())
                .isEqualTo(
                        ENV + "module-finish\",\"dir\":\"a/b\",\"coord\":\"g:a\",\"success\":true,\"duration_ms\":77}");
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

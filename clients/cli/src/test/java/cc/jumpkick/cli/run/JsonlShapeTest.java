// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Machine JSONL shape + output mode aliases (docs/machine-output.md). */
class JsonlShapeTest {

    @AfterEach
    void clearProgress() {
        LiveProgress.get().clear();
    }

    @Test
    void events_include_schema_ts_and_type() {
        String line =
                JsonlShape.stepFinish("run-tests", "test", TaskStatus.SUCCESS, Duration.ofMillis(12), Duration.ZERO);
        assertThat(line).startsWith("{\"schema\":" + JsonlShape.SCHEMA);
        assertThat(line).contains("\"type\":\"task-finish\"");
        assertThat(line).contains("\"ts\":");
        assertThat(line).contains("\"task\":\"run-tests\"");
        assertThat(line).contains("\"duration_ms\":12");
    }

    @Test
    void a_guard_event_carries_the_violation_row_under_the_envelope() {
        String row =
                "{\"code\":\"one-digest-surface\",\"kind\":\"forbid\",\"baseline\":\"new\",\"file\":\"a/A.java\",\"line\":12,"
                        + "\"at\":\"a.A#f\",\"message\":\"m\",\"instead\":\"i\",\"why\":\"w\",\"source\":\"jk-guards.toml:1\"}";
        String line = JsonlShape.guard(row);
        assertThat(line).startsWith("{\"schema\":" + JsonlShape.SCHEMA);
        assertThat(line).contains("\"type\":\"guard\"");
        assertThat(line)
                .contains("\"code\":\"one-digest-surface\"")
                .contains("\"baseline\":\"new\"")
                .endsWith("\"source\":\"jk-guards.toml:1\"}");
        // a row that is not an object degrades to the bare envelope
        assertThat(JsonlShape.guard("not json"))
                .matches("\\{\"schema\":" + JsonlShape.SCHEMA + ",\"ts\":\\d+,\"type\":\"guard\"\\}");
    }

    @Test
    void withProgress_attaches_percent_only() {
        LiveProgress.get().update(25, 100);
        String base = JsonlShape.stepStart("compile-main", "compile", 10);
        String line = JsonlShape.withProgress(base);
        assertThat(line).contains("\"progress\":25");
        assertThat(line).doesNotContain("progress_num");
        assertThat(line).doesNotContain("progress_den");
        // Idempotent.
        assertThat(JsonlShape.withProgress(line)).isEqualTo(line);
    }

    @Test
    void withProgress_null_when_unknown() {
        LiveProgress.get().clear();
        String line = JsonlShape.withProgress(JsonlShape.workspaceStart(2));
        assertThat(line).contains("\"progress\":null");
    }

    @Test
    void withProgress_tracks_preflight_and_execute() {
        // Preflight-only band (WorkspaceProgressTracker.PREFLIGHT_UNITS = 100).
        LiveProgress.get().update(40, WorkspaceProgressTracker.PREFLIGHT_UNITS);
        assertThat(JsonlShape.withProgress("{\"schema\":1,\"ts\":1,\"type\":\"x\"}"))
                .contains("\"progress\":40");
        // Mid-execute: preflight full + half of execute weights.
        long pf = WorkspaceProgressTracker.PREFLIGHT_UNITS;
        LiveProgress.get().update(pf + 50, pf + 100);
        String mid = JsonlShape.withProgress("{\"schema\":1,\"ts\":1,\"type\":\"x\"}");
        assertThat(mid).contains("\"progress\":75"); // (150/200)*100
        LiveProgress.get().update(pf + 100, pf + 100);
        assertThat(JsonlShape.withProgress("{\"schema\":1,\"ts\":1,\"type\":\"x\"}"))
                .contains("\"progress\":100");
    }

    @Test
    void session_events() {
        String start = JsonlShape.sessionStart("build", List.of("build", "--skip-tests"));
        assertThat(start).contains("\"type\":\"session-start\"");
        assertThat(start).contains("\"command\":\"build\"");
        assertThat(start).contains("\"argv\":[\"build\",\"--skip-tests\"]");
        String finish = JsonlShape.sessionFinish(0, 42, "ok", List.of("a:b"));
        assertThat(finish).contains("\"type\":\"session-finish\"");
        assertThat(finish).contains("\"exit\":0");
        assertThat(finish).contains("\"duration_ms\":42");
        assertThat(finish).contains("\"wedge\":\"ok\"");
        assertThat(finish).contains("\"modules\":[\"a:b\"]");
    }

    @Test
    void error_carries_optional_test_fields() {
        String line = JsonlShape.error(
                "run-tests",
                "test-failure",
                "nope",
                "cc.jumpkick:core :: Foo > bar()  [w2]",
                "java.lang.AssertionError");
        assertThat(line).contains("\"schema\":1");
        assertThat(line).contains("\"type\":\"error\"");
        assertThat(line).contains("\"test\":\"cc.jumpkick:core :: Foo > bar()  [w2]\"");
        assertThat(line).contains("\"exceptionClass\":\"java.lang.AssertionError\"");
    }

    @Test
    void error_carries_enriched_test_failure() {
        var failure = new TestFailureInfo(
                "cc.jumpkick:jk-engine",
                "junit-jupiter",
                "cc.jumpkick.runtime.LockFreshenConservativeTest",
                "freshen_preserves_pins_while_explicit_lock_floats(java.nio.file.Path)",
                "org.opentest4j.AssertionFailedError",
                "expected: \"1.1\"\n but was: \"1.0\"",
                "org.opentest4j.AssertionFailedError: …\n\tat cc.jumpkick.runtime.LockFreshenConservativeTest.freshen(LockFreshenConservativeTest.java:96)",
                2);
        String line = JsonlShape.error("run-tests", "test-failure", failure.message(), failure);
        assertThat(line).contains("\"worker\":2");
        assertThat(line).contains("\"module\":\"cc.jumpkick:jk-engine\"");
        assertThat(line).contains("\"engine\":\"junit-jupiter\"");
        assertThat(line).contains("\"class\":\"cc.jumpkick.runtime.LockFreshenConservativeTest\"");
        assertThat(line)
                .contains("\"method\":\"freshen_preserves_pins_while_explicit_lock_floats(java.nio.file.Path)\"");
        assertThat(line).contains("\"exceptionClass\":\"org.opentest4j.AssertionFailedError\"");
        assertThat(line).contains("\"stack\":");
        // The stack is serialized exactly once — no nested throwable duplicate.
        assertThat(line).doesNotContain("\"throwable\":");
        assertThat(line).doesNotContain("\"test\":");
        assertThat(line).contains("LockFreshenConservativeTest.java:96");
    }

    @Test
    void output_json_and_jsonl_both_select_machine_mode() {
        assertThat(optsWithOutput("json").outputIsJson()).isTrue();
        assertThat(optsWithOutput("jsonl").outputIsJson()).isTrue();
        assertThat(optsWithOutput("JSONL").outputIsJson()).isTrue();
        assertThat(optsWithOutput("text").outputIsJson()).isFalse();
        assertThat(optsWithOutput(null).outputIsJson()).isFalse();
        assertThat(BuildPlanConsole.modeFor(optsWithOutput("jsonl"))).isEqualTo(BuildPlanConsole.Mode.JSON);
        assertThat(BuildPlanConsole.modeFor(optsWithOutput("json"))).isEqualTo(BuildPlanConsole.Mode.JSON);
    }

    @Test
    void workspace_module_events_include_coords() {
        String start = JsonlShape.moduleStart("/w/core", "com.example:core");
        assertThat(start).contains("\"type\":\"module-start\"");
        assertThat(start).contains("\"dir\":\"/w/core\"");
        assertThat(start).contains("\"coord\":\"com.example:core\"");
        String finish = JsonlShape.moduleFinish("/w/core", "com.example:core", true, 42);
        assertThat(finish).contains("\"type\":\"module-finish\"");
        assertThat(finish).contains("\"success\":true");
        assertThat(finish).contains("\"duration_ms\":42");
        String ws = JsonlShape.workspaceStart(3);
        assertThat(ws).contains("\"type\":\"workspace-start\"");
        assertThat(ws).contains("\"modules\":3");
        String done = JsonlShape.workspaceFinish(true, 100, 3);
        assertThat(done).contains("\"type\":\"workspace-finish\"");
    }

    private static GlobalOptions optsWithOutput(@Nullable String output) {
        // Minimal Invocation stand-in: GlobalOptions.from needs a real Invocation.
        // Set field directly for unit isolation.
        GlobalOptions g = new GlobalOptions();
        g.output = output;
        return g;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.run.StepStatus;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Machine JSONL shape + output mode aliases (docs/machine-output.md). */
class JsonlShapeTest {

    @Test
    void events_include_schema_ts_and_type() {
        String line = JsonlShape.stepFinish("run-tests", "test", StepStatus.SUCCESS, Duration.ofMillis(12));
        assertThat(line).startsWith("{\"schema\":" + JsonlShape.SCHEMA);
        assertThat(line).contains("\"type\":\"step-finish\"");
        assertThat(line).contains("\"ts\":");
        assertThat(line).contains("\"step\":\"run-tests\"");
        assertThat(line).contains("\"duration_ms\":12");
    }

    @Test
    void error_carries_optional_test_fields() {
        String line = JsonlShape.error(
                "run-tests", "test-failure", "nope", "cc.jumpkick:core :: Foo > bar()  [w2]", "java.lang.AssertionError");
        assertThat(line).contains("\"schema\":1");
        assertThat(line).contains("\"type\":\"error\"");
        assertThat(line).contains("\"test\":\"cc.jumpkick:core :: Foo > bar()  [w2]\"");
        assertThat(line).contains("\"exceptionClass\":\"java.lang.AssertionError\"");
    }

    @Test
    void output_json_and_jsonl_both_select_machine_mode() {
        assertThat(optsWithOutput("json").outputIsJson()).isTrue();
        assertThat(optsWithOutput("jsonl").outputIsJson()).isTrue();
        assertThat(optsWithOutput("JSONL").outputIsJson()).isTrue();
        assertThat(optsWithOutput("text").outputIsJson()).isFalse();
        assertThat(optsWithOutput(null).outputIsJson()).isFalse();
        assertThat(PipelineConsole.modeFor(optsWithOutput("jsonl"))).isEqualTo(PipelineConsole.Mode.JSON);
        assertThat(PipelineConsole.modeFor(optsWithOutput("json"))).isEqualTo(PipelineConsole.Mode.JSON);
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

    private static GlobalOptions optsWithOutput(String output) {
        // Minimal Invocation stand-in: GlobalOptions.from needs a real Invocation.
        // Set field directly for unit isolation.
        GlobalOptions g = new GlobalOptions();
        g.output = output;
        return g;
    }
}

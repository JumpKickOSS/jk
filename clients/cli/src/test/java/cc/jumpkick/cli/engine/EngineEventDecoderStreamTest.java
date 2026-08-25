// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.ModulePlan;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import cc.jumpkick.runtime.WorkspaceResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wire-event decoding, both shapes. Single-plan: the {@link EngineClient.ActiveJobs} note must not
 * outlive the stream (a stale jid adds a 2s cancel RPC to every later Ctrl-C in a watch loop), and
 * a cancel terminal injected before {@code plan-done} must settle, not NPE. Workspace: the events
 * that both shapes share are decoded by one table (JK-2436), so the assertions below are what stops
 * the workspace copy from drifting away from the single-plan one again.
 */
class EngineEventDecoderStreamTest {

    @BeforeEach
    void reset() {
        EngineClient.ActiveJobs.forgetAll();
    }

    private static BufferedReader stream(String... lines) {
        return new BufferedReader(new StringReader(String.join("\n", lines) + "\n"));
    }

    /** A reader that records every line the loop actually consumed. */
    private static BufferedReader recording(List<String> consumed, String... lines) {
        BufferedReader source = stream(lines);
        return new BufferedReader(source) {
            @Override
            public String readLine() throws IOException {
                String line = source.readLine();
                if (line != null) consumed.add(line);
                return line;
            }
        };
    }

    @Test
    void the_stream_waits_for_job_finish_before_returning() throws Exception {
        List<String> consumed = new ArrayList<>();
        // The engine keeps writing under target/ after the plan terminal (preflight memos, the
        // journal's jk-results.md copy) and only then sends job-finish. Returning on the terminal
        // hands the caller a tree the engine is still writing into (JK-2451).
        BufferedReader reader = recording(
                consumed,
                ProtoLifecycle.jobStart(43, "build", "/proj", 9),
                ProtoEvents.planDone(0),
                ProtoEvents.planFinish("/proj", true, false),
                ProtoJobs.timeline("/proj/target/jk-profile.json"),
                ProtoLifecycle.jobFinish(43),
                "{\"type\":\"past-the-end\"}");

        BuildPlanResult result =
                EngineEventDecoder.streamSingleBuildPlanEvents(reader, steps -> new BuildPlanListener() {}, null, null);

        assertThat(result.success()).isTrue();
        assertThat(consumed).anyMatch(l -> l.contains("job-finish"));
        // …and not one line further: the wait ends at job-finish, it does not drain the socket.
        assertThat(consumed).noneMatch(l -> l.contains("past-the-end"));
    }

    @Test
    void the_job_note_is_forgotten_when_the_stream_finishes() throws Exception {
        BufferedReader reader = stream(
                ProtoLifecycle.jobStart(41, "build", "/proj", 7),
                ProtoEvents.planDone(0),
                ProtoEvents.planFinish("/proj", true, false));

        BuildPlanResult result =
                EngineEventDecoder.streamSingleBuildPlanEvents(reader, steps -> new BuildPlanListener() {}, null, null);

        assertThat(result.success()).isTrue();
        assertThat(EngineClient.ActiveJobs.snapshot()).isEmpty();
    }

    @Test
    void a_cancel_terminal_before_plan_done_settles_without_a_listener() throws Exception {
        BufferedReader reader = stream(
                ProtoLifecycle.jobStart(42, "build", "/proj", 8),
                // Remote cancel injected before the plan burst ever created the listener.
                ProtoEvents.planFinish("/proj", false, true));

        BuildPlanResult result =
                EngineEventDecoder.streamSingleBuildPlanEvents(reader, steps -> new BuildPlanListener() {}, null, null);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(EngineClient.ActiveJobs.snapshot()).isEmpty();
    }

    /**
     * The CLI reads the counts off {@code plan-finish} — and reads them from the one nested
     * {@code tests} object, not from the flat {@code testTotal}/{@code testFailed} scalars the wire
     * used to carry (JK-2424). {@code jk test}'s "Passed N tests" line is rendered from this.
     */
    @Test
    void the_summary_line_reads_test_counts_off_the_nested_tests_object() throws Exception {
        TestSummary[] out = new TestSummary[1];
        BufferedReader reader = stream(ProtoEvents.planDone(0), ProtoEvents.planFinish("/p", false, 7, 4, 2, 1));

        EngineEventDecoder.streamSingleBuildPlanEvents(reader, steps -> new BuildPlanListener() {}, out, null);

        assertThat(out[0]).isNotNull();
        assertThat(out[0].total()).isEqualTo(7);
        assertThat(out[0].succeeded()).isEqualTo(4);
        assertThat(out[0].failed()).isEqualTo(2);
        assertThat(out[0].skipped()).isEqualTo(1);
    }

    /** A build with no test phase leaves the summary null — not a zeroed one that prints "0 tests". */
    @Test
    void a_plan_finish_without_test_counts_leaves_the_summary_unset() throws Exception {
        TestSummary[] out = new TestSummary[1];
        BufferedReader reader = stream(ProtoEvents.planDone(0), ProtoEvents.planFinish("/p", true, false));

        EngineEventDecoder.streamSingleBuildPlanEvents(reader, steps -> new BuildPlanListener() {}, out, null);

        assertThat(out[0]).isNull();
    }

    @Test
    void test_failure_does_not_take_class_from_nested_throwable() throws Exception {
        var info = new TestFailureInfo(
                "",
                "junit-jupiter",
                "",
                "bar()",
                "java.lang.AssertionError",
                "boom",
                "java.lang.AssertionError: boom\n\tat x.Y.z(Y.java:1)");
        BufferedReader reader = stream(
                ProtoEvents.planDone(0),
                ProtoEvents.planDiagnostic("/p", "run-tests", "test-failure", "boom", info),
                ProtoEvents.planFinish("/p", false, false));

        BuildPlanResult result =
                EngineEventDecoder.streamSingleBuildPlanEvents(reader, steps -> new BuildPlanListener() {}, null, null);

        assertThat(result.errors()).singleElement().satisfies(d -> {
            assertThat(d.className()).isEmpty();
            assertThat(d.method()).isEqualTo("bar()");
            assertThat(d.exceptionClass()).isEqualTo("java.lang.AssertionError");
            assertThat(d.testFailure()).isNotNull();
            assertThat(d.testFailure().className()).isEmpty();
        });
    }

    /**
     * A workspace step terminal carries its real wall-clock {@code millis}. Before JK-2436 the
     * workspace loop, the single-plan loop and {@link EnginePluginAdapter} each kept their own copy
     * of this arm, and the plugin copy passed {@code Duration.ZERO} — so "which loop decoded it"
     * silently decided whether a step had a duration at all. One table now answers for both shapes.
     */
    @Test
    void a_workspace_step_finish_keeps_the_wire_duration() throws Exception {
        Recorder rec = new Recorder();
        EngineEventDecoder.streamWorkspaceEvents(
                stream(
                        ProtoLifecycle.jobStart(51, "build", "/w", 2),
                        ProtoEvents.planModule("/w/a", "g:a:1", "a", 10, false),
                        ProtoEvents.planStep("/w/a", "compile-java", "compile", "compile"),
                        ProtoEvents.planDone(1),
                        ProtoEvents.moduleStart("/w/a"),
                        ProtoEvents.stepFinish("/w/a", "compile-java", "compile", TaskStatus.SUCCESS.name(), 1_234),
                        ProtoEvents.planFinish("/w/a", true),
                        ProtoEvents.moduleFinish("/w/a", "g:a:1", true, 0, 1_234),
                        ProtoEvents.workspaceFinish(true, 0, List.of(), false),
                        ProtoLifecycle.jobFinish(51)),
                rec,
                Path.of("/cache"),
                null);

        assertThat(rec.stepDurations).containsExactly(Duration.ofMillis(1_234));
    }

    /**
     * Two modules failing in the same workspace keep their diagnostics apart: each {@code
     * plan-finish} carries only its own module's. A single shared accumulator would hand module B's
     * plan-finish A's failure too, which is what the reader sees in the summary.
     */
    @Test
    void workspace_diagnostics_land_on_the_module_that_produced_them() throws Exception {
        Recorder rec = new Recorder();
        WorkspaceResult result = EngineEventDecoder.streamWorkspaceEvents(
                stream(
                        ProtoLifecycle.jobStart(52, "build", "/w", 2),
                        ProtoEvents.planModule("/w/a", "g:a:1", "a", 10, false),
                        ProtoEvents.planModule("/w/b", "g:b:1", "b", 10, false),
                        ProtoEvents.planDone(2),
                        ProtoEvents.moduleStart("/w/a"),
                        ProtoEvents.planDiagnostic("/w/a", "run-tests", "test-failure", "a blew up", "", "E"),
                        ProtoEvents.moduleStart("/w/b"),
                        ProtoEvents.planDiagnostic("/w/b", "run-tests", "test-failure", "b blew up", "", "E"),
                        ProtoEvents.planFinish("/w/a", false),
                        ProtoEvents.moduleFinish("/w/a", "g:a:1", false, 1, 5),
                        ProtoEvents.planFinish("/w/b", false),
                        ProtoEvents.moduleFinish("/w/b", "g:b:1", false, 1, 5),
                        ProtoEvents.workspaceFinish(false, 1, List.of(), false),
                        ProtoLifecycle.jobFinish(52)),
                rec,
                Path.of("/cache"),
                null);

        assertThat(result.success()).isFalse();
        assertThat(rec.diagnosticsByModule.get("a")).containsExactly("a blew up");
        assertThat(rec.diagnosticsByModule.get("b")).containsExactly("b blew up");
    }

    /** Records what a workspace build actually told its front-end, per module. */
    private static final class Recorder implements WorkspaceBuildListener {
        final List<Duration> stepDurations = new ArrayList<>();
        final Map<String, List<String>> diagnosticsByModule = new LinkedHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void stepFinish(String step, String group, TaskStatus status, Duration duration) {
                    stepDurations.add(duration);
                }

                @Override
                public void planFinish(BuildPlanResult result) {
                    diagnosticsByModule.put(
                            name,
                            result.errors().stream()
                                    .map(BuildPlanResult.Diagnostic::message)
                                    .toList());
                }
            };
        }
    }
}

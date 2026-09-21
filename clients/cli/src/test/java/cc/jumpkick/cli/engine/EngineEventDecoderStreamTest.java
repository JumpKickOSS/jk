// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.protocol.EngineWireException;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import cc.jumpkick.wire.protocol.TimelineEvent;
import cc.jumpkick.wire.protocol.WorkspaceFinishEvent;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wire-event decoding, both shapes. Single-plan: the {@link ActiveJobs} note must not
 * outlive the stream (a stale jid adds a 2s cancel RPC to every later Ctrl-C in a watch loop), and
 * a cancel terminal injected before {@code plan-done} must settle, not NPE. Workspace: the events
 * that both shapes share are decoded by one table, so the assertions below are what stops
 * the workspace copy from drifting away from the single-plan one again.
 */
class EngineEventDecoderStreamTest {

    @BeforeEach
    void reset() {
        ActiveJobs.forgetAll();
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
        // hands the caller a tree the engine is still writing into.
        BufferedReader reader = recording(
                consumed,
                ProtoLifecycle.jobStart(43, "build", "/proj", 9),
                ProtoEvents.planDone(0),
                ProtoEvents.planFinish("/proj", true, false),
                new TimelineEvent("/proj/target/jk-profile.json").encode(),
                ProtoLifecycle.jobFinish(43),
                "{\"type\":\"past-the-end\"}");

        BuildPlanResult result =
                EngineEventDecoder.streamSingleBuildPlanEvents(reader, steps -> new BuildPlanListener() {}, null, null);

        assertThat(result.success()).isTrue();
        assertThat(consumed).anyMatch(l -> l.contains("job-finish"));
        // …and not one line further: the wait ends at job-finish, it does not drain the socket.
        assertThat(consumed).noneMatch(l -> l.contains("past-the-end"));
    }

    /**
     * A workspace terminal on a single-plan read is a client bug: the caller chose the single-plan
     * reader for a workspace root or member. Both single-plan readers refuse it loudly rather than
     * folding several modules' plans into one green result.
     */
    @Test
    void a_workspace_terminal_on_a_single_plan_read_is_refused_by_both_readers() {
        String terminal = new WorkspaceFinishEvent(true, 0, List.of(), false).encode();

        assertThatThrownBy(() -> EngineEventDecoder.streamSingleBuildPlanEvents(
                        stream(ProtoLifecycle.jobStart(43, "compile", "/ws/app", 9), terminal),
                        steps -> new BuildPlanListener() {},
                        null,
                        null))
                .isInstanceOf(EngineWireException.class)
                .hasMessageContaining("workspace vocabulary");
        assertThat(ActiveJobs.snapshot()).isEmpty();

        assertThatThrownBy(() -> WireStream.pumpJob(
                        stream(ProtoEvents.planDone(0), terminal),
                        null,
                        EnginePluginAdapter.hostedDecoder(
                                "compile", steps -> new BuildPlanListener() {}, (type, line) -> {}, null)))
                .isInstanceOf(EngineWireException.class)
                .hasMessageContaining("workspace vocabulary");
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
        assertThat(ActiveJobs.snapshot()).isEmpty();
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
        assertThat(ActiveJobs.snapshot()).isEmpty();
    }

    /**
     * The CLI reads test counts from the nested {@code tests} object on {@code plan-finish}.
     * {@code jk test}'s "Passed N tests" line is rendered from this.
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
    void a_test_failure_without_a_class_keeps_it_empty() throws Exception {
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
            assertThat(Objects.requireNonNull(d.testFailure()).className()).isEmpty();
        });
    }

    /**
     * A workspace step terminal carries its real wall-clock {@code millis}.
     * One decode table answers for both workspace and single-plan shapes.
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
                        ProtoEvents.stepFinish(
                                "/w/a", "compile-java", "compile", TaskStatus.SUCCESS.name(), 1_234, 900),
                        ProtoEvents.planFinish("/w/a", true),
                        ProtoEvents.moduleFinish("/w/a", "g:a:1", true, 0, 1_234),
                        ProtoEvents.workspaceFinish(true, 0, List.of(), false),
                        ProtoLifecycle.jobFinish(51)),
                rec,
                Path.of("/cache"),
                null);

        assertThat(rec.stepDurations).containsExactly(Duration.ofMillis(1_234));
        assertThat(rec.stepWaits).containsExactly(Duration.ofMillis(900));
    }

    /** The shelf publish an install pass reports rides the module's outcome to the client. */
    @Test
    void a_module_finish_carries_what_the_install_pass_shelved() throws Exception {
        ModuleOutcome.Shelved shelved = new ModuleOutcome.Shelved("g:a:1", "a".repeat(64), "b".repeat(64));
        WorkspaceResult result = EngineEventDecoder.streamWorkspaceEvents(
                stream(
                        ProtoLifecycle.jobStart(53, "install", "/w", 1),
                        ProtoEvents.planModule("/w/a", "g:a:1", "a", 10, false),
                        ProtoEvents.planDone(1),
                        ProtoEvents.moduleStart("/w/a"),
                        ProtoEvents.planFinish("/w/a", true),
                        ProtoEvents.moduleFinish("/w/a", "g:a:1", true, 0, 12, true, false, null, shelved),
                        ProtoEvents.workspaceFinish(true, 0, List.of(), false),
                        ProtoLifecycle.jobFinish(53)),
                new Recorder(),
                Path.of("/cache"),
                null);

        assertThat(result.modules()).hasSize(1);
        assertThat(result.modules().getFirst().shelved()).isEqualTo(shelved);
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

    /**
     * The hosted adapters ({@link EnginePluginAdapter}, {@link EngineResolveAdapter}) route their
     * {@code default ->} arm through this same table — each carried a private drifted copy that
     * reported {@code Duration.ZERO} on {@code task-finish} and flattened an enriched test failure
     * to five flat fields. This pins the shared arms those adapters now depend on, invoked exactly
     * as their pump loops invoke them.
     */
    @Test
    void the_shared_table_keeps_millis_and_test_failure_enrichment_for_the_hosted_adapters() {
        List<Duration> durations = new ArrayList<>();
        List<TestFailureInfo> failures = new ArrayList<>();
        List<BuildPlanResult.Diagnostic> diagnostics = new ArrayList<>();
        BuildPlanListener listener = new BuildPlanListener() {
            @Override
            public void stepFinish(
                    String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                durations.add(duration);
            }

            @Override
            public void error(String step, String code, String message, @Nullable TestFailureInfo failure) {
                if (failure != null) failures.add(failure);
            }
        };
        var info = new TestFailureInfo(
                "g:a",
                "junit-jupiter",
                "pkg.FooTest",
                "bar()",
                "java.lang.AssertionError",
                "boom",
                "java.lang.AssertionError: boom\n\tat pkg.FooTest.bar(FooTest.java:4)",
                1,
                "src/test/java/pkg/FooTest.java",
                4,
                2,
                List.of("void bar() {", "  fail();"));

        String finish = ProtoEvents.stepFinish("/p", "audit", "verify", TaskStatus.SUCCESS.name(), 2_500, 0);
        String error = ProtoEvents.errorLine("/p", "run-tests", "test-failure", "boom", info);
        EngineEventDecoder.dispatch(Jsonl.requiredStr(finish, "type"), finish, listener, diagnostics::add);
        EngineEventDecoder.dispatch(Jsonl.requiredStr(error, "type"), error, listener, diagnostics::add);

        assertThat(durations).containsExactly(Duration.ofMillis(2_500));
        assertThat(failures).singleElement().satisfies(f -> {
            assertThat(f.className()).isEqualTo("pkg.FooTest");
            assertThat(f.file()).isEqualTo("src/test/java/pkg/FooTest.java");
            assertThat(f.line()).isEqualTo(4);
            assertThat(f.snippet()).hasSize(2);
        });
    }

    /** Records what a workspace build actually told its front-end, per module. */
    private static final class Recorder implements WorkspaceBuildListener {
        final List<Duration> stepDurations = new ArrayList<>();
        final List<Duration> stepWaits = new ArrayList<>();
        final Map<String, List<String>> diagnosticsByModule = new LinkedHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    stepDurations.add(duration);
                    stepWaits.add(waited);
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

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.run.BuildPlanResult;
import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Single-plan stream decoding: the {@link EngineClient.ActiveJobs} note must not outlive the
 * stream (a stale jid adds a 2s cancel RPC to every later Ctrl-C in a watch loop —, and
 * a cancel terminal injected before {@code plan-done} must settle, not NPE.
 */
class EngineBuildListenerAdapterStreamTest {

    @BeforeEach
    void reset() {
        EngineClient.ActiveJobs.forgetAll();
    }

    private static BufferedReader stream(String... lines) {
        return new BufferedReader(new StringReader(String.join("\n", lines) + "\n"));
    }

    @Test
    void the_job_note_is_forgotten_when_the_stream_finishes() throws Exception {
        BufferedReader reader = stream(
                ProtoLifecycle.jobStart(41, "build", "/proj", 7),
                ProtoEvents.planDone(0),
                ProtoEvents.planFinish("/proj", true, false));

        BuildPlanResult result = EngineBuildListenerAdapter.streamSingleBuildPlanEvents(
                reader, steps -> new cc.jumpkick.run.BuildPlanListener() {}, null, null);

        assertThat(result.success()).isTrue();
        assertThat(EngineClient.ActiveJobs.snapshot()).isEmpty();
    }

    @Test
    void a_cancel_terminal_before_plan_done_settles_without_a_listener() throws Exception {
        BufferedReader reader = stream(
                ProtoLifecycle.jobStart(42, "build", "/proj", 8),
                // Remote cancel injected before the plan burst ever created the listener.
                ProtoEvents.planFinish("/proj", false, true));

        BuildPlanResult result = EngineBuildListenerAdapter.streamSingleBuildPlanEvents(
                reader, steps -> new cc.jumpkick.run.BuildPlanListener() {}, null, null);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(EngineClient.ActiveJobs.snapshot()).isEmpty();
    }

    @Test
    void test_failure_does_not_take_class_from_nested_throwable() throws Exception {
        var info = new cc.jumpkick.run.TestFailureInfo(
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

        BuildPlanResult result = EngineBuildListenerAdapter.streamSingleBuildPlanEvents(
                reader, steps -> new cc.jumpkick.run.BuildPlanListener() {}, null, null);

        assertThat(result.errors()).singleElement().satisfies(d -> {
            assertThat(d.className()).isEmpty();
            assertThat(d.method()).isEqualTo("bar()");
            assertThat(d.exceptionClass()).isEqualTo("java.lang.AssertionError");
            assertThat(d.testFailure()).isNotNull();
            assertThat(d.testFailure().className()).isEmpty();
        });
    }
}

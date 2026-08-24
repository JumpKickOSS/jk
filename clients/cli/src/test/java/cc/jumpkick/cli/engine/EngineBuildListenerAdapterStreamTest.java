// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.run.BuildPlanResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
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

        BuildPlanResult result = EngineBuildListenerAdapter.streamSingleBuildPlanEvents(
                reader, steps -> new cc.jumpkick.run.BuildPlanListener() {}, null, null);

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

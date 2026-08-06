// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.EngineProtocol;
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
                EngineProtocol.jobStart(41, "build", "/proj", 7),
                EngineProtocol.planDone(0),
                EngineProtocol.planFinish("/proj", true, false));

        BuildPlanResult result = EngineBuildListenerAdapter.streamSingleBuildPlanEvents(
                reader, steps -> new cc.jumpkick.run.BuildPlanListener() {}, null, null);

        assertThat(result.success()).isTrue();
        assertThat(EngineClient.ActiveJobs.snapshot()).isEmpty();
    }

    @Test
    void a_cancel_terminal_before_plan_done_settles_without_a_listener() throws Exception {
        BufferedReader reader = stream(
                EngineProtocol.jobStart(42, "build", "/proj", 8),
                // Remote cancel injected before the plan burst ever created the listener.
                EngineProtocol.planFinish("/proj", false, true));

        BuildPlanResult result = EngineBuildListenerAdapter.streamSingleBuildPlanEvents(
                reader, steps -> new cc.jumpkick.run.BuildPlanListener() {}, null, null);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(EngineClient.ActiveJobs.snapshot()).isEmpty();
    }
}

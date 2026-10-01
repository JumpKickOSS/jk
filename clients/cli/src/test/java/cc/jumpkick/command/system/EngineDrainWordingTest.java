// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A draining engine answers status, so status and stop describe it as stopping — how many jobs it
 * waits for and when it exits at the latest — never as a busy engine that does not answer.
 */
class EngineDrainWordingTest {

    private static final long DEADLINE = 1_700_003_600_000L;

    /** A status snapshot from a draining engine with {@code jobs} live jobs and the given deadline. */
    @SuppressWarnings("NullAway") // the absent vitals are the engine's own nulls
    private static EngineProbe.Status draining(int jobs, long deadline) {
        return new EngineProbe.Status(
                "0.13.0",
                19518L,
                1_700_000_000_000L,
                0,
                jobs,
                true,
                0L,
                0L,
                0L,
                0L,
                null,
                null,
                null,
                null,
                -1,
                -1L,
                -1L,
                -1.0,
                -1.0,
                "epoch-1",
                -1L,
                -1L,
                -1L,
                null,
                0,
                null,
                null,
                null,
                -1L,
                -1L,
                -1L,
                0L,
                0,
                null,
                -1,
                -1,
                false,
                null,
                null,
                deadline,
                List.of());
    }

    @Test
    void status_headline_says_stopping_with_the_job_count_and_the_deadline() {
        assertThat(EngineStatusCommand.stoppingHeadline("19518", draining(1, DEADLINE)))
                .isEqualTo("Engine is stopping (pid 19518): draining 1 job, exits by "
                        + EngineStatusCommand.wallClock(DEADLINE))
                .doesNotContain("busy")
                .doesNotContain("does not answer");
        assertThat(EngineStatusCommand.stoppingHeadline("19518", draining(2, -1)))
                .as("an unbounded drain names no exit time")
                .isEqualTo("Engine is stopping (pid 19518): draining 2 jobs");
    }

    @Test
    void the_fleet_row_names_a_draining_engine_draining_not_unresponsive() {
        assertThat(EngineStatusCommand.drainingFleetNote(1, DEADLINE))
                .isEqualTo("draining (1 job, deadline " + EngineStatusCommand.wallClock(DEADLINE) + ")");
        assertThat(EngineStatusCommand.drainingFleetNote(3, -1)).isEqualTo("draining (3 jobs)");
    }

    @Test
    void json_carries_draining_and_the_deadline_for_the_engine_and_each_fleet_member() {
        EngineProbe.Status s = draining(1, DEADLINE);
        EngineFleet.Member member = new EngineFleet.Member(null, null, s, 19518L, false);
        String json = EngineStatusCommand.runningJson(s, 60, List.of(member));
        assertThat(Jsonl.bool(json, "draining", false)).isTrue();
        assertThat(Jsonl.longValue(json, "drainDeadline", 0)).isEqualTo(DEADLINE);
        assertThat(EngineStatusCommand.enginesJson(List.of(member)))
                .contains("\"draining\":true,\"drainDeadline\":" + DEADLINE);
    }

    @Test
    void stop_says_what_it_waits_for_and_names_now() {
        assertThat(EngineStopCommand.drainingMessage(1, DEADLINE))
                .isEqualTo("Engine is stopping: waiting for 1 in-flight job (exits by "
                        + EngineStatusCommand.wallClock(DEADLINE)
                        + "); the next build starts a new engine. `jk engine stop --now` stops it now");
        assertThat(EngineStopCommand.drainingMessage(2, -1))
                .startsWith("Engine is stopping: waiting for 2 in-flight jobs;")
                .contains("--now");
    }

    @Test
    void jk_status_calls_a_draining_engine_stopping() {
        String line = TestAnsi.strip(StatusCommand.engineStatusMessage(Optional.of(draining(1, DEADLINE))));
        assertThat(line)
                .contains(
                        "is stopping (pid 19518): draining 1 job, exits by " + EngineStatusCommand.wallClock(DEADLINE));
    }
}

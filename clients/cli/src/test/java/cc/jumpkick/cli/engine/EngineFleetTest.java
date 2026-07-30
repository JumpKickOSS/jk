// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * JK-1293: stopping an engine must be reliable without the user reaching for {@code kill}.
 *
 * <p>These cover the termination contract rather than the socket protocol: the point of the fleet helper
 * is that it reports what actually happened to a process instead of assuming a request was obeyed. On
 * Windows the alternative is telling someone to identify the right JVM in Task Manager.
 */
class EngineFleetTest {

    @Test
    void a_process_that_is_already_gone_counts_as_exited() {
        // A pid nothing owns must read as gone rather than hang out the grace period, or every stop of an
        // already-dead engine would pause for seconds and then claim a kill it did not perform.
        long neverRunning = 0x7FFF_FFFFL;

        assertThat(EngineFleet.waitForExit(neverRunning)).isTrue();
    }

    @Test
    void an_unaddressable_pid_is_treated_as_gone_not_as_a_kill() {
        // pid <= 0 means "no pid recorded". Claiming to have killed it would be a lie; claiming it is gone
        // is the honest reading, since there is nothing left to stop.
        assertThat(EngineFleet.waitForExit(0)).isTrue();
        assertThat(EngineFleet.waitForExit(-1)).isTrue();
    }

    @Test
    void a_live_process_is_not_reported_as_exited() throws Exception {
        // The case that matters: waitForExit must not return true for something still running, or a stop
        // would report success over a surviving engine.
        Process sleeper = new ProcessBuilder("sleep", "30").start();
        try {
            assertThat(sleeper.isAlive()).isTrue();

            assertThat(EngineFleet.waitForExit(sleeper.pid())).isFalse();
        } finally {
            sleeper.destroyForcibly();
            sleeper.waitFor();
        }
    }

    @Test
    void exit_is_noticed_while_waiting_rather_than_only_at_the_deadline() throws Exception {
        // Polls for the exit, so a quick shutdown returns quickly. If this waited out the full grace, every
        // `stop` would feel broken even when it worked.
        Process sleeper = new ProcessBuilder("sleep", "30").start();
        long startNanos = System.nanoTime();
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            sleeper.destroyForcibly();
        });
        killer.start();
        try {
            assertThat(EngineFleet.waitForExit(sleeper.pid())).isTrue();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            assertThat(elapsedMs).as("returned on exit, not at the deadline").isLessThan(4_000);
        } finally {
            killer.join();
            sleeper.waitFor();
        }
    }

    @Test
    void stopping_a_pid_no_engine_owns_reports_nothing_rather_than_pretending() {
        // `stop --pid <wrong>` must say so. Silently succeeding would leave the real engine running while
        // telling the user it was handled.
        assertThat(EngineFleet.stopByPid(0x7FFF_FFFEL, /* now= */ true)).isEmpty();
    }

    @Test
    void the_outcome_vocabulary_distinguishes_the_case_a_user_must_act_on() {
        // SURVIVED exists so a stop that did not work cannot be reported as one that did.
        assertThat(EngineFleet.Outcome.values())
                .containsExactly(
                        EngineFleet.Outcome.STOPPED,
                        EngineFleet.Outcome.KILLED,
                        EngineFleet.Outcome.DRAINING,
                        EngineFleet.Outcome.SURVIVED);
    }

    @Test
    void listing_never_throws_when_nothing_is_running() {
        // Called from `jk engine status` on every invocation, including a machine with no engine at all.
        assertThat(EngineFleet.list()).isNotNull();
    }
}

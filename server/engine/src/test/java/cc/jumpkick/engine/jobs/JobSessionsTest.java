// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.RemainingWork;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Late open after retire must not resurrect a session. */
class JobSessionsTest {

    @Test
    void late_open_after_retire_is_noop() {
        AtomicLong newest = new AtomicLong(5);
        JobSessions sessions = new JobSessions(newest::get);
        JobSession live = sessions.open(5);
        assertThat(live).isNotNull();
        sessions.retire(5);
        assertThat(sessions.retired(5)).isTrue();
        assertThat(sessions.get(5)).isNull();
        assertThat(sessions.open(5)).isNull();
        assertThat(sessions.liveCount()).isZero();
    }

    /**
     * An open and a retire of one id race from two threads, as a late progress event and the
     * job's settlement do. Whichever wins, no session is left in the map once the id is retired.
     */
    @Test
    void an_open_racing_a_retire_leaves_no_session_behind() throws Exception {
        JobSessions sessions = new JobSessions(() -> 0L);
        int ids = 200_000;
        AtomicInteger arrived = new AtomicInteger();
        Thread retirer = Thread.ofPlatform().start(() -> {
            for (int id = 1; id <= ids; id++) {
                meet(arrived, id);
                sessions.retire(id);
            }
        });
        for (int id = 1; id <= ids; id++) {
            meet(arrived, id);
            sessions.open(id);
        }
        retirer.join();
        assertThat(sessions.liveCount())
                .as("sessions opened for ids already retired")
                .isZero();
    }

    /** Spin until both racers have reached round {@code id}, so each round starts them together. */
    private static void meet(AtomicInteger arrived, int id) {
        arrived.incrementAndGet();
        while (arrived.get() < 2 * id) Thread.onSpinWait();
    }

    @Test
    void tracker_after_retire_is_detached() {
        JobSessions sessions = new JobSessions(() -> 1L);
        JobSession s = requireNonNull(sessions.open(1));
        var first = s.tracker();
        sessions.retire(1);
        var late = s.tracker();
        assertThat(late).isNotSameAs(first);
        assertThat(sessions.open(1)).isNull();
    }

    /**
     * The build no longer waits for its ETA, so modules can finish before the work model exists.
     * A model set late must not count them as still to do.
     */
    @Test
    void work_model_set_late_drains_modules_that_already_finished() {
        JobSessions sessions = new JobSessions(() -> 1L);
        sessions.open(1);
        sessions.moduleCompleted(1, "/ws/a");
        RemainingWork rw = RemainingWork.seed(
                List.of(
                        new ModuleWorkCost(Path.of("/ws/a"), Set.of(), 10, 0),
                        new ModuleWorkCost(Path.of("/ws/b"), Set.of(), 10, 0)),
                2_000,
                1,
                true,
                false);
        assertThat(rw.pendingModules()).isEqualTo(2);
        sessions.remaining(1, rw);
        assertThat(rw.pendingModules()).isEqualTo(1);
    }

    @Test
    void finished_modules_are_forgotten_on_retire() {
        JobSessions sessions = new JobSessions(() -> 1L);
        JobSession s = requireNonNull(sessions.open(1));
        s.moduleCompleted("/ws/a");
        s.retire();
        RemainingWork rw = RemainingWork.seed(
                List.of(new ModuleWorkCost(Path.of("/ws/a"), Set.of(), 10, 0)), 1_000, 1, true, false);
        s.remaining(rw);
        assertThat(rw.pendingModules()).isEqualTo(1);
    }
}

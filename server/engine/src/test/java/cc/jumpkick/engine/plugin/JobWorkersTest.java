// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.testing.Sleepers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JobWorkersTest {

    @Test
    void destroy_kills_registered_alive_process() throws Exception {
        long req = 42L;
        JobWorkers.open(req);
        Process p = Sleepers.sleeper(60).start();
        try {
            JobWorkers.register(p);
            assertThat(JobWorkers.trackedCount(req)).isEqualTo(1);
            assertThat(p.isAlive()).isTrue();

            int killed = JobWorkers.destroyForRequest(req);
            assertThat(killed).isEqualTo(1);
            assertThat(p.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(p.isAlive()).isFalse();
            assertThat(JobWorkers.trackedCount(req)).isEqualTo(0);
        } finally {
            if (p.isAlive()) p.destroyForcibly();
            JobWorkers.close();
            JobWorkers.clear(req);
        }
    }

    @Test
    void start_registers_when_a_request_scope_is_open() throws Exception {
        long req = 55L;
        JobWorkers.open(req);
        Process p = JobWorkers.start(Sleepers.sleeper(30));
        try {
            assertThat(JobWorkers.trackedCount(req)).isEqualTo(1);
            assertThat(p.isAlive()).isTrue();
        } finally {
            if (p.isAlive()) p.destroyForcibly();
            JobWorkers.close();
            JobWorkers.clear(req);
        }
    }

    @Test
    void register_without_scope_is_noop() throws Exception {
        Process p = Sleepers.sleeper(1).start();
        try {
            JobWorkers.register(p); // no open() — ignored
            assertThat(JobWorkers.destroyForRequest(999L)).isEqualTo(0);
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void unregister_removes_from_tracking() throws Exception {
        long req = 7L;
        JobWorkers.open(req);
        Process p = Sleepers.sleeper(30).start();
        try {
            JobWorkers.register(p);
            JobWorkers.unregister(p);
            assertThat(JobWorkers.trackedCount(req)).isEqualTo(0);
            assertThat(JobWorkers.destroyForRequest(req)).isEqualTo(0);
            assertThat(p.isAlive()).isTrue();
        } finally {
            p.destroyForcibly();
            JobWorkers.close();
            JobWorkers.clear(req);
        }
    }

    @Test
    void late_register_after_shutdown_kills_on_arrival_and_leaks_nothing() throws Exception {
        // : a cpu-pool thread still draining after cancel may register a fresh process
        // AFTER shutdownForRequest ran. It must not resurrect a BY_REQUEST entry (permanent
        // leak) nor escape the kill.
        long req = 97L;
        JobWorkers.open(req);
        Process p = Sleepers.sleeper(60).start();
        try {
            assertThat(JobWorkers.shutdownForRequest(req, 0L)).isEqualTo(0); // nothing yet
            JobWorkers.register(p); // late registration from a draining thread
            assertThat(JobWorkers.trackedCount(req)).isEqualTo(0); // no resurrected entry
            assertThat(p.waitFor(5, TimeUnit.SECONDS)).isTrue(); // killed on arrival
            assertThat(p.isAlive()).isFalse();
            // A fresh scope open for the same id clears the tombstone: registration works again.
            JobWorkers.open(req);
            Process q = Sleepers.sleeper(60).start();
            try {
                JobWorkers.register(q);
                assertThat(JobWorkers.trackedCount(req)).isEqualTo(1);
            } finally {
                if (q.isAlive()) q.destroyForcibly();
                JobWorkers.clear(req);
            }
        } finally {
            if (p.isAlive()) p.destroyForcibly();
            JobWorkers.close();
            JobWorkers.clear(req);
        }
    }

    @Test
    void destroy_is_idempotent() throws Exception {
        long req = 11L;
        JobWorkers.open(req);
        List<Process> kids = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                Process p = Sleepers.sleeper(60).start();
                kids.add(p);
                JobWorkers.register(p);
            }
            assertThat(JobWorkers.destroyForRequest(req)).isEqualTo(3);
            assertThat(JobWorkers.destroyForRequest(req)).isEqualTo(0);
        } finally {
            for (Process p : kids) {
                if (p.isAlive()) p.destroyForcibly();
            }
            JobWorkers.close();
            JobWorkers.clear(req);
        }
    }

    @Test
    void shutdown_with_grace_terminates_and_does_not_hang() throws Exception {
        long req = 99L;
        JobWorkers.open(req);
        // Ignore SIGTERM-friendly process: sleep still exits on destroy() on macOS/Linux.
        Process p = Sleepers.sleeper(120).start();
        try {
            JobWorkers.register(p);
            long t0 = System.nanoTime();
            int killed = JobWorkers.shutdownForRequest(req, 300L);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertThat(killed).isEqualTo(1);
            assertThat(p.isAlive()).isFalse();
            // LIVENESS, not performance: the grace asked for above is 300ms, so 3s is 10x it.
            // What this can catch is a shutdown that ignores its grace entirely; what it must not
            // become is a measurement of how fast this machine forks and reaps.
            assertThat(ms)
                    .as("honoured the 300ms grace rather than waiting out the child's own 120s sleep")
                    .isLessThan(3_000L);
            assertThat(JobWorkers.trackedCount(req)).isEqualTo(0);
        } finally {
            if (p.isAlive()) p.destroyForcibly();
            JobWorkers.close();
            JobWorkers.clear(req);
        }
    }

    @Test
    void cancel_grace_default_is_sub_second_shared_not_per_worker() {
        assertThat(JobLimits.DEFAULT_CANCEL_GRACE_MS).isEqualTo(500L);
        assertThat(JobLimits.MAX_CANCEL_GRACE_MS).isEqualTo(5_000L);
    }

    /**
     * CPU steps run on a process-wide ForkJoinPool whose threads inherit whatever scope
     * was open when the pool created them. A fork must attach to the request that submitted the
     * work, not to that stale inherited one.
     */
    @Test
    void request_scope_rides_the_shared_cpu_pool() throws Exception {
        // Warm the pool from a thread carrying a *different* (stale) scope, so its threads inherit
        // 111 — exactly the situation that used to misattribute later requests' workers.
        JobWorkers.open(111L);
        JobWorkers.warmPoolForTest();
        JobWorkers.close();

        JobWorkers.open(222L);
        try {
            Long seen = JkThreads.cpu().submit(JobWorkers::currentScope).get(10, TimeUnit.SECONDS);
            assertThat(seen)
                    .as("pool task must see the submitting request's scope")
                    .isEqualTo(222L);
        } finally {
            JobWorkers.close();
        }

        // With no scope open, a pool task must not inherit a stale one either.
        Long unscoped = JkThreads.cpu().submit(JobWorkers::currentScope).get(10, TimeUnit.SECONDS);
        assertThat(unscoped)
                .as("no ambient scope must not leak a stale request")
                .isNull();
    }
}

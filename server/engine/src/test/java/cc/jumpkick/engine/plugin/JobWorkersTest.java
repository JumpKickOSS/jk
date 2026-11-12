// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.host.Processes;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.Sleepers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

@Tag("integration")
class JobWorkersTest {

    @Test
    void destroy_kills_registered_alive_process() throws Exception {
        long req = 42L;
        JobWorkers.begin(req);
        JobWorkers.runAs(req, () -> {
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
                JobWorkers.clear(req);
            }
        });
    }

    /**
     * A suspended job's workers are stopped and no fork of its starts until it resumes; a cancel of
     * a suspended job continues them so they can act on the SIGTERM.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_held_request_stops_its_workers_and_its_next_fork_until_released() throws Exception {
        long req = 4242L;
        JobWorkers.begin(req);
        JobWorkers.runAs(req, () -> {
            Process p = Sleepers.sleeper(60).start();
            try {
                JobWorkers.register(p);
                assertThat(JobWorkers.hold(req)).isEqualTo(1);
                Await.until(Duration.ofSeconds(5), () -> state(p.pid()) == 'T');

                CountDownLatch forked = new CountDownLatch(1);
                Thread fork = Thread.ofPlatform().start(() -> {
                    try {
                        JobWorkers.runAs(req, () -> {
                            Process next = JobWorkers.start(Sleepers.sleeper(1));
                            forked.countDown();
                            next.waitFor();
                        });
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                assertThat(forked.await(500, TimeUnit.MILLISECONDS))
                        .as("held at the fork")
                        .isFalse();

                assertThat(JobWorkers.release(req)).isEqualTo(1);
                assertThat(forked.await(5, TimeUnit.SECONDS)).isTrue();
                Await.until(Duration.ofSeconds(5), () -> state(p.pid()) != 'T');
                fork.join(5_000);

                JobWorkers.hold(req);
                Await.until(Duration.ofSeconds(5), () -> state(p.pid()) == 'T');
                JobWorkers.shutdownForRequest(req, 2_000);
                assertThat(p.waitFor(5, TimeUnit.SECONDS))
                        .as("a stopped worker still ends on cancel")
                        .isTrue();
            } finally {
                if (p.isAlive()) p.destroyForcibly();
                JobWorkers.release(req);
                JobWorkers.clear(req);
            }
        });
    }

    /** The process state letter from {@code ps} ({@code T} = stopped), or {@code ?}; macOS has no {@code /proc}. */
    private static char state(long pid) {
        String stat = Processes.outputIfOk(
                new ProcessBuilder("ps", "-o", "stat=", "-p", Long.toString(pid)), Duration.ofSeconds(10));
        return stat == null || stat.isBlank() ? '?' : stat.strip().charAt(0);
    }

    @Test
    void start_registers_when_a_request_scope_is_open() throws Exception {
        long req = 55L;
        JobWorkers.begin(req);
        JobWorkers.runAs(req, () -> {
            Process p = JobWorkers.start(Sleepers.sleeper(30));
            try {
                assertThat(JobWorkers.trackedCount(req)).isEqualTo(1);
                assertThat(p.isAlive()).isTrue();
            } finally {
                if (p.isAlive()) p.destroyForcibly();
                JobWorkers.clear(req);
            }
        });
    }

    @Test
    void register_without_scope_is_noop() throws Exception {
        Process p = Sleepers.sleeper(1).start();
        try {
            JobWorkers.register(p); // no request bound — ignored
            assertThat(JobWorkers.destroyForRequest(999L)).isEqualTo(0);
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void unregister_removes_from_tracking() throws Exception {
        long req = 7L;
        JobWorkers.begin(req);
        JobWorkers.runAs(req, () -> {
            Process p = Sleepers.sleeper(30).start();
            try {
                JobWorkers.register(p);
                JobWorkers.unregister(p);
                assertThat(JobWorkers.trackedCount(req)).isEqualTo(0);
                assertThat(JobWorkers.destroyForRequest(req)).isEqualTo(0);
                assertThat(p.isAlive()).isTrue();
            } finally {
                p.destroyForcibly();
                JobWorkers.clear(req);
            }
        });
    }

    @Test
    void late_register_after_shutdown_kills_on_arrival_and_leaks_nothing() throws Exception {
        // A cpu-pool thread still draining after cancel may register a fresh process
        // AFTER shutdownForRequest ran. It must not resurrect a BY_REQUEST entry (permanent
        // leak) nor escape the kill.
        long req = 97L;
        JobWorkers.begin(req);
        JobWorkers.runAs(req, () -> {
            Process p = Sleepers.sleeper(60).start();
            try {
                assertThat(JobWorkers.shutdownForRequest(req, 0L)).isEqualTo(0); // nothing yet
                JobWorkers.register(p); // late registration from a draining thread
                assertThat(JobWorkers.trackedCount(req)).isEqualTo(0); // no resurrected entry
                assertThat(p.waitFor(5, TimeUnit.SECONDS)).isTrue(); // killed on arrival
                assertThat(p.isAlive()).isFalse();
                // A request that starts again under the same id clears the tombstone: registration works again.
                JobWorkers.begin(req);
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
                JobWorkers.clear(req);
            }
        });
    }

    @Test
    void destroy_is_idempotent() throws Exception {
        long req = 11L;
        JobWorkers.begin(req);
        JobWorkers.runAs(req, () -> {
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
                JobWorkers.clear(req);
            }
        });
    }

    @Test
    void shutdown_with_grace_terminates_and_does_not_hang() throws Exception {
        long req = 99L;
        JobWorkers.begin(req);
        JobWorkers.runAs(req, () -> {
            // Ignore SIGTERM-friendly process: sleep still exits on destroy() on macOS/Linux.
            Process p = Sleepers.sleeper(120).start();
            try {
                JobWorkers.register(p);
                long t0 = System.nanoTime();
                int killed = JobWorkers.shutdownForRequest(req, 300L);
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                assertThat(killed).isEqualTo(1);
                assertThat(p.isAlive()).isFalse();
                // LIVENESS, not performance. Size the bound against the failure mode, not against the
                // grace: this catches a shutdown that ignores its grace and waits out the child's own
                // 120s sleep, and any bound well under that catches it just as well. Ten times the
                // grace was the tighter choice and it measured the machine instead — forking and
                // reaping a child under a full parallel suite outruns 3s on a loaded Windows box,
                // which is the thing the line above says this must not become.
                assertThat(ms)
                        .as("honoured the 300ms grace rather than waiting out the child's own 120s sleep")
                        .isLessThan(30_000L);
                assertThat(JobWorkers.trackedCount(req)).isEqualTo(0);
            } finally {
                if (p.isAlive()) p.destroyForcibly();
                JobWorkers.clear(req);
            }
        });
    }

    @Test
    void cancel_grace_default_is_sub_second_shared_not_per_worker() {
        assertThat(JobLimits.DEFAULT_CANCEL_GRACE_MS).isEqualTo(500L);
        assertThat(JobLimits.MAX_CANCEL_GRACE_MS).isEqualTo(5_000L);
    }

    /**
     * CPU steps run on a process-wide ForkJoinPool whose threads are created inside whichever request
     * first needed them. A fork must attach to the request that submitted the work, not to that one.
     */
    @Test
    void request_scope_rides_the_shared_cpu_pool() throws Exception {
        // Warm the pool under a *different* request, so its threads are created inside 111.
        JobWorkers.runAs(111L, JobWorkers::warmPoolForTest);

        JobWorkers.begin(222L);
        JobWorkers.runAs(222L, () -> {
            Long seen = JkThreads.cpu().submit(JobWorkers::currentScope).get(10, TimeUnit.SECONDS);
            assertThat(seen)
                    .as("pool task must see the submitting request's scope")
                    .isEqualTo(222L);
        });

        // With no scope open, a pool task must not inherit a stale one either.
        Long unscoped = JkThreads.cpu().submit(JobWorkers::currentScope).get(10, TimeUnit.SECONDS);
        assertThat(unscoped)
                .as("no ambient scope must not leak a stale request")
                .isNull();
    }
}

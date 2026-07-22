// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JobWorkersTest {

    @Test
    void destroy_kills_registered_alive_process() throws Exception {
        long req = 42L;
        JobWorkers.open(req);
        Process p = new ProcessBuilder("sleep", "60").start();
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
    void register_without_scope_is_noop() throws Exception {
        Process p = new ProcessBuilder("sleep", "1").start();
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
        Process p = new ProcessBuilder("sleep", "30").start();
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
    void destroy_is_idempotent() throws Exception {
        long req = 11L;
        JobWorkers.open(req);
        List<Process> kids = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                Process p = new ProcessBuilder("sleep", "60").start();
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
        Process p = new ProcessBuilder("sleep", "120").start();
        try {
            JobWorkers.register(p);
            long t0 = System.nanoTime();
            int killed = JobWorkers.shutdownForRequest(req, 300L);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertThat(killed).isEqualTo(1);
            assertThat(p.isAlive()).isFalse();
            // Must not wait far past grace (poll + force); allow generous CI slack.
            assertThat(ms).isLessThan(3_000L);
            assertThat(JobWorkers.trackedCount(req)).isEqualTo(0);
        } finally {
            if (p.isAlive()) p.destroyForcibly();
            JobWorkers.close();
            JobWorkers.clear(req);
        }
    }

    @Test
    void cancel_grace_default_is_sub_second_shared_not_per_worker() {
        assertThat(JobWorkers.DEFAULT_CANCEL_GRACE_MS).isEqualTo(500L);
        assertThat(JobWorkers.MAX_CANCEL_GRACE_MS).isEqualTo(5_000L);
        // Default product path (env unset in unit tests): 500ms shared wall clock.
        assertThat(JobWorkers.cancelGraceMs()).isEqualTo(500L);
    }
}

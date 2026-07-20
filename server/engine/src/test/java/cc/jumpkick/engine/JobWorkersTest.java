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
}

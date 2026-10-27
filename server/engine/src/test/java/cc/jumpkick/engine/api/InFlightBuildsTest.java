// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InFlightBuildsTest {

    @Test
    void second_same_fingerprint_is_rejected() {
        InFlightBuilds reg = new InFlightBuilds();
        var h1 = new InFlightBuilds.Hold(1, 27, "fp-a", "build", "/p", "g:a", 1L, "j1", "cli", null);
        assertThat(reg.tryAcquire(h1)).isEmpty();
        var h2 = new InFlightBuilds.Hold(2, 28, "fp-a", "build", "/p", "g:a", 2L, "j2", "cli", null);
        assertThat(reg.tryAcquire(h2)).contains(h1);
        assertThat(reg.peek("fp-a")).contains(h1);
    }

    @Test
    void different_fingerprints_run_concurrently() {
        InFlightBuilds reg = new InFlightBuilds();
        var h1 = new InFlightBuilds.Hold(1, 1, "fp-a", "build", "/a", null, 1L, null, "cli", null);
        var h2 = new InFlightBuilds.Hold(2, 2, "fp-b", "build", "/b", null, 2L, null, "cli", null);
        assertThat(reg.tryAcquire(h1)).isEmpty();
        assertThat(reg.tryAcquire(h2)).isEmpty();
        assertThat(reg.list()).hasSize(2);
    }

    @Test
    void release_allows_reacquire() {
        InFlightBuilds reg = new InFlightBuilds();
        var h1 = new InFlightBuilds.Hold(1, 5, "fp", "build", "/p", null, 1L, null, "cli", null);
        reg.tryAcquire(h1);
        reg.release(1);
        var h2 = new InFlightBuilds.Hold(2, 6, "fp", "build", "/p", null, 2L, null, "cli", null);
        assertThat(reg.tryAcquire(h2)).isEmpty();
        assertThat(reg.peek("fp").orElseThrow().requestId()).isEqualTo(2);
    }

    @Test
    void binding_a_journal_id_updates_both_views() {
        InFlightBuilds reg = new InFlightBuilds();
        reg.tryAcquire(new InFlightBuilds.Hold(1, 5, "fp", "build", "/p", null, 1L, null, "cli", null));

        reg.bindJournalId(1, "j1");

        assertThat(reg.get(1).orElseThrow().journalId()).isEqualTo("j1");
        assertThat(reg.peek("fp").orElseThrow().journalId()).isEqualTo("j1");
    }

    @Test
    void a_late_bind_never_relocks_a_released_checkout_or_overwrites_its_next_holder() {
        InFlightBuilds reg = new InFlightBuilds();
        reg.tryAcquire(new InFlightBuilds.Hold(1, 5, "fp", "build", "/p", null, 1L, null, "cli", null));
        reg.release(1);

        reg.bindJournalId(1, "j1");
        assertThat(reg.peek("fp")).isEmpty();

        reg.tryAcquire(new InFlightBuilds.Hold(2, 6, "fp", "build", "/p", null, 2L, null, "cli", null));
        reg.bindJournalId(1, "j1");
        assertThat(reg.peek("fp").orElseThrow().requestId()).isEqualTo(2);
        assertThat(reg.peek("fp").orElseThrow().journalId()).isNull();
    }

    @Test
    void concurrent_acquire_bind_and_release_never_wedge() throws Exception {
        InFlightBuilds reg = new InFlightBuilds();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int base = t * 100_000;
                runs.add(pool.submit(() -> {
                    for (int i = 0; i < 5_000; i++) {
                        long id = base + i;
                        reg.tryAcquire(new InFlightBuilds.Hold(
                                id, 1, "fp-" + (id % 37), "build", "/p", null, 1L, null, "cli", null));
                        reg.bindJournalId(id, "j" + id);
                        reg.release(id);
                    }
                }));
            }
            for (Future<?> run : runs) run.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reg.list()).isEmpty();
    }
}

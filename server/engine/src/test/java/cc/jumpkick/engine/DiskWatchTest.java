// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The guard's response to a critical volume: reclaim at once, at most once a minute, and say so. */
class DiskWatchTest {

    private final AtomicLong now = new AtomicLong(10_000_000L);
    private final List<String> log = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private final AtomicBoolean idle = new AtomicBoolean(true);

    private DiskWatch watch(AtomicBoolean critical) {
        DiskWatch.Responder responder = new DiskWatch.Responder() {
            @Override
            public void collectStore() {
                calls.add("store");
            }

            @Override
            public boolean pruneNow(Path cache) {
                calls.add("prune");
                return idle.get();
            }
        };
        return new DiskWatch(
                List.of(Path.of("/cache"), Path.of("/store")),
                DiskWatch.constant(p -> critical.get() && p.endsWith("store")),
                responder,
                log::add,
                now::get);
    }

    @Test
    void a_critical_volume_reclaims_at_once_and_again_only_after_the_cooldown() {
        AtomicBoolean critical = new AtomicBoolean(false);
        DiskWatch w = watch(critical);

        assertThat(w.check()).isFalse();
        assertThat(calls).isEmpty();

        critical.set(true);
        assertThat(w.check()).isTrue();
        assertThat(calls).containsExactly("store", "prune");
        assertThat(log).anySatisfy(l -> assertThat(l).contains("disk nearly full at " + Path.of("/store")));
        assertThat(log).anySatisfy(l -> assertThat(l).contains("emergency reclaim ran"));

        now.addAndGet(DiskWatch.COOLDOWN.toMillis() - 1);
        w.check();
        assertThat(calls).as("inside the cooldown: no second walk").hasSize(2);

        idle.set(false);
        now.addAndGet(1);
        w.check();
        assertThat(calls).hasSize(4);
        assertThat(log.getLast()).contains("queued for the build boundary");

        critical.set(false);
        assertThat(w.check()).isFalse();
        assertThat(log.getLast()).contains("disk space recovered");
    }
}

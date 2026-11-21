// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** A burst of manifest writes costs one sync, after the quiet window; nothing runs early. */
class JkSyncDebouncerTest {

    /** Records scheduled tasks; the test fires them by hand. */
    static final class FakeScheduler implements JkSyncDebouncer.Scheduler {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        final List<CompletableFuture<?>> futures = new ArrayList<>();

        @Override
        public Future<?> schedule(Runnable task, long delayMs) {
            tasks.add(task);
            delays.add(delayMs);
            CompletableFuture<?> f = new CompletableFuture<>();
            futures.add(f);
            return f;
        }

        void fire(int i) {
            if (!futures.get(i).isCancelled()) tasks.get(i).run();
        }
    }

    @Test
    void three_touches_inside_the_window_schedule_one_live_run() {
        FakeScheduler scheduler = new FakeScheduler();
        int[] runs = {0};
        JkSyncDebouncer d = new JkSyncDebouncer(2_000, scheduler, () -> runs[0]++);
        d.touch();
        d.touch();
        d.touch();
        assertThat(scheduler.tasks).hasSize(3);
        assertThat(scheduler.futures.get(0).isCancelled()).isTrue();
        assertThat(scheduler.futures.get(1).isCancelled()).isTrue();
        assertThat(scheduler.futures.get(2).isCancelled()).isFalse();
        assertThat(scheduler.delays).isEqualTo(List.of(2_000L, 2_000L, 2_000L));
        assertThat(d.isPending()).isTrue();
        assertThat(runs[0]).isEqualTo(0);
        scheduler.fire(0);
        scheduler.fire(1);
        assertThat(runs[0]).isEqualTo(0);
        scheduler.fire(2);
        assertThat(runs[0]).isEqualTo(1);
        assertThat(d.isPending()).isFalse();
    }

    @Test
    void a_touch_after_the_run_schedules_a_fresh_one() {
        FakeScheduler scheduler = new FakeScheduler();
        int[] runs = {0};
        JkSyncDebouncer d = new JkSyncDebouncer(10, scheduler, () -> runs[0]++);
        d.touch();
        scheduler.fire(0);
        d.touch();
        scheduler.fire(1);
        assertThat(runs[0]).isEqualTo(2);
    }

    /** Once closed, a waiting run is cancelled, one already handed to the scheduler does nothing, and a touch schedules nothing. */
    @Test
    void closing_cancels_the_waiting_run_and_ignores_later_touches() {
        FakeScheduler scheduler = new FakeScheduler();
        int[] runs = {0};
        JkSyncDebouncer d = new JkSyncDebouncer(2_000, scheduler, () -> runs[0]++);
        d.touch();
        d.close();
        assertThat(scheduler.futures.get(0).isCancelled()).isTrue();
        assertThat(d.isPending()).isFalse();
        scheduler.tasks.get(0).run();
        d.touch();
        assertThat(scheduler.tasks).hasSize(1);
        assertThat(runs[0]).isZero();
    }
}

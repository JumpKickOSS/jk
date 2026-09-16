// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.Test;

/** A burst of manifest writes costs one sync, after the quiet window; nothing runs early. */
public class JkSyncDebouncerTest {

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
    public void three_touches_inside_the_window_schedule_one_live_run() {
        FakeScheduler scheduler = new FakeScheduler();
        int[] runs = {0};
        JkSyncDebouncer d = new JkSyncDebouncer(2_000, scheduler, () -> runs[0]++);
        d.touch();
        d.touch();
        d.touch();
        assertEquals(3, scheduler.tasks.size());
        assertTrue(scheduler.futures.get(0).isCancelled());
        assertTrue(scheduler.futures.get(1).isCancelled());
        assertFalse(scheduler.futures.get(2).isCancelled());
        assertEquals(List.of(2_000L, 2_000L, 2_000L), scheduler.delays);
        assertTrue(d.isPending());
        assertEquals(0, runs[0]);
        scheduler.fire(0);
        scheduler.fire(1);
        assertEquals(0, runs[0]);
        scheduler.fire(2);
        assertEquals(1, runs[0]);
        assertFalse(d.isPending());
    }

    @Test
    public void a_touch_after_the_run_schedules_a_fresh_one() {
        FakeScheduler scheduler = new FakeScheduler();
        int[] runs = {0};
        JkSyncDebouncer d = new JkSyncDebouncer(10, scheduler, () -> runs[0]++);
        d.touch();
        scheduler.fire(0);
        d.touch();
        scheduler.fire(1);
        assertEquals(2, runs[0]);
    }
}

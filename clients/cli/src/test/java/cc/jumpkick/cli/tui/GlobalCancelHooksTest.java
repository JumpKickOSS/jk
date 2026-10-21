// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.watch.ProcessTrees;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The SIGINT handler's hooks: every registered one runs, a thrower does not stop the next, a hang is bounded, a closed handle is gone. */
class GlobalCancelHooksTest {

    @Test
    void every_hook_runs_once_and_a_closed_registration_does_not() {
        List<String> ran = new CopyOnWriteArrayList<>();
        try (var a = GlobalCancel.onInterrupt(() -> ran.add("a"));
                var b = GlobalCancel.onInterrupt(() -> {
                    ran.add("b");
                    throw new IllegalStateException("boom");
                });
                var c = GlobalCancel.onInterrupt(() -> ran.add("c"))) {
            b.close();
            GlobalCancel.runInterruptHooks(2_000);
        }
        assertThat(ran).containsExactly("a", "c");
    }

    @Test
    void a_thrower_does_not_stop_the_next_hook() {
        List<String> ran = new CopyOnWriteArrayList<>();
        try (var a = GlobalCancel.onInterrupt(() -> {
                    throw new IllegalStateException("boom");
                });
                var b = GlobalCancel.onInterrupt(() -> ran.add("b"))) {
            GlobalCancel.runInterruptHooks(2_000);
        }
        assertThat(ran).containsExactly("b");
    }

    @Test
    void a_hook_that_throws_an_error_does_not_stop_the_next_either() {
        List<String> ran = new CopyOnWriteArrayList<>();
        try (var a = GlobalCancel.onInterrupt(() -> {
                    throw new AssertionError("boom");
                });
                var b = GlobalCancel.onInterrupt(() -> ran.add("b"))) {
            GlobalCancel.runInterruptHooks(2_000);
        }
        assertThat(ran).containsExactly("b");
    }

    @Test
    void a_hook_that_hangs_is_abandoned_at_the_bound() {
        CountDownLatch release = new CountDownLatch(1);
        try (var hang = GlobalCancel.onInterrupt(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })) {
            long start = System.nanoTime();
            GlobalCancel.runInterruptHooks(200);
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void the_run_hook_stops_a_child_that_would_otherwise_outlive_halt() throws Exception {
        Process child = new ProcessBuilder("sh", "-c", "sleep 30").start();
        Thread waiter = Thread.ofPlatform().name("await-exit").start(() -> {
            try {
                ProcessTrees.awaitExit(child);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (child.isAlive() && System.nanoTime() < deadline) {
                GlobalCancel.runInterruptHooks(GlobalCancel.HOOKS_BOUND_MILLIS);
                if (!child.isAlive()) break;
                Thread.sleep(20);
            }
            waiter.join(2_000);
            assertThat(child.isAlive()).isFalse();
            assertThat(waiter.isAlive()).isFalse();
        } finally {
            child.destroyForcibly();
            waiter.join(2_000);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.Symlinks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Without {@code --parallel-tests} one suite of a checkout runs at a time; two checkouts do not wait on each other. */
class TestGatesTest {

    @Test
    void two_checkouts_do_not_block_each_other(@TempDir Path dir) throws Exception {
        Path main = Files.createDirectories(dir.resolve("main"));
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        try (TestGates.Hold held = TestGates.acquire(main, () -> false)) {
            assertThat(takenWithin(worktree, 2_000))
                    .as("the other checkout's suite starts")
                    .isTrue();
        }
    }

    @Test
    void one_checkout_still_runs_one_suite_at_a_time_under_any_spelling(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Path alias = Symlinks.create(dir.resolve("alias"), root);
        CountDownLatch taken = new CountDownLatch(1);
        Thread second;
        try (TestGates.Hold held = TestGates.acquire(root, () -> false)) {
            second = Thread.ofPlatform().start(() -> {
                try (TestGates.Hold mine = TestGates.acquire(alias.resolve("."), () -> false)) {
                    taken.countDown();
                }
            });
            assertThat(taken.await(500, TimeUnit.MILLISECONDS))
                    .as("the checkout's second suite waits")
                    .isFalse();
        }
        assertThat(taken.await(5, TimeUnit.SECONDS))
                .as("and starts once the first is done")
                .isTrue();
        second.join(5_000);
    }

    @Test
    void a_gate_nobody_holds_or_waits_for_is_forgotten(@TempDir Path dir) {
        int before = TestGates.live();
        try (TestGates.Hold held = TestGates.acquire(dir, () -> false)) {
            assertThat(TestGates.live()).isEqualTo(before + 1);
        }
        assertThat(TestGates.live()).isEqualTo(before);
    }

    @Test
    void a_suite_parked_at_a_held_gate_gives_up_when_cancelled_and_holds_nothing() {
        Semaphore gate = new Semaphore(1);
        gate.acquireUninterruptibly(); // another workspace's suite holds it
        AtomicInteger asked = new AtomicInteger();
        assertThatThrownBy(() -> TestGates.await(gate, () -> asked.incrementAndGet() >= 2))
                .isInstanceOf(CancellationException.class);
        assertThat(gate.availablePermits()).isZero();
        gate.release();
        TestGates.await(gate, () -> true);
        assertThat(gate.availablePermits())
                .as("a free gate is taken even when cancelled is asked")
                .isZero();
    }

    private static boolean takenWithin(Path root, long millis) throws InterruptedException {
        CountDownLatch taken = new CountDownLatch(1);
        Thread t = Thread.ofPlatform().start(() -> {
            try (TestGates.Hold mine = TestGates.acquire(root, () -> false)) {
                taken.countDown();
            }
        });
        boolean ok = taken.await(millis, TimeUnit.MILLISECONDS);
        t.join(millis);
        return ok;
    }
}

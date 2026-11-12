// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A key's lock excludes, re-enters, and leaves no entry behind once nobody holds it. */
class KeyedLocksTest {

    @Test
    void a_key_is_held_by_one_thread_at_a_time_and_forgotten_when_released() throws Exception {
        KeyedLocks<String> locks = new KeyedLocks<>();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    try (KeyedLocks.Held held = locks.hold("k")) {
                        most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                        Thread.yield();
                        inside.decrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                done.countDown();
            });
            t.start();
        }
        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(most.get()).isEqualTo(1);
        assertThat(locks.size()).as("no entry outlives its last holder").isZero();
    }

    @Test
    void the_holder_re_enters_and_another_key_is_its_own() {
        KeyedLocks<String> locks = new KeyedLocks<>();
        try (KeyedLocks.Held a = locks.hold("a")) {
            assertThat(locks.heldByCurrentThread("a")).isTrue();
            assertThat(locks.heldByCurrentThread("b")).isFalse();
            try (KeyedLocks.Held again = locks.hold("a");
                    KeyedLocks.Held b = locks.hold("b")) {
                assertThat(locks.size()).isEqualTo(2);
            }
            assertThat(locks.heldByCurrentThread("a"))
                    .as("still held by the outer hold")
                    .isTrue();
        }
        assertThat(locks.size()).isZero();
    }

    @Test
    void an_interrupted_wait_gives_up_and_leaves_no_entry() throws Exception {
        KeyedLocks<String> locks = new KeyedLocks<>();
        AtomicInteger interrupted = new AtomicInteger();
        Thread waiter;
        try (KeyedLocks.Held held = locks.hold("k")) {
            waiter = new Thread(() -> {
                try (KeyedLocks.Held never = locks.holdInterruptibly("k")) {
                    // not reached while the test holds the key
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();
                }
            });
            waiter.start();
            waiter.interrupt();
            waiter.join(30_000);
        }

        assertThat(interrupted.get()).isEqualTo(1);
        assertThat(locks.size()).isZero();
    }
}

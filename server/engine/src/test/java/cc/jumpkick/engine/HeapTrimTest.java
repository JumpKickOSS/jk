// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The settled trim runs once, after the idle period, and only while the engine is still idle. */
class HeapTrimTest {

    @Test
    void the_native_heap_trim_answers_on_this_jvm() {
        String answer = HeapTrim.trimNative();
        assertThat(answer).isNotBlank();
        assertThat(HeapTrim.trimNative()).as("repeatable").isNotBlank();
    }

    @Test
    void a_settled_trim_fires_once_after_the_idle_period_when_still_idle() throws Exception {
        AtomicInteger trims = new AtomicInteger();
        CountDownLatch fired = new CountDownLatch(1);
        HeapTrim.later(
                () -> true,
                () -> {
                    trims.incrementAndGet();
                    fired.countDown();
                },
                Duration.ofMillis(50));
        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(150);
        assertThat(trims).hasValue(1);
    }

    @Test
    void a_trim_armed_while_idle_is_skipped_once_a_job_is_running_again() throws Exception {
        AtomicInteger trims = new AtomicInteger();
        AtomicBoolean idle = new AtomicBoolean(true);
        HeapTrim.later(idle::get, trims::incrementAndGet, Duration.ofMillis(50));
        idle.set(false);
        Thread.sleep(300);
        assertThat(trims)
                .as("a job started inside the settle window; nothing to give back yet")
                .hasValue(0);
    }

    @Test
    void re_arming_inside_the_settle_window_pays_one_trim_after_the_last_job() throws Exception {
        AtomicInteger trims = new AtomicInteger();
        CountDownLatch fired = new CountDownLatch(1);
        Runnable trim = () -> {
            trims.incrementAndGet();
            fired.countDown();
        };
        for (int i = 0; i < 5; i++) HeapTrim.later(() -> true, trim, Duration.ofMillis(100));
        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);
        assertThat(trims).as("five boundaries in a burst, one settled trim").hasValue(1);
    }
}

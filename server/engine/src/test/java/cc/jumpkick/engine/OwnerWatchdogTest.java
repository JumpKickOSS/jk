// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A sandbox engine dies with the process that owns it; a resident engine has no owner. */
class OwnerWatchdogTest {

    @Test
    void the_engine_stops_once_the_owner_is_gone_and_only_then() throws Exception {
        AtomicBoolean alive = new AtomicBoolean(true);
        AtomicInteger stops = new AtomicInteger();
        CountDownLatch stopped = new CountDownLatch(1);
        Thread t = OwnerWatchdog.watch(alive::get, Duration.ofMillis(10), () -> {
            stops.incrementAndGet();
            stopped.countDown();
        });

        // Real time: the negative. Sixty quiet milliseconds must not have stopped anything.
        Thread.sleep(60);
        assertThat(stops).hasValue(0);

        alive.set(false);
        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        t.join(5_000);
        assertThat(stops).hasValue(1);
    }

    @Test
    void no_property_means_no_watch() {
        List<String> log = new ArrayList<>();
        assertThat(OwnerWatchdog.start(null, () -> {}, log::add)).isNull();
        assertThat(OwnerWatchdog.start("  ", () -> {}, log::add)).isNull();
        assertThat(log).isEmpty();
    }

    @Test
    void a_value_that_is_not_a_pid_is_logged_and_ignored() {
        List<String> log = new ArrayList<>();
        AtomicInteger stops = new AtomicInteger();
        assertThat(OwnerWatchdog.start("not-a-pid", stops::incrementAndGet, log::add))
                .isNull();
        assertThat(stops).hasValue(0);
        assertThat(log).singleElement().asString().contains("not a pid");
    }

    @Test
    void an_owner_that_is_already_gone_stops_the_engine_at_once() {
        // A pid nothing on this box can hold: the kernel's pid space ends well below it.
        List<String> log = new ArrayList<>();
        AtomicInteger stops = new AtomicInteger();
        assertThat(OwnerWatchdog.start(Long.toString(Long.MAX_VALUE), stops::incrementAndGet, log::add))
                .isNull();
        assertThat(stops).hasValue(1);
        assertThat(log).singleElement().asString().contains("already gone");
    }

    @Test
    void a_live_owner_is_watched_on_a_daemon_thread() throws Exception {
        List<String> log = new ArrayList<>();
        Thread t = OwnerWatchdog.start(Long.toString(ProcessHandle.current().pid()), () -> {}, log::add);
        assertThat(t).isNotNull();
        assertThat(t.isDaemon()).isTrue();
        assertThat(log).singleElement().asString().contains("stops when owner pid");
        t.interrupt();
        t.join(5_000);
        assertThat(t.isAlive()).isFalse();
    }
}

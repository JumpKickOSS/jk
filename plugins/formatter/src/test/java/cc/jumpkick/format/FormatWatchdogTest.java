// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.FakeClock;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The per-file wall bound: what it says while a file is merely slow, and what it decides once the
 * file is past saving. Time is a fake clock and {@code tick} is driven by hand, so the thresholds
 * are asserted rather than waited on.
 */
class FormatWatchdogTest {

    private static final File FILE = new File("/w/LockfilePropertyTest.java");

    private final FakeClock clock = new FakeClock();
    private final List<String> notices = new ArrayList<>();
    private final AtomicInteger replacements = new AtomicInteger();

    @Test
    void a_file_inside_the_warn_threshold_says_nothing() {
        FormatWatchdog dog = watchdog(500, 2_000);
        try (var window = dog.watch(0, FILE)) {
            advanceMs(400);
            dog.tick();

            assertThat(notices).isEmpty();
            assertThat(dog.verdict(0)).isNull();
        }
    }

    @Test
    void a_file_past_the_warn_threshold_is_named_with_its_elapsed_time() {
        FormatWatchdog dog = watchdog(500, 2_000);
        try (var window = dog.watch(0, FILE)) {
            advanceMs(600);
            dog.tick();

            assertThat(notices).containsExactly(FILE + " @ 600 ms");
            // Naming is not killing: the file is still the run's to finish.
            assertThat(dog.verdict(0)).isNull();
        }
    }

    @Test
    void a_long_peg_is_named_on_a_backoff_rather_than_every_tick() {
        FormatWatchdog dog = watchdog(500, 0);
        try (var window = dog.watch(0, FILE)) {
            for (int i = 0; i < 200; i++) {
                advanceMs(100);
                dog.tick();
            }

            // 20 seconds at ten ticks a second is 200 chances to speak; doubling off the elapsed
            // time takes that to a handful.
            assertThat(notices).hasSizeLessThan(10);
            assertThat(notices.getFirst()).isEqualTo(FILE + " @ 500 ms");
            assertThat(notices.getLast()).isEqualTo(FILE + " @ 16000 ms");
        }
    }

    @Test
    void a_file_past_the_hard_threshold_is_abandoned_and_named_in_its_verdict() {
        FormatWatchdog dog = watchdog(500, 2_000);
        try (var window = dog.watch(3, FILE)) {
            advanceMs(2_100);
            dog.tick();

            assertThat(dog.verdict(3)).isEqualTo("timed out after 2.1s (limit 2000 ms)");
            // The run was told to replace the slot it lost.
            assertThat(replacements).hasValue(1);
        }
    }

    @Test
    void an_abandoned_file_is_not_abandoned_twice() {
        FormatWatchdog dog = watchdog(500, 2_000);
        try (var window = dog.watch(0, FILE)) {
            advanceMs(2_100);
            dog.tick();
            advanceMs(60_000);
            dog.tick();

            assertThat(replacements).hasValue(1);
            assertThat(dog.verdict(0)).isEqualTo("timed out after 2.1s (limit 2000 ms)");
        }
    }

    @Test
    void a_file_that_closed_its_window_can_no_longer_be_abandoned() {
        FormatWatchdog dog = watchdog(500, 2_000);
        var window = dog.watch(0, FILE);
        advanceMs(2_100);
        window.close();

        dog.tick();

        assertThat(dog.verdict(0)).isNull();
        assertThat(replacements).hasValue(0);
    }

    @Test
    void both_thresholds_off_leaves_the_file_alone_forever() {
        FormatWatchdog dog = watchdog(0, 0);
        try (var window = dog.watch(0, FILE)) {
            advanceMs(600_000);
            dog.tick();

            assertThat(notices).isEmpty();
            assertThat(dog.verdict(0)).isNull();
        }
    }

    @Test
    void a_verdict_is_only_ever_about_the_file_that_earned_it() {
        FormatWatchdog dog = watchdog(500, 2_000);
        var slow = dog.watch(7, FILE);
        advanceMs(1_000);
        try (var quick = dog.watch(8, new File("/w/Fine.java"))) {
            advanceMs(1_200);
            dog.tick();
        }

        assertThat(dog.verdict(7)).startsWith("timed out after 2.2s");
        assertThat(dog.verdict(8)).isNull();
        slow.close();
    }

    @Test
    void durations_read_as_milliseconds_below_a_second_and_seconds_above_it() {
        assertThat(FormatWatchdog.human(0)).isEqualTo("0 ms");
        assertThat(FormatWatchdog.human(999)).isEqualTo("999 ms");
        assertThat(FormatWatchdog.human(1_000)).isEqualTo("1.0s");
        assertThat(FormatWatchdog.human(416_000)).isEqualTo("416.0s");
    }

    /** A limit the host's load stretched names the stretch in the verdict, so a slow host is told apart from a slow file. */
    @Test
    void a_stretched_limit_says_why_in_the_verdict() {
        FormatWatchdog dog = new FormatWatchdog(
                500,
                FormatTimeout.forHost(48, 24),
                clock,
                (file, elapsedMs) -> notices.add(file + " @ " + elapsedMs + " ms"),
                replacements::incrementAndGet);
        try (var window = dog.watch(0, FILE)) {
            advanceMs(6_000);
            dog.tick();

            assertThat(dog.verdict(0))
                    .isEqualTo("timed out after 6.0s (limit 6000 ms, the 3000 ms default stretched 2× for a load"
                            + " average of 48.0 over 24 processors)");
        }
    }

    private FormatWatchdog watchdog(long warnMs, long timeoutMs) {
        return new FormatWatchdog(
                warnMs,
                FormatTimeout.explicit(timeoutMs),
                clock,
                (file, elapsedMs) -> notices.add(file + " @ " + elapsedMs + " ms"),
                replacements::incrementAndGet);
    }

    private void advanceMs(long ms) {
        clock.advance(Duration.ofMillis(ms));
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host.time;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.FakeClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The heartbeat's reading of a pair of clocks: what is a gap, and what is a clock step. */
class SuspensionsTest {

    private final FakeClock clock = new FakeClock();

    /** A boot clock that counts suspended time: it moves with the monotonic clock and through a suspend. */
    private final AtomicLong boot = new AtomicLong(1_000_000);

    private final Suspensions watch = Suspensions.forTests(clock, boot::get);

    private void tick() {
        clock.advance(Duration.ofSeconds(1));
        boot.addAndGet(1_000);
    }

    /** The machine sleeps {@code d}: on Linux the wall and boot clocks move, the monotonic one does not. */
    private void sleepLinux(Duration d) {
        clock.set(clock.instant().plus(d));
        boot.addAndGet(d.toMillis());
    }

    @Test
    void a_suspend_on_linux_moves_the_wall_and_boot_clocks_and_is_a_gap_the_monotonic_clock_never_saw() {
        List<Set<Long>> told = new ArrayList<>();
        watch.listen((gap, jobs) -> told.add(jobs));
        watch.jobStarted(7);
        tick();
        assertThat(watch.poll()).isNull();

        sleepLinux(Duration.ofSeconds(274));
        tick();
        Suspensions.Gap gap = Objects.requireNonNull(watch.poll());

        assertThat(gap.wallMs()).isEqualTo(274_000);
        assertThat(gap.monoMs()).isZero();
        assertThat(gap.endedAt()).isEqualTo(clock.millis());
        assertThat(told).containsExactly(Set.of(7L));
        assertThat(watch.suspendedMillis(7)).isEqualTo(274_000);
        assertThat(watch.poll()).as("told once").isNull();
    }

    @Test
    void a_stopped_process_or_a_suspend_on_windows_moves_both_clocks_and_is_a_gap_on_both() {
        watch.jobStarted(1);
        clock.advance(Duration.ofSeconds(21));
        boot.addAndGet(21_000);

        Suspensions.Gap gap = Objects.requireNonNull(watch.poll());

        assertThat(gap.wallMs()).isEqualTo(20_000);
        assertThat(gap.monoMs()).isEqualTo(20_000);
    }

    @Test
    void a_wall_clock_step_the_boot_clock_does_not_see_is_not_a_suspend() {
        watch.jobStarted(1);
        tick();
        clock.set(clock.instant().plus(Duration.ofMinutes(5)));
        tick();

        assertThat(watch.poll()).isNull();
        assertThat(watch.suspendedMillis(1)).isZero();
    }

    @Test
    void with_no_boot_clock_a_short_wall_jump_is_taken_for_a_step_and_a_long_one_for_a_suspend() {
        Suspensions bare = Suspensions.forTests(clock, () -> -1);
        bare.jobStarted(1);
        clock.set(clock.instant().plus(Duration.ofSeconds(12)));
        clock.advance(Duration.ofSeconds(1));
        assertThat(bare.poll()).as("could be NTP").isNull();

        clock.set(clock.instant().plus(Duration.ofMinutes(3)));
        clock.advance(Duration.ofSeconds(1));
        Suspensions.Gap gap = Objects.requireNonNull(bare.poll());
        assertThat(gap.wallMs()).isEqualTo(180_000);
    }

    @Test
    void a_few_slow_seconds_are_not_a_gap() {
        watch.jobStarted(1);
        clock.advance(Duration.ofSeconds(6));
        boot.addAndGet(6_000);

        assertThat(watch.poll()).as("a long GC pause or a loaded host").isNull();
    }

    @Test
    void an_idle_engine_watches_nothing_and_its_idle_time_is_not_a_gap_when_a_job_starts() {
        clock.advance(Duration.ofHours(3));
        assertThat(watch.poll()).isNull();

        watch.jobStarted(2);
        watch.jobEnded(2);
        sleepLinux(Duration.ofHours(1));
        tick();
        assertThat(watch.poll()).as("no job runs").isNull();

        watch.jobStarted(3);
        tick();
        assertThat(watch.poll()).isNull();
        assertThat(watch.beating()).as("a test's watch has no heartbeat thread").isFalse();
    }

    @Test
    void a_cursor_reads_the_gaps_after_it_and_when_the_last_one_ended() {
        watch.jobStarted(1);
        long before = watch.cursor();
        assertThat(watch.resumedSince(before)).isFalse();
        assertThat(watch.resumedAt(before)).isEqualTo(Long.MIN_VALUE);

        sleepLinux(Duration.ofSeconds(30));
        tick();
        long after = watch.cursor();

        assertThat(watch.resumedSince(before)).isTrue();
        assertThat(watch.resumedAt(before)).isEqualTo(clock.millis());
        assertThat(watch.since(before)).hasSize(1);
        assertThat(watch.resumedSince(after)).isFalse();
    }
}

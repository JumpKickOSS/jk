// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.FakeClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * When the update check runs: after the first build or two minutes in, every four hours after,
 * each pushed back by its jitter; what it finds applied once the engine is quiet or has waited
 * half an hour; nothing while a build is in flight, while draining, or when turned off.
 */
class UpdateSchedulerTest {

    private final FakeClock clock = new FakeClock();
    private final AtomicInteger builds = new AtomicInteger();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final List<String> ran = new ArrayList<>();
    private boolean finds = true;
    private long jitter = 0;

    private UpdateScheduler scheduler() {
        return new UpdateScheduler(
                builds::get,
                stopping::get,
                enabled::get,
                new UpdateScheduler.Work() {
                    @Override
                    public boolean check() {
                        ran.add("check");
                        return finds;
                    }

                    @Override
                    public void apply() {
                        ran.add("apply");
                    }
                },
                clock,
                () -> jitter);
    }

    /** Let {@code d} pass with a tick at the end. */
    private void after(UpdateScheduler s, Duration d) {
        clock.advance(d);
        s.tick();
    }

    @Test
    void with_no_build_the_first_check_comes_two_minutes_after_start_then_every_four_hours() {
        UpdateScheduler s = scheduler();
        finds = false;

        after(s, Duration.ofSeconds(119));
        assertThat(ran).isEmpty();
        after(s, Duration.ofSeconds(1));
        assertThat(ran).containsExactly("check");

        after(s, Duration.ofHours(4).minusSeconds(1));
        assertThat(ran).hasSize(1);
        after(s, Duration.ofSeconds(1));
        assertThat(ran).containsExactly("check", "check");
    }

    @Test
    void a_first_build_brings_the_first_check_to_when_it_finishes_and_never_while_one_runs() {
        UpdateScheduler s = scheduler();
        finds = false;
        builds.set(1);
        after(s, Duration.ofSeconds(30));
        after(s, Duration.ofMinutes(5));
        assertThat(ran).as("a check is due, but a build is in flight").isEmpty();

        builds.set(0);
        s.buildFinished();
        s.tick();
        assertThat(ran).containsExactly("check");
    }

    @Test
    void the_first_build_finishing_early_checks_right_after_it() {
        UpdateScheduler s = scheduler();
        finds = false;
        clock.advance(Duration.ofSeconds(20));
        s.buildFinished();
        s.tick();
        assertThat(ran).containsExactly("check");

        s.buildFinished();
        s.tick();
        assertThat(ran).as("later builds wait for the four-hour mark").containsExactly("check");
    }

    @Test
    void every_check_is_pushed_back_by_its_jitter() {
        jitter = Duration.ofMinutes(3).toMillis();
        UpdateScheduler s = scheduler();
        finds = false;

        after(s, Duration.ofMinutes(4));
        assertThat(ran).isEmpty();
        after(s, Duration.ofMinutes(1));
        assertThat(ran).containsExactly("check");

        after(s, Duration.ofHours(4));
        assertThat(ran).hasSize(1);
        after(s, Duration.ofMinutes(3));
        assertThat(ran).hasSize(2);
    }

    @Test
    void what_a_check_finds_is_applied_once_the_engine_is_quiet() {
        UpdateScheduler s = scheduler();
        clock.advance(Duration.ofSeconds(10));
        s.buildFinished();
        s.tick();
        assertThat(ran).as("the build just finished: not quiet yet").containsExactly("check");

        after(s, Duration.ofMinutes(1));
        assertThat(ran).containsExactly("check");
        after(s, Duration.ofMinutes(1));
        assertThat(ran).containsExactly("check", "apply");

        after(s, Duration.ofMinutes(5));
        assertThat(ran).as("one apply per check").containsExactly("check", "apply");
    }

    @Test
    void with_builds_back_to_back_the_apply_runs_between_two_after_half_an_hour() {
        UpdateScheduler s = scheduler();
        clock.advance(Duration.ofSeconds(10));
        s.buildFinished();
        s.tick();
        assertThat(ran).containsExactly("check");

        for (int minute = 1; minute < 30; minute++) {
            clock.advance(Duration.ofMinutes(1));
            s.buildFinished();
            s.tick();
        }
        assertThat(ran).containsExactly("check");

        builds.set(1);
        after(s, Duration.ofMinutes(1));
        assertThat(ran).as("never mid-build").containsExactly("check");
        builds.set(0);
        s.buildFinished();
        s.tick();
        assertThat(ran).containsExactly("check", "apply");
    }

    @Test
    void nothing_runs_when_turned_off_or_draining() {
        UpdateScheduler s = scheduler();
        enabled.set(false);
        after(s, Duration.ofHours(5));
        assertThat(ran).isEmpty();

        enabled.set(true);
        stopping.set(true);
        after(s, Duration.ofHours(5));
        assertThat(ran).isEmpty();

        stopping.set(false);
        s.tick();
        assertThat(ran).containsExactly("check", "apply");
    }
}

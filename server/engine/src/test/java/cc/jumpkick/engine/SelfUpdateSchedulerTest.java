// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.config.SelfUpdatePolicy;
import cc.jumpkick.config.SelfUpdatePolicy.Action;
import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.testing.FakeClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The engine's update schedule: when a check is due, what wakes a run, and how a build that
 * arrives mid-run pauses it. Steps and the home are fakes; {@link SelfUpdateScheduler#runSteps} is
 * called directly so nothing races a background thread.
 */
class SelfUpdateSchedulerTest {

    private static final String RUNNING = "0.16.2";

    private final FakeClock clock = new FakeClock();
    private final AtomicInteger active = new AtomicInteger();
    private final FakeHome home = new FakeHome();
    private final FakeSteps steps = new FakeSteps();
    private final List<String> log = new ArrayList<>();

    private SelfUpdateScheduler scheduler() {
        return new SelfUpdateScheduler(RUNNING, active::get, () -> false, steps, home, clock, log::add);
    }

    @Test
    void a_quiet_engine_checks_downloads_and_installs_back_to_back() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.QUIET);

        s.runSteps(false);

        assertThat(steps.ran).containsExactly("check", "download 0.17.0", "install 0.17.0");
        assertThat(log).anyMatch(l -> l.contains("installing jk 0.17.0"));
    }

    @Test
    void nothing_runs_before_ten_quiet_minutes_without_the_build_trigger() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.QUIET.minusSeconds(1));
        s.runSteps(false);
        assertThat(steps.ran).isEmpty();
    }

    @Test
    void the_check_runs_at_most_once_a_day_and_a_failed_one_retries_hourly() {
        var s = scheduler();
        Instant now = clock.instant();
        home.state = SelfUpdateState.EMPTY.withCheck(now.minus(Duration.ofHours(23)), RUNNING);
        assertThat(s.next(now)).isInstanceOf(SelfUpdateScheduler.Next.Nothing.class);

        home.state = SelfUpdateState.EMPTY.withCheck(now.minus(Duration.ofHours(24)), RUNNING);
        assertThat(s.next(now)).isInstanceOf(SelfUpdateScheduler.Next.Check.class);

        home.state = home.state.withAttempt(now.minus(Duration.ofMinutes(59)));
        assertThat(s.next(now)).isInstanceOf(SelfUpdateScheduler.Next.Nothing.class);
        home.state = home.state.withAttempt(now.minus(Duration.ofMinutes(60)));
        assertThat(s.next(now)).isInstanceOf(SelfUpdateScheduler.Next.Check.class);
    }

    @Test
    void the_third_build_in_a_row_runs_a_step_even_while_others_build() {
        var s = scheduler();
        active.set(1);
        steps.newest = RUNNING;

        s.runSteps(true);

        assertThat(steps.ran).containsExactly("check");
    }

    @Test
    void builds_count_toward_the_trigger_only_while_they_come_back_to_back() {
        var s = scheduler();
        // No step runs on the first two; a quiet gap starts the count again.
        s.buildFinished();
        s.buildFinished();
        clock.advance(SelfUpdateScheduler.QUIET);
        s.buildFinished();
        s.buildFinished();
        assertThat(steps.ran).isEmpty();
    }

    @Test
    void a_build_that_starts_during_a_step_holds_the_next_step_for_quiet() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.QUIET);
        steps.duringCheck = () -> active.set(1);

        s.runSteps(false);
        assertThat(steps.ran).containsExactly("check");

        // Still building: the quiet trigger stays shut.
        s.runSteps(false);
        assertThat(steps.ran).containsExactly("check");

        active.set(0);
        clock.advance(SelfUpdateScheduler.QUIET);
        s.runSteps(false);
        assertThat(steps.ran).containsExactly("check", "download 0.17.0", "install 0.17.0");
    }

    @Test
    void ask_stops_after_the_download_and_off_runs_nothing() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.QUIET);
        home.action = Action.ASK;
        s.runSteps(false);
        assertThat(steps.ran).containsExactly("check", "download 0.17.0");

        steps.ran.clear();
        home.action = Action.OFF;
        s.runSteps(false);
        assertThat(steps.ran).isEmpty();
    }

    @Test
    void a_download_that_failed_waits_an_hour() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.QUIET);
        steps.downloadWorks = false;
        s.runSteps(false);
        assertThat(steps.ran).containsExactly("check", "download 0.17.0");

        steps.ran.clear();
        clock.advance(Duration.ofMinutes(30));
        s.runSteps(false);
        assertThat(steps.ran).isEmpty();
        clock.advance(Duration.ofMinutes(30));
        s.runSteps(false);
        assertThat(steps.ran).containsExactly("download 0.17.0");
    }

    @Test
    void a_newer_install_or_a_fresh_ci_home_leaves_the_update_to_others() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.QUIET);
        home.installed = "0.17.0";
        s.runSteps(false);
        home.installed = RUNNING;
        home.freshCi = true;
        s.runSteps(false);
        assertThat(steps.ran).isEmpty();
    }

    @Test
    void a_waiting_download_installs_without_checking_again() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.QUIET);
        home.state = SelfUpdateState.EMPTY.withCheck(clock.instant(), "0.17.0");
        home.prepared = new PreparedUpdate(
                "0.17.0", RUNNING, "a".repeat(64), PreparedUpdate.Client.NATIVE, "b".repeat(64), clock.instant());

        s.runSteps(false);

        assertThat(steps.ran).containsExactly("install 0.17.0");
    }

    @Test
    void the_default_asks_only_where_a_person_was_seen_lately_and_never_on_ci() {
        Instant now = Instant.parse("2026-10-30T00:00:00Z");
        var seen = SelfUpdateState.EMPTY.withInteractiveSeen(now.minus(Duration.ofDays(3)));
        assertThat(SelfUpdatePolicy.attended(seen, now, false)).isTrue();
        assertThat(SelfUpdatePolicy.attended(seen, now, true)).isFalse();
        assertThat(SelfUpdatePolicy.attended(seen, now.plus(Duration.ofDays(30)), false))
                .isFalse();
        assertThat(SelfUpdatePolicy.attended(SelfUpdateState.EMPTY, now, false)).isFalse();
    }

    @Test
    void only_the_installed_engine_jar_updates_itself() {
        assertThat(SelfUpdateScheduler.runsInstalledJar("")).isFalse();
        assertThat(SelfUpdateScheduler.runsInstalledJar("a.jar" + Classpaths.SEPARATOR + "b.jar"))
                .isFalse();
    }

    /** Steps that record what ran and write what a real step would into the fake home. */
    private final class FakeSteps implements SelfUpdateScheduler.Steps {
        final List<String> ran = new ArrayList<>();
        String newest = "0.17.0";
        boolean downloadWorks = true;
        Runnable duringCheck = () -> {};

        @Override
        public boolean check() {
            ran.add("check");
            duringCheck.run();
            home.state = home.state.withCheck(clock.instant(), newest);
            return true;
        }

        @Override
        public boolean download(String version) {
            ran.add("download " + version);
            if (!downloadWorks) return false;
            home.prepared = new PreparedUpdate(
                    version, RUNNING, "a".repeat(64), PreparedUpdate.Client.NATIVE, "b".repeat(64), clock.instant());
            return true;
        }

        @Override
        public boolean install(String version) {
            ran.add("install " + version);
            home.installed = version;
            home.prepared = null;
            return true;
        }
    }

    private static final class FakeHome implements SelfUpdateScheduler.View {
        Action action = Action.AUTO;
        SelfUpdateState state = SelfUpdateState.EMPTY;

        @Nullable
        PreparedUpdate prepared;

        String installed = RUNNING;
        boolean freshCi;

        @Override
        public Action action() {
            return action;
        }

        @Override
        public SelfUpdateState state() {
            return state;
        }

        @Override
        public Optional<PreparedUpdate> prepared() {
            return Optional.ofNullable(prepared);
        }

        @Override
        public Optional<String> installed() {
            return Optional.of(installed);
        }

        @Override
        public boolean freshCiHome() {
            return freshCi;
        }

        @Override
        public void recordAttempt(Instant at) {
            state = state.withAttempt(at);
        }
    }
}

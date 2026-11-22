// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.config.SelfUpdatePolicy;
import cc.jumpkick.config.SelfUpdatePolicy.Action;
import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.config.UpdateOffer;
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
 * The engine's own update: the release the update manifest names is downloaded once the engine is
 * quiet or the release has waited long enough, and installed only when no build runs and no session
 * is connected; never on CI, never with the update check off. Steps and the home are fakes; {@link
 * SelfUpdateScheduler#tick} is called directly so nothing races a background thread.
 */
class SelfUpdateSchedulerTest {

    private static final String RUNNING = "0.16.2";

    private final FakeClock clock = new FakeClock();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger sessions = new AtomicInteger();
    private final FakeHome home = new FakeHome();
    private final FakeSteps steps = new FakeSteps();
    private final List<String> log = new ArrayList<>();

    private SelfUpdateScheduler scheduler() {
        return new SelfUpdateScheduler(RUNNING, active::get, sessions::get, () -> false, steps, home, clock, log::add);
    }

    private void stage(String version) {
        home.prepared = new PreparedUpdate(
                version, RUNNING, "a".repeat(64), PreparedUpdate.Client.NATIVE, "b".repeat(64), clock.instant());
    }

    @Test
    void a_downloaded_release_is_offered_until_snoozed() {
        var s = scheduler();
        assertThat(s.offer()).isEmpty();
        stage("0.17.0");
        assertThat(s.offer()).contains("0.17.0");

        assertThat(s.snooze()).isTrue();
        assertThat(s.offer()).isEmpty();
        assertThat(s.snooze()).isFalse();

        home.action = Action.OFF;
        home.state = SelfUpdateState.EMPTY;
        assertThat(s.offer()).isEmpty();
    }

    @Test
    void install_now_skips_the_idle_trigger_and_installs_the_download() {
        var s = scheduler();
        active.set(2); // builds in flight: the idle trigger would wait
        assertThat(s.installNow()).isFalse();

        stage("0.17.0");
        s.runInstallNow("0.17.0");

        assertThat(steps.ran).containsExactly("install 0.17.0");
        assertThat(s.installing()).isFalse();
        assertThat(s.offer()).isEmpty();
        assertThat(s.installNow()).isFalse();
    }

    @Test
    void an_idle_engine_downloads_the_release_the_manifest_names_and_installs_it() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.IDLE);

        s.tick();

        assertThat(steps.ran).containsExactly("download 0.17.0", "install 0.17.0");
        assertThat(log).anyMatch(l -> l.contains("installing jk 0.17.0"));
        s.tick();
        assertThat(steps.ran).as("one install per engine").hasSize(2);
    }

    @Test
    void the_download_waits_for_a_quiet_spell_and_the_install_for_an_idle_one() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.DOWNLOAD_QUIET.minusSeconds(1));
        s.tick();
        assertThat(steps.ran).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        s.tick();
        assertThat(steps.ran).as("downloaded; not idle long enough to install").containsExactly("download 0.17.0");

        clock.advance(SelfUpdateScheduler.IDLE);
        s.tick();
        assertThat(steps.ran).containsExactly("download 0.17.0", "install 0.17.0");
    }

    @Test
    void a_release_that_never_finds_a_quiet_spell_is_downloaded_after_waiting_between_builds() {
        var s = scheduler();
        Duration gap = SelfUpdateScheduler.DOWNLOAD_QUIET.minusSeconds(30);
        for (Duration waited = Duration.ZERO;
                waited.compareTo(SelfUpdateScheduler.DOWNLOAD_WAIT) < 0;
                waited = waited.plus(gap)) {
            s.buildFinished();
            clock.advance(gap);
            s.tick();
        }
        s.buildFinished();
        clock.advance(gap);
        s.tick();

        assertThat(steps.ran).containsExactly("download 0.17.0");
    }

    @Test
    void nothing_is_downloaded_or_replaced_while_a_build_runs() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.IDLE);
        active.set(1);
        s.tick();
        stage("0.17.0");
        s.tick();
        assertThat(steps.ran).isEmpty();
    }

    @Test
    void nothing_is_replaced_while_a_session_is_connected() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.IDLE);
        sessions.set(1);

        s.tick();
        s.tick();
        assertThat(steps.ran).as("a download replaces nothing").containsExactly("download 0.17.0");

        sessions.set(0);
        s.tick();
        assertThat(steps.ran).containsExactly("download 0.17.0", "install 0.17.0");
    }

    @Test
    void ci_checks_but_never_downloads_or_replaces_itself() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.IDLE);
        home.ci = true;
        s.tick();
        stage("0.17.0");
        s.tick();
        assertThat(steps.ran).isEmpty();
        assertThat(s.next(clock.instant())).isInstanceOf(SelfUpdateScheduler.Next.Nothing.class);
    }

    @Test
    void the_update_check_turned_off_downloads_and_installs_nothing() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.IDLE);
        home.enabled = false;
        s.tick();
        stage("0.17.0");
        s.tick();
        assertThat(steps.ran).isEmpty();
    }

    @Test
    void a_manifest_naming_no_newer_release_downloads_nothing() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.IDLE);
        home.offered = null;
        s.tick();
        home.offered = RUNNING;
        s.tick();
        home.offered = "0.16.1";
        s.tick();
        assertThat(steps.ran).isEmpty();
    }

    @Test
    void ask_stops_after_the_download_and_off_runs_nothing() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.IDLE);
        home.action = Action.ASK;
        s.tick();
        assertThat(steps.ran).containsExactly("download 0.17.0");

        steps.ran.clear();
        home.prepared = null;
        home.action = Action.OFF;
        s.tick();
        assertThat(steps.ran).isEmpty();
    }

    @Test
    void a_download_that_failed_waits_an_hour() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.IDLE);
        steps.downloadWorks = false;
        s.tick();
        assertThat(steps.ran).containsExactly("download 0.17.0");

        steps.ran.clear();
        clock.advance(Duration.ofMinutes(30));
        s.tick();
        assertThat(steps.ran).isEmpty();
        clock.advance(Duration.ofMinutes(30));
        s.tick();
        assertThat(steps.ran).containsExactly("download 0.17.0");
    }

    @Test
    void a_newer_install_leaves_the_update_to_its_engine() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.IDLE);
        home.installed = "0.17.0";
        s.tick();
        assertThat(steps.ran).isEmpty();
    }

    @Test
    void a_waiting_download_installs_without_downloading_again() {
        var s = scheduler();
        clock.advance(SelfUpdateScheduler.IDLE);
        stage("0.17.0");

        s.tick();

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
        boolean downloadWorks = true;

        @Override
        public boolean download(String version) {
            ran.add("download " + version);
            if (!downloadWorks) return false;
            stage(version);
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
        boolean enabled = true;
        boolean ci;

        @Nullable
        String offered = "0.17.0";

        @Nullable
        PreparedUpdate prepared;

        String installed = RUNNING;

        @Override
        public Action action() {
            return action;
        }

        @Override
        public SelfUpdateState state() {
            return state;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public boolean ci() {
            return ci;
        }

        @Override
        public Optional<String> offered() {
            return Optional.ofNullable(offered);
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
        public void snooze(String version, Instant at) {
            state = UpdateOffer.snooze(state, version, at);
        }
    }
}

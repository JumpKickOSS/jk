// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.Osc;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildNotifyTest {

    @Test
    void message_shapes_outcomes_and_duration() {
        assertThat(BuildNotify.message(BuildNotify.Outcome.COMPLETE, "cc.jumpkick:jk", 90_000))
                .isEqualTo("Build complete for cc.jumpkick:jk (took 1m 30s)");
        assertThat(BuildNotify.message(BuildNotify.Outcome.FAILED, "g:n", 5 * 60_000L + 18_000L))
                .isEqualTo("Build failed for g:n (took 5m 18s)");
        assertThat(BuildNotify.message(BuildNotify.Outcome.CANCELLED, "g:n", 18_000))
                .isEqualTo("Build cancelled for g:n (took 18s)");
    }

    @Test
    void shouldNotify_threshold_and_policy() {
        GlobalOptions auto = new GlobalOptions();
        auto.notify = JkConfig.NotifyChoice.AUTO;
        assertThat(BuildNotify.shouldNotify(auto, 0, 30_000)).isFalse();
        assertThat(BuildNotify.shouldNotify(auto, 61_000, 1_000)).isTrue(); // estimate
        assertThat(BuildNotify.shouldNotify(auto, 0, 61_000)).isTrue(); // elapsed

        GlobalOptions always = new GlobalOptions();
        always.notify = JkConfig.NotifyChoice.ALWAYS;
        assertThat(BuildNotify.shouldNotify(always, 0, 100)).isTrue();

        GlobalOptions never = new GlobalOptions();
        never.notify = JkConfig.NotifyChoice.NEVER;
        assertThat(BuildNotify.shouldNotify(never, 120_000, 120_000)).isFalse();

        GlobalOptions noProgress = new GlobalOptions();
        noProgress.notify = JkConfig.NotifyChoice.ALWAYS;
        noProgress.noProgress = true;
        assertThat(BuildNotify.shouldNotify(noProgress, 120_000, 120_000)).isFalse();

        GlobalOptions noOsc = new GlobalOptions();
        noOsc.notify = JkConfig.NotifyChoice.ALWAYS;
        noOsc.noOsc = true;
        assertThat(BuildNotify.shouldNotify(noOsc, 120_000, 120_000)).isFalse();
    }

    @Test
    void maybeNotify_emits_osc99_title_and_body() {
        GlobalOptions g = new GlobalOptions();
        g.notify = JkConfig.NotifyChoice.ALWAYS;
        var buf = new ByteArrayOutputStream();
        BuildNotify.maybeNotify(
                new PrintStream(buf, true, StandardCharsets.UTF_8),
                g,
                BuildNotify.Outcome.COMPLETE,
                "cc.jumpkick:jk",
                0,
                90_000);
        String out = buf.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("\033]99;");
        assertThat(out).contains(BuildNotify.TITLE);
        assertThat(out).contains("Build complete for cc.jumpkick:jk (took 1m 30s)");
    }

    @Test
    void a_quarantine_notifies_under_every_policy_but_never_whatever_the_run_took() {
        GlobalOptions auto = new GlobalOptions();
        auto.notify = JkConfig.NotifyChoice.AUTO;
        assertThat(BuildNotify.shouldNotifyQuarantine(auto)).isTrue();
        assertThat(BuildNotify.shouldNotifyQuarantine(new GlobalOptions())).isTrue();

        GlobalOptions never = new GlobalOptions();
        never.notify = JkConfig.NotifyChoice.NEVER;
        assertThat(BuildNotify.shouldNotifyQuarantine(never)).isFalse();

        GlobalOptions noOsc = new GlobalOptions();
        noOsc.noOsc = true;
        assertThat(BuildNotify.shouldNotifyQuarantine(noOsc)).isFalse();

        GlobalOptions noProgress = new GlobalOptions();
        noProgress.noProgress = true;
        assertThat(BuildNotify.shouldNotifyQuarantine(noProgress)).isFalse();
        assertThat(BuildNotify.shouldNotifyQuarantine(null)).isFalse();
    }

    @Test
    void each_quarantined_class_is_one_osc99_notification() {
        var buf = new ByteArrayOutputStream();
        BuildNotify.maybeNotifyQuarantined(
                new PrintStream(buf, true, StandardCharsets.UTF_8),
                new GlobalOptions(),
                List.of(
                        BuildNotify.quarantineMessage("g:a", "quarantined a.FlakyTest: passed on a re-run"),
                        BuildNotify.quarantineMessage("g:b", "quarantined b.OtherTest: passed on a re-run")));
        String out = buf.toString(StandardCharsets.UTF_8);
        assertThat(out.split("\033]99;", -1)).as("notification escapes").hasSize(1 + 2 * 2);
        assertThat(out)
                .contains(BuildNotify.QUARANTINE_TITLE)
                .contains("g:a: quarantined a.FlakyTest: passed on a re-run")
                .contains("g:b: quarantined b.OtherTest: passed on a re-run");
    }

    @Test
    void no_quarantine_means_no_notification() {
        var buf = new ByteArrayOutputStream();
        BuildNotify.maybeNotifyQuarantined(
                new PrintStream(buf, true, StandardCharsets.UTF_8), new GlobalOptions(), List.of());
        assertThat(buf.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void desktopNotify_empty_when_no_osc() {
        var noOsc = JkConfig.empty().withNoOsc(true);
        SessionContext.runWhere(Session.defaults().withConfig(noOsc), () -> {
            assertThat(Osc.oscEnabled()).isFalse();
            assertThat(Osc.desktopNotify("JumpKick Build", "hello")).isEmpty();
            assertThat(Osc.windowTitle("x")).isEmpty();
        });
    }
}

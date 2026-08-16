// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.config.JkConfig;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
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
    void desktopNotify_empty_when_no_osc() {
        var noOsc = cc.jumpkick.config.JkConfig.empty().withNoOsc(Optional.of(true));
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withConfig(noOsc), () -> {
                    assertThat(Ansi.oscEnabled()).isFalse();
                    assertThat(Ansi.desktopNotify("JumpKick Build", "hello")).isEmpty();
                    assertThat(Ansi.windowTitle("x")).isEmpty();
                    assertThat(Ansi.taskbarIndeterminate()).isEmpty();
                });
    }
}

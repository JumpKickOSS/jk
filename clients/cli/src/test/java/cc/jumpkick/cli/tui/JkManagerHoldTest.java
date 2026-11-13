// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static cc.jumpkick.cli.tui.JkManagerTestSupport.stream;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A build on hold for memory: the header, the work rows and the plain lines, and their return; and a
 * step that waits for a lease while the build works, shown on its own row only.
 */
class JkManagerHoldTest {

    @Test
    void on_hold_the_header_counts_the_hold_up_in_place_of_the_eta_and_one_waiting_line_replaces_the_work() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.progress(50, 100);
        cm.setEtaEstimate(60_000);
        cm.addTask("g:a", "compile-java");
        cm.stepRunning("g:a", "compile-java", "compile");

        cm.hold(512L << 20, 128L << 20);
        List<String> held = cm.renderBuildPlanLines(120, cm.elapsedMillis() + 12_000);

        String header = TestAnsi.strip(held.get(0));
        assertThat(header).contains("On hold 12s").doesNotContain("ETA");
        assertThat(header).contains(Glyphs.STOP).doesNotContain(Glyphs.PULSE);
        String body = TestAnsi.strip(String.join("\n", held.subList(1, held.size())));
        assertThat(body).contains(JkManagerView.HOLD_LINE).doesNotContain("compile");
    }

    @Test
    void a_waiting_step_dims_its_row_and_says_why_while_the_bar_the_rows_and_the_clock_go_on() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.progress(50, 100);
        cm.setEtaEstimate(60_000);
        cm.addTask("g:a", "run-tests");
        cm.stepRunning("g:a", "run-tests", "test");
        cm.addTask("g:b", "compile-java");
        cm.stepRunning("g:b", "compile-java", "compile");
        cm.stepMessage("g:a", "run-tests", "FooTest");

        cm.stepWaiting("g:a", "run-tests", "memory");
        List<String> lines = cm.renderBuildPlanLines(120, cm.elapsedMillis() + 5_000);
        String header = TestAnsi.strip(lines.get(0));
        assertThat(header).contains("ETA").doesNotContain("On hold");
        String body = TestAnsi.strip(String.join("\n", lines.subList(1, lines.size())));
        assertThat(body)
                .contains(Glyphs.WAITING + " g:a › Test › Waiting for memory" + Glyphs.ELLIPSIS)
                .doesNotContain("FooTest")
                .doesNotContain(JkManagerView.HOLD_LINE)
                .contains("g:b");

        cm.stepWaiting("g:a", "run-tests", "slot");
        body = TestAnsi.strip(String.join("\n", cm.renderBuildPlanLines(120, cm.elapsedMillis())));
        assertThat(body).contains("g:a › Test › Waiting for a free slot" + Glyphs.ELLIPSIS);

        cm.stepWaiting("g:a", "run-tests", null);
        body = TestAnsi.strip(String.join("\n", cm.renderBuildPlanLines(120, cm.elapsedMillis())));
        assertThat(body).contains("g:a › Test › FooTest").doesNotContain("Waiting for");
    }

    @Test
    void the_countdown_stands_still_through_a_hold_and_resumes_where_it_stood() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.progress(50, 100);
        cm.setEtaEstimate(60_000);
        long before = cm.workElapsedMillis();

        cm.hold(1, 0);
        long heldAt = cm.elapsedMillis() + 30_000;
        assertThat(cm.workElapsedMillis(heldAt))
                .as("work time stands still on hold")
                .isLessThan(before + 1_000);

        cm.resume(30_000);
        List<String> after = cm.renderBuildPlanLines(120, cm.elapsedMillis());
        String header = TestAnsi.strip(after.get(0));
        assertThat(header).contains("ETA ~").doesNotContain("On hold");
        assertThat(header).contains(Glyphs.PULSE);
    }

    @Test
    void plain_mode_says_the_build_paused_and_how_long_it_held_when_it_resumes() {
        var noAnsi = JkConfig.empty().withNoAnsi(true);
        SessionContext.runWhere(Session.defaults().withConfig(noAnsi), () -> {
            var buf = new ByteArrayOutputStream();
            var cm = JkManager.plan(stream(buf), "Build", true);
            cm.progress(0, 100);

            cm.hold(1, 0);
            cm.resume(19_000);

            String out = buf.toString(StandardCharsets.UTF_8);
            assertThat(out).contains(JkManagerPlainView.HOLD_STARTED);
            assertThat(out).contains("JumpKick build resumed after 19s on hold");
            assertThat(out).doesNotContain("\u001B[");
        });
    }

    @Test
    void the_plain_count_up_names_the_seconds_held() {
        assertThat(JkManagerPlainView.holdLine(45_200)).isEqualTo("On hold 45s");
    }
}

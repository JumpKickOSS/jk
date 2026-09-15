// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static cc.jumpkick.cli.tui.JkManagerTestSupport.stream;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.terminal.Size;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end plan paint against a scrolling viewport. Byte-stream tests cannot see a {@code ● Build}
 * that the extra trailing {@code \n} of a growing region pushed into scrollback.
 */
class JkManagerLiveRegionTest {

    private static final int ROWS = 8;
    private static final int COLS = 40;

    private final Supplier<Size.Window> savedProbe = Size.probe;

    @AfterEach
    void restoreSize() {
        Size.probe = savedProbe;
        Size.reset();
        TerminalReflow.reset();
    }

    @Test
    void growing_the_region_at_the_bottom_of_the_viewport_keeps_one_header() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            LiveRegionScreen screen = LiveRegionScreen.filled(ROWS, COLS);
            var buf = new ByteArrayOutputStream();
            JkManager cm = live(buf);
            cm.stepRunning("cc.jumpkick:jk-cli", "native-image", "native");
            cm.stepMessage("cc.jumpkick:jk-cli", "native-image", "classpath input size: ~4.3 MiB");
            paint(screen, buf, cm);
            assertThat(screen.countBuildHeaders()).isEqualTo(1);

            for (int i = 0; i < 5; i++) {
                cm.addCompletion("✓ [" + (11 - i) + " of 15] cc.jumpkick:mod-" + i + " took 1.0s");
                paint(screen, buf, cm);
                assertThat(screen.countBuildHeaders())
                        .as("after completion %d\n%s", i, dump(screen))
                        .isEqualTo(1);
            }
            cm.close();
            return null;
        });
    }

    @Test
    void force_shown_process_output_then_growth_keeps_one_header() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            LiveRegionScreen screen = LiveRegionScreen.filled(ROWS, COLS);
            var buf = new ByteArrayOutputStream();
            JkManager cm = live(buf);
            cm.stepRunning("cc.jumpkick:jk-cli", "native-image", "native");
            paint(screen, buf, cm);
            for (int i = 0; i < 12; i++) {
                cm.writeProcessOutput("native-image: [linking] cannot find -lz line " + i);
            }
            buf.reset();
            cm.showProcessFailureOutput();
            screen.write(buf.toString(StandardCharsets.UTF_8));
            assertThat(screen.countBuildHeaders())
                    .as("after force-show\n%s", dump(screen))
                    .isEqualTo(1);

            for (int i = 0; i < 5; i++) {
                cm.addCompletion("✓ [" + (11 - i) + " of 15] cc.jumpkick:mod-" + i + " took 1.0s");
            }
            paint(screen, buf, cm);
            assertThat(screen.countBuildHeaders())
                    .as("after growth on an open peek\n%s", dump(screen))
                    .isEqualTo(1);
            cm.close();
            return null;
        });
    }

    @Test
    void a_stray_newline_on_the_paint_stream_does_not_stack_headers() throws Exception {
        // A child that writes to the TTY (or any PrintStream sharing the pane) inserts a row at
        // the park without updating lastLines. The next frame must not leave the previous ● Build.
        NoAnsi.forcedAnsi(() -> {
            LiveRegionScreen screen = LiveRegionScreen.filled(ROWS, COLS);
            var buf = new ByteArrayOutputStream();
            var out = stream(buf);
            JkManager cm = live(out, buf);
            cm.stepRunning("m", "native-image", "native");
            paint(screen, buf, cm);
            buf.reset();
            cm.out.println("linker: cannot find -lz");
            screen.write(buf.toString(StandardCharsets.UTF_8));
            paint(screen, buf, cm);
            assertThat(screen.countBuildHeaders())
                    .as("after stray newline\n%s", dump(screen))
                    .isEqualTo(1);
            cm.close();
            return null;
        });
    }

    @Test
    void captured_system_out_does_not_land_on_the_tty_while_peek_is_closed() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            LiveRegionScreen screen = LiveRegionScreen.filled(ROWS, COLS);
            var buf = new ByteArrayOutputStream();
            JkManager cm = live(buf);
            cm.stepRunning("m", "compile", "compile");
            paint(screen, buf, cm);
            var original = System.out;
            try (var ignored = cm.captureOutput()) {
                System.out.println("a compiler wrote this to System.out");
                paint(screen, buf, cm);
            }
            assertThat(System.out).isSameAs(original);
            assertThat(cm.outputWindow().linesForDisplay(10)).contains("a compiler wrote this to System.out");
            assertThat(dump(screen)).doesNotContain("a compiler wrote this to System.out");
            assertThat(screen.countBuildHeaders()).isEqualTo(1);
            cm.close();
            return null;
        });
    }

    @Test
    void pin_scope_caption_keeps_one_header_with_the_caption_above() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            LiveRegionScreen screen = LiveRegionScreen.filled(ROWS, COLS);
            var buf = new ByteArrayOutputStream();
            JkManager cm = live(buf);
            paint(screen, buf, cm);
            buf.reset();
            ModuleScopeHint.show("building", List.of("jk-cli"), false, cm);
            screen.write(buf.toString(StandardCharsets.UTF_8));
            assertThat(screen.countBuildHeaders())
                    .as("after pinScopeCaption\n%s", dump(screen))
                    .isEqualTo(1);
            assertThat(dump(screen)).contains("building module jk-cli");
            cm.close();
            return null;
        });
    }

    private static void paint(LiveRegionScreen screen, ByteArrayOutputStream buf, JkManager cm) {
        buf.reset();
        cm.tick();
        screen.write(buf.toString(StandardCharsets.UTF_8));
    }

    private static JkManager live(ByteArrayOutputStream buf) {
        return live(stream(buf), buf);
    }

    private static JkManager live(PrintStream out, ByteArrayOutputStream buf) {
        Size.probe = () -> new Size.Window(ROWS, COLS);
        Size.reset();
        var cm = new JkManager(out, true, true, COLS);
        cm.height = ROWS;
        cm.name = "Build";
        cm.startNanos = System.nanoTime();
        cm.nerdFont = NerdFontCaps.NONE;
        return cm;
    }

    private static String dump(LiveRegionScreen screen) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- scrollback --\n");
        for (String line : screen.scrollback()) sb.append(line).append('\n');
        sb.append("-- viewport --\n");
        for (String line : screen.viewport()) sb.append(line).append('\n');
        return sb.toString();
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.host.time.Clock;
import java.io.PrintStream;
import java.time.Duration;

/**
 * The row {@code jk outdated} paints while the engine reads repositories: the blue {@code Outdated}
 * chip, a bar over the rows checked so far, and the coordinate in flight. Repainted in place; {@link
 * #finish} wipes it so the table takes the row.
 *
 * <p>Content only — the live region (animator, cursor, taskbar, Ctrl-C settle) is {@link LiveLine}'s,
 * and {@link LiveLine} decides that {@code --no-progress} and a machine-consumed stdout paint
 * nothing. Plain mode ({@code --no-ansi}) paints nothing either: the table follows within seconds
 * and there is no phase a status line would name.
 */
public final class OutdatedBar implements AutoCloseable {

    private final NerdFontCaps nerdFont;
    private final Clock clock = Clock.SYSTEM;
    private final long startedAtMillis;
    private final LiveLine line;

    private volatile int checked;
    private volatile int total;
    private volatile String coordinate = "";

    private OutdatedBar(PrintStream out) {
        this.nerdFont = GlobalConfig.nerdFont();
        this.startedAtMillis = clock.millis();
        this.line = LiveLine.of(out, this::frame).settle(this::cancelledLine).open();
    }

    /** Take the row on {@code out}; the first frame is the indeterminate chip until the first beat. */
    public static OutdatedBar show(PrintStream out) {
        return new OutdatedBar(out);
    }

    /** One beat off the wire; safe to call from any thread. */
    public void update(int checked, int total, String coordinate) {
        this.checked = checked;
        this.total = total;
        this.coordinate = coordinate == null ? "" : coordinate;
        if (total > 0) line.progress((int) Math.min(100L, checked * 100L / total));
    }

    /** Wipe the row and restore the cursor; the caller's table takes the row's place. */
    public void finish() {
        line.finish();
    }

    @Override
    public void close() {
        finish();
    }

    /** The animating row: spinner chip, bar once the total is known, the coordinate in flight. */
    private String frame(int tick) {
        Theme t = Theme.active();
        int n = total;
        String what = n > 0 ? "Checking " + coordinate : "Checking dependencies";
        RichText status = RichText.ansi(Theme.colorize(what, t.normalGray()));
        JkWedge wedge = new JkWedge(Icon.spinner(), "Outdated", n > 0 ? RichText.empty() : status)
                .variant(JkWedge.Variant.WORK);
        if (n > 0) {
            // Narrow: the trailing text is a coordinate we are handed, so the bar yields the columns.
            wedge = wedge.progress(
                    new Progress(checked, n).narrow().suffix(RichText.of(RichText.parse("[dark-gray]·[/] "), status)));
        }
        return wedge.renderLiveLine(context(tick));
    }

    /** What a cancelled read settles on: the same chrome a cancelled build gets. */
    private String cancelledLine() {
        String took = ConsoleSpec.took(Duration.ofMillis(Math.max(0L, clock.millis() - startedAtMillis)));
        return JkWedge.cancelled("Outdated", "outdated check", true, took).renderLine(context(0));
    }

    private RenderContext context(int tick) {
        return RenderContext.current().withCaps(nerdFont).withFrame(tick);
    }
}

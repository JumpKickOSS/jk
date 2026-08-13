// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.CliOutput;
import java.util.List;

/**
 * A snapshot TUI fragment: render to lines, then print. Live (animating) widgets implement
 * {@link LiveWidget}.
 */
public interface Widget {

    List<String> render(RenderContext ctx);

    /**
     * Paint on stdout. Opens the per-command blank-line envelope (JK-1373) so callers cannot skip
     * it the way a raw {@code JkWedge.chipLine} print used to.
     */
    default void print() {
        CommandWedge.envelopeStart();
        for (String line : render(RenderContext.current())) {
            CliOutput.out(line);
        }
    }

    /** Like {@link #print()} but each line goes to stderr (failures). */
    default void printErr() {
        if (!CommandWedge.envelopeStarted()) {
            CommandWedge.envelopeStart();
        }
        for (String line : render(RenderContext.current())) {
            CliOutput.err(line);
        }
    }
}

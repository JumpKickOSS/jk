// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.CliOutput;
import java.util.List;

/**
 * A snapshot TUI fragment: render to lines, then print. A widget that animates in place is not a
 * subtype of this — it composes one: {@link LiveLine} owns the region and takes an already-rendered
 * row per frame.
 */
public interface Widget {

    List<String> render(RenderContext ctx);

    /**
     * Paint on stdout. Opens the per-command blank-line envelope so callers cannot skip
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
        // First line is wedge chrome — open the envelope on stderr (not stdout).
        boolean first = true;
        for (String line : render(RenderContext.current())) {
            if (first) {
                CommandWedge.printErrLine(line);
                first = false;
            } else {
                CliOutput.err(line);
            }
        }
    }
}

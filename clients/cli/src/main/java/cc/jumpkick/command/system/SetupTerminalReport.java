// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.config.NerdFontDetect;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lines {@code jk self setup-terminal} prints, each held to {@link #WIDTH} text columns so that
 * with the status chip and indent the output stays inside the 78-column budget.
 */
final class SetupTerminalReport {

    /** Text columns per line, leaving room for the chip on the first line and the indent after it. */
    static final int WIDTH = 64;

    private SetupTerminalReport() {}

    /** {@code wedge+pill}, {@code wedge}, {@code pill} or {@code none}. */
    static String describe(NerdFontCaps caps) {
        return !caps.any() ? "none" : caps.wedge() && caps.pill() ? "wedge+pill" : caps.wedge() ? "wedge" : "pill";
    }

    /** What detection concluded and why, as short lines. */
    static List<String> explain(NerdFontDetect.Result r) {
        List<String> lines = new ArrayList<>();
        lines.add(
                "detected " + describe(r.caps()) + " (" + r.confidence().name().toLowerCase(Locale.ROOT) + ")");
        lines.addAll(wrap("why: " + r.reason(), WIDTH));
        lines.add("source: " + r.source());
        lines.addAll(wrap("confirmation: " + r.cache(), WIDTH));
        if (needsConfirmation(r)) lines.add("confirm what you see: jk self setup-terminal");
        return lines;
    }

    /** {@code text} broken at spaces into lines of at most {@code columns}; a longer word stands alone. */
    static List<String> wrap(String text, int columns) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > columns) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        lines.add(line.toString());
        return lines;
    }

    /** One chip body: the lines joined with the indent the chip uses for continuation lines. */
    static String body(List<String> lines) {
        return String.join("\n  ", lines);
    }

    private static boolean needsConfirmation(NerdFontDetect.Result r) {
        return switch (r.confidence()) {
            case CONFIRMED -> false;
            case UNKNOWN, INFERRED -> true;
            case KNOWN -> r.source().equals("apple-terminal");
        };
    }
}

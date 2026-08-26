// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.engine.protocol.ProjectInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Caption above a workspace wedge when a module selection is in effect (cwd member or {@code
 * -m}): {@code  …building module jk-cli…}
 *
 * <p>Names come from the engine {@link ProjectInfo} peek — the CLI does not parse {@code jk.toml}.
 */
public final class ModuleScopeHint {

    private ModuleScopeHint() {}

    /**
     * Markup for the caption. {@code verb} is the gerund ({@code building}, {@code testing},
     * {@code compiling}). One name → {@code module}; several → {@code modules}.
     */
    public static String markup(String verb, List<String> names) {
        if (names == null || names.isEmpty()) return "";
        String noun = names.size() == 1 ? " module " : " modules ";
        StringBuilder sb = new StringBuilder();
        sb.append("[dark-gray]").append(Glyphs.ELLIPSIS).append(verb).append(noun);
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(RichText.escape(names.get(i)));
        }
        sb.append(Glyphs.ELLIPSIS).append("[/]");
        return sb.toString();
    }

    /** Painted caption: one leading space, then the ellipsis line. */
    public static String line(String verb, List<String> names) {
        return line(verb, names, RenderContext.current());
    }

    public static String line(String verb, List<String> names, RenderContext ctx) {
        if (names == null || names.isEmpty()) return "";
        return " " + RichText.parse(markup(verb, names)).render(ctx);
    }

    /** Non-blank {@code moduleNames} from a project summary. Empty when unset. */
    public static List<String> namesFrom(ProjectInfo info) {
        if (info == null || info.moduleNames() == null || info.moduleNames().isEmpty()) {
            if (info != null && info.name() != null && !info.name().isBlank()) return List.of(info.name());
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String n : info.moduleNames()) {
            if (n != null && !n.isBlank()) names.add(n);
        }
        return List.copyOf(names);
    }

    /** Apply the caption on a live plan, or no-op when {@code names} is empty. */
    public static void apply(JkManager view, String verb, List<String> names) {
        if (view == null || names == null || names.isEmpty()) return;
        view.setModuleScopeHint(verb, names);
    }

    /**
     * Print the caption once (envelope-aware) for a module selection. Skipped for JSON and when
     * {@code names} is null or empty (whole-workspace run).
     */
    public static void print(String verb, List<String> names, boolean json) {
        if (json) return;
        if (names == null || names.isEmpty()) return;
        CommandWedge.envelopeStart();
        String text = line(verb, names);
        if (!Theme.active().isAnsi()) {
            String body = text.startsWith(" ") ? text.substring(1) : text;
            text = JkWedge.PLAIN_LINE_PREFIX + body;
        }
        CliOutput.out(text);
    }

    /** Print the scrollback caption and, when {@code view} is open, pin it above the wedge. */
    public static void show(String verb, List<String> names, boolean json, JkManager view) {
        print(verb, names, json);
        apply(view, verb, names);
    }
}

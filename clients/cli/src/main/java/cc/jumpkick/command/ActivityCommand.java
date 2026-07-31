// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Badge;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.List;
import org.jline.utils.AttributedStyle;

/**
 * {@code jk jobs} — recent and running engine jobs via the journal RPC (same backend as the web UI).
 * {@code jk activity} / {@code act} remain hidden aliases (JK-1252).
 *
 * <pre>
 *  ≡ Jobs
 *  │
 *  ├─ ✓ #31 Success group:name · build · 8 modules · 2.3s · 1h ago
 *  │
 *  ╰─ ✘ #12 Failure other:app · test · 1 module · 1.3s · 6h ago
 * </pre>
 *
 * <p>Rail is indented one space. Outcome is a colored pill (Nerd Font half-circles, space pads
 * without, {@code […]} when ANSI is off) — same chip language as {@code jk tree} scope badges.
 */
public final class ActivityCommand implements CliCommand {

    /** Default rows — matches the web Jobs feed window. */
    static final int DEFAULT_LIMIT = 30;

    @Override
    public String name() {
        return "jobs";
    }

    @Override
    public List<String> aliases() {
        // Primary name is `jobs`; keep activity/act for muscle memory (JK-1252).
        return List.of("activity", "act");
    }

    @Override
    public String description() {
        return "Show running and recent engine jobs (jid, build #, status)";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.value(
                "<N>", "Maximum number of runs to show (default: " + DEFAULT_LIMIT + ").", "--limit", "-n"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        int limit = in.value("limit").map(ActivityCommand::parseLimit).orElse(DEFAULT_LIMIT);
        List<String> lines = EngineClient.historyList(EnginePaths.current(), limit);
        List<String> entries = lines.stream()
                .filter(l -> EngineProtocol.HISTORY_ENTRY.equals(EngineProtocol.typeOf(l)))
                .toList();
        if (entries.isEmpty()) {
            CliOutput.out(titleLine());
            CliOutput.out(rail());
            CliOutput.out(branch(true, Theme.active()) + "No jobs yet");
            return 0;
        }
        long now = System.currentTimeMillis();
        Theme t = Theme.active();
        int buildNumberWidth = buildNumberWidth(entries);
        CliOutput.out(titleLine());
        for (int i = 0; i < entries.size(); i++) {
            boolean last = i == entries.size() - 1;
            CliOutput.out(rail());
            CliOutput.out(branch(last, t) + formatLine(entries.get(i), now, t, buildNumberWidth));
        }
        return 0;
    }

    /**
     * Digit width for zero-padded build numbers in this listing — wide enough for the largest
     * {@code buildNumber} present (e.g. max 21 → 2 digits so {@code #01}…{@code #21}; max 100 → 3).
     */
    static int buildNumberWidth(List<String> entries) {
        long max = 0;
        for (String e : entries) {
            max = Math.max(max, Jsonl.longValue(e, "buildNumber", 0));
        }
        if (max <= 0) return 1;
        return String.valueOf(max).length();
    }

    /** Blue menu CommandWedge: {@code ≡ Jobs}. */
    static String titleLine() {
        return CommandWedge.menu("Jobs");
    }

    /** Lone vertical rail between rows — indented one space under the title chip. */
    static String rail() {
        Theme t = Theme.active();
        if (!t.isAnsi()) return " |";
        return " " + Theme.colorize("│", t.darkGray());
    }

    /**
     * {@code  ├─} or {@code  ╰─} (ASCII {@code  +-} / {@code  `-}), indented one space under the
     * title chip. No trailing space — the status pill abuts the connector.
     */
    static String branch(boolean last, Theme t) {
        if (!t.isAnsi()) return last ? " `-" : " +-";
        return " " + Theme.colorize(last ? "╰─" : "├─", t.darkGray());
    }

    /**
     * Body of one Jobs row (no tree prefix) from a flat {@code history-entry} JSONL line.
     * Package-visible for unit tests. {@code buildNumberWidth} zero-pads {@code #N} across the list.
     */
    static String formatLine(String entry, long now, Theme t) {
        return formatLine(entry, now, t, 1);
    }

    static String formatLine(String entry, long now, Theme t, int buildNumberWidth) {
        boolean running = Jsonl.bool(entry, "running", false);
        boolean success = Jsonl.bool(entry, "success", false);
        boolean cancelled = Jsonl.bool(entry, "cancelled", false);
        long buildNumber = Jsonl.longValue(entry, "buildNumber", 0);
        String kind = Jsonl.str(entry, "kind");
        if (kind == null || kind.isBlank()) kind = "build";
        // Single-pipeline journal rows often have moduleCount 0 (steps live at top level).
        int moduleCount = Jsonl.intValue(entry, "moduleCount", 0);
        if (moduleCount <= 0) moduleCount = 1;
        long millis = Jsonl.longValue(entry, "millis", -1);
        long finishedAt = Jsonl.longValue(entry, "finishedAt", 0);
        long startedAt = Jsonl.longValue(entry, "startedAt", 0);
        // Optional 0–100 progress on in-flight entries (absent → omit).
        double progress = progressPercent(entry);

        String glyph;
        String outcomeWord;
        String kindWord;
        OutcomeStyle outcome;
        if (running) {
            glyph = t.isAnsi() ? Glyphs.PLAY : "*";
            outcomeWord = "Building";
            kindWord = "Building";
            outcome = OutcomeStyle.RUNNING;
        } else if (cancelled) {
            glyph = t.isAnsi() ? "⊛" : "o";
            // Trailing space so "Cancel " lines up with "Success" / "Failure" / "Building".
            outcomeWord = "Cancel ";
            kindWord = kind;
            outcome = OutcomeStyle.CANCELLED;
        } else if (success) {
            glyph = t.isAnsi() ? Glyphs.CHECK : "+";
            outcomeWord = "Success";
            kindWord = kind;
            outcome = OutcomeStyle.SUCCESS;
        } else {
            glyph = t.isAnsi() ? Glyphs.CROSS : "!";
            outcomeWord = "Failure";
            kindWord = kind;
            outcome = OutcomeStyle.FAILURE;
        }

        long jid = Jsonl.longValue(entry, "jid", Jsonl.longValue(entry, "requestId", 0));
        // Pill body: glyph + optional jid + #N (zero-padded) + outcome word.
        StringBuilder pillLabel = new StringBuilder();
        pillLabel.append(glyph).append(' ');
        if (running && jid > 0) pillLabel.append("jid=").append(jid).append(' ');
        if (buildNumber > 0) {
            int width = Math.max(1, buildNumberWidth);
            pillLabel.append('#').append(String.format("%0" + width + "d", buildNumber)).append(' ');
        } else if (!running) {
            pillLabel.append("#— ");
        }
        pillLabel.append(outcomeWord);
        // Don't trim trailing spaces — "Cancel " is padded to align with "Success"/"Failure".
        String head = statusPill(pillLabel.toString().stripLeading(), outcome, t);

        String coordPart = formatCoord(Jsonl.str(entry, "coord"), Jsonl.str(entry, "dir"), t);
        String modulesPart = moduleCount == 1 ? "1 module" : moduleCount + " modules";
        String durationPart = running
                ? (startedAt > 0 ? HistoryCommand.duration(Math.max(0, now - startedAt)) : "…")
                : HistoryCommand.duration(millis);
        String agoPart = running ? "" : HistoryCommand.ago(finishedAt > 0 ? finishedAt : startedAt, now);
        if (agoPart == null) agoPart = "";

        String sep = t.isAnsi() ? Theme.colorize(" · ", t.darkGray()) : " · ";
        StringBuilder line = new StringBuilder();
        line.append(head).append(' ').append(coordPart);
        line.append(sep).append(muted(kindWord, t));
        line.append(sep).append(muted(modulesPart, t));
        if (running && progress >= 0) {
            line.append(sep).append(muted(Math.round(progress) + "%", t));
        }
        line.append(sep).append(muted(durationPart, t));
        if (!agoPart.isEmpty()) {
            line.append(sep).append(t.isAnsi() ? Theme.colorize(agoPart, t.dim()) : agoPart);
        }
        return line.toString();
    }

    /** Outcome → chip colors for the status pill. */
    private enum OutcomeStyle {
        SUCCESS,
        FAILURE,
        CANCELLED,
        RUNNING
    }

    /**
     * Status pill around {@code label} (e.g. {@code ✓ #20 Success}):
     *
     * <ul>
     *   <li>Nerd Font: rounded half-circles in the chip color, black text on chip bg
     *   <li>ANSI, no Nerd Font: space pads on chip bg (no half-circles)
     *   <li>No ANSI: {@code [label]} plain ASCII
     * </ul>
     */
    static String statusPill(String label, OutcomeStyle outcome, Theme t) {
        if (!t.isAnsi()) {
            return "[" + label + "]";
        }
        // Black text on the outcome color; caps painted in that same color as FG (rounded edges).
        cc.jumpkick.cli.theme.Rgb chipRgb =
                switch (outcome) {
                    case SUCCESS -> t.pipelineChipColor();
                    case FAILURE -> t.pipelineFailColor();
                    case CANCELLED -> cc.jumpkick.cli.theme.Rgb.hex(0xFFB800); // matches web --warn
                    case RUNNING -> t.planBadgeColor();
                };
        // Pure black (#000000) text on the chip color — not theme "black" (palette gray).
        AttributedStyle body = t.withBackground(t.bright(0, 0, 0), chipRgb);
        AttributedStyle caps = t.bright(chipRgb);
        return Badge.pill(label, GlobalConfig.nerdfont(), body, caps);
    }

    /** Progress 0–100 from entry, or {@code -1} when absent. */
    static double progressPercent(String entry) {
        if (!Jsonl.has(entry, "progress")) return -1;
        // progress may be int or floating; Jsonl.longValue truncates floats at the dot.
        long whole = Jsonl.longValue(entry, "progress", -1);
        if (whole < 0) return -1;
        return Math.min(100, whole);
    }

    static String formatCoord(String coord, String dir, Theme t) {
        String raw = HistoryCommand.label(coord, dir);
        if (!t.isAnsi()) return raw;
        int i = raw.indexOf(':');
        if (i <= 0 || i >= raw.length() - 1) {
            return Theme.colorize(raw, t.coordName().bold());
        }
        return Theme.colorize(raw.substring(0, i), t.coordGroup())
                + Theme.colorize(":", t.darkGray())
                + Theme.colorize(raw.substring(i + 1), t.coordName().bold());
    }

    private static String muted(String s, Theme t) {
        return t.isAnsi() ? Theme.colorize(s, t.normalGray()) : s;
    }

    private static int parseLimit(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }
}

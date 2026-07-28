// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.List;

/**
 * {@code jk activity} — Activity feed of recent builds via the engine journal RPC (same backend as
 * the web UI).
 *
 * <pre>
 * ≡ Build Activity
 * │
 * ├─ ▶ #2 Building group:name · Building · 1 module · 55% · 4.2s
 * │
 * ├─ ✓ #31 Success  group:name · build · 8 modules · 2.3s · 3h ago
 * │
 * ╰─ ✘ #12 Failure  other:app · test · 1 module · 1.3s · 6h ago
 * </pre>
 */
public final class ActivityCommand implements CliCommand {

    /** Default rows — matches the web Activity feed window. */
    static final int DEFAULT_LIMIT = 30;

    @Override
    public String name() {
        return "activity";
    }

    @Override
    public List<String> aliases() {
        return List.of("act");
    }

    @Override
    public String description() {
        return "Show recent builds (Activity feed)";
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
            CliOutput.out(Theme.active().isAnsi()
                    ? Theme.colorize("╰─ ", Theme.active().darkGray()) + "No activity yet"
                    : "`- No activity yet");
            return 0;
        }
        long now = System.currentTimeMillis();
        Theme t = Theme.active();
        CliOutput.out(titleLine());
        for (int i = 0; i < entries.size(); i++) {
            boolean last = i == entries.size() - 1;
            CliOutput.out(rail());
            CliOutput.out(branch(last, t) + formatLine(entries.get(i), now, t));
        }
        return 0;
    }

    /** Blue menu CommandWedge: {@code ≡ Build Activity}. */
    static String titleLine() {
        return CommandWedge.menu("Build Activity");
    }

    /** Lone vertical rail between rows. */
    static String rail() {
        Theme t = Theme.active();
        if (!t.isAnsi()) return "|";
        return Theme.colorize("│", t.darkGray());
    }

    /** {@code ├─ } or {@code ╰─ } (ASCII fallback {@code +- } / {@code `- }). */
    static String branch(boolean last, Theme t) {
        if (!t.isAnsi()) return last ? "`- " : "+- ";
        return Theme.colorize(last ? "╰─ " : "├─ ", t.darkGray());
    }

    /**
     * Body of one Activity row (no tree prefix) from a flat {@code history-entry} JSONL line.
     * Package-visible for unit tests.
     */
    static String formatLine(String entry, long now, Theme t) {
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
        if (running) {
            glyph = t.isAnsi() ? Theme.colorize(Glyphs.PLAY, t.primary()) : "*";
            outcomeWord = "Building";
            kindWord = "Building";
        } else if (cancelled) {
            glyph = t.isAnsi() ? Theme.colorize("⊛", t.warning()) : "o";
            outcomeWord = "Cancelled";
            kindWord = kind;
        } else if (success) {
            glyph = t.isAnsi() ? Theme.colorize(Glyphs.CHECK, t.success()) : "+";
            outcomeWord = "Success";
            kindWord = kind;
        } else {
            glyph = t.isAnsi() ? Theme.colorize(Glyphs.CROSS, t.error()) : "!";
            outcomeWord = "Failure";
            kindWord = kind;
        }

        String num = buildNumber > 0 ? "#" + buildNumber : "#—";
        String head = glyph + " " + num + " " + outcomeWord;

        String coordPart = formatCoord(Jsonl.str(entry, "coord"), Jsonl.str(entry, "dir"), t);
        String modulesPart = moduleCount == 1 ? "1 module" : moduleCount + " modules";
        String durationPart = running
                ? (startedAt > 0 ? HistoryCommand.duration(Math.max(0, now - startedAt)) : "…")
                : HistoryCommand.duration(millis);
        String agoPart = running ? "" : HistoryCommand.ago(finishedAt > 0 ? finishedAt : startedAt, now);
        if (agoPart == null) agoPart = "";

        String sep = t.isAnsi() ? Theme.colorize(" · ", t.darkGray()) : " · ";
        StringBuilder line = new StringBuilder();
        line.append(head).append("  ").append(coordPart);
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

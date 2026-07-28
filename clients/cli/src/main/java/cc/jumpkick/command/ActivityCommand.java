// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Badge;
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
 * {@code jk activity} — Activity-page style feed of recent builds (same journal as the web UI and
 * {@code jk history}). Newest first; default 30 rows.
 *
 * <pre>
 * ✓ #31 Success  group:name · build · 8 modules · 2.3s · 3h ago
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
            CliOutput.out("No activity yet — run a build and it will appear here.");
            return 0;
        }
        long now = System.currentTimeMillis();
        boolean nerdfont = GlobalConfig.nerdfont();
        Theme t = Theme.active();
        for (String e : entries) {
            CliOutput.out(formatLine(e, now, nerdfont, t));
        }
        return 0;
    }

    /** One Activity row — pure formatting (unit-tested). */
    static String formatLine(String entry, long now, boolean nerdfont, Theme t) {
        boolean running = Jsonl.bool(entry, "running", false);
        boolean success = Jsonl.bool(entry, "success", false);
        boolean cancelled = Jsonl.bool(entry, "cancelled", false);
        long buildNumber = Jsonl.longValue(entry, "buildNumber", 0);
        String kind = Jsonl.str(entry, "kind");
        if (kind == null || kind.isBlank()) kind = "build";
        // Single-pipeline journal rows often have moduleCount 0 (steps live at top level).
        int moduleCount = Jsonl.intValue(entry, "moduleCount", 0);
        if (moduleCount <= 0 && !running) moduleCount = 1;
        long millis = Jsonl.longValue(entry, "millis", -1);
        long finishedAt = Jsonl.longValue(entry, "finishedAt", 0);
        long startedAt = Jsonl.longValue(entry, "startedAt", 0);

        String outcomeWord;
        String glyph;
        AttributedStyle chipBody;
        AttributedStyle chipCaps;
        if (running) {
            outcomeWord = "Running";
            glyph = Glyphs.PLAY;
            chipBody = t.pipelineChip();
            chipCaps = t.bright(t.planBadgeColor());
        } else if (cancelled) {
            outcomeWord = "Cancelled";
            glyph = "⊛";
            chipBody = t.withBackground(t.brightWhite(), t.planBadgeColor()); // blue-ish neutral
            chipCaps = t.bright(t.planBadgeColor());
        } else if (success) {
            outcomeWord = "Success";
            glyph = Glyphs.CHECK;
            chipBody = t.pipelineSuccessChip();
            chipCaps = t.bright(t.pipelineChipColor());
        } else {
            outcomeWord = "Failure";
            glyph = Glyphs.CROSS;
            chipBody = t.pipelineFailureChip();
            chipCaps = t.bright(t.pipelineFailColor());
        }

        String num = buildNumber > 0 ? "#" + buildNumber : "#—";
        String pillLabel = glyph + " " + num + " " + outcomeWord;
        String pill;
        if (!t.isAnsi()) {
            String prefix = success && !cancelled && !running ? "+" : (!success && !cancelled && !running ? "!" : "*");
            pill = prefix + " " + num + " " + outcomeWord;
        } else {
            pill = Badge.pill(pillLabel, nerdfont, chipBody, chipCaps);
        }

        String coordPart = formatCoord(Jsonl.str(entry, "coord"), Jsonl.str(entry, "dir"), t);
        String modulesPart = moduleCount == 1 ? "1 module" : moduleCount + " modules";
        String durationPart = running
                ? (startedAt > 0 ? HistoryCommand.duration(Math.max(0, now - startedAt)) : "…")
                : HistoryCommand.duration(millis);
        String agoPart = running
                ? "now"
                : HistoryCommand.ago(finishedAt > 0 ? finishedAt : startedAt, now);
        if (agoPart == null || agoPart.isBlank()) agoPart = "";

        String sep = t.isAnsi() ? Theme.colorize(" · ", t.darkGray()) : " · ";
        StringBuilder line = new StringBuilder();
        line.append(pill).append(" ");
        line.append(coordPart);
        line.append(sep).append(t.isAnsi() ? Theme.colorize(kind, t.normalGray()) : kind);
        line.append(sep).append(t.isAnsi() ? Theme.colorize(modulesPart, t.normalGray()) : modulesPart);
        line.append(sep).append(t.isAnsi() ? Theme.colorize(durationPart, t.normalGray()) : durationPart);
        if (!agoPart.isEmpty()) {
            line.append(sep)
                    .append(t.isAnsi() ? Theme.colorize(agoPart, t.dim()) : agoPart);
        }
        return line.toString();
    }

    /** {@code group:name} with coord colors; falls back to last path segment. */
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

    private static int parseLimit(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }
}

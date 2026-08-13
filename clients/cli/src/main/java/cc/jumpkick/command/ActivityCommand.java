// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Pill;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.cli.tui.Tree;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.List;

/**
 * {@code jk jobs} — recent and running engine jobs via the journal RPC (same backend as the web UI).
 * {@code jk activity} / {@code act} remain hidden aliases.
 *
 * <pre>
 * ≡ Build Jobs
 * │
 * ├─▶ #23 Building group:name · building… · 1 module · 4% · 7.6s · id: 1
 * │
 * ╰─✓ #31 Success other:app · build · 8 modules · 2.3s · 1h ago
 * </pre>
 *
 * <p>Rail is indented one space. Outcome is a colored pill (Nerd Font half-circles, space pads
 * without, {@code […]} when ANSI is off) — same chip language as {@code jk tree} scope badges.
 * Running pills use white label text; the job id sits at the end of the row ({@code id: N}).
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
        // Primary name is `jobs`; builds/activity/act for muscle memory.
        return List.of("builds", "activity", "act");
    }

    @Override
    public String description() {
        return "Show running and recent engine jobs (jid, build #, status)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<N>", "Maximum number of runs to show (default: " + DEFAULT_LIMIT + ").", "--limit", "-n"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        int limit = in.value("limit").map(ActivityCommand::parseLimit).orElse(DEFAULT_LIMIT);
        List<String> lines = EngineClient.historyList(EnginePaths.current(), limit);
        List<String> entries = lines.stream()
                .filter(l -> EngineProtocol.HISTORY_ENTRY.equals(EngineProtocol.typeOf(l)))
                .toList();
        Tree tree = new Tree("Build Jobs").gap(Tree.Gap.EACH);
        if (entries.isEmpty()) {
            tree.child(Tree.node("No jobs yet"));
        } else {
            long now = System.currentTimeMillis();
            Theme t = Theme.active();
            int buildNumberWidth = buildNumberWidth(entries);
            for (String entry : entries) {
                tree.child(jobNode(entry, now, t, buildNumberWidth));
            }
        }
        tree.print();
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

    /** Blue menu CommandWedge: {@code ≡ Build Jobs}. */
    static String titleLine() {
        return new Tree("Build Jobs").render(RenderContext.current()).getFirst();
    }

    /**
     * Body of one Jobs row (no tree prefix) from a flat {@code history-entry} JSONL line.
     * Package-visible for unit tests. {@code buildNumberWidth} zero-pads {@code #N} across the list.
     */
    static String formatLine(String entry, long now, Theme t) {
        return formatLine(entry, now, t, 1);
    }

    static String formatLine(String entry, long now, Theme t, int buildNumberWidth) {
        Tree.Node node = jobNode(entry, now, t, buildNumberWidth);
        RenderContext ctx = RenderContext.current();
        return node.pill().renderInline(ctx) + node.label().render(ctx);
    }

    /** {@code id: N} with the number bold white when ANSI is on. */
    static String formatJobId(long jid, Theme t) {
        if (!t.isAnsi()) return "id: " + jid;
        return muted("id: ", t) + Theme.colorize(String.valueOf(jid), t.focused());
    }

    /** Outcome → chip colors for the status pill. */
    private enum OutcomeStyle {
        SUCCESS,
        FAILURE,
        CANCELLED,
        RUNNING
    }

    static Tree.Node jobNode(String entry, long now, Theme t, int buildNumberWidth) {
        boolean running = Jsonl.bool(entry, "running", false);
        boolean cancelled = Jsonl.bool(entry, "cancelled", false);
        boolean success = Jsonl.bool(entry, "success", false);
        OutcomeStyle outcome = running
                ? OutcomeStyle.RUNNING
                : cancelled ? OutcomeStyle.CANCELLED : success ? OutcomeStyle.SUCCESS : OutcomeStyle.FAILURE;
        String kind = Jsonl.str(entry, "kind");
        if (kind == null || kind.isBlank()) kind = "build";
        int moduleCount = Jsonl.intValue(entry, "moduleCount", 0);
        if (moduleCount <= 0) moduleCount = 1;
        long millis = Jsonl.longValue(entry, "millis", -1);
        long finishedAt = Jsonl.longValue(entry, "finishedAt", 0);
        long startedAt = Jsonl.longValue(entry, "startedAt", 0);
        double progress = progressPercent(entry);
        long jid = Jsonl.longValue(entry, "jid", Jsonl.longValue(entry, "requestId", 0));

        String kindWord = running ? "building…" : kind;
        String coordPart = formatCoord(Jsonl.str(entry, "coord"), Jsonl.str(entry, "dir"), t);
        String modulesPart = moduleCount == 1 ? "1 module" : moduleCount + " modules";
        String durationPart = running
                ? (startedAt > 0 ? HistoryCommand.duration(Math.max(0, now - startedAt)) : "…")
                : HistoryCommand.duration(millis);
        String agoPart = running ? "" : HistoryCommand.ago(finishedAt > 0 ? finishedAt : startedAt, now);
        if (agoPart == null) agoPart = "";

        String sep = t.isAnsi() ? Theme.colorize(" · ", t.darkGray()) : " · ";
        var rest = new StringBuilder();
        rest.append(' ').append(coordPart);
        rest.append(sep).append(muted(kindWord, t));
        rest.append(sep).append(muted(modulesPart, t));
        if (running && progress >= 0) {
            rest.append(sep).append(muted(Math.round(progress) + "%", t));
        }
        rest.append(sep).append(muted(durationPart, t));
        if (!agoPart.isEmpty()) {
            rest.append(sep).append(t.isAnsi() ? Theme.colorize(agoPart, t.dim()) : agoPart);
        }
        if (running && jid > 0) {
            rest.append(sep).append(formatJobId(jid, t));
        }
        return Tree.node(statusPill(pillText(entry, t, buildNumberWidth), outcome), RichText.ansi(rest.toString()));
    }

    static Pill statusPill(String label, OutcomeStyle outcome) {
        return switch (outcome) {
            case SUCCESS -> Pill.success(label);
            case FAILURE -> Pill.fail(label);
            case CANCELLED -> Pill.warning(label);
            case RUNNING -> Pill.running(label);
        };
    }

    private static String pillText(String entry, Theme t, int buildNumberWidth) {
        boolean running = Jsonl.bool(entry, "running", false);
        boolean success = Jsonl.bool(entry, "success", false);
        boolean cancelled = Jsonl.bool(entry, "cancelled", false);
        long buildNumber = Jsonl.longValue(entry, "buildNumber", 0);
        String glyph;
        String outcomeWord;
        if (running) {
            glyph = t.isAnsi() ? Glyphs.PLAY : "*";
            outcomeWord = "Building";
        } else if (cancelled) {
            glyph = t.isAnsi() ? "⊛" : "o";
            outcomeWord = "Cancel ";
        } else if (success) {
            glyph = t.isAnsi() ? Glyphs.CHECK : "+";
            outcomeWord = "Success";
        } else {
            glyph = t.isAnsi() ? Glyphs.CROSS : "!";
            outcomeWord = "Failure";
        }
        var sb = new StringBuilder();
        sb.append(glyph).append(' ');
        if (buildNumber > 0) {
            int width = Math.max(1, buildNumberWidth);
            sb.append('#')
                    .append(String.format("%0" + width + "d", buildNumber))
                    .append(' ');
        } else if (!running) {
            sb.append("#— ");
        }
        sb.append(outcomeWord);
        return sb.toString().stripLeading();
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

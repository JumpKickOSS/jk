// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.EngineJournalReads;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk stats}: what the cache, the code graph and the guards saved over the last days, overall
 * and per project, added up by the engine from the build history and the agent sessions it keeps.
 * {@code -O jsonl} prints the same rows the dashboard's panel reads from {@code GET /api/stats}.
 */
public final class StatsCommand implements CliCommand {

    static final int DEFAULT_DAYS = 7;

    private static final long DAY_MILLIS = 86_400_000L;

    /** Projects the text table lists; the rest are in the total. */
    private static final int PROJECT_ROWS = 10;

    @Override
    public String name() {
        return "stats";
    }

    @Override
    public String description() {
        return "Show what the cache, code graph and guards saved";
    }

    @Override
    public MachineOutput machineOutput() {
        return MachineOutput.JSONL;
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.value(
                "<N>", "The last N days (default: " + DEFAULT_DAYS + "; 0: all the history kept).", "-d", "--days"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        String raw = in.value("days").orElse(null);
        @Nullable Integer days = raw == null ? Integer.valueOf(DEFAULT_DAYS) : parseDays(raw);
        if (days == null) {
            CommandWedge.printFail("Stats", "--days takes a whole number of days, not " + raw);
            return Exit.USAGE;
        }
        long since = since(days, Clock.SYSTEM.millis());
        List<String> rows = EngineJournalReads.stats(EnginePaths.current(), since).stream()
                .filter(l -> EngineProtocol.STATS.equals(EngineProtocol.typeOf(l)))
                .toList();
        if (CliOutput.jsonMode()) {
            for (String row : rows) JsonlShape.rowOf(EngineProtocol.STATS, row);
            return 0;
        }
        String period = period(days);
        if (rows.isEmpty() || nothing(rows.getFirst())) {
            CliOutput.out("Nothing saved " + period + ": no runs or agent actions");
            return 0;
        }
        CommandWedge.envelopeStart();
        for (String line : Table.render("Saved " + period, List.of("", "Total"), totalRows(rows.getFirst()))) {
            CliOutput.out(line);
        }
        List<String> projects = rows.subList(1, rows.size());
        if (!projects.isEmpty()) {
            for (String line : Table.render(
                    "By project",
                    List.of("Project", "Time", "Skipped", "Graph", "Renames", "Edits", "Guards"),
                    projectRows(projects))) {
                CliOutput.out(line);
            }
        }
        return 0;
    }

    /** The start of the last {@code days} days before {@code now}; 0 (every run kept) for 0 days. */
    static long since(int days, long now) {
        return days == 0 ? 0 : Math.max(0, now - days * DAY_MILLIS);
    }

    /** {@code raw} as a day count, or null when it is not a whole number of zero or more. */
    static @Nullable Integer parseDays(String raw) {
        try {
            int n = Integer.parseInt(raw.strip());
            return n < 0 ? null : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String period(int days) {
        return days == 0 ? "in all the history kept" : days == 1 ? "in the last day" : "in the last " + days + " days";
    }

    private static boolean nothing(String row) {
        return Jsonl.longValue(row, "runs", 0) == 0
                && Jsonl.longValue(row, "graphCalls", 0) == 0
                && Jsonl.longValue(row, "renames", 0) == 0
                && Jsonl.longValue(row, "edits", 0) == 0;
    }

    /** The total, one figure per row. */
    static List<List<String>> totalRows(String row) {
        List<List<String>> out = new ArrayList<>();
        out.add(List.of("Build and test time avoided", saved(row)));
        out.add(List.of("Steps served from the cache", count(row, "stepsSkipped")));
        out.add(List.of("Test suites not re-run", count(row, "suitesSkipped")));
        out.add(List.of("Test classes not re-run", count(row, "testClassesNotRerun")));
        out.add(List.of("Code-graph questions answered", count(row, "graphCalls")));
        out.add(List.of("Renames applied", count(row, "renames")));
        out.add(List.of("Dependency edits made", count(row, "edits")));
        long stops = Jsonl.longValue(row, "guardStops", 0);
        out.add(List.of(
                "Guard violations stopped",
                count(row, "guardViolations") + (stops > 0 ? " in " + stops + (stops == 1 ? " run" : " runs") : "")));
        out.add(List.of("Runs", count(row, "runs")));
        return out;
    }

    static List<List<String>> projectRows(List<String> projects) {
        List<List<String>> out = new ArrayList<>();
        for (String p : projects.subList(0, Math.min(PROJECT_ROWS, projects.size()))) {
            out.add(List.of(
                    HistoryCommand.truncate(HistoryCommand.label(Jsonl.str(p, "coord"), Jsonl.str(p, "dir")), 28),
                    HistoryCommand.duration(Jsonl.longValue(p, "savedMillis", 0)),
                    count(p, "stepsSkipped"),
                    count(p, "graphCalls"),
                    count(p, "renames"),
                    count(p, "edits"),
                    count(p, "guardViolations")));
        }
        if (projects.size() > PROJECT_ROWS) {
            out.add(List.of("+" + (projects.size() - PROJECT_ROWS) + " more", "", "", "", "", "", ""));
        }
        return out;
    }

    /**
     * The time saved, marked as the estimate it is: {@code ~4m12s (estimate, 80% measured)}, the
     * share being the cache hits whose saving rests on a measured time.
     */
    static String saved(String row) {
        long saved = Jsonl.longValue(row, "savedMillis", 0);
        long hits = Jsonl.longValue(row, "estimatedSkips", 0);
        String measured = hits > 0
                ? ", " + HistoryCommand.pctString(Jsonl.longValue(row, "coveredSkips", 0), hits) + " measured"
                : "";
        return "~" + HistoryCommand.duration(saved) + " (estimate" + measured + ")";
    }

    private static String count(String row, String field) {
        return Long.toString(Jsonl.longValue(row, field, 0));
    }
}

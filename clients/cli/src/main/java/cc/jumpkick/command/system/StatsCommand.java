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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk stats}: what the cache, the code graph and the guards saved over a period, overall and
 * per project, added up by the engine from the build history and the agent sessions it keeps.
 * {@code -O jsonl} prints the same rows the dashboard's panel reads from {@code GET /api/stats}.
 */
public final class StatsCommand implements CliCommand {

    static final String DEFAULT_SINCE = "7d";

    /** {@code --since} for every run and action the engine keeps. */
    static final String ALL = "all";

    private static final Pattern DURATION = Pattern.compile("(\\d{1,6})([hdw])");

    private static final long HOUR_MILLIS = 3_600_000L;

    /** Projects the text table lists; the rest are in the total. */
    private static final int PROJECT_ROWS = 10;

    /** The per-project table's columns, in the order {@link #projectRows} fills them. */
    static final List<String> PROJECT_COLUMNS =
            List.of("Project", "Time", "Cached", "Code", "Refactorings", "Edits", "Guards");

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
                "<when>",
                "A duration back from now (12h, 7d, 2w), a date (2026-11-01) or " + ALL + " (default: " + DEFAULT_SINCE
                        + ").",
                "--since"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        String raw = in.value("since").orElse(DEFAULT_SINCE);
        @Nullable Period p = period(raw, Clock.SYSTEM.millis(), ZoneId.systemDefault());
        if (p == null) {
            CommandWedge.printFail(
                    "Stats", "--since takes a duration (12h, 7d, 2w), a date (2026-11-01) or " + ALL + ", not " + raw);
            return Exit.USAGE;
        }
        List<String> rows = EngineJournalReads.stats(EnginePaths.current(), p.since()).stream()
                .filter(l -> EngineProtocol.STATS.equals(EngineProtocol.typeOf(l)))
                .toList();
        if (CliOutput.jsonMode()) {
            for (String row : rows) JsonlShape.rowOf(EngineProtocol.STATS, row);
            return 0;
        }
        String period = p.text();
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
            for (String line : Table.render("By project", PROJECT_COLUMNS, projectRows(projects))) {
                CliOutput.out(line);
            }
        }
        return 0;
    }

    /**
     * The period's start, epoch millis (0 for every run kept), and how the output names it: {@code
     * in the last 7 days}.
     */
    record Period(long since, String text) {}

    /**
     * {@code --since}'s value read at {@code now}: a duration back from it in hours, days or weeks,
     * the start of a date in {@code zone}, or {@link #ALL}; null when it is none of them.
     */
    static @Nullable Period period(String raw, long now, ZoneId zone) {
        String v = raw.strip();
        if (ALL.equals(v)) return new Period(0, "in all the history kept");
        Matcher m = DURATION.matcher(v);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            if (n == 0) return null;
            String unit =
                    switch (m.group(2)) {
                        case "h" -> "hour";
                        case "d" -> "day";
                        default -> "week";
                    };
            long hours =
                    switch (unit) {
                        case "hour" -> 1;
                        case "day" -> 24;
                        default -> 168;
                    };
            return new Period(
                    Math.max(0, now - n * hours * HOUR_MILLIS),
                    "in the last " + (n == 1 ? unit : n + " " + unit + "s"));
        }
        try {
            LocalDate day = LocalDate.parse(v);
            return new Period(Math.max(0, day.atStartOfDay(zone).toInstant().toEpochMilli()), "since " + day);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean nothing(String row) {
        return Jsonl.longValue(row, "runs", 0) == 0
                && Jsonl.longValue(row, "graphCalls", 0) == 0
                && Jsonl.longValue(row, "refactorings", 0) == 0
                && Jsonl.longValue(row, "edits", 0) == 0;
    }

    /** The total, one figure per row. */
    static List<List<String>> totalRows(String row) {
        List<List<String>> out = new ArrayList<>();
        out.add(List.of("Build and test time avoided", saved(row)));
        out.add(List.of("Steps served from the cache", count(row, "stepsSkipped")));
        out.add(List.of("Test suites not re-run", count(row, "suitesSkipped")));
        out.add(List.of("Test classes not re-run", count(row, "testClassesNotRerun")));
        out.add(List.of("Code questions answered", count(row, "graphCalls")));
        out.add(List.of("Refactorings applied", count(row, "refactorings")));
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
                    count(p, "refactorings"),
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

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.EnsureFreshLock;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.DurationText;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.ExplainPlan;
import cc.jumpkick.runtime.TaskForecast;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code jk status} — project + machine build dashboard: engine vitals, project identity,
 * recent/forecast build timing, global aggregates, and cache footprint. Engine process details
 * live under {@code jk engine status}; this command is about the builds and the workspace.
 */
public final class StatusCommand implements CliCommand {

    /**
     * Label field width including the trailing colon (widest is {@code Total Build Count:}). Labels
     * are left-aligned and padded with dim dots to this width.
     */
    private static final int LABEL_W = 18;

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Show project, build, and cache status";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag("Only machine-wide totals and cache", "--global"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        boolean globalOnly = in.has("global");
        Path cwd = Path.of("").toAbsolutePath().normalize();
        EnginePaths.Paths paths = EnginePaths.current();

        // Collect under a live CommandWedge spinner; settle when ready (same line chrome).
        // JSON skips the wedge — structured metrics only.
        boolean live = !global.outputIsJson()
                && !global.quiet
                && !global.noProgress
                && BuildPlanConsole.isInteractiveTerminal();

        List<String> rows;
        Optional<EngineClient.Status> engine = Optional.empty();
        ProjectSnapshot project = null;
        Forecast forecast = null;
        String lastHistory = null;
        CacheSnapshot cache = null;

        // Fresh lock before forecast / module pins — never make the user run `jk lock` for status.
        if (!globalOnly && Files.isRegularFile(cwd.resolve(ManifestPaths.MANIFEST))) {
            int lockCode = EnsureFreshLock.ensure(cwd, JkDirs.cache(), global, "Status");
            if (lockCode != 0) return lockCode;
        }

        try (var analyzing = live ? CommandWedge.analyzingStdout("Status", "Analyzing status...") : null) {
            rows = EngineClient.metrics(paths, globalOnly ? null : cwd.toString()).stream()
                    .filter(l -> EngineProtocol.METRICS_ENTRY.equals(EngineProtocol.typeOf(l)))
                    .toList();

            if (global.outputIsJson()) {
                CliOutput.out("[" + String.join(",", rows) + "]");
                return 0;
            }

            engine = EngineClient.status(EnginePaths.activeSocket(paths));
            if (!globalOnly) {
                project = loadProject(cwd);
                if (project != null) {
                    lastHistory = findLastHistory(paths, cwd);
                    forecast = tryForecast(paths, cwd);
                }
            }
            cache = loadCacheSnapshot();
        }

        // ── Header: ≡ Status  JumpKick Engine v[focused]X.Y.Z[/] is running (pid [yellow]N[/]) ─
        // Prep lock / analyzing may already have opened the envelope; this is first chrome if not.
        JkWedge.menu("Status", RichText.parse(engineStatusMarkup(engine))).print();
        CliOutput.out("");

        if (!globalOnly) {
            printProjectSection(project, forecast, lastHistory);
            CliOutput.out("");
            printProjectBuildSection(project, rows, cwd, lastHistory, forecast);
            CliOutput.out("");
        }

        printGlobalBuildSection(rows);
        CliOutput.out("");
        printCacheSection(cache);
        return 0;
    }

    // ── sections ─────────────────────────────────────────────────────────────

    private static void printProjectSection(ProjectSnapshot project, Forecast forecast, String lastHistory) {
        if (project == null) {
            sectionHeader("Project", "(no jk.toml in this directory)");
            return;
        }
        sectionHeader("Project", project.coord);
        kv("Language", project.languageLine);
        kv("JDK", project.jdk);
        int modules = forecast != null ? forecast.moduleTotal : project.moduleCount;
        kv("Modules", Integer.toString(modules));
        int sources = forecast != null ? forecast.sourceCount : project.sourceCount;
        int tests = forecast != null ? forecast.testCount : project.testCount;
        // Prefer last history test totals when the journal recorded them.
        TestSummary lastTests = TestSummary.readCounts(lastHistory);
        if (lastTests != null) tests = (int) lastTests.total();
        kv("Sources", formatCount(sources));
        kv("Tests", formatCount(tests));
    }

    private static void printProjectBuildSection(
            ProjectSnapshot project, List<String> rows, Path cwd, String lastHistory, Forecast forecast) {
        String titleCoord = project != null ? project.coord : "—";
        sectionHeader("Project Build", titleCoord);

        InvAgg agg = aggregateProjectBuilds(rows, cwd.toString());
        long fullMs = -1;
        long lastMs = -1;
        long nextMs = -1;
        String modulesCached = "—";
        String artifactsCached = "—";

        if (lastHistory != null) {
            lastMs = Jsonl.longValue(lastHistory, "millis", -1);
            long est = Jsonl.longValue(lastHistory, "estimatedUncachedMillis", -1);
            if (est > 0) fullMs = est;
            long covered = Jsonl.longValue(lastHistory, "savedMillis", -1);
            // coveredSkips rides history-show only; list has saved + estimated.
            long skips = Jsonl.longValue(lastHistory, "coveredSkips", -1);
            if (skips < 0) {
                // Approximate artifact-level cache hits from the last run's skip coverage when
                // the list payload only has benefit totals (show has coveredSkips).
                skips = -1;
            }
            int modCount = (int) Jsonl.longValue(lastHistory, "moduleCount", -1);
            int failedMods = (int) Jsonl.longValue(lastHistory, "failedModules", -1);
            if (modCount > 0 && failedMods >= 0) {
                // History list lacks per-module cache flags; use forecast when available.
            }
        }
        if (fullMs < 0 && agg.okCount > 0) fullMs = agg.okMaxMillis;
        if (lastMs < 0 && agg.okCount > 0) lastMs = agg.okAvgMillis; // fallback

        if (forecast != null) {
            nextMs = forecast.etaMillis;
            if (forecast.moduleTotal > 0) {
                modulesCached = forecast.modulesCached
                        + "/"
                        + forecast.moduleTotal
                        + " ("
                        + pct(forecast.modulesCached, forecast.moduleTotal)
                        + "%)";
            }
            if (forecast.artifactsCached >= 0) {
                artifactsCached = formatCount(forecast.artifactsCached);
            }
        } else if (lastHistory != null) {
            int modCount = (int) Jsonl.longValue(lastHistory, "moduleCount", 0);
            if (modCount > 0) {
                // Without a forecast, we only know module count — not the cached fraction.
                modulesCached = "?/" + modCount;
            }
            long saved = Jsonl.longValue(lastHistory, "savedMillis", -1);
            long est = Jsonl.longValue(lastHistory, "estimatedUncachedMillis", -1);
            if (saved >= 0 && est > 0) {
                // Rough next estimate when we lack a live forecast: last residual work fraction.
                long residual = Math.max(0, est - saved);
                // Prefer residual over zero when the last run was fully cached.
                nextMs = residual;
            }
        }

        kv("Full Build Time", formatDuration(fullMs));
        kv("Last Build Time", formatDuration(lastMs));
        kv("Next Build Time", nextMs >= 0 ? "~" + formatDuration(nextMs) : "—");
        kv("Modules Cached", modulesCached);
        kv("Artifacts Cached", artifactsCached);
    }

    private static void printGlobalBuildSection(List<String> rows) {
        sectionHeader("Global Build", null);
        InvAgg g = aggregateGlobalBuilds(rows);
        if (g.okCount + g.failCount + g.cancelCount == 0) {
            kv("Avg Build Time", "—");
            kv("Min Build Time", "—");
            kv("Max Build Time", "—");
            kv("Total Build Count", "0");
            kv("Total Build Time", "—");
            return;
        }
        kv("Avg Build Time", formatDuration(g.okCount > 0 ? g.okAvgMillis : -1));
        kv("Min Build Time", formatDuration(g.okCount > 0 ? g.okMinMillis : -1));
        kv("Max Build Time", formatDuration(g.okCount > 0 ? g.okMaxMillis : -1));
        long total = g.okCount + g.failCount + g.cancelCount;
        StringBuilder outcomes = new StringBuilder(formatCount(total));
        outcomes.append(" (");
        outcomes.append(formatCount(g.okCount)).append(" ok");
        if (g.failCount > 0)
            outcomes.append(", ").append(formatCount(g.failCount)).append(" failed");
        if (g.cancelCount > 0)
            outcomes.append(", ").append(formatCount(g.cancelCount)).append(" cancelled");
        outcomes.append(")");
        kv("Total Build Count", outcomes.toString());
        long wall = g.okTotalMillis + g.failTotalMillis + g.cancelTotalMillis;
        kv("Total Build Time", wall > 0 ? formatDuration(wall) : "—");
    }

    /** Pre-collected cache footprint so the disk walk stays under the analyzing wedge. */
    private record CacheSnapshot(String sizeOnDisk, String casEntries, String actionsCached) {}

    private static CacheSnapshot loadCacheSnapshot() {
        Path root = JkDirs.cache();
        try {
            if (!Files.isDirectory(root)) {
                return new CacheSnapshot("—", "0", "0");
            }
            // Cache only. The artifact store lives under JK_STORE_DIR, survives a nuke, and has
            // its own report in `jk storage usage`; folding it in here made "Size on Disk" name a
            // number no cache command can act on. Shared inodes inside the root count once.
            CacheCommand.SectionStats s = CacheCommand.sectionStats(root);
            return new CacheSnapshot(
                    CacheCommand.fmtBytes(s.root().bytes()),
                    formatCount(s.cacheCas().files()),
                    formatCount(s.actionKeys().files()));
        } catch (IOException e) {
            return new CacheSnapshot("—", "—", "—");
        }
    }

    private static void printCacheSection(CacheSnapshot cache) {
        sectionHeader("Cache", null);
        CacheSnapshot c = cache != null ? cache : new CacheSnapshot("—", "—", "—");
        kv("Size on Disk", c.sizeOnDisk());
        kv("CAS Entries", c.casEntries());
        kv("Actions Cached", c.actionsCached());
    }

    // ── rendering helpers ────────────────────────────────────────────────────

    /**
     * Status chip tail: {@code JumpKick Engine v[focused]X[/] is running (pid [yellow]N[/])}.
     */
    static String engineStatusMessage(Optional<EngineClient.Status> engine) {
        return RichText.parse(engineStatusMarkup(engine)).render();
    }

    static String engineStatusMarkup(Optional<EngineClient.Status> engine) {
        String version = Jk.VERSION;
        if (engine.isEmpty()) {
            return "JumpKick Engine v[focused]" + version + "[/] is not running";
        }
        return "JumpKick Engine v[focused]"
                + version
                + "[/] is running (pid [yellow]"
                + engine.get().pid()
                + "[/])";
    }

    private static void sectionHeader(String title, String suffix) {
        Theme t = Theme.active();
        String bullet = Theme.colorize("●", t.blue());
        String head = Theme.colorize(title, t.brightWhite());
        if (suffix == null || suffix.isBlank()) {
            CliOutput.out(bullet + " " + head);
            return;
        }
        // "Project: coord" / "Project Build: coord" — suffix in standard coord colors when G:A[:V].
        CliOutput.out(bullet + " " + head + ":" + " " + styleCoord(suffix));
    }

    private static String styleCoord(String coord) {
        if (coord == null || coord.isBlank() || "—".equals(coord) || coord.startsWith("(")) {
            return Theme.colorize(coord == null ? "—" : coord, Theme.active().normalGray());
        }
        String[] parts = coord.split(":", 3);
        if (parts.length >= 3) return Coords.gav(parts[0], parts[1], parts[2]);
        if (parts.length == 2) return Coords.ga(parts[0], parts[1]);
        return Coords.shortName(coord);
    }

    /**
     * One detail row: {@link Theme#settled()} label (body foreground), bright-black
     * ({@link Theme#darkGray()}) dotted leader + colon, white value.
     *
     * <pre>
     * Language.........: Java 25
     * Total Build Count: 3 (3 ok)
     * </pre>
     */
    private static void kv(String label, String value) {
        Theme t = Theme.active();
        String field = dottedLabel(label, LABEL_W);
        // Split so the bare label is settled() and the dots+colon are bright black (darkGray).
        int labelLen = label.length();
        String name = field.substring(0, labelLen);
        String leader = field.substring(labelLen); // dots + ':'
        String val = value == null ? "—" : value;
        CliOutput.out(Theme.colorize(name, t.settled())
                + Theme.colorize(leader, t.darkGray())
                + " "
                + Theme.colorize(val, t.brightWhite()));
    }

    /**
     * Left-align {@code label} and pad with dots up to {@code width - 1}, then append {@code :}.
     * Example: {@code dottedLabel("JDK", 18)} → {@code "JDK..............:"}.
     */
    static String dottedLabel(String label, int width) {
        int dots = Math.max(0, width - label.length() - 1);
        return label + ".".repeat(dots) + ":";
    }

    // ── metrics aggregation ──────────────────────────────────────────────────

    /** Folded invocation stats for one kind across one or more metrics-entry rows. */
    static final class InvAgg {
        long okCount, failCount, cancelCount;
        long okTotalMillis, failTotalMillis, cancelTotalMillis;
        long okMinMillis = Long.MAX_VALUE, okMaxMillis;
        long okAvgMillis;
    }

    private static InvAgg aggregateGlobalBuilds(List<String> rows) {
        InvAgg a = new InvAgg();
        for (String r : rows) {
            if (!"global".equals(Jsonl.str(r, "scope"))) continue;
            if (!"build".equals(Jsonl.str(r, "kind"))) continue;
            fold(a, r);
        }
        finalizeAvg(a);
        return a;
    }

    private static InvAgg aggregateProjectBuilds(List<String> rows, String dir) {
        InvAgg a = new InvAgg();
        for (String r : rows) {
            if (!"project".equals(Jsonl.str(r, "scope"))) continue;
            if (!"build".equals(Jsonl.str(r, "kind"))) continue;
            String d = Jsonl.str(r, "dir");
            if (d == null || !sameBaseDir(dir, d)) continue;
            fold(a, r);
        }
        finalizeAvg(a);
        return a;
    }

    private static void fold(InvAgg a, String r) {
        long ok = Jsonl.longValue(r, "okCount", 0);
        long fail = Jsonl.longValue(r, "failCount", 0);
        long cancel = Jsonl.longValue(r, "cancelledCount", 0);
        a.okCount += ok;
        a.failCount += fail;
        a.cancelCount += cancel;
        a.okTotalMillis += Jsonl.longValue(r, "okTotalMillis", 0);
        a.failTotalMillis += Jsonl.longValue(r, "failTotalMillis", 0);
        a.cancelTotalMillis += Jsonl.longValue(r, "cancelledTotalMillis", 0);
        if (ok > 0) {
            long min = Jsonl.longValue(r, "okMinMillis", -1);
            long max = Jsonl.longValue(r, "okMaxMillis", -1);
            if (min >= 0) a.okMinMillis = Math.min(a.okMinMillis, min);
            if (max >= 0) a.okMaxMillis = Math.max(a.okMaxMillis, max);
        }
    }

    private static void finalizeAvg(InvAgg a) {
        if (a.okMinMillis == Long.MAX_VALUE) a.okMinMillis = 0;
        a.okAvgMillis = a.okCount == 0 ? 0 : a.okTotalMillis / a.okCount;
    }

    /** Match {@link cc.jumpkick.runtime.BuildMetrics#sameBaseDir} without depending on engine. */
    static boolean sameBaseDir(String dir, String candidate) {
        if (dir == null || candidate == null) return false;
        if (dir.equals(candidate)) return true;
        return dir.equals(baseDir(candidate));
    }

    static String baseDir(String dir) {
        if (dir == null) return "";
        int i = dir.lastIndexOf("#d");
        if (i <= 0) return dir;
        for (int j = i + 2; j < dir.length(); j++) {
            if (!Character.isDigit(dir.charAt(j))) return dir;
        }
        return i + 2 == dir.length() ? dir : dir.substring(0, i);
    }

    // ── project / forecast loading ───────────────────────────────────────────

    record ProjectSnapshot(
            String coord, String languageLine, String jdk, int moduleCount, int sourceCount, int testCount) {}

    record Forecast(
            long etaMillis, int moduleTotal, int modulesCached, int sourceCount, int testCount, int artifactsCached) {}

    private static ProjectSnapshot loadProject(Path cwd) {
        Path buildFile = cwd.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(buildFile)) return null;
        try {
            var info = ProjectInfos.orNull(cwd, true);
            if (info == null) {
                return new ProjectSnapshot(cwd.getFileName().toString(), "—", "—", 0, 0, 0);
            }
            String group = info.group() == null ? "" : info.group();
            String name = info.name() == null || info.name().isBlank()
                    ? cwd.getFileName().toString()
                    : info.name();
            String version = info.version() == null ? "" : info.version();
            String coord = group.isEmpty()
                    ? name + (version.isEmpty() ? "" : ":" + version)
                    : group + ":" + name + (version.isEmpty() ? "" : ":" + version);

            List<String> langs = new ArrayList<>();
            if (info.javaRelease() > 0) langs.add("Java " + info.javaRelease());
            if (info.kotlin() && !info.kotlinVersion().isBlank()) langs.add("Kotlin " + info.kotlinVersion());
            else if (info.kotlin()) langs.add("Kotlin");
            if (info.groovy() && !info.groovyVersion().isBlank()) langs.add("Groovy " + info.groovyVersion());
            else if (info.groovy()) langs.add("Groovy");
            if (langs.isEmpty()) langs.add("Java");

            String jdk = info.jdk();
            if (jdk == null || jdk.isBlank()) jdk = info.lockJdk();
            if (jdk == null || jdk.isBlank()) jdk = "—";

            int modules = info.moduleDirs().isEmpty() ? 1 : info.moduleDirs().size();
            return new ProjectSnapshot(
                    coord, String.join(", ", langs), jdk, modules, info.sourceCount(), info.testCount());
        } catch (Exception e) {
            return new ProjectSnapshot(cwd.getFileName().toString(), "—", "—", 0, 0, 0);
        }
    }

    private static String findLastHistory(EnginePaths.Paths paths, Path cwd) {
        try {
            String base = cwd.toString();
            List<String> lines = EngineClient.historyList(paths, 50);
            for (String line : lines) {
                if (!EngineProtocol.HISTORY_ENTRY.equals(EngineProtocol.typeOf(line))) continue;
                if (Jsonl.bool(line, "running", false)) continue;
                String dir = Jsonl.str(line, "dir");
                if (dir == null || !sameBaseDir(base, dir)) continue;
                if (!"build".equals(Jsonl.str(line, "kind"))) continue;
                return line;
            }
        } catch (Exception ignored) {
            // history is advisory
        }
        return null;
    }

    private static Forecast tryForecast(EnginePaths.Paths paths, Path cwd) {
        try {
            long[] etaOut = new long[1];
            ExplainPlan plan = EngineClient.explain(
                    paths,
                    new EngineRequests.ExplainRequest(
                            cwd, JkDirs.cache(), 1, false, null, null, true, false, false, false),
                    etaOut);
            if (plan == null || plan.modules() == null) return null;
            int total = plan.modules().size();
            int cached = 0;
            int sources = 0, tests = 0, artifacts = 0;
            for (TaskForecast.Module m : plan.modules()) {
                sources += m.sourceCount();
                tests += m.testCount();
                boolean allCached = !m.steps().isEmpty() && m.steps().stream().allMatch(TaskForecast.Task::cached);
                if (allCached) cached++;
                for (TaskForecast.Task s : m.steps()) {
                    if (s.cached()) artifacts++;
                }
            }
            return new Forecast(etaOut[0], total, cached, sources, tests, artifacts);
        } catch (Exception e) {
            return null;
        }
    }

    // ── formatting ───────────────────────────────────────────────────────────

    /** Status-table duration, zero components omitted; {@code —} when negative. */
    static String formatDuration(long millis) {
        return DurationText.omitZero(millis);
    }

    private static String formatCount(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static int pct(int part, int whole) {
        return whole <= 0 ? 0 : (int) Math.round(100.0 * part / whole);
    }
}

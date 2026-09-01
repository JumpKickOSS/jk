// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.EnsureFreshLock;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ParallelTestsOpts;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.DurationText;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Coord;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Icon;
import cc.jumpkick.cli.tui.Pill;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.cli.tui.Spinner;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.cli.tui.Tree;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.ModuleGraphAck;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.runtime.ExplainPlan;
import cc.jumpkick.runtime.TaskForecast;
import cc.jumpkick.util.HostCalibrationStatus;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code jk explain} — forecast of what a build would run (cache hit/miss per module/stage). Prefer
 * this over Gradle build scans for "why will this rebuild?" questions. Hidden aliases: {@code plan},
 * {@code why-rebuilt}. Refreshes a stale/missing lock first (same as {@code jk build}) so the ETA
 * matches the build countdown. Default output is a build graph (tasks rolled up by {@link
 * BuildStage}) plus a summary table; {@code --verbose} expands every task; {@code --run} executes
 * the plan.
 */
public final class ExplainCommand implements CliCommand {

    @Override
    public String name() {
        return "explain";
    }

    // Hidden aliases plan / why-rebuilt live in Jk.VERB_ALIASES (exact-token rewrite before
    // dispatch) — never here: dispatcher aliases join the unique-prefix candidate set and would
    // make `jk pl` a prefix of plan and `jk wh` ambiguous with why.

    @Override
    public String description() {
        return "Forecast rebuilds (cache hit/miss per stage)";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<Opt>();
        opts.add(Opt.flag("Build the plan instead of printing it", "--run"));
        opts.addAll(ParallelTestsOpts.options());
        // The plan-affecting options `jk build` accepts — forecasting `jk build <flags>`
        // means feeding the same inputs to the shared estimate (and, with --run, to build).
        // Module concurrency: global -j/--jobs.
        opts.add(Opt.value("<name>", "Forecast with a build profile", "--profile"));
        opts.add(Opt.value("<N>", "Test JVMs per module (0=auto)", "-w", "--workers"));
        opts.add(CommonOpts.skipTests());
        // -r/--redo is a global flag (same as `jk build --redo`); see GlobalOptions.
        opts.add(CommonOpts.jdksDir());
        opts.add(CommonOpts.cacheDir());
        opts.addAll(CommonOpts.moduleSelection());
        opts.add(Opt.value("<fmt>", "Emit module DAG as dot or mermaid", "--graph"));
        opts.add(Opt.value("<file>", "Write --graph output to this file", "--graph-out"));
        return opts;
    }

    /** The {@code --graph} format ({@code dot} / {@code mermaid}), or null when the flag is absent. */
    private static String graphFormat(Invocation in) {
        return in.value("graph").filter(s -> !s.isBlank()).orElse(null);
    }

    /** The {@code --graph-out} destination, or empty when the graph goes to stdout instead. */
    private static Optional<String> graphOut(Invocation in) {
        return in.value("graph-out").filter(s -> !s.isBlank());
    }

    /** {@code jk explain --graph dot} pipes a graph source into {@code dot} or an editor preview. */
    @Override
    public boolean scriptMode(Invocation in) {
        // With --graph-out the graph lands in a file and stdout carries only the human settle.
        return graphFormat(in) != null && graphOut(in).isEmpty();
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        Path startDir = global.workingDir();
        var proj = ProjectContext.require(startDir, "explain").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path buildFile = proj.buildFile();
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        String graphFmt = graphFormat(in);
        boolean hasGraph = graphFmt != null;
        String modulesSpec = in.value("modules").orElse(null);
        String affectedSinceEarly = in.value("affected-since").orElse(null);
        boolean affectedWip = in.isSet("affected");
        if (ModuleSelectors.bothSelectors(affectedWip, affectedSinceEarly)) {
            CommandWedge.printFail("Explain", ModuleSelectors.BOTH_MESSAGE);
            return Exit.CONFIG;
        }
        var peek = ProjectInfos.orNull(startDir);
        CwdModuleScope.Resolved cwdScope = CwdModuleScope.resolve(startDir, modulesSpec, peek);
        if (cwdScope.inferredFromCwd()) modulesSpec = cwdScope.modulesSpec();
        Path graphDir = cwdScope.workspaceMember() ? cwdScope.workspaceRoot() : startDir;
        if (in.isSet("run") && hasGraph) {
            CommandWedge.printFail("Explain", "cannot combine --run with --graph (pick one)");
            return Exit.USAGE;
        }
        if (in.isSet("run")) {
            return new BuildCommand().run(in); // forwards --cache-dir; build options default
        }

        // Module DAG export is engine-hosted. Honor --modules / --affected-since.
        // On single-project layouts, selectors only validate; the graph is one node.
        if (hasGraph) {
            // Resolve a relative --graph-out against the INVOCATION dir before graphDir is
            // rehomed to the workspace root for member cwds (JK-2167).
            String graphOutPath = graphOut(in)
                    .map(o -> startDir.resolve(o).toAbsolutePath().normalize().toString())
                    .orElse(null);
            return emitModuleGraph(graphDir, graphFmt, modulesSpec, affectedSinceEarly, graphOutPath);
        }

        // HARD INVARIANT: bare `jk explain` uses the exact same defaults as bare `jk build`
        // (-w 0 = auto, -j from jobsEffective, parallel-tests default on). The estimate must
        // match the live countdown bit-for-bit — docs/perf/progress-contract.md.
        boolean parallelTests = ParallelTestsOpts.enabled(in);
        int jobs = global.jobsEffective();
        boolean serial = jobs == 1;
        // 0 = auto within-module test JVMs — same as BuildCommand when -w is omitted.
        int workers = in.value("workers").map(Integer::parseInt).orElse(0);
        boolean skipTests = in.isSet("skip-tests");
        // Global --redo / --force: forecast full work + rebuild ETA priors.
        boolean rebuild = global.rebuild || global.force;
        String profile = in.value("profile").orElse(null);
        Path jdksDir = CommonOpts.jdksDirValue(in);
        String affectedSince = affectedSinceEarly;

        // Client-side module filter listing (before engine forecast) when selectors are set.
        if (ModuleSelectors.anySelector(modulesSpec, affectedSince, affectedWip)) {
            try {
                var selected = ProjectInfos.orError(graphDir, modulesSpec, affectedSince, affectedWip);
                if (selected.error() != null && !selected.error().isBlank()) {
                    CommandWedge.printFail("Explain", selected.error());
                    return Exit.CONFIG;
                }
                if (selected.moduleDirs().isEmpty()) return 0;
                CliOutput.out("Selected modules (" + selected.moduleDirs().size() + "):");
                for (String raw : selected.moduleDirs()) {
                    Path m = Path.of(raw);
                    Path rel;
                    try {
                        rel = graphDir.toAbsolutePath().normalize().relativize(m);
                    } catch (IllegalArgumentException e) {
                        rel = m;
                    }
                    CliOutput.out("  " + (rel.toString().isEmpty() ? "." : rel));
                }
            } catch (Exception e) {
                CommandWedge.printFail("Explain", "module selection failed: " + e.getMessage());
                return Exit.CONFIG;
            }
        }

        // Live prep wedge: Locking versions… → Calculating build plan… (or Calibrating host…),
        // then clear and print the settled Build Plan tree.
        boolean livePrep = EnsureFreshLock.isInteractiveAuto(global) && !global.outputIsJson();
        boolean needsLock = EnsureFreshLock.needsRefresh(startDir);
        boolean needsCalibrate = HostCalibrationStatus.needsBootstrapProbe();
        String prepMsg =
                needsLock ? "Locking versions…" : needsCalibrate ? "Calibrating host…" : "Calculating build plan…";

        ExplainPlan plan;
        long etaMillis;
        long fullEtaMillis;
        // [0] = current remaining ETA; [1] = full-rebuild ETA (effort denominator).
        long[] etaOut = new long[2];
        try (Spinner prep = livePrep ? CommandWedge.analyzingStdout("Explain", prepMsg) : null) {
            // Same starting lock as `jk build` so the dirty plan and ETA match the countdown.
            // Pass the prep spinner so a freshen failure settles it before writing stderr.
            if (needsLock) {
                if (prep != null) prep.update("Locking versions…");
                int lockCode = EnsureFreshLock.ensure(startDir, cache, global, "Explain", prep, false);
                if (lockCode != 0) return lockCode;
            }
            if (prep != null) {
                prep.update(needsCalibrate ? "Calibrating host…" : "Calculating build plan…");
            }
            // Forecast via engine: graph + per-step plan + schedule-aware ETA.
            plan = EngineClient.explain(
                    EnginePaths.current(),
                    new EngineRequests.ExplainRequest(
                            startDir,
                            cache,
                            workers,
                            skipTests,
                            profile,
                            jdksDir,
                            serial,
                            parallelTests,
                            global.verbose,
                            rebuild,
                            jobs),
                    etaOut);
        }
        etaMillis = etaOut[0];
        fullEtaMillis = etaOut[1];

        if (plan.hasErrors()) {
            for (String err : plan.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            return Exit.CONFIG;
        }

        Theme t = Theme.active();
        boolean ansi = t.isAnsi();

        // Forecast every module's full step plan (compile → test → package),
        // truthfully — see TaskForecaster.
        List<TaskForecast.Module> modules = plan.modules();
        boolean verbose = in.isSet("verbose");
        boolean fullyCached = !modules.isEmpty() && modules.stream().noneMatch(TaskForecast.Module::dirty);
        String coord = BuildCommand.buildTarget(buildFile, startDir);

        buildGraph(coord, modules, verbose, t, ansi).print();

        // Summary table: totals vs rebuild effort (ETA/full-ETA) + countdown seed.
        CliOutput.out("");
        for (String line : renderSummaryTable(modules, etaMillis, fullEtaMillis, fullyCached, t, ansi)) {
            CliOutput.out(line);
        }
        return 0;
    }

    /**
     * Build Graph: wedge title, {@code ● group:artifact} root, Fully Cached / Rebuild section
     * pills, Rebuild modules as coord-name (bold bright-cyan) text (verbose Fully Cached still uses
     * branded pills), hanging stage-chain (or {@code --verbose} step children).
     */
    static Tree buildGraph(
            String rootCoord, List<TaskForecast.Module> modules, boolean verbose, Theme t, boolean ansi) {
        Tree.Node root = Tree.node(Icon.pulse(), Coord.module(rootCoord).text());

        List<TaskForecast.Module> cached = new ArrayList<>();
        List<TaskForecast.Module> dirty = new ArrayList<>();
        for (TaskForecast.Module m : modules) {
            (m.dirty() ? dirty : cached).add(m);
        }

        if (!cached.isEmpty()) {
            int n = cached.size();
            String note = n + (n == 1 ? " module is fresh" : " modules are fresh");
            Tree.Node section = Tree.node(Pill.of("Fully Cached"), note);
            if (verbose) {
                for (TaskForecast.Module m : cached) {
                    section.child(moduleNode(m, true, t, ansi));
                }
            } else {
                List<String> names = new ArrayList<>(cached.size());
                for (TaskForecast.Module m : cached) names.add(shortName(m.coord()));
                List<List<String>> chunks = chunkNames(names, CACHED_NAMES_PER_LINE);
                var lines = new ArrayList<RichText>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    lines.add(RichText.ansi(renderCachedNameLine(chunks.get(i), i < chunks.size() - 1, t, ansi)));
                }
                section.body(lines);
            }
            root.child(section);
        }
        if (!dirty.isEmpty()) {
            int n = dirty.size();
            String note = n + (n == 1 ? " module is dirty" : " modules are dirty");
            Tree.Node section = Tree.node(Pill.of("Rebuild"), note);
            for (TaskForecast.Module m : dirty) {
                section.child(moduleNode(m, verbose, t, ansi));
            }
            root.child(section);
        }
        return new Tree("Build Graph").gap(Tree.Gap.CHILDREN).root(root);
    }

    /**
     * One rebuild (or verbose cached) module: Rebuild rows use {@code coord-name} (bold
     * bright-cyan); verbose Fully Cached rows keep the branded name pill. Stage chain or step
     * children hang below.
     */
    private static Tree.Node moduleNode(TaskForecast.Module m, boolean verbose, Theme t, boolean ansi) {
        String name = shortName(m.coord());
        Tree.Node node = m.dirty()
                ? Tree.node(RichText.parse("[coord-name]" + RichText.escape(name) + "[/]"))
                : Tree.node(Pill.branded(name));
        if (verbose) {
            List<TaskForecast.Task> ph = m.steps();
            int nameCol = 0;
            for (TaskForecast.Task p : ph) {
                nameCol = Math.max(nameCol, p.name().length());
            }
            nameCol += STEP_NAME_DOT_GAP;
            int commandCol = 0;
            for (TaskForecast.Task p : ph) {
                if (!p.cached()) commandCol = Math.max(commandCol, commandWidth(p.text()));
            }
            for (TaskForecast.Task p : ph) {
                String step = formatStepName(p.name(), nameCol, t, ansi) + renderStatus(p, commandCol, t, ansi);
                node.child(Tree.node(RichText.ansi(step)));
            }
            return node;
        }
        if (m.dirty()) {
            String chain = renderStageChain(m, t, ansi);
            if (!chain.isEmpty()) node.body(RichText.ansi(chain));
        }
        return node;
    }

    /**
     * ETA value only ({@code ~8s} / {@code <1s} / {@code not yet measured}) — same authority as
     * {@code jk build}'s countdown seed. Never mask a multi-minute eta behind Fully Cached / {@code
     * <1s}.
     */
    static String buildTimeEstimateValue(long etaMillis, boolean fullyCached) {
        if (etaMillis <= 0) return fullyCached ? "<1s" : "not yet measured";
        if (etaMillis < 1000) return "<1s";
        return "~" + DurationText.coarseFloor(etaMillis);
    }

    /**
     * Extra bright-black dots after every step name (including the longest) so the {@code □}/{@code
     * ✓} column never butts up against the name.
     */
    private static final int STEP_NAME_DOT_GAP = 2;

    /** Fixed columns in the Fully Cached name list (no terminal-width wrap). */
    private static final int CACHED_NAMES_PER_LINE = 4;

    /**
     * Roll material tasks up into a stage chain: {@code ✓ Compile › □ Test ~28 tests › □
     * Package}. Stages follow {@link BuildStage} pipeline order; bookkeeping-only steps are
     * omitted so stamp/resolve noise never appears.
     */
    static String renderStageChain(TaskForecast.Module m, Theme t, boolean ansi) {
        Map<BuildStage, List<TaskForecast.Task>> byStage = new LinkedHashMap<>();
        for (TaskForecast.Task step : m.steps()) {
            if (!TaskForecast.Module.isMaterialWork(step.name())) continue;
            BuildStage stage = BuildStage.ofTaskName(step.name());
            byStage.computeIfAbsent(stage, _ -> new ArrayList<>()).add(step);
        }
        if (byStage.isEmpty()) return "";

        List<BuildStage> order = byStage.keySet().stream()
                .sorted(Comparator.comparingInt(
                                (BuildStage s) -> s == BuildStage.OTHER ? Integer.MAX_VALUE : s.pipelineOrder())
                        .thenComparing(BuildStage::wireName))
                .toList();

        String sep = ansi ? Theme.colorize(" › ", t.darkGray()) : " > ";
        StringBuilder sb = new StringBuilder();
        for (BuildStage stage : order) {
            if (!sb.isEmpty()) sb.append(sep);
            List<TaskForecast.Task> steps = byStage.get(stage);
            boolean dirty = steps.stream().anyMatch(s -> !s.cached());
            sb.append(renderStageToken(stage, dirty, stageDetail(stage, dirty, m), t, ansi));
        }
        return sb.toString();
    }

    /** One stage token: {@code ✓ Compile} (green) or {@code □ Test ~28 tests} (blue + dim detail). */
    private static String renderStageToken(BuildStage stage, boolean dirty, String detail, Theme t, boolean ansi) {
        String glyph = dirty ? Glyphs.PENDING : Glyphs.CHECK;
        String label = stage.displayName();
        if (!ansi) {
            String plain = (dirty ? Glyphs.PENDING_PLAIN : Glyphs.CHECK_PLAIN) + " " + label;
            return detail == null || detail.isEmpty() ? plain : plain + " " + detail;
        }
        var style = dirty ? t.blue() : t.success();
        String head = Theme.colorize(glyph + " " + label, style);
        if (detail == null || detail.isEmpty()) return head;
        return head + " " + Theme.colorize(detail, t.darkGray());
    }

    /**
     * Short detail next to a dirty stage — source/test counts for Compile/Test; nothing for
     * package/native/image (the stage name is enough).
     */
    private static String stageDetail(BuildStage stage, boolean dirty, TaskForecast.Module m) {
        if (!dirty) return null;
        return switch (stage) {
            case COMPILE -> {
                // An incremental recompile must read as one: the forecast step text carries the
                // real changed count ("3 sources changed"); the module total would overstate the
                // work.
                int changed = changedSourceCount(m);
                if (changed >= 0) yield fmtCount(changed, "source changed", "sources changed");
                yield m.sourceCount() > 0 ? fmtCount(m.sourceCount(), "source", "sources") : null;
            }
            case TEST -> m.testCount() > 0 ? "~" + fmtCount(m.testCount(), "test", "tests") : null;
            default -> null;
        };
    }

    /** {@code "N source(s) changed"} from {@code JavaCompile}, digit-guarded. */
    private static final Pattern CHANGED_SOURCES = Pattern.compile("(?<!\\d)(\\d+) sources? changed");

    /**
     * Changed-source count summed over the module's non-cached compile steps, or {@code -1} when
     * no step states one (full compile, no incremental state).
     */
    static int changedSourceCount(TaskForecast.Module m) {
        int total = -1;
        for (TaskForecast.Task s : m.steps()) {
            if (s.cached() || BuildStage.ofTaskName(s.name()) != BuildStage.COMPILE) continue;
            var matcher = CHANGED_SOURCES.matcher(s.text() == null ? "" : s.text());
            while (matcher.find()) {
                total = Math.max(0, total) + Integer.parseInt(matcher.group(1));
            }
        }
        return total;
    }

    /** True when the module plan includes a material native stage task. */
    static boolean producesNative(TaskForecast.Module m) {
        return m.steps().stream()
                .anyMatch(s -> TaskForecast.Module.isMaterialWork(s.name())
                        && BuildStage.ofTaskName(s.name()) == BuildStage.NATIVE);
    }

    /**
     * Rebuild effort as a percent of a full rebuild's schedule-aware ETA: {@code remaining / full}.
     * Example: 1.5m remaining against a 3m full rebuild → 50%. Capped at 100; 0 when nothing to do
     * or the seed is still unmeasured.
     */
    static int rebuildEffortPct(long etaMillis, long fullEtaMillis) {
        if (etaMillis <= 0) return 0;
        if (fullEtaMillis <= 0) return 100;
        if (etaMillis >= fullEtaMillis) return 100;
        return (int) Math.round(100.0 * etaMillis / fullEtaMillis);
    }

    /**
     * Summary table under the build graph: Plan Item / Total / Rebuild / Delta, plus rebuild-effort
     * (time-weighted) and ETA footer rows.
     */
    static List<String> renderSummaryTable(
            List<TaskForecast.Module> modules,
            long etaMillis,
            long fullEtaMillis,
            boolean fullyCached,
            Theme t,
            boolean ansi) {
        int totalModules = modules.size();
        int totalSources =
                modules.stream().mapToInt(TaskForecast.Module::sourceCount).sum();
        int totalTests =
                modules.stream().mapToInt(TaskForecast.Module::testCount).sum();
        int totalJars =
                (int) modules.stream().filter(TaskForecast.Module::producesJar).count();
        int totalNatives =
                (int) modules.stream().filter(ExplainCommand::producesNative).count();
        int totalImages = (int)
                modules.stream().filter(TaskForecast.Module::producesImage).count();

        int dirtyModules =
                (int) modules.stream().filter(TaskForecast.Module::dirty).count();
        int dirtySources = modules.stream()
                .filter(TaskForecast.Module::dirty)
                .mapToInt(TaskForecast.Module::sourceCount)
                .sum();
        int dirtyTests = modules.stream()
                .filter(TaskForecast.Module::dirty)
                .mapToInt(TaskForecast.Module::testCount)
                .sum();
        int dirtyJars =
                (int) modules.stream().filter(m -> m.dirty() && m.producesJar()).count();
        int dirtyNatives = (int)
                modules.stream().filter(m -> m.dirty() && producesNative(m)).count();
        int dirtyImages = (int)
                modules.stream().filter(m -> m.dirty() && m.producesImage()).count();

        // Rows: label, total cell text, rebuild count, total count (for per-item delta only).
        record PlanRow(String item, String totalCell, int rebuild, int total) {}
        List<PlanRow> planRows = new ArrayList<>();
        planRows.add(
                new PlanRow("Modules", boldNum(totalModules, t, ansi) + " in workspace", dirtyModules, totalModules));
        planRows.add(new PlanRow("Sources", boldNum(totalSources, t, ansi) + " files", dirtySources, totalSources));
        planRows.add(new PlanRow("Tests", boldNum(totalTests, t, ansi) + " methods", dirtyTests, totalTests));
        if (totalJars > 0) {
            planRows.add(new PlanRow(
                    "Packages",
                    boldNum(totalJars, t, ansi) + (totalJars == 1 ? " jar" : " jars"),
                    dirtyJars,
                    totalJars));
        }
        if (totalNatives > 0) {
            planRows.add(new PlanRow(
                    "Native Bins",
                    boldNum(totalNatives, t, ansi) + (totalNatives == 1 ? " executable" : " executables"),
                    dirtyNatives,
                    totalNatives));
        }
        if (totalImages > 0) {
            planRows.add(new PlanRow(
                    "OCI Images",
                    boldNum(totalImages, t, ansi) + (totalImages == 1 ? " container img" : " container imgs"),
                    dirtyImages,
                    totalImages));
        }

        Table table = new Table("Build Plan").columns("Plan Item", "Total", "Rebuild", "Delta");
        for (PlanRow r : planRows) {
            table.row(
                    RichText.plain(r.item()),
                    RichText.ansi(r.totalCell()),
                    RichText.ansi(colorRebuild(r.rebuild(), t, ansi)),
                    RichText.ansi(colorDelta(pctValue(r.rebuild(), r.total()), t, ansi)));
        }
        int effortPct = rebuildEffortPct(etaMillis, fullEtaMillis);
        table.append(
                new Table("")
                        .columns("Label", "Value")
                        .row(RichText.plain("Total rebuild effort"), RichText.ansi(colorDelta(effortPct, t, ansi)))
                        .row(
                                RichText.plain("Build time estimate"),
                                RichText.ansi(
                                        Theme.colorize(buildTimeEstimateValue(etaMillis, fullyCached), t.warning()))),
                Table.Append.SECTION);
        return table.render(RenderContext.current().withAnsi(ansi));
    }

    private static String boldNum(int n, Theme t, boolean ansi) {
        String s = String.format("%,d", n);
        return ansi ? Theme.colorize(s, t.brightWhite().bold()) : s;
    }

    private static String colorRebuild(int n, Theme t, boolean ansi) {
        String s = String.format("%,d", n);
        if (!ansi) return s;
        return n > 0 ? Theme.colorize(s, t.blue()) : Theme.colorize(s, t.darkGray());
    }

    private static String colorDelta(int pct, Theme t, boolean ansi) {
        String s = pct + "%";
        // All plan percentages are bold white; only the build-time estimate stays yellow.
        return ansi ? Theme.colorize(s, t.brightWhite().bold()) : s;
    }

    private static int pctValue(int part, int whole) {
        if (whole <= 0) return 0;
        return part * 100 / whole;
    }

    /**
     * Step name in bright white, right-padded with bright-black {@code .} to {@code width} so the
     * status glyph lines up across steps. Non-ANSI: plain dots, no color.
     */
    static String formatStepName(String name, int width, Theme t, boolean ansi) {
        int pad = Math.max(0, width - name.length());
        String dots = pad == 0 ? "" : ".".repeat(pad);
        if (!ansi) return name + dots;
        return Theme.colorize(name, t.brightWhite()) + Theme.colorize(dots, t.darkGray());
    }

    /** Visible width of a step command — the text before {@code " · "} (or the whole text). */
    private static int commandWidth(String text) {
        int sep = text.indexOf(" · ");
        return (sep < 0 ? text : text.substring(0, sep)).length();
    }

    /** The artifact half of a {@code group:artifact} coordinate. */
    private static String shortName(String coord) {
        int c = coord.indexOf(':');
        return c < 0 ? coord : coord.substring(c + 1);
    }

    /**
     * One Fully Cached line: {@code ✓ name, ✓ name, …} — green check, green+strikethrough name,
     * dim comma. Trailing comma when {@code trailingComma} (more lines follow).
     */
    static String renderCachedNameLine(List<String> names, boolean trailingComma, Theme t, boolean ansi) {
        if (names.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String sep = ansi ? Theme.colorize(", ", t.darkGray()) : ", ";
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(renderCachedModuleToken(names.get(i), t, ansi));
        }
        if (trailingComma) sb.append(ansi ? Theme.colorize(",", t.darkGray()) : ",");
        return sb.toString();
    }

    /** {@code ✓ name} — green check; green strikethrough name (done / fresh). */
    static String renderCachedModuleToken(String name, Theme t, boolean ansi) {
        if (!ansi) return Glyphs.CHECK_PLAIN + " " + name;
        return Theme.colorize(Glyphs.CHECK, t.success())
                + " "
                + Theme.colorize(name, t.success().crossedOut());
    }

    /** Pack {@code names} into fixed-size chunks (last chunk may be shorter). */
    static List<List<String>> chunkNames(List<String> names, int perLine) {
        int n = Math.max(1, perLine);
        List<List<String>> lines = new ArrayList<>();
        for (int i = 0; i < names.size(); i += n) {
            lines.add(List.copyOf(names.subList(i, Math.min(i + n, names.size()))));
        }
        return lines;
    }

    /**
     * A step's status: {@code ✓ cached <key> · detail} (green) when cached, otherwise {@code □
     * <command> · <detail>} with the command in yellow (padded to {@code commandCol} so the {@code ·} lines up
     * across the module's steps), the {@code ·} bright-black, and the trailing detail in italic.
     * When {@code !ansi}: {@code (cached)} / {@code [run]} ASCII equivalents, no color.
     */
    private static String renderStatus(TaskForecast.Task p, int commandCol, Theme t, boolean ansi) {
        if (p.cached()) {
            if (!ansi) {
                StringBuilder s = new StringBuilder("(cached)");
                if (p.key() != null) s.append(' ').append(p.key());
                if (p.text() != null && !p.text().isEmpty()) s.append(' ').append(p.text());
                return s.toString();
            }
            StringBuilder s = new StringBuilder(Theme.colorize("✓ cached", t.success()));
            if (p.key() != null) s.append(' ').append(Theme.colorize(p.key(), t.path()));
            if (p.text() != null && !p.text().isEmpty())
                s.append(' ').append(Theme.colorize(p.text(), t.darkGray().italic()));
            return s.toString();
        }
        String text = p.text();
        int sep = text.indexOf(" · ");
        String command = sep < 0 ? text : text.substring(0, sep);
        String detail = sep < 0 ? null : text.substring(sep + 3);
        if (!ansi) {
            StringBuilder s = new StringBuilder("[run] ").append(padRight(command, commandCol));
            if (detail != null) s.append(" - ").append(detail);
            return s.toString();
        }
        StringBuilder s = new StringBuilder(Theme.colorize("□ ", t.brightWhite()))
                .append(Theme.colorize(padRight(command, commandCol), t.warning()));
        if (detail != null) {
            s.append(' ')
                    .append(Theme.colorize("·", t.darkGray()))
                    .append(' ')
                    .append(Theme.colorize(detail, t.brightWhite().italic()));
        }
        return s.toString();
    }

    private static String fmtCount(int n, String singular, String plural) {
        return String.format("%,d", n) + " " + (n == 1 ? singular : plural);
    }

    private static String padRight(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    /**
     * {@code jk explain --graph dot|mermaid} — module dependency DAG (engine-hosted).
     */
    private static int emitModuleGraph(
            Path startDir, String format, String modulesSpec, String affectedSince, String outputPath)
            throws Exception {
        ModuleGraphAck ack;
        try {
            ack = EngineClient.moduleGraph(EnginePaths.current(), startDir, format, modulesSpec, affectedSince);
        } catch (Exception e) {
            CommandWedge.printFail("Explain", String.valueOf(e.getMessage()));
            return Exit.CONFIG;
        }
        if (ack.error() != null) {
            CommandWedge.printFail("Explain", ack.error());
            return Exit.CONFIG;
        }
        String graph = ack.graph();
        Path root = startDir.toAbsolutePath().normalize();
        if (outputPath != null && !outputPath.isBlank()) {
            Path out = Path.of(outputPath);
            if (!out.isAbsolute()) out = root.resolve(out);
            Path parent = out.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, graph);
            CommandWedge.printOk("Explain", "wrote " + out.toAbsolutePath().normalize());
        } else {
            CliOutput.out(graph.endsWith("\n") ? graph.substring(0, graph.length() - 1) : graph);
        }
        return 0;
    }
}

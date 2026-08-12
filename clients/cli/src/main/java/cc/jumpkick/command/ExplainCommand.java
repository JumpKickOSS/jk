// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.EnsureFreshLock;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.BoxTable;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Spinner;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleDotGraph;
import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.model.JkBuild;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    // make `jk pl` ambiguous with plugin and `jk wh` with why (JK-1364).

    @Override
    public String description() {
        return "Forecast rebuilds (cache hit/miss per stage)";
    }

    @Override
    public List<Opt> options() {
        var opts = new java.util.ArrayList<Opt>();
        opts.add(Opt.flag("Build the plan instead of printing it", "--run"));
        opts.addAll(cc.jumpkick.cli.ParallelTestsOpts.options());
        // The plan-affecting options `jk build` accepts — forecasting `jk build <flags>`
        // means feeding the same inputs to the shared estimate (and, with --run, to build).
        // Module concurrency: global -j/--jobs.
        opts.add(Opt.value("<name>", "Forecast with a build profile", "--profile"));
        opts.add(Opt.value("<N>", "Test JVMs per module (0=auto)", "-w", "--workers"));
        opts.add(cc.jumpkick.cli.CommonOpts.skipTests());
        // -r/--redo is a global flag (same as `jk build --redo`); see GlobalOptions.
        opts.add(Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                .hide());
        opts.add(cc.jumpkick.cli.CommonOpts.cacheDir());
        opts.addAll(cc.jumpkick.cli.CommonOpts.moduleSelection());
        opts.add(Opt.value("<fmt>", "Emit module DAG as dot or mermaid", "--graph"));
        opts.add(Opt.value("<file>", "Write --graph output to this file", "--graph-out"));
        return opts;
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        Path startDir = global.workingDir();
        var proj = ProjectContext.require(startDir, "explain").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path buildFile = proj.buildFile();
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        String graphFmt = in.value("graph").orElse(null);
        boolean hasGraph = graphFmt != null && !graphFmt.isBlank();
        if (in.isSet("run") && hasGraph) {
            CliOutput.err(
                    cc.jumpkick.cli.tui.CommandWedge.fail("Explain", "cannot combine --run with --graph (pick one)"));
            return Exit.USAGE;
        }
        if (in.isSet("run")) {
            return new BuildCommand().run(in); // forwards --cache-dir; build options default
        }

        // Module DAG export is offline (no engine / lock). Honor --modules / --affected-since.
        // On single-project layouts, selectors only validate; the graph is one node.
        if (hasGraph) {
            return emitModuleGraph(
                    startDir,
                    buildFile,
                    graphFmt,
                    in.value("modules").orElse(null),
                    in.value("affected-since").orElse(null),
                    in.value("graph-out").orElse(null));
        }

        // HARD INVARIANT: bare `jk explain` uses the exact same defaults as bare `jk build`
        // (-w 0 = auto, -j from jobsEffective, parallel-tests default on). The estimate must
        // match the live countdown bit-for-bit — docs/perf/progress-contract.md.
        boolean parallelTests = cc.jumpkick.cli.ParallelTestsOpts.enabled(in);
        int jobs = global.jobsEffective();
        boolean serial = jobs == 1;
        // 0 = auto within-module test JVMs — same as BuildCommand when -w is omitted.
        int workers = in.value("workers").map(Integer::parseInt).orElse(0);
        boolean skipTests = in.isSet("skip-tests");
        // Global --redo / --force: forecast full work + rebuild ETA priors.
        boolean rebuild = global.rebuild || global.force;
        String profile = in.value("profile").orElse(null);
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        String affectedSince = in.value("affected-since").orElse(null);
        String modulesSpec = in.value("modules").orElse(null);

        // Client-side module filter listing (before engine forecast) when selectors are set.
        if ((affectedSince != null && !affectedSince.isBlank()) || (modulesSpec != null && !modulesSpec.isBlank())) {
            try {
                var entry = cc.jumpkick.config.JkBuildParser.parse(buildFile);
                var selected =
                        cc.jumpkick.config.ModuleSelection.resolveOptional(startDir, entry, modulesSpec, affectedSince);
                if (selected != null && !selected.ok()) {
                    CliOutput.err(CommandWedge.fail("Explain", selected.errorMessage()));
                    return Exit.CONFIG;
                }
                if (selected != null) {
                    CliOutput.out("Selected modules (" + selected.moduleDirs().size() + "):");
                    for (Path m : selected.moduleDirs()) {
                        Path rel;
                        try {
                            rel = startDir.toAbsolutePath().normalize().relativize(m);
                        } catch (IllegalArgumentException e) {
                            rel = m;
                        }
                        CliOutput.out("  " + (rel.toString().isEmpty() ? "." : rel));
                    }
                    if (selected.moduleDirs().isEmpty()) {
                        return 0;
                    }
                }
            } catch (Exception e) {
                CliOutput.err(CommandWedge.fail("Explain", "module selection failed: " + e.getMessage()));
                return Exit.CONFIG;
            }
        }

        // Live prep wedge: Locking versions… → Calculating build plan… (or Calibrating host…),
        // then clear and print the settled Build Plan tree.
        boolean livePrep = EnsureFreshLock.isInteractiveAuto(global) && !global.outputIsJson();
        boolean needsLock = LockFreshness.needsRefresh(startDir);
        boolean needsCalibrate = HostCalibrationStatus.needsBootstrapProbe();
        String prepMsg =
                needsLock ? "Locking versions…" : needsCalibrate ? "Calibrating host…" : "Calculating build plan…";

        ExplainPlan plan;
        long etaMillis;
        long fullEtaMillis;
        // [0] = current remaining ETA; [1] = full-rebuild ETA (effort denominator).
        long[] etaOut = new long[2];
        try (Spinner prep = livePrep ? CommandWedge.analyzing(CliOutput.stdout(), "Explain", prepMsg) : null) {
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
            plan = cc.jumpkick.cli.engine.EngineClient.explain(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineClient.ExplainRequest(
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
        boolean nerdfont = cc.jumpkick.config.GlobalConfig.nerdfont();

        // Forecast every module's full step plan (compile → test → package),
        // truthfully — see TaskForecaster.
        List<TaskForecast.Module> modules = plan.modules();
        boolean verbose = in.isSet("verbose");

        // Header: ≡ Build Graph (ETA lives in the summary table).
        String header =
                cc.jumpkick.cli.tui.BuildPlanWedge.planChip(cc.jumpkick.cli.tui.Glyphs.MENU, "Build Graph", nerdfont);
        boolean fullyCached = !modules.isEmpty() && modules.stream().noneMatch(TaskForecast.Module::dirty);
        // Leading blank once per command (prep lock wedge may already have opened it).
        CommandWedge.envelopeStart();
        CliOutput.out(header);
        // Root node: ● bullet, then the entry project's group:artifact in bold.
        String rootBullet = ansi ? Theme.colorize("●", t.darkGray()) : "*";
        String coord = BuildCommand.buildTarget(buildFile, startDir);
        CliOutput.out(" " + rootBullet + " " + (ansi ? boldCoord(coord, t) : coord));
        // Empty spine under the root before Fully Cached / Rebuild sections.
        CliOutput.out(ansi ? " " + Theme.colorize("│", t.darkGray()) : " |");

        // Partition the topo order by cache status: every fully-cached module (wherever
        // it sits in the order) collapses into the "Fully Cached" section (names only);
        // only the modules that actually rebuild appear in the detailed "Rebuild" section.
        // --verbose expands every task (cached ones too).
        List<Integer> cachedIdx = new ArrayList<>();
        List<Integer> dirtyIdx = new ArrayList<>();
        for (int i = 0; i < modules.size(); i++) {
            (modules.get(i).dirty() ? dirtyIdx : cachedIdx).add(i);
        }

        if (!cachedIdx.isEmpty()) {
            boolean lastSection = dirtyIdx.isEmpty();
            String sectionConnector = lastSection ? "╰─" : "├─";
            // Plain: no space between connector and pill (`-[Fully Cached]`) — matches jk tree.
            String sectionBadge = ansi ? cc.jumpkick.cli.tui.Badge.pill("Fully Cached", nerdfont) : "[Fully Cached]";
            int nFresh = cachedIdx.size();
            String freshNote = " " + nFresh + (nFresh == 1 ? " module is fresh" : " modules are fresh");
            CliOutput.out(" "
                    + (ansi ? Theme.colorize(sectionConnector, t.darkGray()) : (lastSection ? "`-" : "+-"))
                    + sectionBadge
                    + freshNote);
            String childPrefix = ansi
                    ? " " + Theme.colorize(lastSection ? "   " : "│  ", t.darkGray())
                    : " " + (lastSection ? "   " : "|  ");
            if (verbose) {
                for (int j = 0; j < cachedIdx.size(); j++) {
                    int i = cachedIdx.get(j);
                    renderModuleRow(modules.get(i), j == cachedIdx.size() - 1, childPrefix, true, t, ansi);
                }
            } else {
                List<String> names = new ArrayList<>();
                for (int i : cachedIdx) names.add(shortName(modules.get(i).coord()));
                // Fixed 4 names per line (no terminal wrap).
                List<List<String>> lines = chunkNames(names, CACHED_NAMES_PER_LINE);
                String cont = childPrefix + "   "; // align past "╰─ " / "`- "
                String firstConnector = ansi ? Theme.colorize("╰─ ", t.darkGray()) : "`- ";
                for (int li = 0; li < lines.size(); li++) {
                    boolean more = li < lines.size() - 1;
                    CliOutput.out((li == 0 ? childPrefix + firstConnector : cont)
                            + renderCachedNameLine(lines.get(li), more, t, ansi));
                }
            }
        }
        if (!dirtyIdx.isEmpty()) {
            String rebuildBadge = ansi ? cc.jumpkick.cli.tui.Badge.pill("Rebuild", nerdfont) : "[Rebuild]";
            int nDirty = dirtyIdx.size();
            String dirtyNote = " " + nDirty + (nDirty == 1 ? " module is dirty" : " modules are dirty");
            CliOutput.out(" "
                    + (ansi ? Theme.colorize("╰─", t.darkGray()) : "`-")
                    + rebuildBadge
                    + dirtyNote);
            // Empty spine under Rebuild before the first module row.
            CliOutput.out(ansi ? "    " + Theme.colorize("│", t.darkGray()) : "    |");
            for (int j = 0; j < dirtyIdx.size(); j++) {
                int i = dirtyIdx.get(j);
                renderModuleRow(modules.get(i), j == dirtyIdx.size() - 1, "    ", verbose, t, ansi);
            }
        }

        // Summary table: totals vs rebuild effort (ETA/full-ETA) + countdown seed.
        CliOutput.out("");
        for (String line : renderSummaryTable(modules, etaMillis, fullEtaMillis, fullyCached, t, ansi)) {
            CliOutput.out(line);
        }
        return 0;
    }

    /**
     * Header estimate fragment: {@code Build time estimate ~8s}, {@code Build time estimate <1s}
     * (fully cached / sub-second), or {@code Build time estimate not yet measured} when dirty work
     * has no host/project timings yet.
     */
    static String buildTimeEstimate(long etaMillis, boolean fullyCached, Theme t) {
        return "Build time estimate " + Theme.colorize(buildTimeEstimateValue(etaMillis, fullyCached), t.warning());
    }

    /**
     * ETA value only ({@code ~8s} / {@code <1s} / {@code not yet measured}) — same authority as
     * {@code jk build}'s countdown seed. Never mask a multi-minute eta behind Fully Cached / {@code
     * <1s}.
     */
    static String buildTimeEstimateValue(long etaMillis, boolean fullyCached) {
        if (etaMillis <= 0) return fullyCached ? "<1s" : "not yet measured";
        if (etaMillis < 1000) return "<1s";
        return "~" + fmtDuration(etaMillis);
    }

    /** "1m 20s" / "8s" / "<1s" — coarse predicted-duration formatting for the plan summary. */
    private static String fmtDuration(long millis) {
        if (millis <= 0) return "<1s";
        long s = millis / 1000; // floor: don't over-state
        if (s == 0) return "<1s"; // a sub-second cache-verify pass
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    /**
     * Extra bright-black dots after every step name (including the longest) so the {@code □}/{@code
     * ✓} column never butts up against the name.
     */
    private static final int STEP_NAME_DOT_GAP = 2;

    /** Fixed columns in the Fully Cached name list (no terminal-width wrap). */
    private static final int CACHED_NAMES_PER_LINE = 4;

    /**
     * Render one module row under a section: {@code prefix} + connector + bold bright-cyan artifact
     * name, then either a phase-chain line (default) or the expanded task sub-tree ({@code
     * --verbose}). The verdict is implied by the enclosing section (Fully Cached / Rebuild).
     */
    private static void renderModuleRow(
            TaskForecast.Module m, boolean last, String prefix, boolean verbose, Theme t, boolean ansi) {
        String moduleConnector = ansi ? Theme.colorize((last ? "╰" : "├") + "─", t.darkGray()) : (last ? "`-" : "+-");
        String name = shortName(m.coord());
        String moduleLabel = ansi ? Theme.colorize(name, t.brightCyan().bold()) : name;
        CliOutput.out(prefix + moduleConnector + moduleLabel);

        if (!(m.dirty() || verbose)) return;

        String spine = ansi
                ? prefix + (last ? "   " : Theme.colorize("│", t.darkGray()) + "  ")
                : prefix + (last ? "   " : "|  ");

        if (!verbose) {
            String chain = renderPhaseChain(m, t, ansi);
            if (!chain.isEmpty()) {
                String stepConnector = ansi ? Theme.colorize("╰─ ", t.darkGray()) : "`- ";
                CliOutput.out(spine + stepConnector + chain);
            }
            return;
        }

        List<TaskForecast.Task> ph = m.steps();
        // Pad each step name to the widest in this module with bright-black dots so the
        // □ / ✓ column lines up (package-assembly is longer than compile-main, etc.).
        int nameCol = 0;
        for (TaskForecast.Task p : ph) {
            nameCol = Math.max(nameCol, p.name().length());
        }
        nameCol += STEP_NAME_DOT_GAP;
        // Pad each □ step's command to the widest in this module so the · column lines up.
        int commandCol = 0;
        for (TaskForecast.Task p : ph) {
            if (!p.cached()) commandCol = Math.max(commandCol, commandWidth(p.text()));
        }
        for (int k = 0; k < ph.size(); k++) {
            boolean lp = k == ph.size() - 1;
            String stepConnector = ansi ? Theme.colorize(lp ? "╰─ " : "├─ ", t.darkGray()) : (lp ? "`- " : "+- ");
            String stepName = formatStepName(ph.get(k).name(), nameCol, t, ansi);
            CliOutput.out(spine + stepConnector + stepName + renderStatus(ph.get(k), commandCol, t, ansi));
        }
    }

    /**
     * Roll material tasks up into a web-style phase chain: {@code ✓ Compile › □ Test ~28 tests › □
     * Package}. Stages follow {@link BuildStage} pipeline order; bookkeeping-only steps are
     * omitted so stamp/resolve noise never appears.
     */
    static String renderPhaseChain(TaskForecast.Module m, Theme t, boolean ansi) {
        Map<BuildStage, List<TaskForecast.Task>> byStage = new LinkedHashMap<>();
        for (TaskForecast.Task step : m.steps()) {
            if (!TaskForecast.Module.isMaterialWork(step.name())) continue;
            BuildStage stage = BuildStage.ofTaskName(step.name());
            byStage.computeIfAbsent(stage, _ -> new ArrayList<>()).add(step);
        }
        if (byStage.isEmpty()) return "";

        List<BuildStage> order = byStage.keySet().stream()
                .sorted(Comparator.comparingInt((BuildStage s) ->
                                s == BuildStage.OTHER ? Integer.MAX_VALUE : s.pipelineOrder())
                        .thenComparing(BuildStage::wireName))
                .toList();

        String sep = ansi ? Theme.colorize(" › ", t.darkGray()) : " > ";
        StringBuilder sb = new StringBuilder();
        for (BuildStage stage : order) {
            if (!sb.isEmpty()) sb.append(sep);
            List<TaskForecast.Task> steps = byStage.get(stage);
            boolean dirty = steps.stream().anyMatch(s -> !s.cached());
            sb.append(renderPhaseToken(stage, dirty, phaseDetail(stage, dirty, m), t, ansi));
        }
        return sb.toString();
    }

    /** One phase token: {@code ✓ Compile} (green) or {@code □ Test ~28 tests} (blue + dim detail). */
    private static String renderPhaseToken(
            BuildStage stage, boolean dirty, String detail, Theme t, boolean ansi) {
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
     * Short detail next to a dirty phase — source/test counts for Compile/Test; nothing for
     * package/native/image (the phase name is enough).
     */
    private static String phaseDetail(BuildStage stage, boolean dirty, TaskForecast.Module m) {
        if (!dirty) return null;
        return switch (stage) {
            case COMPILE -> m.sourceCount() > 0 ? fmtCount(m.sourceCount(), "source", "sources") : null;
            case TEST -> m.testCount() > 0 ? "~" + String.format("%,d", m.testCount()) + " tests" : null;
            default -> null;
        };
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
        int totalNatives = (int) modules.stream().filter(ExplainCommand::producesNative).count();
        int totalImages = (int)
                modules.stream().filter(TaskForecast.Module::producesImage).count();

        int dirtyModules = (int) modules.stream().filter(TaskForecast.Module::dirty).count();
        int dirtySources = modules.stream()
                .filter(TaskForecast.Module::dirty)
                .mapToInt(TaskForecast.Module::sourceCount)
                .sum();
        int dirtyTests = modules.stream()
                .filter(TaskForecast.Module::dirty)
                .mapToInt(TaskForecast.Module::testCount)
                .sum();
        int dirtyJars = (int)
                modules.stream().filter(m -> m.dirty() && m.producesJar()).count();
        int dirtyNatives = (int)
                modules.stream().filter(m -> m.dirty() && producesNative(m)).count();
        int dirtyImages = (int)
                modules.stream().filter(m -> m.dirty() && m.producesImage()).count();

        // Rows: label, total cell text, rebuild count, total count (for per-item delta only).
        record PlanRow(String item, String totalCell, int rebuild, int total) {}
        List<PlanRow> planRows = new ArrayList<>();
        planRows.add(new PlanRow(
                "Modules",
                boldNum(totalModules, t, ansi) + " in workspace",
                dirtyModules,
                totalModules));
        planRows.add(new PlanRow(
                "Sources", boldNum(totalSources, t, ansi) + " files", dirtySources, totalSources));
        planRows.add(new PlanRow(
                "Tests", boldNum(totalTests, t, ansi) + " methods", dirtyTests, totalTests));
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

        String[] headers = {"Plan Item", "Total", "Rebuild", "Delta"};
        List<List<String>> cells = new ArrayList<>();
        for (PlanRow r : planRows) {
            cells.add(List.of(
                    r.item(),
                    r.totalCell(),
                    colorRebuild(r.rebuild(), t, ansi),
                    colorDelta(pctValue(r.rebuild(), r.total()), t, ansi)));
        }

        // Column widths from visible cell content (ANSI-stripped).
        int[] w = new int[4];
        for (int i = 0; i < 4; i++) w[i] = BoxTable.visibleWidth(headers[i]);
        for (var row : cells) {
            for (int i = 0; i < 4; i++) w[i] = Math.max(w[i], BoxTable.visibleWidth(row.get(i)));
        }
        // Footer: effort = remaining ETA / full-rebuild ETA (not a count average).
        int effortPct = rebuildEffortPct(etaMillis, fullEtaMillis);
        String effortLabel = "Total rebuild effort";
        String effortValue = colorDelta(effortPct, t, ansi);
        String etaLabel = "Build time estimate";
        String etaValue = Theme.colorize(buildTimeEstimateValue(etaMillis, fullyCached), t.warning());
        // Left span = col0 + col1; right span = col2 + col3 (plus inter-column gutters).
        int leftSpan = w[0] + 2 + 1 + w[1] + 2; // cell pads + mid rail
        int rightSpan = w[2] + 2 + 1 + w[3] + 2;
        int leftNeed = Math.max(BoxTable.visibleWidth(effortLabel), BoxTable.visibleWidth(etaLabel)) + 2;
        int rightNeed = Math.max(BoxTable.visibleWidth(effortValue), BoxTable.visibleWidth(etaValue)) + 2;
        if (leftNeed > leftSpan) {
            w[1] += leftNeed - leftSpan;
            leftSpan = leftNeed;
        }
        if (rightNeed > rightSpan) {
            w[3] += rightNeed - rightSpan;
            rightSpan = rightNeed;
        }

        int inner = leftSpan + 1 + rightSpan; // mid rail between the two footer spans
        List<String> out = new ArrayList<>();
        out.add(BoxTable.titleBar("Build Plan", inner + 2));
        out.add(boxDivider("├", "┬", "┤", w, ansi, t));
        out.add(boxHeaderRow(headers, w, ansi, t));
        out.add(boxDivider("├", "┼", "┤", w, ansi, t));
        for (var row : cells) out.add(boxDataRow(row, w, ansi, t));
        // Footer: collapse 4 body cols → 2 spans (┴ ends a rail, ┼ continues the mid rail).
        out.add(boxFooterJoin(w, ansi, t));
        out.add(boxFooterRow(effortLabel, effortValue, leftSpan, rightSpan, ansi, t));
        out.add(boxFooterRow(etaLabel, etaValue, leftSpan, rightSpan, ansi, t));
        out.add(boxFooterClose(leftSpan, rightSpan, ansi, t));
        return out;
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

    private static String boxDivider(String left, String junction, String right, int[] w, boolean ansi, Theme t) {
        if (!ansi) {
            StringBuilder sb = new StringBuilder("+");
            for (int i = 0; i < w.length; i++) {
                sb.append("-".repeat(w[i] + 2));
                sb.append(i == w.length - 1 ? "+" : "+");
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder(left);
        for (int i = 0; i < w.length; i++) {
            sb.append("─".repeat(w[i] + 2));
            sb.append(i == w.length - 1 ? right : junction);
        }
        return Theme.colorize(sb.toString(), t.darkGray());
    }

    private static String boxHeaderRow(String[] headers, int[] w, boolean ansi, Theme t) {
        String bar = ansi ? Theme.colorize("│", t.darkGray()) : "|";
        StringBuilder sb = new StringBuilder(bar);
        for (int i = 0; i < headers.length; i++) {
            sb.append(' ')
                    .append(BoxTable.headerCell(padVisible(headers[i], w[i])))
                    .append(' ')
                    .append(bar);
        }
        return sb.toString();
    }

    private static String boxDataRow(List<String> cells, int[] w, boolean ansi, Theme t) {
        String bar = ansi ? Theme.colorize("│", t.darkGray()) : "|";
        StringBuilder sb = new StringBuilder(bar);
        for (int i = 0; i < w.length; i++) {
            String c = i < cells.size() ? cells.get(i) : "";
            sb.append(' ').append(padVisible(c, w[i])).append(' ').append(bar);
        }
        return sb.toString();
    }

    /**
     * Divider that collapses the 4 body columns into the 2-span footer.
     *
     * <p>Box-drawing rule: a vertical that <em>ends</em> on this horizontal uses {@code ┴}; a
     * vertical that <em>continues</em> in the same column (footer mid-rail under Total|Rebuild)
     * uses {@code ┼}. So: Plan Item|Total → {@code ┴}, Total|Rebuild → {@code ┼}, Rebuild|Delta →
     * {@code ┴}.
     */
    private static String boxFooterJoin(int[] w, boolean ansi, Theme t) {
        int leftInner = w[0] + 2 + 1 + w[1] + 2;
        int rightInner = w[2] + 2 + 1 + w[3] + 2;
        if (!ansi) {
            return "+" + "-".repeat(leftInner) + "+" + "-".repeat(rightInner) + "+";
        }
        // ├─────┴─────┼─────┴─────┤
        String body = "├"
                + "─".repeat(w[0] + 2)
                + "┴"
                + "─".repeat(w[1] + 2)
                + "┼"
                + "─".repeat(w[2] + 2)
                + "┴"
                + "─".repeat(w[3] + 2)
                + "┤";
        return Theme.colorize(body, t.darkGray());
    }

    private static String boxFooterRow(
            String label, String value, int leftSpan, int rightSpan, boolean ansi, Theme t) {
        String bar = ansi ? Theme.colorize("│", t.darkGray()) : "|";
        return bar
                + " "
                + padVisible(label, leftSpan - 2)
                + " "
                + bar
                + " "
                + padVisible(value, rightSpan - 2)
                + " "
                + bar;
    }

    private static String boxFooterClose(int leftSpan, int rightSpan, boolean ansi, Theme t) {
        if (!ansi) {
            return "+" + "-".repeat(leftSpan) + "+" + "-".repeat(rightSpan) + "+";
        }
        return Theme.colorize("╰" + "─".repeat(leftSpan) + "┴" + "─".repeat(rightSpan) + "╯", t.darkGray());
    }

    /** Right-pad {@code s} to {@code width} visible columns (ANSI-aware). */
    private static String padVisible(String s, int width) {
        int len = BoxTable.visibleWidth(s);
        return len >= width ? s : s + " ".repeat(width - len);
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

    /** The entry project's {@code group:artifact} in bold, each segment in its coord color. */
    private static String boldCoord(String coord, Theme t) {
        int colon = coord.indexOf(':');
        if (colon < 0) return Theme.colorize(coord, t.coordName().bold());
        return Theme.colorize(coord.substring(0, colon), t.coordGroup().bold())
                + ":"
                + Theme.colorize(coord.substring(colon + 1), t.coordName().bold());
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
     * Join {@code units} with {@code ", "} to fit {@code available} visible columns. When the full
     * list is too wide, show as many leading units as fit followed by a {@code …+N more…} marker,
     * where {@code N} is the count of remaining units that didn't fit. {@code available} is
     * effectively unbounded on a non-TTY, so the full list is shown there.
     */
    static String elideDeps(List<String> units, int available) {
        String full = String.join(", ", units);
        if (available <= 0 || units.size() <= 1 || full.length() <= available) return full;
        String best = "…+" + units.size() + " more…"; // marker-only, if even one unit won't fit
        for (int k = 1; k < units.size(); k++) {
            String candidate = String.join(", ", units.subList(0, k)) + ", …+" + (units.size() - k) + " more…";
            if (candidate.length() > available) break; // front grows monotonically
            best = candidate;
        }
        return best;
    }

    /**
     * Greedily pack {@code tokens} into {@code ", "}-joined lines, each at most {@code avail} visible
     * columns wide (the wrap point drops the separator rather than leaving a trailing comma). On a
     * non-TTY {@code avail} is effectively unbounded, so the whole list lands on one line.
     */
    static List<String> wrapNames(List<String> tokens, int avail) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String tok : tokens) {
            if (cur.length() == 0) {
                cur.append(tok);
            } else if (cur.length() + 2 + tok.length() <= avail) {
                cur.append(", ").append(tok);
            } else {
                lines.add(cur.toString());
                cur = new StringBuilder(tok);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    /**
     * {@code jk explain --graph dot|mermaid} — module dependency DAG (no engine).
     */
    private static int emitModuleGraph(
            Path startDir, Path buildFile, String format, String modulesSpec, String affectedSince, String outputPath)
            throws Exception {
        String fmt = format.trim().toLowerCase(Locale.ROOT);
        if (!ModuleDotGraph.isSupportedFormat(fmt)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Explain",
                    "unsupported --graph format '" + format + "' (supported: "
                            + String.join(" | ", ModuleDotGraph.FORMATS) + ")"));
            return Exit.CONFIG;
        }
        JkBuild entry = JkBuildParser.parse(buildFile);
        Path root = startDir.toAbsolutePath().normalize();
        String graph;
        if (entry.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(root, entry);
            ModuleSelection.Result selected =
                    ModuleSelection.resolveOptional(startDir, entry, modulesSpec, affectedSince);
            if (selected != null && !selected.ok()) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Explain", selected.errorMessage()));
                return Exit.CONFIG;
            }
            Set<Path> only = selected != null ? selected.moduleDirs() : null;
            if (only != null && only.isEmpty()) {
                // Nothing selected — still valid empty digraph
                graph = ModuleDotGraph.render(fmt, root, Map.of(), null);
            } else {
                // Workspace root may not be in modules map; graph is modules only (Mill-like module DAG).
                Map<Path, JkBuild> forGraph = new LinkedHashMap<>(modules);
                graph = ModuleDotGraph.render(fmt, root, forGraph, only);
            }
        } else {
            // Single project: trivial one-node graph (selectors ignored / no-op).
            if ((modulesSpec != null && !modulesSpec.isBlank())
                    || (affectedSince != null && !affectedSince.isBlank())) {
                ModuleSelection.Result selected =
                        ModuleSelection.resolveOptional(startDir, entry, modulesSpec, affectedSince);
                if (selected != null && !selected.ok()) {
                    CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Explain", selected.errorMessage()));
                    return Exit.CONFIG;
                }
            }
            graph = ModuleDotGraph.singleModule(entry, root, fmt);
        }
        if (outputPath != null && !outputPath.isBlank()) {
            Path out = Path.of(outputPath);
            if (!out.isAbsolute()) out = root.resolve(out);
            Path parent = out.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, graph);
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Explain", "wrote " + out.toAbsolutePath().normalize()));
        } else {
            CliOutput.out(graph.endsWith("\n") ? graph.substring(0, graph.length() - 1) : graph);
        }
        return 0;
    }
}

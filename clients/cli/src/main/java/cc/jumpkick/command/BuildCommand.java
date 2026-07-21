// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.AggregateContext;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.PipelineWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineKey;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code jk build} — orchestrates lock, sync, compile, test, and package. */
public final class BuildCommand implements CliCommand {

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String description() {
        return "Compile, test, and package the project";
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = new java.util.ArrayList<>(List.of(
                Opt.value("<name>", "Apply a build profile. Default: auto (ci on CI).", "--profile"),
                Opt.value("<N>", "Test-runner JVMs to fork per module (within -j). Default 1.", "-w", "--workers"),
                cc.jumpkick.cli.CommonOpts.cacheDir(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
                Opt.flag("Skip compiling and running tests.", "--skip-tests"),
                Opt.flag("Package an extracted layout + trained JVM startup cache.", "--aot-cache"),
                // Module concurrency is global -j/--jobs (JK-1082). --parallel/--no-parallel removed.
                Opt.flag("Run modules' tests concurrently too (cross-module). Default: off.", "--parallel-tests"),
                Opt.value(
                        "<git-ref>",
                        "Build only modules (and dependents) changed since this git ref.",
                        "--affected-since"),
                Opt.value(
                        "<sel>",
                        "Build only selected modules (comma list, globs, braces). Intersects with --affected-since.",
                        "--modules")));
        opts.addAll(VariantSelection.options());
        return opts;
    }

    String profileName;
    Integer workers;
    Path cacheDir;
    Path jdksDir;
    cc.jumpkick.cli.BuildOptions buildOpts;
    GlobalOptions global;
    /** Resolved concurrent module budget (from global -j / JK_JOBS / [engine] jobs). */
    int jobs;

    boolean parallelTests;
    boolean aotCache;
    String variant;
    String affectedSince;
    String modulesSpec;
    java.util.Map<String, String> clientEnv = java.util.Map.of();
    /** Best-effort session transcript (JK-1079); null when disabled / no project. */
    private CliSessionTranscript session;
    // ---- PipelineKeys -------------------------------------------------------
    //
    // BuildPipelines owns the step DAG and all of its keys; BuildCommand only
    // reads a few results back out of the finished pipeline to render its result
    // line. PipelineKeys are name-keyed, so these match BuildPipelines's by name.

    private static final PipelineKey<String> BUILD_OUTCOME = PipelineKey.of("build-outcome", String.class);
    private static final PipelineKey<Path> JAR_PATH = PipelineKey.of("jar-path", Path.class);
    private static final PipelineKey<BuildLayout> LAYOUT = PipelineKey.of("layout", BuildLayout.class);
    private static final PipelineKey<TestSummary> TEST_RESULT = PipelineKey.of("test-result", TestSummary.class);

    // ---- Entry point ----------------------------------------------------

    @Override
    public int run(Invocation in) throws Exception {
        this.profileName = in.value("profile").orElse(null);
        this.workers = in.value("workers").map(Integer::parseInt).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.buildOpts = new cc.jumpkick.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.aotCache = in.isSet("aot-cache");
        this.global = GlobalOptions.from(in);
        this.jobs = global.jobsEffective();
        // Opt-in: run modules' tests concurrently. Default serializes them
        // (shared ports/locks/fixtures) — see BuildPipelines's test gate.
        this.parallelTests = in.isSet("parallel-tests");
        this.affectedSince = in.value("affected-since").orElse(null);
        this.modulesSpec = in.value("modules").orElse(null);
        cc.jumpkick.config.SessionContext.install(
                cc.jumpkick.config.SessionContext.current().withParallelTests(parallelTests));
        Path startDir = global.workingDir();
        Path buildFile = startDir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Build", "no jk.toml in " + cc.jumpkick.cli.PathDisplay.styledRaw(startDir)));
            return Exit.CONFIG;
        }
        // Variant selection (--release / --variant <dim>=<value>): rides the request as a compact
        // selector plus the client-resolved env: values (VariantSelection). Also installed on the
        // ambient session for the in-process paths.
        this.variant = VariantSelection.install(in, startDir);
        this.clientEnv = cc.jumpkick.config.SessionContext.current().clientEnv();
        this.session = CliSessionTranscript.open(startDir, "build", buildArgv(in));

        // Workspace root or module → full workspace build in topological order.
        cc.jumpkick.engine.protocol.ProjectInfo peek = projectInfoOrNull(startDir);

        if (peek != null && peek.workspaceRoot()) {
            if (aotCache) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                        "Build",
                        "--aot-cache packages a single application project;" + " run it from the module directory."));
                return finishSession(Exit.USAGE);
            }
            return finishSession(buildWorkspace(startDir));
        }
        if (peek != null
                && !peek.workspaceRootDir().isEmpty()
                && !peek.workspaceRootDir().equals(startDir.toString())) {
            Path root = Path.of(peek.workspaceRootDir());
            if (!global.outputIsJson()) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                        "Build",
                        "building workspace from " + root.getFileName() + " (module: " + startDir.getFileName() + ")"));
            }
            return finishSession(buildWorkspace(root));
        }
        int code = runForDir(startDir);
        if (code == 0 && aotCache) {
            // Post-build tail (like run's exec): extract layout + training run, client-side —
            // the layout inputs come from the engine's exec plan (thin client).
            code = AotCachePackage.run(startDir, cacheDir != null ? cacheDir : JkDirs.cache());
        }
        return finishSession(code);
    }

    /** Write the session transcript (best-effort) and return {@code code} unchanged. */
    private int finishSession(int code) {
        return CliSessionTranscript.finish(session, code, global != null && global.verbose);
    }

    /** Compact argv snapshot for details.json (command + selection flags). */
    private List<String> buildArgv(Invocation in) {
        List<String> argv = new ArrayList<>();
        argv.add("build");
        if (in.isSet("skip-tests")) argv.add("--skip-tests");
        if (in.isSet("aot-cache")) argv.add("--aot-cache");
        if (in.isSet("parallel-tests")) argv.add("--parallel-tests");
        if (in.isSet("no-timeline")) argv.add("--no-timeline");
        in.value("profile").ifPresent(p -> {
            argv.add("--profile");
            argv.add(p);
        });
        in.value("modules").ifPresent(m -> {
            argv.add("--modules");
            argv.add(m);
        });
        in.value("affected-since").ifPresent(r -> {
            argv.add("--affected-since");
            argv.add(r);
        });
        return argv;
    }

    /** Workspace graph build; concurrency from {@link #jobs} ({@code -j1} = serial UI path). */
    private int buildWorkspace(Path root) throws Exception {
        // The whole-workspace lock-staleness guard now runs engine-side, inside
        // BuildService.buildWorkspace (the request carries freshenLock=true) — the CLI only renders
        // the failure via the standard workspace-errors path. jobs=1 is strict serial; else N-wide.
        //
        // Thin client: entryBuild never crosses the wire (EngineProtocol.buildRequest serializes
        // only entryDir + flags; the engine re-parses). The parsed model is needed ONLY by the
        // in-process test seam, so parse lazily on that branch alone.
        JkBuild rootBuild = null;
        return runGraphParallel(root, rootBuild);
    }

    private static final Object OUT_LOCK = new Object();

    /** One unit's build outcome, with its buffered output (flushed together on completion). */

    /**
     * Build the whole composite + workspace graph in parallel (Option B): one build graph,
     * scheduled by topological level, independent units built concurrently on {@link JkThreads#io()}
     * (their CPU work shares the bounded cpu pool, so no oversubscription). Each unit runs buffered —
     * its output is captured and flushed as one contiguous block on completion, so parallel logs
     * never interleave. Tests are serialized across units by default (BuildPipelines's gate); {@code
     * --parallel-tests} lifts that.
     */
    private int runGraphParallel(Path entryDir, JkBuild entryBuild) throws Exception {
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        // Detect mode first (zero I/O) so we can branch before touching the disk.
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
        boolean live = mode == PipelineConsole.Mode.AUTO || mode == PipelineConsole.Mode.QUIET;

        if (!live) {
            // --output json / --verbose: buffered, non-animated path. The engine drives the whole
            // workspace build (BuildService.buildWorkspace — resolve graph, memory plan, schedule,
            // run each module's pipeline); this listener renders the append-only block + [k/N] line.
            return runWorkspaceHeadless(entryDir, entryBuild, cache);
        }

        // Live path (AUTO / QUIET): resolve the graph and run the cache forecast
        // *before* creating the CommandManager so a fully-cached build never
        // flashes the animated spinner. The TUI is created only when there is
        // confirmed work to do; for a cached build we print the chip line directly.
        boolean animate = mode == PipelineConsole.Mode.AUTO && PipelineConsole.isInteractiveTerminal();
        boolean nerdfont = cc.jumpkick.config.GlobalConfig.nerdfont();

        // Optimize/start the engine before the first engine touch (the forecast) and before the Build
        // pipeline console, so a one-time AOT training shows the "Engine — optimizing…" wedge first and the
        // Build TUI then takes over (never interleaved). A running engine makes this a fast no-op.
        cc.jumpkick.cli.engine.EnginePrewarm.ensure();

        long buildStart = System.nanoTime();
        // Pre-flight forecast (engine-hosted; test bypass uses the in-process seam).
        cc.jumpkick.runtime.BuildForecast forecast;
        try {
            forecast = cc.jumpkick.cli.engine.EngineClient.forecast(
                    cc.jumpkick.engine.EnginePaths.current(), entryDir, cache, buildOpts.skipTests);
        } catch (java.io.IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Build", e.getMessage()));
            return Exit.SOFTWARE;
        }
        if (forecast.hasErrors()) {
            for (String err : forecast.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            CliOutput.err(
                    cc.jumpkick.cli.tui.PipelineWedge.failureLine("Build", nerdfont, "dependency resolution failed"));
            return Exit.CONFIG;
        }
        if (forecast.empty()) {
            CliOutput.out(cc.jumpkick.cli.tui.PipelineWedge.chipLine(
                    cc.jumpkick.cli.tui.Glyphs.CHECK, "Build", nerdfont, "workspace declares no modules"));
            return 0;
        }
        Set<Path> dirtyDirs = forecast.dirtyDirs();
        if (System.getenv("JK_PERF") != null) {
            System.err.println("[jk-perf] client-forecast " + (System.nanoTime() - buildStart) / 1_000_000 + "ms dirty="
                    + dirtyDirs.size());
        }
        // Optional: --modules and/or --affected-since (intersection when both).
        if ((affectedSince != null && !affectedSince.isBlank()) || (modulesSpec != null && !modulesSpec.isBlank())) {
            JkBuild buildForSelect = entryBuild;
            if (buildForSelect == null) {
                try {
                    buildForSelect = cc.jumpkick.config.JkBuildParser.parse(entryDir.resolve("jk.toml"));
                } catch (Exception e) {
                    CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                            "Build", "cannot load jk.toml for module selection: " + e.getMessage()));
                    return Exit.CONFIG;
                }
            }
            var selected = cc.jumpkick.config.ModuleSelection.resolveOptional(
                    entryDir, buildForSelect, modulesSpec, affectedSince);
            if (selected != null && !selected.ok()) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Build", selected.errorMessage()));
                return Exit.CONFIG;
            }
            if (selected != null && selected.moduleDirs().isEmpty()) {
                CliOutput.out(cc.jumpkick.cli.tui.PipelineWedge.chipLine(
                        cc.jumpkick.cli.tui.Glyphs.CHECK, "Build", nerdfont, selectionEmptyMessage()));
                return 0;
            }
            if (selected != null) {
                Set<Path> restricted = new java.util.LinkedHashSet<>();
                for (Path d : dirtyDirs) {
                    if (selected.moduleDirs().contains(d.toAbsolutePath().normalize())) restricted.add(d);
                }
                // Force-include selected modules even if forecast thinks they are cached.
                dirtyDirs = restricted.isEmpty() ? selected.moduleDirs() : restricted;
            }
        }
        // The forecast runs against the per-module locks; when the merged workspace lock is stale
        // the engine will re-lock (freshenLock on the request) and the forecast may be wrong — so a
        // stale lock disables the fully-cached shortcut AND the dirty hint (the engine re-forecasts
        // after freshening).
        boolean lockStale = forecast.lockStale();
        // A distrusting build (--force/--rebuild) never takes the trust-the-cache shortcut.
        if (forecast.fullyCached()
                && !global.force
                && !global.rebuild
                && affectedSince == null
                && (modulesSpec == null || modulesSpec.isBlank())) {
            // Fully cached — print chip line directly with no spinner ever created.
            CliOutput.out(cc.jumpkick.cli.tui.PipelineWedge.chipLine(
                    cc.jumpkick.cli.tui.Glyphs.CHECK, "Build", nerdfont, upToDateTail("all modules", buildStart)));
            return 0;
        }
        // Work confirmed — create the CommandManager now so the spinner starts the instant we know
        // there's something to build. The engine (BuildService.buildWorkspace, invoked by
        // runGraphLive) sizes the memory plan and drives the build; we pass the forecast as a hint.
        CommandManager view = CommandManager.pipeline(CliOutput.stdout(), "Build", animate);
        return runGraphLive(view, entryDir, entryBuild, cache, buildStart, dirtyDirs, lockStale);
    }

    private String selectionEmptyMessage() {
        if (modulesSpec != null && !modulesSpec.isBlank() && affectedSince != null && !affectedSince.isBlank()) {
            return "nothing matched --modules=" + modulesSpec + " ∩ --affected-since=" + affectedSince;
        }
        if (modulesSpec != null && !modulesSpec.isBlank()) {
            return "nothing matched --modules=" + modulesSpec;
        }
        return "nothing affected since " + affectedSince;
    }

    /**
     * Non-animated workspace build: the engine ({@link cc.jumpkick.runtime.BuildService#buildWorkspace})
     * owns the whole loop; this listener renders each module's buffered output block + a ✓/✗ {@code
     * [k/N]} line, then the summary chip — the same append-only output the CLI produced before, now a
     * pure renderer over the engine's events.
     */
    private int runWorkspaceHeadless(Path entryDir, JkBuild entryBuild, Path cache) {
        var request = new cc.jumpkick.runtime.WorkspaceRequest(
                        entryDir,
                        entryBuild,
                        cache,
                        jdksDir,
                        workers != null ? workers : 1,
                        profileName,
                        buildOpts.skipTests,
                        global.verbose,
                        jobs, // -j / JK_JOBS / [engine] jobs (always ≥ 1)
                        null, // headless: let the engine forecast dirty modules
                        true, // single-process CLI: plan our own worker-JVM memory budget
                        true) // jk build: auto-freshen a stale workspace lock engine-side
                .withVariant(variant, clientEnv);
        Map<Path, List<String>> buffers = new java.util.concurrent.ConcurrentHashMap<>();
        int[] total = {0};
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        long start = System.nanoTime();
        cc.jumpkick.runtime.WorkspaceResult result;
        try {
            cc.jumpkick.runtime.WorkspaceBuildListener headlessListener =
                    new cc.jumpkick.runtime.WorkspaceBuildListener() {
                        @Override
                        public void onPlan(List<cc.jumpkick.runtime.ModulePlan> plan) {
                            total[0] = plan.size();
                        }

                        @Override
                        public cc.jumpkick.run.PipelineListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                            // Durable run log, same as the old buffered path. Composed into the *returned*
                            // listener (not attached to m.pipeline() directly) since an engine-hosted module's
                            // pipeline is a client-side reconstruction that's never run() — only the returned
                            // listener is actually driven by wire-replayed events either way.
                            var log = cc.jumpkick.cli.run.EventLogListener.open(
                                    m.cache(), m.pipeline().name());
                            List<String> buf = java.util.Collections.synchronizedList(new ArrayList<>());
                            buffers.put(m.dir(), buf);
                            var outLis = new cc.jumpkick.run.PipelineListener() {
                                @Override
                                public synchronized void output(String step, String line) {
                                    buf.add(line);
                                }

                                @Override
                                public synchronized void warn(String step, String code, String message) {
                                    buf.add("  " + Glyphs.BANG + " " + step + ": " + message);
                                }

                                @Override
                                public synchronized void error(String step, String code, String message) {
                                    buf.add("  " + Glyphs.CROSS + " " + step + ": " + message);
                                }
                            };
                            return cc.jumpkick.cli.run.CompositePipelineListener.of(outLis, log);
                        }

                        @Override
                        public void onModuleFinish(cc.jumpkick.runtime.ModuleOutcome o) {
                            if (global.outputIsJson()) return;
                            List<String> buf = buffers.getOrDefault(o.dir(), List.of());
                            synchronized (OUT_LOCK) {
                                for (String line : buf) CliOutput.out(line);
                                CliOutput.out(completionLine(
                                        o.success(), done.incrementAndGet(), total[0], o.coord(), o.millis()));
                            }
                        }
                    };
            result = cc.jumpkick.cli.engine.EngineClient.buildWorkspace(
                    cc.jumpkick.engine.EnginePaths.current(), request, headlessListener);
        } catch (java.io.IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Build", e.getMessage()));
            if (session != null) session.error(e.getMessage());
            return Exit.SOFTWARE;
        }
        if (session != null) {
            for (var m : result.modules()) session.module(m.coord());
            for (String err : result.errors()) session.error(err);
        }
        if (!result.errors().isEmpty()) {
            for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            // exitCode carries the engine's verdict: 2 for graph errors, 6 for an unsatisfiable
            // workspace lock (the freshen guard) — preserved rather than flattened to CONFIG.
            return result.exitCode();
        }
        if (total[0] == 0) {
            CliOutput.out("(workspace declares no modules)");
            if (session != null) session.wedge("workspace declares no modules");
            return 0;
        }
        if (!result.success()) {
            result.modules().stream().filter(m -> !m.success()).findFirst().ifPresent(f -> {
                String msg = f.coord() + " failed (exit " + f.exitCode() + ")";
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Build", msg));
                if (session != null) session.error(msg).wedge(msg);
            });
            return result.exitCode();
        }
        String okTail = modulesTail(total[0], start);
        CliOutput.out(
                PipelineWedge.chipLine(cc.jumpkick.cli.tui.Glyphs.CHECK, "Build", GlobalConfig.nerdfont(), okTail));
        if (session != null) session.wedge(okTail);
        return 0;
    }

    /**
     * Live aggregate scheduler: one {@link CommandManager} (pipeline mode) shows a spinner header + a
     * single bar calibrated to the whole graph + a tree of the modules building <em>right now</em>;
     * the tree grows to the parallelism limit and shrinks back to 0 as units drain. Each unit's
     * process output is buffered and flushed (with a ✓/✗ {@code [k/N]} line) above the region when it
     * completes — so concurrent logs never interleave. On a non-interactive terminal nothing
     * animates; the same blocks + lines print append-only.
     */
    private int runGraphLive(
            CommandManager view,
            Path entryDir,
            JkBuild entryBuild,
            Path cache,
            long start,
            Set<Path> dirtyDirs,
            boolean lockStale) {
        AggregateContext agg = new AggregateContext(view);
        Map<Path, List<String>> buffers = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> deferredOutput = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        int[] total = {0};
        // Reuse the forecast dirty set unless the workspace lock is stale (engine re-locks and
        // re-forecasts). Exception (JK-1060): an explicit --modules / --affected-since selection
        // must still be honored — nulling the hint would cascade the whole workspace.
        boolean honorSelection =
                (modulesSpec != null && !modulesSpec.isBlank()) || (affectedSince != null && !affectedSince.isBlank());
        Set<Path> dirtyHint = (lockStale && !honorSelection) ? null : dirtyDirs;
        var request = new cc.jumpkick.runtime.WorkspaceRequest(
                        entryDir,
                        entryBuild,
                        cache,
                        jdksDir,
                        workers != null ? workers : 1,
                        profileName,
                        buildOpts.skipTests,
                        global.verbose,
                        jobs,
                        dirtyHint,
                        true, // single-process CLI: plan our own worker-JVM memory budget
                        true) // jk build: auto-freshen a stale workspace lock engine-side
                .withVariant(variant, clientEnv);
        cc.jumpkick.runtime.WorkspaceResult result;
        try {
            cc.jumpkick.runtime.WorkspaceBuildListener liveListener = new cc.jumpkick.runtime.WorkspaceBuildListener() {
                @Override
                public void onPlan(List<cc.jumpkick.runtime.ModulePlan> plan) {
                    total[0] = plan.size();
                    long tw = 0;
                    for (var p : plan) tw += p.weight();
                    agg.calibrate(tw); // bar calibrated to the whole-graph tick total
                }

                @Override
                public void onEtaEstimate(long millis) {
                    view.setEtaEstimate(millis); // engine computes the schedule-aware estimate; we render it
                }

                @Override
                public cc.jumpkick.run.PipelineListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                    // Composed into the returned listener, not attached to m.pipeline() directly — see
                    // the headless path's onModuleStart above for why.
                    var log = cc.jumpkick.cli.run.EventLogListener.open(
                            m.cache(), m.pipeline().name());
                    List<String> buf = java.util.Collections.synchronizedList(new ArrayList<>());
                    buffers.put(m.dir(), buf);
                    // The module's pipeline feeds the shared aggregate bar; its output buffers for
                    // ordered flush (parallel modules' logs never interleave).
                    var lis = new cc.jumpkick.cli.run.AggregateModuleListener(
                            agg, m.coord(), m.pipeline().steps(), m.weight());
                    lis.bufferOutputInto(buf);
                    return cc.jumpkick.cli.run.CompositePipelineListener.of(lis, log);
                }

                @Override
                public void onModuleFinish(cc.jumpkick.runtime.ModuleOutcome o) {
                    List<String> buf = buffers.getOrDefault(o.dir(), List.of());
                    String completion =
                            completionLine(o.success(), completed.incrementAndGet(), total[0], o.coord(), o.millis());
                    if (view.animating()) {
                        view.addCompletion(completion);
                        synchronized (buf) {
                            if (!buf.isEmpty()) deferredOutput.addAll(buf);
                        }
                    } else {
                        StringBuilder block = new StringBuilder();
                        synchronized (buf) {
                            for (String l : buf) block.append(l).append('\n');
                        }
                        block.append(completion);
                        view.writeAbove(block.toString());
                    }
                }
            };
            result = cc.jumpkick.cli.engine.EngineClient.buildWorkspace(
                    cc.jumpkick.engine.EnginePaths.current(), request, liveListener);
        } catch (java.io.IOException e) {
            // finishPipelineFailure's own `tail` already gets wrapped in PipelineWedge.failureLine(pipelineName(),
            // nerdfont, tail) internally — pass the plain message, not a pre-rendered failure line
            // (passing one double-wraps it into a garbled "‼ Build ‼ Build ..." chip).
            view.finishPipelineFailure(String.valueOf(e.getMessage()), List.of());
            if (session != null) session.error(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        if (session != null) {
            for (var m : result.modules()) session.module(m.coord());
            for (String err : result.errors()) session.error(err);
            for (PipelineResult.Diagnostic d : agg.lastErrors()) {
                session.error(d.step(), d.code(), d.message());
            }
        }
        if (!result.errors().isEmpty()) {
            List<String> above = new ArrayList<>();
            for (String err : result.errors()) above.add(ConsoleSpec.errorLine("composite", err));
            view.finishPipelineFailure("dependency resolution failed", above);
            if (session != null) session.wedge("dependency resolution failed");
            // 2 for graph errors, 6 for an unsatisfiable workspace lock (the engine's freshen guard).
            return result.exitCode();
        }
        if (!result.success()) {
            // Buffered sub-process output first, then the error diagnostics just above the
            // "‼ Build failed" line — which stays last so the outcome is visible without scrolling.
            List<String> above = snapshot(deferredOutput);
            for (PipelineResult.Diagnostic d : agg.lastErrors()) {
                if ("test-failure".equals(d.code())) continue; // already printed by run-tests
                above.add(ConsoleSpec.renderError(d));
            }
            String failedCoord = result.modules().stream()
                    .filter(m -> !m.success())
                    .map(cc.jumpkick.runtime.ModuleOutcome::coord)
                    .findFirst()
                    .orElse("build");
            String failTail = failureTail(failedCoord, start);
            view.finishPipelineFailure(failTail, above);
            if (session != null) session.wedge(failTail);
            return result.exitCode();
        }
        String okTail = dirtyDirs.isEmpty() ? upToDateTail("all modules", start) : modulesTail(total[0], start);
        view.finishPipelineSuccess(okTail, snapshot(deferredOutput));
        if (session != null) session.wedge(okTail);
        return 0;
    }

    /** Print buffered unit output below the (settled) live region, in completion order. */
    /** Stable copy of the concurrently-appended deferred-output buffer. */
    private static List<String> snapshot(List<String> deferred) {
        synchronized (deferred) {
            return new ArrayList<>(deferred);
        }
    }

    /** Build one graph unit with output buffered. */
    /** Test failures exit 4; everything else exits 1 (mirrors {@link #runPrepared}). */
    private static int exitCodeFor(Pipeline pipeline) {
        var testResult = pipeline.get(TEST_RESULT).orElse(null);
        return testResult != null && !testResult.allPassed() ? 4 : 1;
    }

    /**
     * A finished unit's scroll-back line: {@code ✓ [01 of 16] group:artifact took 16ms}. No leading
     * indent (it's complete, not active); the numerator is zero-padded to the denominator's width;
     * the duration is normalized like every other jk duration ({@link ConsoleSpec#took}). Colors:
     * green check, bright-black {@code [ ]} brackets around a plain {@code NN of MM} count, the
     * {@code group:artifact} plain with a strikethrough to mark it done, and the bright-black italic
     * {@code took …} suffix. A failed unit keeps the red cross and {@code — failed}.
     */
    private static String completionLine(boolean ok, int index, int total, String coord, long millis) {
        var th = Theme.active();
        String mark = Theme.colorize(ok ? Glyphs.CHECK : Glyphs.CROSS, ok ? th.success() : th.error());
        StringBuilder sb = new StringBuilder();
        sb.append(mark)
                .append(' ')
                .append(ConsoleSpec.countBracket(index, total, th))
                .append(' ');
        if (ok) {
            sb.append(Theme.colorize(coord, th.plainWhite().crossedOut()))
                    .append(' ')
                    .append(ConsoleSpec.took(java.time.Duration.ofMillis(millis)));
        } else {
            sb.append(CommandManager.coloredModule(coord)).append(' ').append(Theme.colorize("— failed", th.error()));
        }
        return sb.toString();
    }

    /**
     * Build one (non-workspace) project directory. Engine-hosted forecast + streamed build; test
     * is engine-hosted over the wire.
     */
    private int runForDir(Path dir) throws Exception {
        long startNanos = System.nanoTime(); // captured before the forecast so timing includes it
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Build", "no jk.toml in " + cc.jumpkick.cli.PathDisplay.styledRaw(dir)));
            return Exit.CONFIG;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        try {
            dir = dir.toRealPath();
        } catch (java.io.IOException ignored) {
        }
        String target = buildTarget(buildFile, dir);

        // Single-module fast path: skip the TUI entirely when the engine's forecast says every
        // work step is already cached (stat/CAS lookups engine-side, one round trip here).
        // A distrusting build (--force/--rebuild) never takes the trust-the-cache shortcut.
        if (PipelineConsole.isInteractiveTerminal() && !global.outputIsJson() && !global.force && !global.rebuild) {
            try {
                var forecast = cc.jumpkick.cli.engine.EngineClient.forecast(
                        cc.jumpkick.engine.EnginePaths.current(), dir, cache, buildOpts.skipTests);
                if (!forecast.hasErrors() && !forecast.empty() && forecast.fullyCached()) {
                    String upToDate = buildOk() + ", project up to date " + elapsedSince(startNanos);
                    CliOutput.out(cc.jumpkick.cli.tui.PipelineWedge.chipLine(
                            cc.jumpkick.cli.tui.Glyphs.CHECK,
                            "Build",
                            cc.jumpkick.config.GlobalConfig.nerdfont(),
                            upToDate));
                    if (session != null) session.module(target).wedge(upToDate);
                    return 0;
                }
            } catch (java.io.IOException | RuntimeException ignored) {
                // best-effort shortcut — fall through to the real build
            }
        }

        // The wire has no real Pipeline to read BUILD_OUTCOME/LAYOUT from ahead of time (they arrive
        // on the terminal pipeline-finish event), so projectTail's ingredients are supplied two ways:
        // BUILD_OUTCOME rides the wire (only the engine, which actually ran the pipeline, knows it);
        // LAYOUT is reconstructed independently — it's a pure derivation from dir + the parsed
        // jk.toml, both of which the client already has, and the artifact file it points at lives
        // on the same local filesystem the engine just built into. The engine does the
        // calibration-refine + cache-prune itself on success (it measured the work).
        TestSummary[] testResultHolder = new TestSummary[1];
        String[] buildOutcomeHolder = new String[1];
        cc.jumpkick.engine.protocol.ProjectInfo tailInfo = projectInfoOrNull(dir);
        final Path tailDir = dir;
        final String timelineModule = target;
        ConsoleSpec spec = new ConsoleSpec(
                "Build",
                r -> projectTail(buildOutcomeHolder[0], tailDir, tailInfo),
                r -> PipelineWedge.coord(timelineModule),
                true);
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
        PipelineResult result;
        try {
            result = cc.jumpkick.cli.engine.EngineClient.runSingleBuild(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineClient.SingleBuildRequest(
                            dir,
                            cache,
                            jdksDir,
                            workers != null ? workers : 1,
                            profileName,
                            buildOpts.skipTests,
                            global.verbose,
                            cc.jumpkick.config.SessionContext.current().offline(),
                            cc.jumpkick.config.SessionContext.current().force(),
                            variant,
                            clientEnv),
                    steps -> PipelineConsole.chooseConsoleListener(steps, mode, spec, timelineModule),
                    testResultHolder,
                    buildOutcomeHolder);
        } catch (java.io.IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Build", e.getMessage()));
            if (session != null) session.error(e.getMessage());
            return Exit.SOFTWARE;
        }
        if (session != null) {
            session.module(target).absorb(result);
            if (result.success()) {
                session.wedge(projectTail(buildOutcomeHolder[0], tailDir, tailInfo));
            }
        }
        if (result.success()) return 0;
        // Test failures get exit 4; other failures exit 1.
        TestSummary testResult = testResultHolder[0];
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }

    // ---- success summary -----------------------------------------------

    /** Header module label for the pipeline view: the project's {@code group:artifact}. */
    public static String buildTarget(Path buildFile, Path dir) {
        var info = projectInfoOrNull(dir);
        if (info != null) return info.coord();
        return dir.getFileName() == null ? "" : dir.getFileName().toString();
    }

    /** Engine project summary, or null when unavailable / errored. */
    static cc.jumpkick.engine.protocol.ProjectInfo projectInfoOrNull(Path dir) {
        try {
            cc.jumpkick.engine.protocol.ProjectInfo info =
                    cc.jumpkick.cli.engine.EngineClient.projectInfo(cc.jumpkick.engine.EnginePaths.current(), dir);
            return info.error() != null ? null : info;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Dim italic {@code "took Xms"} from a wall-clock start captured with {@link System#nanoTime()}.
     */
    static String elapsedSince(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        return cc.jumpkick.cli.run.ConsoleSpec.took(java.time.Duration.ofMillis(ms));
    }

    /** The green {@code Build successful} lead that opens every build success message. */
    static String buildOk() {
        return Theme.colorize("Build successful", Theme.active().success());
    }

    /** Success tail {@code Build successful for N modules took T} (work done) — N bold-white. */
    private static String modulesTail(int total, long start) {
        return buildOk()
                + " for "
                + Theme.colorize(String.valueOf(total), Theme.active().focused())
                + " module"
                + (total == 1 ? "" : "s")
                + " "
                + elapsedSince(start);
    }

    /**
     * Success tail {@code Build successful, <scope> up to date took T} — when nothing was rebuilt.
     */
    private static String upToDateTail(String scope, long start) {
        return buildOk() + ", " + scope + " up to date " + elapsedSince(start);
    }

    /**
     * Single-project success tail: {@code Build successful, project up to date} when nothing was
     * rebuilt, else {@code Build successful. Built <artifact>} naming the headline output. No
     * duration — the framework appends it.
     */
    static String projectTail(Pipeline pipeline) {
        return projectTail(
                pipeline.get(BUILD_OUTCOME).orElse(""), pipeline.get(LAYOUT).orElse(null));
    }

    /**
     * As {@link #projectTail(Pipeline)}, but from already-resolved values instead of a live {@code Pipeline}
     * — for an engine-hosted build, where there's no local {@code Pipeline} to read {@code BUILD_OUTCOME}/
     * {@code LAYOUT} off of (they arrive over the wire / get reconstructed independently instead; see
     * {@code EngineClient.runSingleBuild}).
     */
    static String projectTail(String buildOutcome, BuildLayout layout) {
        if ("up-to-date".equals(buildOutcome)) {
            return buildOk() + ", project up to date";
        }
        String art = builtArtifact(layout);
        return buildOk() + (art.isEmpty() ? ", project built" : art);
    }

    /** As above, from the engine's project summary (thin-client path — no client-side layout). */
    static String projectTail(String buildOutcome, Path moduleRoot, cc.jumpkick.engine.protocol.ProjectInfo info) {
        if ("up-to-date".equals(buildOutcome)) {
            return buildOk() + ", project up to date";
        }
        String art = info == null ? "" : builtArtifact(moduleRoot, info);
        return buildOk() + (art.isEmpty() ? ", project built" : art);
    }

    /** The headline artifact from ProjectInfo's candidate paths (native > assembly > jar). */
    static String builtArtifact(Path moduleRoot, cc.jumpkick.engine.protocol.ProjectInfo info) {
        for (String candidate : java.util.List.of(
                info.nativeBinPath(), info.nativeLibPath(), info.assemblyJarPath(), info.mainJarPath())) {
            if (candidate.isEmpty()) continue;
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return ". Built "
                        + Theme.colorize(
                                relForDisplay(moduleRoot, p), Theme.active().path());
            }
        }
        return "";
    }

    /**
     * The headline artifact this build produced, as {@code ". Built <relpath>"} in the path color —
     * the native binary/library if present, else the assembly jar, else the plain jar. Empty when
     * none exists. Shared with {@code jk native}.
     */
    static String builtArtifact(Pipeline pipeline) {
        return builtArtifact(pipeline.get(LAYOUT).orElse(null));
    }

    /** As {@link #builtArtifact(Pipeline)}, from an already-resolved {@link BuildLayout} (or {@code null}). */
    static String builtArtifact(BuildLayout layout) {
        if (layout == null) return "";
        Path art = firstExisting(layout.nativeBinary(), layout.nativeLibrary(), layout.assemblyJar(), layout.mainJar());
        return art == null
                ? ""
                : ". Built "
                        + Theme.colorize(
                                relForDisplay(layout.moduleRoot(), art),
                                Theme.active().path());
    }

    private static Path firstExisting(Path... paths) {
        for (Path p : paths) {
            if (p != null && Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /**
     * Pre-computes hard-link destinations for all application module artifacts in a workspace.
     * For each module dir (excluding {@code workspaceRoot} itself) with {@code project.main},
     * maps each candidate artifact path to its link path under {@code workspaceRoot/target/}.
     * When two or more modules produce the same filename the link name is prefixed with the
     * module's group: {@code group-filename}.
     */

    /**
     * Hard-links (or copies) any application artifacts that exist under {@code moduleDir} to their
     * pre-computed workspace {@code target/} destinations. Best-effort — failures are swallowed
     * because the build has already succeeded.
     */
    private static String relForDisplay(Path base, Path p) {
        try {
            return base.relativize(p).toString().replace(java.io.File.separatorChar, '/');
        } catch (RuntimeException e) {
            return p.getFileName().toString();
        }
    }

    /** Failure tail {@code group:name took T} — coord colored, {@code took T} bright-black. */
    private static String failureTail(String coord, long start) {
        return PipelineWedge.coord(coord) + " " + elapsedSince(start);
    }

    /** Failure tail for a module missing its {@code jk.toml}. */
    private static String noTomlTail(String where, long start) {
        return "— no jk.toml in "
                + where
                + " "
                + Theme.colorize(elapsedSince(start), Theme.active().darkGray());
    }
}

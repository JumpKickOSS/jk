// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.BuildOptions;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.GraalResolver;
import cc.jumpkick.cli.ParallelTestsOpts;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EnginePrewarm;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.JobCancelledException;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.AggregateContext;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.run.CompositeBuildPlanListener;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.SessionMirrorListener;
import cc.jumpkick.cli.tui.BuildNotify;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Coord;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.cli.tui.ModuleScopeHint;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
        List<Opt> opts = new ArrayList<>();
        opts.add(Opt.value("<name>", "Build profile (default auto)", "--profile"));
        opts.add(Opt.value("<N>", "Test JVMs per module (0=auto)", "-w", "--workers"));
        opts.add(CommonOpts.cacheDir());
        opts.add(CommonOpts.jdksDir());
        opts.add(CommonOpts.skipTests());
        opts.add(CommonOpts.keepGoing());
        // Suite/tag widening, same vocabulary as `jk test`: --all = every suite
        // AND no config tag excludes — the "build + run everything" gate.
        opts.add(Opt.value("<name>", "Test suite directory (repeatable)", "-s", "--suite")
                .repeat());
        opts.add(Opt.flag("Run every test suite (tags included)", "--all"));
        opts.add(Opt.flag("Guards + integration: share the commit", "--guard"));
        opts.add(Opt.flag("Guard scripts, no JUnit", "--scripts-only"));
        opts.add(Opt.flag("Skip guard scripts", "--no-scripts"));
        opts.add(Opt.value("<tags>", "JUnit tags to include (CSV)", "--include-tags")
                .splitOn(","));
        opts.add(Opt.value("<tags>", "JUnit tags to exclude (CSV)", "--exclude-tags")
                .splitOn(","));
        opts.add(Opt.flag("Skip profile tag filters", "--no-profile"));
        opts.add(Opt.flag("Package with JVM startup AOT cache", "--aot-cache"));
        // Module concurrency is global -j/--jobs. Cross-module tests default on (C2).
        opts.addAll(ParallelTestsOpts.options());
        opts.addAll(CommonOpts.moduleSelection());
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Nullable
    String profileName;

    @Nullable
    Integer workers;

    @Nullable
    Path cacheDir;

    @Nullable
    Path jdksDir;

    BuildOptions buildOpts;
    GlobalOptions global;
    /** Resolved concurrent module budget (from global -j / JK_JOBS / [engine] jobs). */
    int jobs;

    /** {@code --continue} / {@code [engine] continue}: finish the graph, report every failure. */
    boolean keepGoing;

    boolean parallelTests;
    boolean aotCache;

    @Nullable
    String variant;

    @Nullable
    String affectedSince;

    boolean affectedWip;

    @Nullable
    String modulesSpec;

    @Nullable
    Map<String, String> clientEnv = Map.of();
    /** Best-effort session transcript; null when disabled / no project. */
    private @Nullable CliSessionTranscript session;

    // ---- Entry point ----------------------------------------------------

    @Override
    public int run(Invocation in) throws Exception {
        this.profileName = in.value("profile").orElse(null);
        this.workers = in.value("workers").map(Integer::parseInt).orElse(null);
        this.cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        this.jdksDir = CommonOpts.jdksDirValue(in);
        this.buildOpts = new BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.keepGoing = CommonOpts.keepGoingValue(in);
        this.aotCache = in.isSet("aot-cache");
        this.global = GlobalOptions.from(in);
        this.jobs = global.jobsEffective();
        // C2: cross-module tests parallel by default; --serial-tests opts out (TEST_GATE).
        this.parallelTests = ParallelTestsOpts.enabled(in);
        this.affectedSince = in.value("affected-since").orElse(null);
        this.affectedWip = in.isSet("affected");
        this.modulesSpec = in.value("modules").orElse(null);
        if (ModuleSelectors.bothSelectors(affectedWip, affectedSince)) {
            CommandWedge.printFail("Build", ModuleSelectors.BOTH_MESSAGE);
            return Exit.CONFIG;
        }
        // Suite/tag widening rides the session exactly as `jk test`; the wire
        // adapters read it for both workspace and single-project requests.
        TestSelection testSelection;
        try {
            testSelection = TestCommand.resolveTestSelection(in);
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail("Build", e.getMessage());
            return Exit.CONFIG;
        }
        TestCommand.warnGateOverride(in, global);
        if (testSelection.scriptsOnly()) this.buildOpts.skipTests = true;
        SessionContext.install(
                SessionContext.current().withParallelTests(parallelTests).withTestSelection(testSelection));
        Path startDir = global.workingDir();
        Path buildFile = startDir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(buildFile)) {
            CommandWedge.printFail("Build", "no jk.toml in " + PathDisplay.styledRaw(startDir));
            return Exit.CONFIG;
        }
        // Variant selection (--release / --variant <dim>=<value>): rides the request as a compact
        // selector plus the client-resolved env: values (VariantSelection). Also installed on the
        // ambient session for the in-process paths.
        this.variant = VariantSelection.install(in, startDir);
        this.clientEnv = SessionContext.current().clientEnv();
        this.session = CliSessionTranscript.open(startDir, "build", buildArgv(in));
        if (session != null) session.announceIf(global != null && global.verbose);

        // Workspace root: whole graph. Workspace member: same as `-m <this-module>`.
        ProjectInfo peek = ProjectInfos.orNull(startDir);
        CwdModuleScope.Resolved cwdScope = CwdModuleScope.resolve(startDir, modulesSpec, peek);
        if (cwdScope.inferredFromCwd()) this.modulesSpec = cwdScope.modulesSpec();

        if (peek != null && peek.workspaceRoot()) {
            if (aotCache) {
                CommandWedge.printFail(
                        "Build",
                        "--aot-cache packages a single application project;" + " run it from the module directory.");
                return finishSession(Exit.USAGE);
            }
            return finishSession(runGraphParallel(startDir));
        }
        if (cwdScope.workspaceMember()) {
            return finishSession(runGraphParallel(cwdScope.workspaceRoot()));
        }
        // Single project: -m/--affected-since still validate — `-m bogus` must not
        // silently build; a matching selector is just this project.
        if ((affectedSince != null && !affectedSince.isBlank()) || (modulesSpec != null && !modulesSpec.isBlank())) {
            Selection sel = resolveSelection(startDir);
            if (sel.error() != null) {
                CommandWedge.printFail("Build", sel.error());
                return finishSession(Exit.CONFIG);
            }
            if (sel.empty()) {
                CommandWedge.printOk("Build", selectionEmptyMessage());
                return finishSession(0);
            }
        }
        int code = runForDir(startDir);
        if (code == 0 && aotCache) {
            // Post-build tail (like run's exec): extract layout + training run, client-side
            // the layout inputs come from the engine's exec plan (thin client).
            code = AotCachePackage.run(startDir, cacheDir != null ? cacheDir : JkDirs.cache());
        } else if (code == 0) {
            // A cache the build just invalidated is worse than none: it looks like an artifact
            // and does nothing. Drop it rather than leave it to be discovered later.
            AotCachePackage.discardIfStale(startDir);
        }
        return finishSession(code);
    }

    /** Write the session transcript (best-effort) and return {@code code} unchanged. */
    private int finishSession(int code) {
        return CliSessionTranscript.finish(session, code, global != null && global.verbose);
    }

    /** Compact argv snapshot for details.jsonl (command + selection flags). */
    private List<String> buildArgv(Invocation in) {
        List<String> argv = new ArrayList<>();
        argv.add("build");
        if (in.isSet("skip-tests")) argv.add("--skip-tests");
        if (in.isSet("aot-cache")) argv.add("--aot-cache");
        if (in.isSet("serial-tests") || in.isSet("no-parallel-tests")) argv.add("--serial-tests");
        else if (in.isSet("parallel-tests")) argv.add("--parallel-tests");
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

    /**
     * Build the whole composite + workspace graph: one build graph, scheduled by topological level,
     * independent units built concurrently. Each unit runs buffered — its output is captured and
     * flushed as one contiguous block on completion, so parallel logs never interleave. Tests are
     * serialized across units by default (BuildPlanner's gate); {@code --parallel-tests} lifts that.
     *
     * <p>The whole-workspace lock-staleness guard runs engine-side, inside
     * {@code BuildService.buildWorkspace} (the request carries {@code freshenLock=true}) — the CLI
     * only renders the failure via the standard workspace-errors path. Concurrency comes from
     * {@link #jobs}: {@code -j1} is strict serial, else N-wide.
     */
    private int runGraphParallel(Path entryDir) {
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        // Detect mode first (zero I/O) so we can branch before touching the disk.
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        boolean live = mode == BuildPlanConsole.Mode.AUTO || mode == BuildPlanConsole.Mode.QUIET;

        // --modules / --affected-since resolve identically for live and headless paths:
        // the CI-shaped `jk build -m api --output json` must not silently build everything.
        Selection sel = null;
        if ((affectedSince != null && !affectedSince.isBlank()) || (modulesSpec != null && !modulesSpec.isBlank())) {
            sel = resolveSelection(entryDir);
        }

        // The GraalVM home for every always-native member this build will reach, before any
        // progress UI opens: the resolver may prompt or install, and the request carries the answer
        // (the engine is a daemon and must not pick one from the shell that started it). A member
        // -m / --affected-since leaves out is not asked about: its Graal would be a download and a
        // prompt for a module this build never touches, and a pin it cannot satisfy is not this
        // build's failure.
        List<AlwaysNativeGraal.Module> nativeMembers = AlwaysNativeGraal.fromManifests(entryDir);
        if (sel != null && sel.confines()) {
            nativeMembers = AlwaysNativeGraal.within(nativeMembers, sel.dirs());
        }
        Optional<Map<Path, Path>> graal =
                AlwaysNativeGraal.homes(nativeMembers, new GraalResolver(jdksDir, global.yes)::resolve);
        if (graal.isEmpty()) return Exit.FAILURE; // the resolver printed why
        this.graalHomes = graal.get();

        if (!live) {
            // --output json / --verbose: buffered, non-animated path. The engine drives the whole
            // workspace build (BuildService.buildWorkspace — resolve graph, memory plan, schedule,
            // run each module's plan); WorkspaceRunView renders the append-only block + [k/N] line.
            if (sel != null && sel.error() != null) {
                CommandWedge.printFail("Build", sel.error());
                return Exit.CONFIG;
            }
            if (sel != null && sel.empty()) {
                CommandWedge.printOk("Build", selectionEmptyMessage());
                return 0;
            }
            if (sel != null) {
                ModuleScopeHint.print("building", sel.names(), global != null && global.outputIsJson());
            }
            return runWorkspaceHeadless(entryDir, cache, sel != null ? sel.tokens() : List.of());
        }

        // Live path (AUTO / QUIET): open the TUI immediately so forecast + engine preflight are never
        // silent. Fully-cached builds still settle to a success chip after Checking (no long flash).
        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();

        // Optimize/start the engine before forecast (may show engine wedge once); then Build TUI.
        EnginePrewarm.ensure();

        long buildStart = System.nanoTime();
        JkManager view = JkManager.plan(CliOutput.stdout(), "Build", animate);
        view.setPlanCoord(projectGaLabel(entryDir));
        // OSC 0 tab/window title while the live build region is open.
        view.setWindowTitle("JumpKick - Building " + projectGavLabel(entryDir) + "...");
        if (sel != null && sel.error() == null && !sel.empty()) {
            ModuleScopeHint.show("building", sel.names(), global != null && global.outputIsJson(), view);
        }
        // Do not client-seed a "checking" phase row — the engine owns Checking / Lock / Graph
        // preflight events on the single build RPC. A seed left a stale Checking row until
        // checking 1/1. The live build uses that one request for Checking + Graph + Plan + execute:
        // no separate client forecast RPC. --modules / --affected-since force-include those dirs as
        // the dirty hint; --force/--redo leave the hint null so the engine marks everything dirty.
        AggregateContext earlyAgg = new AggregateContext(view);
        List<String> tokens = List.of();
        if (sel != null) {
            if (sel.error() != null) {
                view.finishBuildPlanFailure(sel.error(), List.of());
                return Exit.CONFIG;
            }
            if (sel.empty()) {
                view.finishBuildPlanSuccess(selectionEmptyMessage(), List.of());
                return 0;
            }
            tokens = sel.tokens();
        }
        return runGraphLive(view, earlyAgg, entryDir, cache, buildStart, tokens);
    }

    /** Resolved {@code -m/--affected-since} selection: at most one of the fields is meaningful. */
    /**
     * What {@code --modules} / {@code --affected-since} selected: {@code dirs} is every module the
     * build will touch (empty with {@code empty}, or when nothing was selected at all), so a client
     * step that works per member — the Graal resolution below — can confine itself to them.
     */
    private record Selection(
            @Nullable String error, boolean empty, List<String> tokens, List<String> names, List<String> dirs) {
        Selection(@Nullable String error, boolean empty, List<String> tokens) {
            this(error, empty, tokens, List.of(), List.of());
        }

        /** True when a selector was given and resolved: the build is confined to {@link #dirs}. */
        boolean confines() {
            return error == null && !tokens.isEmpty();
        }
    }

    private Selection resolveSelection(Path entryDir) {
        List<String> tokens = ModuleSelectors.tokens(modulesSpec, affectedSince, affectedWip);
        if (tokens.isEmpty()) return new Selection(null, false, List.of());
        ProjectInfo info = ProjectInfos.orError(entryDir, modulesSpec, affectedSince, affectedWip);
        if (info == null) {
            return new Selection("cannot load project summary for module selection", false, List.of());
        }
        if (info.error() != null && !info.error().isBlank()) {
            return new Selection(info.error(), false, List.of());
        }
        List<String> names = ModuleScopeHint.namesFrom(info);
        if (info.moduleDirs().isEmpty()) return new Selection(null, true, tokens, names, List.of());
        return new Selection(null, false, tokens, names, info.moduleDirs());
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

    /** Client-resolved GraalVM home per always-native member; empty when the workspace links none. */
    private Map<Path, Path> graalHomes = Map.of();

    private WorkspaceRequest workspaceRequest(Path entryDir, Path cache, List<String> modules) {
        return new WorkspaceRequest(
                        entryDir,
                        cache,
                        jdksDir,
                        workers != null ? Math.max(0, workers) : 0,
                        profileName,
                        buildOpts.skipTests,
                        global.verbose,
                        jobs, // -j / JK_JOBS / [engine] jobs (always ≥ 1)
                        null, // engine forecasts unless modules tokens resolve a dirty set
                        true, // single-process CLI: plan our own worker-JVM memory budget
                        true) // jk build: auto-freshen a stale workspace lock engine-side
                .withVariant(variant, clientEnv)
                .withKeepGoing(keepGoing)
                .withModules(modules)
                .withSpec(WorkspaceSpec.DEFAULT.withGraalByDir(graalHomes));
    }

    /**
     * Non-animated workspace build ({@code --output json} / {@code --verbose}). The engine
     * ({@link cc.jumpkick.runtime.BuildService#buildWorkspace}) owns the whole loop and
     * {@link WorkspaceRunView#headless} renders it; this method owns only {@code jk build}'s own
     * settle vocabulary, which has one arm the live ladder does not — a workspace that declares no
     * modules at all is neither a failure nor a build.
     */
    private int runWorkspaceHeadless(Path entryDir, Path cache, List<String> modules) {
        boolean json = global.outputIsJson();
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Build", true), entryDir, session, json);
        long start = System.nanoTime();
        WorkspaceResult result;
        try {
            result = EngineClient.buildWorkspace(
                    EnginePaths.current(), workspaceRequest(entryDir, cache, modules), run.headless());
        } catch (JobCancelledException e) {
            return headlessCancelled(run, entryDir, start, json);
        } catch (IOException e) {
            long elapsed = BuildTails.elapsedMsSince(start);
            run.finishEvent(false, elapsed);
            CommandWedge.printFail("Build", e.getMessage());
            if (session != null) session.error(Errors.text(e));
            notifyBuild(BuildNotify.Outcome.FAILED, entryDir, 0, elapsed);
            return Exit.SOFTWARE;
        }
        long elapsed = BuildTails.elapsedMsSince(start);
        run.absorb(null, result);
        if (result.cancelled()) {
            return headlessCancelled(run, entryDir, start, json);
        }
        if (!result.errors().isEmpty()) {
            run.finishEvent(false, elapsed);
            if (!json) {
                for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            }
            notifyBuild(BuildNotify.Outcome.FAILED, entryDir, 0, elapsed);
            // exitCode carries the engine's verdict: 2 for graph errors, 6 for an unsatisfiable
            // workspace lock (the freshen guard) — preserved rather than flattened to CONFIG.
            return result.exitCode();
        }
        if (run.planned() == 0) {
            run.finishEvent(true, elapsed);
            if (!json) CliOutput.out("(workspace declares no modules)");
            if (session != null) session.wedge("workspace declares no modules");
            notifyBuild(BuildNotify.Outcome.COMPLETE, entryDir, 0, elapsed);
            return 0;
        }
        if (!result.success()) {
            run.finishEvent(false, elapsed);
            if (!json) {
                // Every failure, not the first: under --continue there is more than one, and the
                // point of finishing the graph is lost if the report does not.
                for (var f : result.modules()) {
                    if (f.success()) continue;
                    String msg = f.coord() + " failed (exit " + f.exitCode() + ")";
                    CommandWedge.printFail("Build", msg);
                    if (session != null) session.error(msg).wedge(msg);
                }
            }
            notifyBuild(BuildNotify.Outcome.FAILED, entryDir, 0, elapsed);
            return result.exitCode();
        }
        run.finishEvent(true, elapsed);
        String okTail = BuildTails.successTail(result.modules(), run.planned(), start);
        if (session != null) session.wedge(okTail);
        // Headless path never opened JkManager — printOk supplies the leading blank.
        if (!json) CommandWedge.printOk("Build", okTail);
        notifyBuild(BuildNotify.Outcome.COMPLETE, entryDir, 0, elapsed);
        return 0;
    }

    private int headlessCancelled(WorkspaceRunView run, Path entryDir, long start, boolean json) {
        long elapsed = BuildTails.elapsedMsSince(start);
        run.finishEvent(false, elapsed);
        if (!json) {
            String took = ConsoleSpec.took(Duration.ofMillis(elapsed));
            CommandWedge.printLine(JkWedge.cancelledJobLine("Build", GlobalConfig.nerdFont(), false, took));
        }
        if (session != null) session.wedge("Build job was cancelled");
        notifyBuild(BuildNotify.Outcome.CANCELLED, entryDir, 0, elapsed);
        return 1;
    }

    /**
     * Live aggregate scheduler: one {@link JkManager} (plan mode) shows a spinner header + a single
     * bar calibrated to the whole graph + a tree of the modules building <em>right now</em>; the
     * tree grows to the parallelism limit and shrinks back to 0 as units drain. Finished modules
     * appear as a live {@code ✓ [k of N]} tail under the wedge — not terminal scrollback and not the
     * process-output peek.
     */
    private int runGraphLive(
            JkManager view, AggregateContext agg, Path entryDir, Path cache, long start, List<String> modules) {
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Build", true), entryDir, session, false);
        WorkspaceResult result;
        try {
            result = EngineClient.buildWorkspace(
                    EnginePaths.current(), workspaceRequest(entryDir, cache, modules), run.live(view, agg));
        } catch (JobCancelledException e) {
            view.finishBuildPlanCancelled(List.of());
            if (session != null) session.wedge("Build job was cancelled");
            notifyBuild(
                    BuildNotify.Outcome.CANCELLED, entryDir, view.etaEstimateMs(), BuildTails.elapsedMsSince(start));
            return 1;
        } catch (IOException e) {
            // finishBuildPlanFailure's own `tail` already gets wrapped in JkWedge.failureLine(planName,
            // nerdFont, tail) internally — pass the plain message, not a pre-rendered failure line
            // (passing one double-wraps it into a garbled "‼ Build ‼ Build..." chip).
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()), List.of());
            if (session != null) session.error(String.valueOf(e.getMessage()));
            notifyBuild(BuildNotify.Outcome.FAILED, entryDir, view.etaEstimateMs(), BuildTails.elapsedMsSince(start));
            return Exit.SOFTWARE;
        }
        long elapsed = BuildTails.elapsedMsSince(start);
        long estimateMs = view.etaEstimateMs();
        var tails = new WorkspaceRunView.Tails(
                (r, planned) -> BuildTails.successTail(r.modules(), planned, start),
                r -> BuildTails.failureTail(WorkspaceRunView.failedSubject(r, "build"), start));
        return run.settleLive(
                view,
                agg,
                result,
                elapsed,
                tails,
                settled -> notifyBuild(
                        settled == WorkspaceRunView.Settled.CANCELLED
                                ? BuildNotify.Outcome.CANCELLED
                                : settled == WorkspaceRunView.Settled.SUCCEEDED
                                        ? BuildNotify.Outcome.COMPLETE
                                        : BuildNotify.Outcome.FAILED,
                        entryDir,
                        estimateMs,
                        elapsed));
    }

    /**
     * Build one (non-workspace) project directory. Engine-hosted forecast + streamed build; test
     * is engine-hosted over the wire.
     */
    private int runForDir(Path dir) throws Exception {
        long startNanos = System.nanoTime(); // captured before the forecast so timing includes it
        Path buildFile = dir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(buildFile)) {
            CommandWedge.printFail("Build", "no jk.toml in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        try {
            dir = dir.toRealPath();
        } catch (IOException ignored) {
        }
        String target = buildTarget(buildFile, dir);

        // Single-module fast path: skip the TUI entirely when the engine's forecast says every
        // work step is already cached (stat/CAS lookups engine-side, one round trip here).
        // A distrusting build (--force/--redo) never takes the trust-the-cache shortcut.
        if (BuildPlanConsole.isInteractiveTerminal() && !global.outputIsJson() && !global.force && !global.rebuild) {
            try {
                var forecast = EngineClient.forecast(EnginePaths.current(), dir, cache, buildOpts.skipTests);
                if (!forecast.hasErrors() && !forecast.empty() && forecast.fullyCached()) {
                    // Fast path skips JkManager (no live region) — must still printOk so the
                    // leading blank matches the full build path.
                    String upToDate =
                            BuildTails.buildOk() + ", project up to date " + BuildTails.elapsedSince(startNanos);
                    CommandWedge.printOk("Build", upToDate);
                    if (session != null) session.module(target).wedge(upToDate);
                    return 0;
                }
            } catch (IOException | RuntimeException ignored) {
                // best-effort shortcut — fall through to the real build
            }
        }

        // The wire has no real BuildPlan to read the build outcome / layout from ahead of time (they
        // arrive on the terminal plan-finish event), so projectTail's ingredients are supplied two
        // ways: the outcome rides the wire (only the engine, which actually ran the plan, knows it);
        // the layout is reconstructed independently from the engine's project summary — a pure
        // derivation from dir + the parsed jk.toml, both of which the client already has, and the
        // artifact file it points at lives on the same local filesystem the engine just built into.
        // The engine does the calibration-refine + cache-prune itself on success (it measured the
        // work).
        TestSummary[] testResultHolder = new TestSummary[1];
        String[] buildOutcomeHolder = new String[1];
        ProjectInfo tailInfo = ProjectInfos.orNull(dir);
        // Same owner as the workspace path: a lone always-native module ships its GraalVM home.
        AlwaysNativeGraal.Module alwaysNative = AlwaysNativeGraal.fromManifest(dir);
        Path graalHome = null;
        if (alwaysNative != null) {
            Optional<Path> resolved = new GraalResolver(jdksDir, global.yes).resolve(dir, alwaysNative.graalSpec());
            if (resolved.isEmpty()) return Exit.FAILURE; // the resolver printed why
            graalHome = resolved.get();
        }
        final Path tailDir = dir;
        final String timelineModule = target;
        ConsoleSpec spec = new ConsoleSpec(
                "Build",
                r -> BuildTails.projectTail(buildOutcomeHolder[0], tailDir, tailInfo),
                r -> Coord.module(timelineModule).renderLine(),
                true);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        BuildPlanResult result;
        try {
            result = EngineClient.runSingleBuild(
                    EnginePaths.current(),
                    new EngineRequests.SingleBuildRequest(
                            dir,
                            cache,
                            jdksDir,
                            workers != null ? Math.max(0, workers) : 0,
                            profileName,
                            buildOpts.skipTests,
                            global.verbose,
                            SessionContext.current().offline(),
                            SessionContext.current().force(),
                            variant,
                            clientEnv,
                            graalHome),
                    steps -> {
                        var console = BuildPlanConsole.chooseConsoleListener(steps, mode, spec, timelineModule);
                        // Mirror plan events into details.jsonl (JSON mode dual-writes itself).
                        if (mode == BuildPlanConsole.Mode.JSON || session == null) return console;
                        return CompositeBuildPlanListener.of(new SessionMirrorListener(session), console);
                    },
                    testResultHolder,
                    buildOutcomeHolder);
        } catch (JobCancelledException e) {
            CommandWedge.printLine(JkWedge.cancelledJobLine("Build", GlobalConfig.nerdFont(), false, ""));
            if (session != null) session.wedge("Build job was cancelled");
            return 1;
        } catch (IOException e) {
            CommandWedge.printFail("Build", e.getMessage());
            if (session != null) session.error(Errors.text(e));
            return Exit.SOFTWARE;
        }
        if (session != null) {
            session.module(target).absorb(result);
            if (result.success()) {
                session.wedge(BuildTails.projectTail(buildOutcomeHolder[0], tailDir, tailInfo));
            }
        }
        if (result.success()) return 0;
        // Cancelled is not a failure shape: settle like the workspace paths do.
        if (result.cancelled()) {
            if (session != null) session.wedge("Build job was cancelled");
            return 1;
        }
        // Test failures get exit 4; other failures exit 1.
        TestSummary testResult = testResultHolder[0];
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }

    // ---- project summary peek -------------------------------------------

    /** Header module label for the plan view: the project's {@code group:artifact}. */
    public static String buildTarget(Path buildFile, Path dir) {
        var info = ProjectInfos.orNull(dir);
        if (info != null) return info.coord();
        return dir.getFileName() == null ? "" : dir.getFileName().toString();
    }

    /** {@code group:name:version} for the OSC window title, from {@code projectInfo}. */
    static String projectGavLabel(Path entryDir) {
        ProjectInfo info = ProjectInfos.orNull(entryDir);
        if (info == null) return "project";
        return label(info.group()) + ":" + label(info.name()) + ":" + label(info.version());
    }

    /** {@code group:name} for desktop notifications (no version). */
    static String projectGaLabel(Path entryDir) {
        ProjectInfo info = ProjectInfos.orNull(entryDir);
        if (info == null) return "project";
        return label(info.group()) + ":" + label(info.name());
    }

    private static String label(String value) {
        return value.isBlank() ? "?" : value;
    }

    /** OSC desktop notify when estimate/elapsed ≥ 1m, or {@code --notify} forces it. */
    private void notifyBuild(BuildNotify.Outcome outcome, Path entryDir, long estimateMs, long elapsedMs) {
        BuildNotify.maybeNotify(CliOutput.stdout(), global, outcome, projectGaLabel(entryDir), estimateMs, elapsedMs);
    }
}

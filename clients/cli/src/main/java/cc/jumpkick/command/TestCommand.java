// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.run.AggregateContext;
import cc.jumpkick.cli.run.AggregateModuleListener;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.run.CompositeBuildPlanListener;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.DashboardCodeLink;
import cc.jumpkick.cli.run.EventLogListener;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.run.SessionMirrorListener;
import cc.jumpkick.cli.run.TestFailureHighlight;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code jk test} — compile main + test sources and run JUnit Platform tests.
 *
 * <p>Runs the same core plan ({@code BuildPlanner.coreBuilder}) as {@code jk build}, in
 * {@code testOnly} mode: parse → sync → jdk → compile (Kotlin and/or Java, main and test) →
 * resources → compile-test → run-tests, stopping short of packaging a jar. Sharing the plan
 * means Kotlin test sources compile and run exactly as they do under {@code jk build} — no
 * separate, Java-only test path to keep in sync.
 *
 * <p>The test-runner's JSONL event stream bridges into the plan's progress bar (the same live
 * console {@code jk compile}/{@code jk build} use): each completion ticks the numerator, each
 * failure becomes a {@code ctx.error}, discovery grows the denominator.
 */
public final class TestCommand implements CliCommand {

    @Override
    public String name() {
        return "test";
    }

    @Override
    public String description() {
        return "Compile and run tests";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<Opt>(List.of(
                Opt.value("<name>", "Build profile (default auto)", "-p", "--profile"),
                Opt.flag("Skip profile tag filters", "--no-profile"),
                Opt.value("<N>", "Test JVMs per module (0=auto)", "-w", "--workers")));
        opts.addAll(cc.jumpkick.cli.ParallelTestsOpts.options());
        opts.add(cc.jumpkick.cli.CommonOpts.cacheDir());
        opts.add(Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                .hide());
        opts.addAll(cc.jumpkick.cli.CommonOpts.moduleSelection());
        opts.add(Opt.value("<name>", "Test suite directory (repeatable)", "-s", "--suite")
                .repeat());
        opts.add(Opt.flag("Run every discovered test suite", "--all"));
        opts.add(Opt.value("<tags>", "JUnit tags to include (CSV)", "--include-tags")
                .splitOn(","));
        opts.add(Opt.value("<tags>", "JUnit tags to exclude (CSV)", "--exclude-tags")
                .splitOn(","));
        opts.addAll(VariantSelection.options());
        return opts;
    }

    String profileName;
    Integer workers;
    boolean parallelTests;
    Path cacheDir;
    Path jdksDir;
    GlobalOptions global;
    int jobs;
    String affectedSince;
    String modulesSpec;
    cc.jumpkick.config.TestSelection testSelection = cc.jumpkick.config.TestSelection.DEFAULT;
    private CliSessionTranscript session;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.profileName = in.value("profile").orElse(null);
        this.workers = in.value("workers").map(Integer::parseInt).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.affectedSince = in.value("affected-since").orElse(null);
        this.modulesSpec = in.value("modules").orElse(null);
        this.global = GlobalOptions.from(in);
        this.jobs = global.jobsEffective();
        // C2: overlap module suites by default; --serial-tests opts out (shared ports/locks).
        this.parallelTests = cc.jumpkick.cli.ParallelTestsOpts.enabled(in);
        try {
            this.testSelection = resolveTestSelection(in);
        } catch (IllegalArgumentException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Test", e.getMessage()));
            return Exit.CONFIG;
        }
        cc.jumpkick.config.SessionContext.install(cc.jumpkick.config.SessionContext.current()
                .withParallelTests(parallelTests)
                .withTestSelection(testSelection));
        Path dir = global.workingDir();
        VariantSelection.install(in, dir);
        var proj = ProjectContext.require(dir, "test").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path buildFile = proj.buildFile();
        this.session = CliSessionTranscript.open(dir, "test", testArgv(in));
        if (session != null) session.announceIf(global != null && global.verbose);
        // No jk-lock.toml guard: the plan's parse-build step resolves the lock on
        // first run and re-locks when jk.toml changed — same as `jk build`/`run`.

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        // 0 = auto (Mill-like min(jobs, classCount) + heap clamp); explicit -w1 keeps one JVM.
        int workerCount = workers != null ? Math.max(0, workers) : 0;

        cc.jumpkick.model.JkBuild entry = cc.jumpkick.config.JkBuildParser.parse(buildFile);

        // Workspace root: fan out to members (JK-1285). Bare `jk test` at the root used to run
        // only the root module's (usually empty) suite and print a green "No tests".
        if (entry.isWorkspaceRoot()) {
            Set<Path> moduleDirs;
            boolean selective = (affectedSince != null && !affectedSince.isBlank())
                    || (modulesSpec != null && !modulesSpec.isBlank());
            if (selective) {
                var selected =
                        cc.jumpkick.config.ModuleSelection.resolveOptional(dir, entry, modulesSpec, affectedSince);
                if (selected != null && !selected.ok()) {
                    CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Test", selected.errorMessage()));
                    if (session != null) session.error(selected.errorMessage());
                    return finishSession(Exit.CONFIG);
                }
                if (selected == null || selected.moduleDirs().isEmpty()) {
                    cc.jumpkick.cli.tui.CommandWedge.printOk("Test", "nothing selected for tests");
                    if (session != null) session.wedge("nothing selected for tests");
                    return finishSession(0);
                }
                moduleDirs = selected.moduleDirs();
            } else {
                moduleDirs = allWorkspaceModuleDirs(dir, entry);
                if (moduleDirs.isEmpty()) {
                    cc.jumpkick.cli.tui.CommandWedge.printOk("Test", "workspace declares no modules");
                    if (session != null) session.wedge("workspace declares no modules");
                    return finishSession(0);
                }
            }
            return finishSession(runWorkspaceTests(dir, entry, cache, workerCount, moduleDirs));
        }

        // Single-module selective: --modules / --affected-since may exclude this dir.
        if ((affectedSince != null && !affectedSince.isBlank()) || (modulesSpec != null && !modulesSpec.isBlank())) {
            var selected = cc.jumpkick.config.ModuleSelection.resolveOptional(dir, entry, modulesSpec, affectedSince);
            if (selected != null && !selected.ok()) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Test", selected.errorMessage()));
                if (session != null) session.error(selected.errorMessage());
                return finishSession(Exit.CONFIG);
            }
            if (selected != null && selected.moduleDirs().isEmpty()) {
                cc.jumpkick.cli.tui.CommandWedge.printOk("Test", "nothing selected for tests");
                if (session != null) session.wedge("nothing selected for tests");
                return finishSession(0);
            }
            if (selected != null
                    && !selected.moduleDirs().contains(dir.toAbsolutePath().normalize())) {
                cc.jumpkick.cli.tui.CommandWedge.printOk("Test", "nothing selected for tests");
                if (session != null) session.wedge("nothing selected for tests");
                return finishSession(0);
            }
        }

        BuildPlanResult result;
        TestSummary testResult;
        // Engine-hosted (Task 3): the wire has no real BuildPlan to attach a console listener to
        // ahead of time, so the listener is chosen once the step list arrives over the socket
        // see EngineBuildListenerAdapter.runTest. testResultHolder is populated (if the run-tests
        // step actually ran) before the terminal plan-finish reaches that listener, exactly
        // mirroring how plan.get(TEST_RESULT) is already populated by the in-process path above.
        TestSummary[] testResultHolder = new TestSummary[1];
        ConsoleSpec spec = new ConsoleSpec(
                "Test", r -> testSummary(testResultHolder[0], r), r -> testFailureMessage(testResultHolder[0], r));
        String module = BuildCommand.buildTarget(buildFile, dir);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        try {
            result = cc.jumpkick.cli.engine.EngineClient.runTest(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineRequests.TestRequest(
                            dir,
                            cache,
                            jdksDir,
                            workerCount,
                            profileName,
                            global.verbose,
                            // Global flags are consumed into the session before dispatch
                            // the session (not the Invocation) is their authority, exactly as
                            // BuildCommand's request wiring reads them.
                            cc.jumpkick.config.SessionContext.current().offline(),
                            cc.jumpkick.config.SessionContext.current().force(),
                            parallelTests,
                            testSelection),
                    steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, module),
                    testResultHolder);
        } catch (IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Test", e.getMessage()));
            if (session != null) session.error(e.getMessage());
            return finishSession(Exit.SOFTWARE);
        }
        testResult = testResultHolder[0];
        if (session != null) {
            session.module(module).absorb(result);
            if (result.success()) {
                session.wedge(testSummary(testResult, result));
            } else {
                session.wedge(testFailureMessage(testResult, result));
            }
        }

        if (result.success()) return finishSession(0);
        // Test failures get exit 4; compile / launcher errors are exit 1.
        if (testResult != null && !testResult.allPassed()) return finishSession(4);
        return finishSession(1);
    }

    private int finishSession(int code) {
        return CliSessionTranscript.finish(session, code, global != null && global.verbose);
    }

    private List<String> testArgv(Invocation in) {
        List<String> argv = new ArrayList<>();
        argv.add("test");
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
        if (in.isSet("serial-tests") || in.isSet("no-parallel-tests")) argv.add("--serial-tests");
        else if (in.isSet("parallel-tests")) argv.add("--parallel-tests");
        in.value("workers").ifPresent(w -> {
            argv.add("--workers");
            argv.add(w);
        });
        return argv;
    }

    /** Absolute dirs of every workspace member (declaration order). */
    static Set<Path> allWorkspaceModuleDirs(Path workspaceRoot, cc.jumpkick.model.JkBuild entry) {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        if (!entry.isWorkspaceRoot()) return dirs;
        Path root = workspaceRoot.toAbsolutePath().normalize();
        for (String m : entry.workspaceOpt().orElseThrow().modules()) {
            if (m == null || m.isBlank()) continue;
            dirs.add(root.resolve(m.strip()).normalize());
        }
        return dirs;
    }

    /**
     * Workspace tests: one engine {@code buildWorkspace} RPC with {@code testOnly=true} — same
     * live aggregate TUI as {@code jk build} (single {@link JkManager} header + bar + module
     * tree), terminal target {@code run-tests} per module instead of package.
     */
    private int runWorkspaceTests(Path entryDir, JkBuild entryBuild, Path cache, int workerCount, Set<Path> dirtyDirs)
            throws IOException, InterruptedException {
        if (dirtyDirs == null || dirtyDirs.isEmpty()) return 0;
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        boolean live = mode == BuildPlanConsole.Mode.AUTO || mode == BuildPlanConsole.Mode.QUIET;
        if (!live) {
            return runWorkspaceTestsHeadless(entryDir, entryBuild, cache, workerCount, dirtyDirs);
        }
        return runWorkspaceTestsLive(entryDir, entryBuild, cache, workerCount, dirtyDirs);
    }

    /** Live TTY: one JkManager "Test" region — mirrors {@link BuildCommand} workspace live path. */
    private int runWorkspaceTestsLive(
            Path entryDir, JkBuild entryBuild, Path cache, int workerCount, Set<Path> dirtyDirs) {
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        cc.jumpkick.cli.engine.EnginePrewarm.ensure();
        long start = System.nanoTime();
        JkManager view = JkManager.plan(CliOutput.stdout(), "Test", animate);
        view.setWindowTitle("JumpKick - Testing " + BuildCommand.projectGavLabel(entryDir, entryBuild) + "...");
        AggregateContext agg = new AggregateContext(view);
        Map<Path, List<String>> buffers = new ConcurrentHashMap<>();
        List<String> deferredOutput = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger completed = new AtomicInteger();
        int[] total = {0};
        var request = workspaceTestRequest(entryDir, entryBuild, cache, workerCount, dirtyDirs);
        WorkspaceResult result;
        try {
            result = cc.jumpkick.cli.engine.EngineClient.buildWorkspace(
                    cc.jumpkick.engine.EnginePaths.current(),
                    request,
                    new cc.jumpkick.runtime.WorkspaceBuildListener() {
                        @Override
                        public void onPreflight(String stage, int done, int totalUnits, String label) {
                            agg.preflight(stage, done, totalUnits, label);
                        }

                        @Override
                        public void onWorkspaceProgress(cc.jumpkick.runtime.WorkspaceProgressTracker.Snapshot snap) {
                            agg.applySnapshot(snap);
                            JsonlShape.emitJsonl(
                                    JsonlShape.workspaceProgress(
                                            entryDir.toString(),
                                            snap.numerator(),
                                            snap.denominator(),
                                            snap.phase(),
                                            snap.modulesComplete(),
                                            snap.modulesTotal()),
                                    false);
                        }

                        @Override
                        public void onPlan(List<cc.jumpkick.runtime.ModulePlan> plan) {
                            total[0] = plan.size();
                            JsonlShape.emitJsonl(JsonlShape.workspaceStart(plan.size()), false);
                        }

                        @Override
                        public void onEtaEstimate(long millis) {
                            view.setRemainingWorkEstimate(millis);
                        }

                        @Override
                        public cc.jumpkick.run.BuildPlanListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                            var log = EventLogListener.open(m.cache(), m.plan().name());
                            List<String> buf = Collections.synchronizedList(new ArrayList<>());
                            buffers.put(m.dir(), buf);
                            var lis = new AggregateModuleListener(
                                    agg, m.coord(), m.plan().steps(), m.weight());
                            lis.bufferOutputInto(buf);
                            JsonlShape.emitJsonl(JsonlShape.moduleStart(m.dir().toString(), m.coord()), false);
                            SessionMirrorListener mirror = session == null ? null : new SessionMirrorListener(session);
                            return CompositeBuildPlanListener.of(CompositeBuildPlanListener.of(lis, mirror), log);
                        }

                        @Override
                        public void onModuleFinish(cc.jumpkick.runtime.ModuleOutcome o) {
                            JsonlShape.emitJsonl(
                                    JsonlShape.moduleFinish(o.dir().toString(), o.coord(), o.success(), o.millis()),
                                    false);
                            List<String> buf = buffers.getOrDefault(o.dir(), List.of());
                            String completion = BuildCommand.completionLine(
                                    o.success(), completed.incrementAndGet(), total[0], o.coord(), o.millis());
                            if (view.animating()) {
                                view.addCompletion(completion);
                                // Paint with module link context now; snapshot() re-paint is a no-op
                                // on already-styled lines (no Test Failure sentinel left).
                                synchronized (buf) {
                                    if (!buf.isEmpty()) {
                                        try (var link = DashboardCodeLink.open(entryDir, o.dir())) {
                                            deferredOutput.addAll(TestFailureHighlight.paintLines(buf));
                                        }
                                    }
                                }
                            } else {
                                List<String> painted;
                                synchronized (buf) {
                                    try (var link = DashboardCodeLink.open(entryDir, o.dir())) {
                                        painted = TestFailureHighlight.paintLines(buf);
                                    }
                                }
                                StringBuilder block = new StringBuilder();
                                for (String l : painted) block.append(l).append('\n');
                                block.append(completion);
                                view.writeAbove(block.toString());
                            }
                        }
                    });
        } catch (cc.jumpkick.cli.engine.JobCancelledException e) {
            view.finishBuildPlanCancelled(List.of());
            if (session != null) session.wedge("Test job was cancelled");
            return 1;
        } catch (IOException e) {
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()), List.of());
            if (session != null) session.error(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        if (session != null) {
            for (var m : result.modules()) session.module(m.coord());
            for (String err : result.errors()) session.error(err);
            for (BuildPlanResult.Diagnostic d : agg.lastErrors()) {
                session.error(d.step(), d.code(), d.message());
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        List<String> above = snapshot(deferredOutput);
        if (result.cancelled()) {
            view.finishBuildPlanCancelled(above);
            if (session != null) session.wedge("Test job was cancelled");
            JsonlShape.emitJsonl(JsonlShape.workspaceFinish(false, elapsedMs, total[0]), false);
            return 1;
        }
        if (!result.errors().isEmpty()) {
            List<String> errs = new ArrayList<>(above);
            for (String err : result.errors()) errs.add(ConsoleSpec.errorLine("composite", err));
            view.finishBuildPlanFailure("dependency resolution failed", errs);
            if (session != null) session.wedge("dependency resolution failed");
            JsonlShape.emitJsonl(JsonlShape.workspaceFinish(false, elapsedMs, total[0]), false);
            return result.exitCode();
        }
        if (!result.success()) {
            List<String> failAbove = new ArrayList<>(above);
            for (BuildPlanResult.Diagnostic d : agg.lastErrors()) {
                if ("test-failure".equals(d.code())) continue;
                failAbove.add(ConsoleSpec.renderError(d));
            }
            String failTail = workspaceTestFailureTail(result, elapsedMs);
            view.finishBuildPlanFailure(failTail, failAbove);
            if (session != null) session.wedge(failTail);
            JsonlShape.emitJsonl(JsonlShape.workspaceFinish(false, elapsedMs, total[0]), false);
            return result.exitCode();
        }
        String okTail = workspaceTestSuccessTail(result, total[0], elapsedMs);
        view.finishBuildPlanSuccess(okTail, above);
        if (session != null) session.wedge(okTail);
        JsonlShape.emitJsonl(JsonlShape.workspaceFinish(true, elapsedMs, total[0]), false);
        return 0;
    }

    /** Headless / JSON: same workspace RPC as live, no JkManager chrome. */
    private int runWorkspaceTestsHeadless(
            Path entryDir, JkBuild entryBuild, Path cache, int workerCount, Set<Path> dirtyDirs) {
        boolean json = global != null && global.outputIsJson();
        long start = System.nanoTime();
        int[] total = {0};
        // Per-module console buffers (BuildCommand's headless pattern): modules stream
        // concurrently, so output is buffered and printed as one block per module finish.
        var buffers = new ConcurrentHashMap<Path, List<String>>();
        var done = new AtomicInteger();
        var request = workspaceTestRequest(entryDir, entryBuild, cache, workerCount, dirtyDirs);
        WorkspaceResult result;
        try {
            result = cc.jumpkick.cli.engine.EngineClient.buildWorkspace(
                    cc.jumpkick.engine.EnginePaths.current(),
                    request,
                    new cc.jumpkick.runtime.WorkspaceBuildListener() {
                        @Override
                        public void onWorkspaceProgress(cc.jumpkick.runtime.WorkspaceProgressTracker.Snapshot snap) {
                            cc.jumpkick.cli.run.LiveProgress.get().apply(snap);
                            JsonlShape.emitJsonl(
                                    JsonlShape.workspaceProgress(
                                            entryDir.toString(),
                                            snap.numerator(),
                                            snap.denominator(),
                                            snap.phase(),
                                            snap.modulesComplete(),
                                            snap.modulesTotal()),
                                    json);
                        }

                        @Override
                        public void onPlan(List<cc.jumpkick.runtime.ModulePlan> plan) {
                            total[0] = plan.size();
                            JsonlShape.emitJsonl(JsonlShape.workspaceStart(plan.size()), json);
                        }

                        @Override
                        public cc.jumpkick.run.BuildPlanListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                            var log = EventLogListener.open(m.cache(), m.plan().name());
                            JsonlShape.emitJsonl(JsonlShape.moduleStart(m.dir().toString(), m.coord()), json);
                            if (json) {
                                return CompositeBuildPlanListener.of(
                                        new cc.jumpkick.cli.run.JsonlListener(System.out, false), log);
                            }
                            List<String> buf = Collections.synchronizedList(new ArrayList<>());
                            buffers.put(m.dir(), buf);
                            var outLis = new cc.jumpkick.run.BuildPlanListener() {
                                @Override
                                public synchronized void output(String step, String line) {
                                    buf.add(line);
                                }

                                @Override
                                public synchronized void warn(String step, String code, String message) {
                                    buf.add("  " + cc.jumpkick.cli.tui.Glyphs.BANG + " " + step + ": " + message);
                                }

                                @Override
                                public synchronized void error(String step, String code, String message) {
                                    if ("test-failure".equals(code)) return;
                                    buf.add("  " + cc.jumpkick.cli.tui.Glyphs.CROSS + " " + step + ": " + message);
                                }
                            };
                            SessionMirrorListener mirror = session == null ? null : new SessionMirrorListener(session);
                            return CompositeBuildPlanListener.of(CompositeBuildPlanListener.of(outLis, mirror), log);
                        }

                        @Override
                        public void onModuleFinish(cc.jumpkick.runtime.ModuleOutcome o) {
                            JsonlShape.emitJsonl(
                                    JsonlShape.moduleFinish(o.dir().toString(), o.coord(), o.success(), o.millis()),
                                    json);
                            if (json) return;
                            List<String> buf = buffers.getOrDefault(o.dir(), List.of());
                            List<String> painted;
                            try (var link = DashboardCodeLink.open(entryDir, o.dir())) {
                                painted = TestFailureHighlight.paintLines(buf);
                            }
                            synchronized (BuildCommand.OUT_LOCK) {
                                for (String line : painted) CliOutput.out(line);
                                CliOutput.out(BuildCommand.completionLine(
                                        o.success(), done.incrementAndGet(), total[0], o.coord(), o.millis()));
                            }
                        }
                    });
        } catch (IOException e) {
            if (!json) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Test", e.getMessage()));
            }
            if (session != null) session.error(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        JsonlShape.emitJsonl(JsonlShape.workspaceFinish(result.success(), ms, total[0]), json);
        if (session != null) {
            for (var m : result.modules()) session.module(m.coord());
            for (String err : result.errors()) session.error(err);
        }
        if (result.success()) {
            if (!json) {
                cc.jumpkick.cli.tui.CommandWedge.printOk("Test", workspaceTestSuccessTail(result, total[0], ms));
            }
            return 0;
        }
        if (!json) {
            // Workspace-level errors (graph/lock problems) never reach a module listener —
            // print them before the wedge or a failing run shows no diagnostic at all.
            for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Test", workspaceTestFailureTail(result, ms)));
        }
        return result.exitCode();
    }

    private WorkspaceRequest workspaceTestRequest(
            Path entryDir, JkBuild entryBuild, Path cache, int workerCount, Set<Path> dirtyDirs) {
        String variant = cc.jumpkick.config.SessionContext.current().variant();
        if (variant == null) variant = "";
        Map<String, String> clientEnv =
                cc.jumpkick.config.SessionContext.current().clientEnv();
        int concurrency = parallelTests ? jobs : 1;
        return new WorkspaceRequest(
                        entryDir,
                        entryBuild,
                        cache,
                        jdksDir,
                        workerCount,
                        profileName,
                        /* skipTests */ false,
                        global.verbose,
                        concurrency,
                        dirtyDirs,
                        true,
                        true)
                .withTestOnly(true)
                .withVariant(variant, clientEnv);
    }

    private static List<String> snapshot(List<String> deferred) {
        synchronized (deferred) {
            return new ArrayList<>(cc.jumpkick.cli.run.TestFailureHighlight.paintLines(deferred));
        }
    }

    private static String workspaceTestSuccessTail(WorkspaceResult result, int planned, long elapsedMs) {
        int n = result.modules() == null ? 0 : result.modules().size();
        if (n == 0 || planned == 0) {
            return "No tests to run";
        }
        String took = ConsoleSpec.took(Duration.ofMillis(elapsedMs));
        if (n == 1) {
            return "Tests passed " + took;
        }
        return "Tests passed for " + n + " modules " + took;
    }

    private static String workspaceTestFailureTail(WorkspaceResult result, long elapsedMs) {
        String failed = result.modules() == null
                ? "tests"
                : result.modules().stream()
                        .filter(m -> !m.success())
                        .map(cc.jumpkick.runtime.ModuleOutcome::coord)
                        .findFirst()
                        .orElse("tests");
        return failed + " — failed " + ConsoleSpec.took(Duration.ofMillis(elapsedMs));
    }

    /**
     * Success result line (sans the leading ✓): {@code Passed N tests in 32s}, or {@code No tests in
     * <t>} for a project with no test sources. Takes the resolved {@link TestSummary}
     * directly (rather than a {@code BuildPlan} to look it up from) so both the in-process path (which
     * reads it off {@code plan.get(TEST_RESULT)}) and the engine-hosted path (which has no real
     * {@code BuildPlan}, only a wire-populated holder) share this one rendering method.
     */
    static String testSummary(TestSummary testResult, BuildPlanResult result) {
        if (testResult == null || testResult.total() == 0) return "No tests";
        long total = testResult.total();
        String passed = Theme.colorize("Passed", Theme.active().focused());
        return passed + " " + total + " test" + (total == 1 ? "" : "s");
    }

    static String testFailureMessage(TestSummary testResult, BuildPlanResult result) {
        return (testResult != null && !testResult.allPassed()) ? "Tests failed" : "Build failed";
    }

    /**
     * CLI + {@code [test]} / profile tags → {@link cc.jumpkick.config.TestSelection}. Throws if
     * {@code --all} and {@code --suite} are both set.
     *
     * <p>Precedence (each layer replaces the previous for a given list when it speaks):
     *
     * <ol>
     *   <li>{@code [test] include-tags} / {@code exclude-tags} — baseline
     *   <li>Active profile — replaces a list only when that key is present (empty list clears)
     *   <li>CLI {@code --include-tags} / {@code --exclude-tags} — replace that list for the run
     * </ol>
     *
     * Auto profile defers when CLI set any tag option so explicit CLI selection is not overridden
     * by profile filters.
     */
    static cc.jumpkick.config.TestSelection resolveTestSelection(Invocation in) {
        boolean all = in.isSet("all");
        List<String> suites = new ArrayList<>(in.values("suite"));
        boolean cliInclude = in.has("include-tags");
        boolean cliExclude = in.has("exclude-tags");
        if (all && !suites.isEmpty()) {
            throw new IllegalArgumentException("--all and --suite cannot be combined");
        }
        Path wd = GlobalOptions.from(in).workingDir();
        Path toml = wd.resolve("jk.toml");
        var baseline = cc.jumpkick.config.JkBuildParser.parseTestTags(toml);
        List<String> include = new ArrayList<>(baseline.includeTags());
        List<String> exclude = new ArrayList<>(baseline.excludeTags());
        // Track whether any layer explicitly resolved the tag lists. Only then is the selection
        // final (tagsResolved) — otherwise the engine may still fold per-module [test] tags in
        // for workspace members (a root with no tags must not erase a module's own filters).
        boolean spoke =
                !baseline.includeTags().isEmpty() || !baseline.excludeTags().isEmpty();
        // Profile when selected / auto. --no-profile skips. AUTO profile defers when CLI set any
        // tag option so e.g. `jk test --include-tags slow` is not beaten by profile filters.
        boolean cliTags = cliInclude || cliExclude;
        try {
            if (!in.isSet("no-profile") && Files.isRegularFile(toml)) {
                var build = cc.jumpkick.config.JkBuildParser.parse(toml);
                String explicit = in.value("profile").orElse(null);
                boolean explicitProfile = explicit != null && !explicit.isBlank();
                String name = explicitProfile ? explicit : cc.jumpkick.model.Profiles.autoSelect(System.getenv());
                boolean apply = name != null && build.profiles().contains(name) && (explicitProfile || !cliTags);
                if (apply) {
                    var p = build.profiles().resolve(name);
                    if (p.includeTagsSet()) {
                        include = new ArrayList<>(p.includeTags());
                        spoke = true; // present-but-empty clears — must survive to the runner
                    }
                    if (p.excludeTagsSet()) {
                        exclude = new ArrayList<>(p.excludeTags());
                        spoke = true;
                    }
                }
            }
        } catch (Exception ignored) {
            // profile optional
        }
        if (cliInclude) {
            include = new ArrayList<>(in.values("include-tags"));
            spoke = true;
        }
        if (cliExclude) {
            // `--exclude-tags ""` is the explicit CLI clear: blank values normalize away, the
            // spoke flag keeps the empty result authoritative.
            exclude = new ArrayList<>(in.values("exclude-tags"));
            spoke = true;
        }
        return cc.jumpkick.config.TestSelection.of(suites, all, include, exclude, spoke);
    }
}

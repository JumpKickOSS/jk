// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ParallelTestsOpts;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EnginePrewarm;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.JobCancelledException;
import cc.jumpkick.cli.run.AggregateContext;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.cli.tui.ModuleScopeHint;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.ProjectInfo;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Profiles;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code jk test} — compile main + test sources and run JUnit Platform tests.
 *
 * <p>Runs the same core plan ({@code TrainPlans.coreBuilder}) as {@code jk build}, in
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
        opts.addAll(ParallelTestsOpts.options());
        opts.add(CommonOpts.cacheDir());
        opts.add(CommonOpts.jdksDir());
        opts.addAll(CommonOpts.moduleSelection());
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
    TestSelection testSelection = TestSelection.DEFAULT;
    private CliSessionTranscript session;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.profileName = in.value("profile").orElse(null);
        this.workers = in.value("workers").map(Integer::parseInt).orElse(null);
        this.cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        this.jdksDir = CommonOpts.jdksDirValue(in);
        this.affectedSince = in.value("affected-since").orElse(null);
        this.modulesSpec = in.value("modules").orElse(null);
        this.global = GlobalOptions.from(in);
        this.jobs = global.jobsEffective();
        // C2: overlap module suites by default; --serial-tests opts out (shared ports/locks).
        this.parallelTests = ParallelTestsOpts.enabled(in);
        try {
            this.testSelection = resolveTestSelection(in);
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail("Test", e.getMessage());
            return Exit.CONFIG;
        }
        SessionContext.install(
                SessionContext.current().withParallelTests(parallelTests).withTestSelection(testSelection));
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

        var info = BuildCommand.projectInfoOrNull(dir);
        CwdModuleScope.Resolved cwdScope = CwdModuleScope.resolve(dir, modulesSpec, info);
        if (cwdScope.inferredFromCwd()) this.modulesSpec = cwdScope.modulesSpec();

        // Workspace root: fan out to members so a bare `jk test` is not just the root
        // module's (usually empty) suite. Member dir: same as `jk test -m <this-module>`.
        if (info != null && info.workspaceRoot()) {
            return finishSession(runSelectedWorkspaceTests(dir, info, cache, workerCount));
        }
        if (cwdScope.workspaceMember()) {
            return finishSession(runSelectedWorkspaceTests(cwdScope.workspaceRoot(), null, cache, workerCount));
        }

        // Single-module selective: --modules / --affected-since may exclude this dir.
        if ((affectedSince != null && !affectedSince.isBlank()) || (modulesSpec != null && !modulesSpec.isBlank())) {
            var sel = BuildCommand.projectInfoOrError(dir, modulesSpec, affectedSince);
            if (sel.error() != null && !sel.error().isBlank()) {
                CommandWedge.printFail("Test", sel.error());
                if (session != null) session.error(sel.error());
                return finishSession(Exit.CONFIG);
            }
            if (sel.moduleDirs().isEmpty()) {
                CommandWedge.printOk("Test", "nothing selected for tests");
                if (session != null) session.wedge("nothing selected for tests");
                return finishSession(0);
            }
            Path here = dir.toAbsolutePath().normalize();
            boolean hit = sel.moduleDirs().stream()
                    .anyMatch(d -> Path.of(d).toAbsolutePath().normalize().equals(here));
            if (!hit) {
                CommandWedge.printOk("Test", "nothing selected for tests");
                if (session != null) session.wedge("nothing selected for tests");
                return finishSession(0);
            }
        }

        BuildPlanResult result;
        TestSummary testResult;
        // Engine-hosted (Task 3): the wire has no real BuildPlan to attach a console listener to
        // ahead of time, so the listener is chosen once the step list arrives over the socket
        // see EngineJobs.runTest. testResultHolder is populated (if the run-tests
        // step actually ran) before the terminal plan-finish reaches that listener, exactly
        // mirroring how plan.get(TEST_RESULT) is already populated by the in-process path above.
        TestSummary[] testResultHolder = new TestSummary[1];
        ConsoleSpec spec = new ConsoleSpec(
                "Test", r -> testSummary(testResultHolder[0], r), r -> testFailureMessage(testResultHolder[0], r));
        String module = BuildCommand.buildTarget(buildFile, dir);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        try {
            result = EngineClient.runTest(
                    EnginePaths.current(),
                    new EngineRequests.TestRequest(
                            dir,
                            cache,
                            jdksDir,
                            workerCount,
                            profileName,
                            global.verbose,
                            // Global flags are consumed into the session before dispatch
                            // the session (not the Invocation) is their authority, exactly as
                            // BuildCommand's request wiring reads them.
                            SessionContext.current().offline(),
                            SessionContext.current().force(),
                            parallelTests,
                            testSelection),
                    steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, module),
                    testResultHolder);
        } catch (IOException e) {
            CommandWedge.printFail("Test", e.getMessage());
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

    /**
     * Validate {@code -m}/{@code --affected-since} (including cwd inference) and run workspace
     * tests from {@code entryDir}. {@code rootInfo} is the unfiltered workspace peek when already
     * loaded (root invocation); null when the caller is a member dir.
     */
    private int runSelectedWorkspaceTests(Path entryDir, ProjectInfo rootInfo, Path cache, int workerCount)
            throws IOException, InterruptedException {
        List<String> tokens = ModuleSelectors.tokens(modulesSpec, affectedSince);
        if (!tokens.isEmpty()) {
            var sel = BuildCommand.projectInfoOrError(entryDir, modulesSpec, affectedSince);
            if (sel.error() != null && !sel.error().isBlank()) {
                CommandWedge.printFail("Test", sel.error());
                if (session != null) session.error(sel.error());
                return Exit.CONFIG;
            }
            if (sel.moduleDirs().isEmpty()) {
                CommandWedge.printOk("Test", "nothing selected for tests");
                if (session != null) session.wedge("nothing selected for tests");
                return 0;
            }
        } else if (rootInfo != null && rootInfo.moduleDirs().isEmpty()) {
            CommandWedge.printOk("Test", "workspace declares no modules");
            if (session != null) session.wedge("workspace declares no modules");
            return 0;
        }
        return runWorkspaceTests(entryDir, cache, workerCount, tokens);
    }

    /**
     * Workspace tests: one engine {@code buildWorkspace} RPC with {@code testOnly=true} — same
     * live aggregate TUI as {@code jk build} (single {@link JkManager} header + bar + module
     * tree), terminal target {@code run-tests} per module instead of package.
     */
    private int runWorkspaceTests(Path entryDir, Path cache, int workerCount, List<String> modules)
            throws IOException, InterruptedException {
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        boolean live = mode == BuildPlanConsole.Mode.AUTO || mode == BuildPlanConsole.Mode.QUIET;
        List<String> scopeNames = List.of();
        if (modules != null && !modules.isEmpty()) {
            scopeNames =
                    ModuleScopeHint.namesFrom(BuildCommand.projectInfoOrError(entryDir, modulesSpec, affectedSince));
            if (!live) {
                ModuleScopeHint.print("testing", scopeNames, global != null && global.outputIsJson());
            }
        }
        if (!live) {
            return runWorkspaceTestsHeadless(entryDir, cache, workerCount, modules);
        }
        return runWorkspaceTestsLive(entryDir, cache, workerCount, modules, scopeNames);
    }

    /** Live TTY: one JkManager "Test" region — the same {@link WorkspaceRunView} {@code jk build} drives. */
    private int runWorkspaceTestsLive(
            Path entryDir, Path cache, int workerCount, List<String> modules, List<String> scopeNames) {
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        EnginePrewarm.ensure();
        long start = System.nanoTime();
        JkManager view = JkManager.plan(CliOutput.stdout(), "Test", animate);
        view.setPlanCoord(BuildCommand.projectGaLabel(entryDir));
        view.setWindowTitle("JumpKick - Testing " + BuildCommand.projectGavLabel(entryDir) + "...");
        ModuleScopeHint.show("testing", scopeNames, global != null && global.outputIsJson(), view);
        AggregateContext agg = new AggregateContext(view);
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Test", true, true), entryDir, session, false);
        var request = workspaceTestRequest(entryDir, cache, workerCount, modules);
        WorkspaceResult result;
        try {
            result = EngineClient.buildWorkspace(EnginePaths.current(), request, run.live(view, agg));
        } catch (JobCancelledException e) {
            view.finishBuildPlanCancelled(List.of());
            if (session != null) session.wedge("Test job was cancelled");
            return 1;
        } catch (IOException e) {
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()), List.of());
            if (session != null) session.error(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        var tails = new WorkspaceRunView.Tails(
                (r, planned) -> workspaceTestSuccessTail(r, planned, elapsedMs),
                r -> workspaceTestFailureTail(r, elapsedMs));
        return run.settleLive(view, agg, result, elapsedMs, tails, settled -> {});
    }

    /** Headless / JSON: same workspace RPC as live, no JkManager chrome. */
    private int runWorkspaceTestsHeadless(Path entryDir, Path cache, int workerCount, List<String> modules) {
        boolean json = global != null && global.outputIsJson();
        long start = System.nanoTime();
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Test", true, true), entryDir, session, json);
        var request = workspaceTestRequest(entryDir, cache, workerCount, modules);
        WorkspaceResult result;
        try {
            result = EngineClient.buildWorkspace(EnginePaths.current(), request, run.headless());
        } catch (IOException e) {
            if (!json) {
                CommandWedge.printFail("Test", e.getMessage());
            }
            if (session != null) session.error(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        run.finishEvent(result.success(), ms);
        run.absorb(null, result);
        if (result.success()) {
            if (!json) {
                CommandWedge.printOk("Test", workspaceTestSuccessTail(result, run.planned(), ms));
            }
            return 0;
        }
        if (!json) {
            // Workspace-level errors (graph/lock problems) never reach a module listener —
            // print them before the wedge or a failing run shows no diagnostic at all.
            for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            CommandWedge.printFail("Test", workspaceTestFailureTail(result, ms));
        }
        return result.exitCode();
    }

    private WorkspaceRequest workspaceTestRequest(Path entryDir, Path cache, int workerCount, List<String> modules) {
        String variant = SessionContext.current().variant();
        if (variant == null) variant = "";
        Map<String, String> clientEnv = SessionContext.current().clientEnv();
        int concurrency = parallelTests ? jobs : 1;
        return new WorkspaceRequest(
                        entryDir,
                        cache,
                        jdksDir,
                        workerCount,
                        profileName,
                        /* skipTests */ false,
                        global.verbose,
                        concurrency,
                        null,
                        true,
                        true)
                .withTestOnly(true)
                .withVariant(variant, clientEnv)
                .withModules(modules);
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
        return WorkspaceRunView.failedCoord(result, "tests") + " — failed "
                + ConsoleSpec.took(Duration.ofMillis(elapsedMs));
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
    static TestSelection resolveTestSelection(Invocation in) {
        boolean all = in.isSet("all");
        List<String> suites = new ArrayList<>(in.values("suite"));
        boolean cliInclude = in.has("include-tags");
        boolean cliExclude = in.has("exclude-tags");
        if (all && !suites.isEmpty()) {
            throw new IllegalArgumentException("--all and --suite cannot be combined");
        }
        Path wd = GlobalOptions.from(in).workingDir();
        Path toml = wd.resolve(ManifestPaths.MANIFEST);
        String explicit = in.value("profile").orElse(null);
        boolean explicitProfile = explicit != null && !explicit.isBlank();
        String profileName = explicitProfile ? explicit : Profiles.autoSelect(System.getenv());
        List<String> scanKeys = new ArrayList<>();
        scanKeys.add("test.include-tags");
        scanKeys.add("test.exclude-tags");
        if (profileName != null && !profileName.isBlank()) {
            scanKeys.add("profiles." + profileName + ".include-tags");
            scanKeys.add("profiles." + profileName + ".exclude-tags");
        }
        var scan = TomlScan.scan(toml, scanKeys.toArray(String[]::new));
        List<String> include = new ArrayList<>(scan.stringArray("test.include-tags"));
        List<String> exclude = new ArrayList<>(scan.stringArray("test.exclude-tags"));
        // Track whether any layer explicitly resolved the tag lists. Only then is the selection
        // final (tagsResolved) — otherwise the engine may still fold per-module [test] tags in
        // for workspace members (a root with no tags must not erase a module's own filters).
        boolean spoke = scan.hasKey("test.include-tags") || scan.hasKey("test.exclude-tags");
        boolean cliTags = cliInclude || cliExclude;
        boolean applyProfile = !in.isSet("no-profile") && profileName != null && (explicitProfile || !cliTags);
        if (applyProfile) {
            String incKey = "profiles." + profileName + ".include-tags";
            String excKey = "profiles." + profileName + ".exclude-tags";
            if (scan.hasKey(incKey)) {
                include = new ArrayList<>(scan.stringArray(incKey));
                spoke = true;
            }
            if (scan.hasKey(excKey)) {
                exclude = new ArrayList<>(scan.stringArray(excKey));
                spoke = true;
            }
        }
        if (cliInclude) {
            include = new ArrayList<>(in.values("include-tags"));
            // An explicit --include-tags overrides a baseline/profile exclude of the same tag:
            // composing them hands JUnit include ∧ exclude of one tag, which selects nothing —
            // and the run still exited 0 (JK-2274). An explicit --exclude-tags below still wins
            // over the include (it replaces the exclude list after this).
            List<String> included = include.stream().map(String::trim).toList();
            exclude.removeIf(e -> included.contains(e.trim()));
            spoke = true;
        }
        if (cliExclude) {
            // `--exclude-tags ""` is the explicit CLI clear: blank values normalize away, the
            // spoke flag keeps the empty result authoritative.
            exclude = new ArrayList<>(in.values("exclude-tags"));
            spoke = true;
        }
        // --all means EVERYTHING (docs' "Everything | jk test --all"): every suite AND no
        // baseline/profile tag excludes. Explicit CLI tag flags still compose on top, so
        // `--all --exclude-tags slow` widens but keeps slow out.
        if (all) {
            if (!cliInclude) include = new ArrayList<>();
            if (!cliExclude) exclude = new ArrayList<>();
            spoke = true;
        }
        return TestSelection.of(suites, all, include, exclude, spoke);
    }
}

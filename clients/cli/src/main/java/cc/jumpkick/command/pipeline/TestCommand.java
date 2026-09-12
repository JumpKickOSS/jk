// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.ParallelTestsOpts;
import cc.jumpkick.cli.api.ProjectContext;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EnginePrewarm;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.JobCancelledException;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.AggregateContext;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.DebugAttach;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.cli.tui.ModuleScopeHint;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.command.CwdModuleScope;
import cc.jumpkick.command.ModuleSelectors;
import cc.jumpkick.command.VariantSelection;
import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.Errors;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.AffectedTestsReport;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

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
        opts.add(CommonOpts.keepGoing());
        opts.addAll(CommonOpts.moduleSelection(
                Opt.flag("Ranked WIP tests (does not run)", "--affected"),
                Opt.value("<git-ref>", "Ranked tests since ref (no run)", "--affected-since")));
        opts.add(Opt.value("<name>", "Test suite directory (repeatable)", "-s", "--suite")
                .repeat());
        opts.add(Opt.flag("Run every discovered test suite", "--all"));
        opts.add(CommonOpts.guard());
        opts.add(Opt.flag("Guard scripts, no JUnit", "--scripts-only"));
        opts.add(Opt.flag("Skip guard scripts", "--no-scripts"));
        opts.add(Opt.value("<tags>", "JUnit tags to include (CSV)", "--include-tags")
                .splitOn(","));
        opts.add(Opt.value("<tags>", "JUnit tags to exclude (CSV)", "--exclude-tags")
                .splitOn(","));
        opts.add(Opt.value("<name>", "Only these test classes (repeatable)", "--class")
                .repeat());
        opts.add(Opt.value("<port>", "Debug test JVM (JDWP; 5005, 0=free)", "--debug-jvm")
                .withFallback(""));
        opts.add(Opt.flag("JaCoCo agent; jacoco.xml per module", "--coverage"));
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Nullable
    String profileName;

    @Nullable
    Integer workers;

    boolean parallelTests;

    /** {@code --continue} / {@code [engine] continue}: finish the graph, report every failure. */
    boolean keepGoing;

    @Nullable
    Path cacheDir;

    @Nullable
    Path jdksDir;

    GlobalOptions global;
    int jobs;

    @Nullable
    String affectedSince;

    boolean affectedWip;

    @Nullable
    String modulesSpec;

    TestSelection testSelection = TestSelection.DEFAULT;

    /** {@code --debug-jvm}: the one test JVM starts with a JDWP listener; null for a plain run. */
    @Nullable
    DebugJvm debugJvm;

    /** {@code --coverage}: suite JVMs under the JaCoCo agent, a report per module. */
    boolean coverage;

    private @Nullable CliSessionTranscript session;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.global = GlobalOptions.from(in);
        this.profileName = in.value("profile").orElse(null);
        this.workers = in.value("workers").map(Integer::parseInt).orElse(null);
        this.cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        this.jdksDir = CommonOpts.jdksDirValue(in);
        this.affectedSince = in.value("affected-since").orElse(null);
        this.affectedWip = in.isSet("affected");
        this.modulesSpec = in.value("modules").orElse(null);
        if (ModuleSelectors.bothSelectors(affectedWip, affectedSince)) {
            CommandWedge.printFail("Test", ModuleSelectors.BOTH_MESSAGE);
            return Exit.CONFIG;
        }
        this.jobs = global.jobsEffective();
        // C2: overlap module suites by default; --serial-tests opts out (shared ports/locks).
        this.parallelTests = ParallelTestsOpts.enabled(in);
        this.keepGoing = CommonOpts.keepGoingValue(in);
        try {
            this.testSelection = resolveTestSelection(in);
            this.debugJvm = DebugAttach.fromFlag(in);
            this.coverage = in.isSet("coverage");
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail("Test", e.getMessage());
            return Exit.CONFIG;
        }
        // One listener means one JVM at a time: module suites take the port in turn.
        if (debugJvm != null) this.parallelTests = false;
        warnGateOverride(in, global);
        SessionContext.install(SessionContext.current()
                .withParallelTests(parallelTests)
                .withTestSelection(testSelection)
                .withCoverage(coverage));
        Path dir = global.workingDir();
        VariantSelection.install(in, dir);
        var proj = ProjectContext.require(dir, "test").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path buildFile = proj.buildFile();
        this.session = CliSessionTranscript.open(dir, "test", testArgv(in));
        if (session != null) session.announceIf(global.verbose);
        // No jk-lock.toml guard: the plan's parse-build step resolves the lock on
        // first run and re-locks when jk.toml changed — same as `jk build`/`run`.

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        // 0 = auto: this build's share of the machine (cores / dirty width), then
        // min(share, classCount) + heap clamp. Explicit -w1 keeps one JVM. See docs/user/test.md.
        int workerCount = workers != null ? Math.max(0, workers) : 0;

        if (affectedWip || (affectedSince != null && !affectedSince.isBlank())) {
            return finishSession(showAffected(dir));
        }

        if (!armDebugger()) return finishSession(Exit.CONFIG);

        var info = ProjectInfos.orNull(dir);
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
        if (ModuleSelectors.anySelector(modulesSpec, affectedSince, affectedWip)) {
            var sel = ProjectInfos.orError(dir, modulesSpec, affectedSince, affectedWip);
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
        String module = ProjectInfos.buildTarget(buildFile, dir);
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
                            testSelection,
                            debugJvm,
                            coverage),
                    steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, module),
                    testResultHolder);
        } catch (IOException e) {
            CommandWedge.printFail("Test", e.getMessage());
            if (session != null) session.error(Errors.text(e));
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
        for (var d : result.errors()) {
            if ("affected-refuse".equals(d.code())) return finishSession(Exit.CONFIG);
        }
        // Test failures get exit 4; compile / launcher errors are exit 1.
        if (testResult != null && !testResult.allPassed()) return finishSession(4);
        return finishSession(1);
    }

    private int finishSession(int code) {
        return CliSessionTranscript.finish(session, code, global.verbose);
    }

    /**
     * {@code --affected} / {@code --affected-since} is a ranked list, not a test run. Write
     * {@code target/jk-tests-affected.md} and print the same ranking as a table.
     */
    private int showAffected(Path dir) {
        AffectedTestsReport report;
        try {
            String since = affectedWip ? null : affectedSince;
            report = EngineClient.runAffectedTests(EnginePaths.current(), dir, testSelection, since, modulesSpec);
        } catch (IOException e) {
            CommandWedge.printFail("Test", e.getMessage());
            if (session != null) session.error(Errors.text(e));
            return Exit.SOFTWARE;
        }
        if (global.outputIsJson()) {
            CliOutput.outRaw(report.encode());
            return report.refused() ? Exit.CONFIG : Exit.SUCCESS;
        }
        if (report.refused()) {
            String why = report.error() == null || report.error().isBlank() ? report.refuseCode() : report.error();
            CommandWedge.printFail("Test", "cannot rank affected tests: " + why + ". Run jk test");
            if (session != null) session.error(why);
            return Exit.CONFIG;
        }
        if (report.rows().isEmpty()) {
            CommandWedge.printOk("Test", "nothing affected");
            if (session != null) session.wedge("nothing affected");
            return 0;
        }
        CommandWedge.envelopeStart();
        for (String line : renderAffectedTable(report.rows())) {
            CliOutput.out(line);
        }
        if (session != null) session.wedge(report.rows().size() + " affected");
        return 0;
    }

    public static List<String> renderAffectedTable(List<AffectedTestsReport.Row> rows) {
        Table table = new Table("Affected tests")
                .columns(
                        new Table.Column("Score", Table.Align.RIGHT),
                        new Table.Column("Class"),
                        new Table.Column("Reason"));
        for (AffectedTestsReport.Row r : rows) {
            table.row(String.valueOf(r.score()), r.className(), r.reason());
        }
        return table.render(RenderContext.current());
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
        if (in.isSet("affected")) argv.add("--affected");
        if (in.isSet("serial-tests") || in.isSet("no-parallel-tests")) argv.add("--serial-tests");
        else if (in.isSet("parallel-tests")) argv.add("--parallel-tests");
        in.value("workers").ifPresent(w -> {
            argv.add("--workers");
            argv.add(w);
        });
        for (String c : in.values("class")) argv.add("--class=" + c);
        in.value(DebugAttach.OPTION).ifPresent(d -> argv.add(d.isEmpty() ? "--debug-jvm" : "--debug-jvm=" + d));
        return argv;
    }

    /**
     * Validate {@code -m}/{@code --affected-since} (including cwd inference) and run workspace
     * tests from {@code entryDir}. {@code rootInfo} is the unfiltered workspace peek when already
     * loaded (root invocation); null when the caller is a member dir.
     */
    private int runSelectedWorkspaceTests(Path entryDir, @Nullable ProjectInfo rootInfo, Path cache, int workerCount)
            throws IOException, InterruptedException {
        List<String> tokens = ModuleSelectors.tokens(modulesSpec, affectedSince, affectedWip);
        if (!tokens.isEmpty()) {
            var sel = ProjectInfos.orError(entryDir, modulesSpec, affectedSince, affectedWip);
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
                    ModuleScopeHint.namesFrom(ProjectInfos.orError(entryDir, modulesSpec, affectedSince, affectedWip));
            if (!live) {
                ModuleScopeHint.print("testing", scopeNames, global.outputIsJson());
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
        ModuleScopeHint.show("testing", scopeNames, global.outputIsJson(), view);
        AggregateContext agg = new AggregateContext(view);
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Test", true), entryDir, session, false);
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
        boolean json = global.outputIsJson();
        long start = System.nanoTime();
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Test", true), entryDir, session, json);
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
            String step = WorkspaceRunView.errorStep(result);
            for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine(step, err));
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
                        /* skipTests */ testSelection.scriptsOnly(),
                        global.verbose,
                        concurrency,
                        null,
                        true,
                        true)
                .withTestOnly(true)
                .withKeepGoing(keepGoing)
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
        return WorkspaceRunView.failedSubject(result, "tests") + " — failed "
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
     * Settle and announce the {@code --debug-jvm} listener once, so every JVM this run forks is
     * told the address that was announced — the workspace and single-project paths both read it
     * off the session. False when the port could not be settled (already printed).
     */
    private boolean armDebugger() {
        if (debugJvm == null) return true;
        try {
            this.debugJvm = DebugAttach.bind(debugJvm);
        } catch (IOException e) {
            CommandWedge.printFail("Test", "--debug-jvm: " + e.getMessage());
            return false;
        }
        SessionContext.install(SessionContext.current().withDebugJvm(debugJvm));
        DebugAttach.announce(debugJvm);
        return true;
    }

    /** {@code --guard} is one option identity. */
    static boolean guardRequested(Invocation in) {
        return in.isSet("guard");
    }

    /**
     * A build-type verb's test selection into the session: {@code --guard} on {@code jk image},
     * {@code jk native} and their kin rides the same {@link TestSelection} the build and test verbs
     * use, so the engine plans the guard lanes and the guard scripts from one field. Options the verb
     * does not declare read as unset. Returns {@code false} after printing the config error.
     */
    public static boolean installSelection(Invocation in, String verb) {
        try {
            SessionContext.install(SessionContext.current().withTestSelection(resolveTestSelection(in)));
            return true;
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail(verb, e.getMessage());
            return false;
        }
    }

    public static final String GUARD_SUITE_OVERRIDE_WARNING = "--guard ignored because --suite was set";
    public static final String SCRIPTS_FLAGS_CONFLICT = "--scripts-only and --no-scripts cannot be combined";

    public static void warnGateOverride(Invocation in, GlobalOptions global) {
        if (!guardRequested(in) || in.values("suite").isEmpty()) return;
        if (global.outputIsJson()) return;
        CliOutput.err(Theme.colorize(Glyphs.BANG, Theme.active().warning()) + " " + GUARD_SUITE_OVERRIDE_WARNING);
    }

    /**
     * CLI + {@code [test]} / profile tags → {@link cc.jumpkick.config.TestSelection}. Throws if
     * {@code --all} and {@code --suite} / {@code --guard} are combined.
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
     * by profile filters. {@code --guard} select {@code test} +
     * {@code integration} (or {@code [test] guard-suites}); {@code --suite} wins over {@code --guard}.
     */
    public static TestSelection resolveTestSelection(Invocation in) {
        boolean all = in.isSet("all");
        boolean guard = guardRequested(in);
        boolean scriptsOnly = in.isSet("scripts-only");
        boolean noScripts = in.isSet("no-scripts");
        if (scriptsOnly && noScripts) {
            throw new IllegalArgumentException(SCRIPTS_FLAGS_CONFLICT);
        }
        List<String> suites = new ArrayList<>(in.values("suite"));
        boolean cliInclude = in.has("include-tags");
        boolean cliExclude = in.has("exclude-tags");
        if (all && !suites.isEmpty()) {
            throw new IllegalArgumentException("--all and --suite cannot be combined");
        }
        if (all && guard) {
            throw new IllegalArgumentException("--all and --guard cannot be combined");
        }
        Path wd = GlobalOptions.from(in).workingDir();
        // Tags, guard suites and profiles are workspace facts: from a member directory the root's
        // manifest is the baseline layer, exactly as when invoked at the root. Reading the
        // member's own manifest here made the member's (usually empty) tags the baseline and
        // dropped the root's, so a root exclude-tags = ["slow"] ran slow tests from inside a member.
        Path root = WorkspaceScan.owningRoot(wd).orElse(wd);
        if (scriptsOnly && !BuildLogicToml.hasStem(root, "guard")) {
            throw new IllegalArgumentException(BuildLogicToml.NO_GUARD_SCRIPTS);
        }
        Path toml = root.resolve(ManifestPaths.MANIFEST);
        String explicit = in.value("profile").orElse(null);
        boolean explicitProfile = explicit != null && !explicit.isBlank();
        String profileName = explicitProfile ? explicit : Profiles.autoSelect(System.getenv());
        List<String> scanKeys = new ArrayList<>();
        scanKeys.add("test.include-tags");
        scanKeys.add("test.exclude-tags");
        scanKeys.add("test.guard-suites");
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
            // and the run still exited 0. An explicit --exclude-tags below still wins
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
        boolean applyGuard = guard && suites.isEmpty();
        if (applyGuard) {
            if (scan.hasKey("test.guard-suites")) {
                suites = new ArrayList<>(scan.stringArray("test.guard-suites"));
                if (suites.isEmpty()) suites.add(TestSuites.DEFAULT);
                for (String name : suites) {
                    if (!TestSuites.isSuiteName(name)) {
                        throw new IllegalArgumentException("unknown test suite '" + name + "' in [test] guard-suites");
                    }
                }
            } else {
                suites = new ArrayList<>(TestSuites.GUARD_SUITES);
            }
        }
        return TestSelection.of(
                suites, all, include, exclude, spoke, applyGuard, scriptsOnly, noScripts, in.values("class"));
    }
}

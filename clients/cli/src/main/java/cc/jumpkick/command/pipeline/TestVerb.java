// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.CompositeBuildPlanListener;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.DebugAttach;
import cc.jumpkick.cli.run.SlowestTestsBlock;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.SlowestTests;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.AffectedTestsReport;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk test}: the same core plan as {@code jk build} stopped at {@code run-tests}, so test
 * sources compile and run exactly as they do under a build. {@code --affected} /
 * {@code --affected-since} rank the tests a change touched instead of running anything.
 */
final class TestVerb implements BuildVerb {

    static final String EXCLUSIVE_HELP = "Selected modules only (no prereqs)";

    static final String EXCLUSIVE_NEEDS_MODULES =
            "--exclusive needs a module selection: -m <module>, or run from a member directory";

    private static final Words WORDS = new Words("Test", "testing", "test", "Testing");

    /** {@code --exclusive}: only the selected modules' suites run; their prerequisites only compile. */
    private boolean exclusive;

    /** {@code --debug-jvm}: the one test JVM starts with a JDWP listener; null for a plain run. */
    private @Nullable DebugJvm debugJvm;

    /** {@code --coverage}: suite JVMs under the JaCoCo agent, a report per module. */
    private boolean coverage;

    @Override
    public String name() {
        return "test";
    }

    @Override
    public String description() {
        return "Compile and run tests";
    }

    @Override
    public Words words() {
        return WORDS;
    }

    @Override
    public WorkspaceTarget target() {
        return WorkspaceTarget.TEST;
    }

    @Override
    public boolean ranksSlowestTests() {
        return true;
    }

    @Override
    public BuildFlags.Affected affected() {
        return BuildFlags.Affected.RANKED_TESTS;
    }

    @Override
    public List<Opt> ownOptions() {
        return List.of(
                Opt.flag(EXCLUSIVE_HELP, "-X", "--exclusive"),
                Opt.value("<name>", "Classes or Class#method (repeatable)", "-S", "--class")
                        .repeat(),
                Opt.value("<port>", "Debug test JVM (JDWP; 5005, 0=free)", "-U", "--debug-jvm")
                        .withFallback(""),
                Opt.flag("JaCoCo agent; jacoco.xml per module", "-T", "--coverage"));
    }

    @Override
    public void parse(Invocation in, BuildRun run) {
        this.exclusive = in.isSet("exclusive");
        this.debugJvm = DebugAttach.fromFlag(in);
        this.coverage = in.isSet("coverage");
        // One listener means one JVM at a time: module suites take the port in turn.
        if (debugJvm != null) run.flags(run.flags().serialTests());
        SessionContext.install(SessionContext.current()
                .withParallelTests(run.flags().parallelTests())
                .withCoverage(coverage));
    }

    @Override
    public int before(BuildRun run) {
        BuildFlags flags = run.flags();
        String since = flags.affectedSince();
        if (flags.affectedWip() || (since != null && !since.isBlank())) return showAffected(run);
        if (!armDebugger(run)) return Exit.CONFIG;
        String modules = flags.modulesSpec();
        if (exclusive && (modules == null || modules.isBlank())) {
            run.fail(EXCLUSIVE_NEEDS_MODULES);
            return Exit.CONFIG;
        }
        return -1;
    }

    @Override
    public int selected(BuildRun run, List<String> moduleDirs) {
        if (!exclusive) return -1;
        run.flags(run.flags().withTestSelection(run.flags().testSelection().withOnlyModules(moduleDirs)));
        SessionContext.install(
                SessionContext.current().withTestSelection(run.flags().testSelection()));
        return -1;
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        BuildFlags flags = run.flags();
        WorkspaceRequest request = run.workspaceRequest(entryDir, modules, flags.parallelTests() ? flags.jobs() : 1)
                .withTestOnly(true);
        return EngineClient.buildWorkspace(EnginePaths.current(), request, listener);
    }

    @Override
    public WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos) {
        return new WorkspaceRunView.Tails(
                (r, planned) -> workspaceTestSuccessTail(
                        r,
                        planned,
                        view.servedFromCache(),
                        view.modulesWithSuite(),
                        view.prerequisitesNotTested(),
                        BuildTails.elapsedMsSince(startNanos)),
                r -> WorkspaceRunView.failedSubject(r, "tests") + " — failed "
                        + ConsoleSpec.took(Duration.ofMillis(BuildTails.elapsedMsSince(startNanos))));
    }

    @Override
    public int single(BuildRun run, Path dir) {
        TestSummary[] tests = new TestSummary[1];
        // Counts the suite's replay from its green marker, so the tail can say the tests were served
        // from cache and not run.
        ServedTally served = new ServedTally();
        SlowestTests slowest = new SlowestTests();
        ConsoleSpec spec = new ConsoleSpec(
                WORDS.name(),
                r -> testSummary(tests[0], r, served.served() > 0),
                r -> testFailureMessage(tests[0], r),
                false,
                false,
                null,
                () -> SlowestTestsBlock.lines(slowest.ranking()));
        String module = ProjectInfos.buildTarget(ManifestPaths.manifestIn(dir), dir);
        BuildFlags flags = run.flags();
        var session = SessionContext.current();
        var request = new EngineRequests.TestRequest(
                dir,
                flags.cache(),
                flags.jdksDir(),
                flags.workers(),
                flags.profile(),
                run.global().verbose,
                session.offline(),
                session.force(),
                flags.parallelTests(),
                flags.testSelection(),
                debugJvm,
                coverage);
        return run.settleSingle(
                spec,
                module,
                tests,
                listeners -> EngineClient.runTest(
                        EnginePaths.current(),
                        request,
                        steps -> CompositeBuildPlanListener.of(
                                SlowestTestsBlock.standalone(slowest, run.mode()),
                                CompositeBuildPlanListener.of(listeners.apply(steps), served)),
                        tests));
    }

    /**
     * Settle and announce the {@code --debug-jvm} listener once, so every JVM this run forks is
     * told the address that was announced. False when the port could not be settled (printed).
     */
    private boolean armDebugger(BuildRun run) {
        if (debugJvm == null) return true;
        try {
            this.debugJvm = DebugAttach.bind(debugJvm);
        } catch (IOException e) {
            run.fail("--debug-jvm: " + e.getMessage());
            return false;
        }
        SessionContext.install(SessionContext.current().withDebugJvm(debugJvm));
        DebugAttach.announce(debugJvm);
        return true;
    }

    /**
     * {@code --affected} / {@code --affected-since} is a ranked list, not a test run: write
     * {@code target/jk-tests-affected.md} and print the same ranking as a table.
     */
    private static int showAffected(BuildRun run) {
        BuildFlags flags = run.flags();
        AffectedTestsReport report;
        try {
            String since = flags.affectedWip() ? null : flags.affectedSince();
            report = EngineClient.runAffectedTests(
                    EnginePaths.current(),
                    run.global().workingDir(),
                    flags.testSelection(),
                    since,
                    flags.modulesSpec());
        } catch (IOException e) {
            CommandWedge.printFail(WORDS.name(), e.getMessage());
            if (run.session() != null) run.session().error(Errors.text(e));
            return Exit.SOFTWARE;
        }
        if (run.global().outputIsJson()) {
            CliOutput.outRaw(report.encode());
            return report.refused() ? Exit.CONFIG : Exit.SUCCESS;
        }
        if (report.refused()) {
            String why = report.error() == null || report.error().isBlank() ? report.refuseCode() : report.error();
            CommandWedge.printFail(WORDS.name(), "cannot rank affected tests: " + why + ". Run jk test");
            if (run.session() != null) run.session().error(why);
            return Exit.CONFIG;
        }
        if (report.rows().isEmpty()) {
            run.ok("nothing affected");
            return 0;
        }
        CommandWedge.envelopeStart();
        for (String line : renderAffectedTable(report.rows())) CliOutput.out(line);
        if (run.session() != null) run.session().wedge(report.rows().size() + " affected");
        return 0;
    }

    static List<String> renderAffectedTable(List<AffectedTestsReport.Row> rows) {
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

    /**
     * The workspace success wedge: {@code Tests passed for N modules}, then how many of those
     * modules' suites were served from the action cache, when any was, so a line over an unchanged
     * tree reads as a replay and not as a run.
     *
     * <p>{@code withSuite} is how many modules ran a suite or replayed one; zero is a green run that
     * tested nothing ({@code -p <tier>} over modules with no such tier), which must not read as a
     * passing suite. {@code notTested} is how many prerequisites {@code --exclusive} compiled
     * without running their suites; they are named in their own clause.
     */
    static String workspaceTestSuccessTail(
            WorkspaceResult result, int planned, int served, int withSuite, int notTested, long elapsedMs) {
        int n = (result.modules() == null ? 0 : result.modules().size()) - notTested;
        String skipped = notTested <= 0
                ? ""
                : "; " + notTested + " prerequisite" + (notTested == 1 ? "" : "s") + " not tested (--exclusive)";
        if (n <= 0 || planned == 0 || withSuite == 0) {
            return "No tests to run" + skipped;
        }
        String took = ConsoleSpec.took(Duration.ofMillis(elapsedMs));
        if (n == 1) {
            return "Tests passed" + (served > 0 ? ", served from cache" : "") + skipped + " " + took;
        }
        String servedClause = served <= 0
                ? ""
                : served >= n
                        ? ", all served from cache"
                        : ", "
                                + Theme.colorize(
                                        String.valueOf(served), Theme.active().focused()) + " served from cache";
        return "Tests passed for "
                + Theme.colorize(String.valueOf(n), Theme.active().focused()) + " modules" + servedClause + skipped
                + " "
                + took;
    }

    /**
     * Success line (sans the leading ✓): {@code Passed N tests}, {@code (served from cache)} when the
     * suite's green marker was replayed instead of a run, or {@code No tests}.
     */
    static String testSummary(@Nullable TestSummary testResult, BuildPlanResult result, boolean servedFromCache) {
        if (testResult == null || testResult.total() == 0) return "No tests";
        long total = testResult.total();
        if (!testResult.allPassed()) {
            // A run that succeeded with failing tests is one whose failures are reported, not failing.
            return Theme.colorize("Passed", Theme.active().focused()) + " with " + testResult.failed() + " of " + total
                    + " test" + (total == 1 ? "" : "s")
                    + " failing (reported, not failing: [test] failures = \"report\")";
        }
        String passed = Theme.colorize("Passed", Theme.active().focused());
        return passed + " " + total + " test" + (total == 1 ? "" : "s")
                + (servedFromCache ? " (served from cache)" : "");
    }

    static String testFailureMessage(@Nullable TestSummary testResult, BuildPlanResult result) {
        return (testResult != null && !testResult.allPassed()) ? "Tests failed" : "Build failed";
    }
}

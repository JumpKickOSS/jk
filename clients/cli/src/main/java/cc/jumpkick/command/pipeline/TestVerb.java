// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.engine.RunSuspensions;
import cc.jumpkick.cli.run.DebugAttach;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.DurationText;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.Errors;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.AffectedTestsReport;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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

    /** {@code --rerun}: every selected suite runs, while compiles and packages stay cached. */
    private boolean rerun;

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
                Opt.value("<name>", "Classes, Class#method or module:Class (repeatable)", "-S", "--class")
                        .repeat(),
                Opt.flag("Only the quarantined test classes", "--quarantined"),
                Opt.value("<port>", "Debug test JVM (JDWP; 5005, 0=free)", "-U", "--debug-jvm")
                        .withFallback(""),
                Opt.flag("JaCoCo agent; jacoco.xml per module", "-T", "--coverage"),
                Opt.flag("Run tests again; compiles stay cached", "--rerun"));
    }

    @Override
    public void parse(Invocation in, BuildRun run) {
        this.exclusive = in.isSet("exclusive");
        this.debugJvm = DebugAttach.fromFlag(in);
        this.coverage = in.isSet("coverage");
        this.rerun = in.isSet("rerun");
        // One listener means one JVM at a time: module suites take the port in turn.
        if (debugJvm != null) run.flags(run.flags().serialTests());
        SessionContext.install(SessionContext.current()
                .withParallelTests(run.flags().parallelTests())
                .withCoverage(coverage)
                .withRerunTests(rerun));
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

    /** Each module-qualified {@code --class} names its module's directory, as the engine matches it. */
    @Override
    public int prepareWorkspace(BuildRun run, Path entryDir, BuildRun.Selection selection) {
        TestSelection tests = run.flags().testSelection();
        if (tests.unqualifiedClasses().size() == tests.classes().size()) return -1;
        List<String> resolved;
        try {
            resolved = qualifiedByDir(
                    tests.classes(),
                    module -> ProjectInfos.orError(entryDir, module, null),
                    selection.confines() ? selection.dirs() : null);
        } catch (IllegalArgumentException e) {
            run.fail(e.getMessage());
            return Exit.USAGE;
        }
        run.flags(run.flags().withTestSelection(tests.withClasses(resolved)));
        SessionContext.install(
                SessionContext.current().withTestSelection(run.flags().testSelection()));
        return -1;
    }

    /**
     * {@code patterns} with the module of each qualified one ({@code server/engine:EngineIT}) read as
     * {@code -m} reads it and replaced by the directory of each module it names. A qualifier that
     * names no module, or a module outside {@code runDirs} (the run's {@code -m} selection; null
     * when the run covers the workspace), is refused: the pattern could never hold.
     */
    static List<String> qualifiedByDir(
            List<String> patterns, Function<String, ProjectInfo> modules, @Nullable List<String> runDirs) {
        List<String> out = new ArrayList<>();
        for (String pattern : patterns) {
            String module = TestSelection.moduleOf(pattern);
            if (module == null) {
                out.add(pattern);
                continue;
            }
            ProjectInfo info = modules.apply(module);
            if (info.error() != null && !info.error().isBlank()) {
                throw new IllegalArgumentException("--class " + pattern + ": " + info.error());
            }
            if (info.moduleDirs().isEmpty()) {
                throw new IllegalArgumentException("--class " + pattern + ": no module matches " + module);
            }
            for (String dir : info.moduleDirs()) {
                if (runDirs != null && runDirs.stream().noneMatch(d -> sameDir(d, dir))) {
                    throw new IllegalArgumentException(
                            "--class " + pattern + ": " + module + " is not in this run; add -m " + module);
                }
                out.add(TestSelection.qualify(dir, pattern));
            }
        }
        return out;
    }

    private static boolean sameDir(String a, String b) {
        return Path.of(a)
                .toAbsolutePath()
                .normalize()
                .equals(Path.of(b).toAbsolutePath().normalize());
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
                        + DurationText.took(Duration.ofMillis(BuildTails.elapsedMsSince(startNanos))));
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
            JsonlShape.rowOf("affected-tests", report.encode());
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
     * The workspace success wedge, in {@link BuildTails#settle}'s shape: {@code Tests passed for N
     * modules}, then how many of those modules' suites were served from the action cache, when any
     * was, so a line over an unchanged tree reads as a replay and not as a run: {@code Tests passed
     * for 4 modules. Build up to date (all served from cache)}.
     *
     * <p>{@code withSuite} is how many modules ran a suite or replayed one; zero is a green run that
     * tested nothing ({@code -s <suite>} over modules where it holds no tests), which must not read as a
     * passing suite. {@code notTested} is how many prerequisites {@code --exclusive} compiled
     * without running their suites; they are named in their own clause.
     *
     * <p>The tests each module's suite counted, run or replayed ({@link ModuleOutcome#tests}), say
     * how many were skipped, so a skipped test is never hidden behind a pass: {@code Tests passed; 1
     * test skipped}. When every test was skipped and nothing was replayed, the line does not say
     * anything passed: {@code No test ran; 1 test skipped}.
     */
    static String workspaceTestSuccessTail(
            WorkspaceResult result, int planned, int served, int withSuite, int notTested, long elapsedMs) {
        long ran = 0;
        long skipped = 0;
        for (ModuleOutcome m : result.modules() == null ? List.<ModuleOutcome>of() : result.modules()) {
            ModuleOutcome.Tests tests = m.tests();
            if (tests == null) continue;
            ran += tests.total();
            skipped += tests.skipped();
        }
        int n = (result.modules() == null ? 0 : result.modules().size()) - notTested;
        String untested = notTested <= 0
                ? ""
                : "; " + notTested + " prerequisite" + (notTested == 1 ? "" : "s") + " not tested (--exclusive)";
        String skips = skipped <= 0 ? "" : "; " + skipped + " test" + (skipped == 1 ? "" : "s") + " skipped";
        if (n <= 0 || planned == 0 || withSuite == 0) {
            return "No tests to run" + untested;
        }
        String took = DurationText.took(Duration.ofMillis(elapsedMs), RunSuspensions.wallMs());
        if (served <= 0 && skipped > 0 && skipped >= ran) return "No test ran" + skips + untested + " " + took;
        String lead = n == 1 ? "Tests passed" : "Tests passed for " + BuildTails.modules(n);
        String line;
        if (served <= 0) line = lead;
        else if (served >= n)
            line = BuildTails.settle(lead, BuildTails.upToDate((n == 1 ? "" : "all ") + "served from cache"));
        else
            line = lead + ", "
                    + Theme.colorize(String.valueOf(served), Theme.active().focused()) + " served from cache";
        return line + skips + untested + " " + took;
    }
}

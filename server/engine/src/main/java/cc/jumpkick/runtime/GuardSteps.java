// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.CLASSPATHS;
import static cc.jumpkick.runtime.BuildPlanner.JAVA_HOME;
import static cc.jumpkick.runtime.BuildPlanner.LAYOUT;
import static cc.jumpkick.runtime.BuildPlanner.PROJECT;
import static cc.jumpkick.runtime.BuildPlanner.TEST_CLASSES;
import static cc.jumpkick.runtime.BuildPlanner.mainClasses;
import static cc.jumpkick.runtime.PlannerGuards.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.guard.eval.EvalContext;
import cc.jumpkick.guard.eval.GuardSuites;
import cc.jumpkick.guard.eval.WorkspaceModel;
import cc.jumpkick.guard.eval.WorkspaceModules;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.GuardSuiteLibrary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One plan's guard lanes: the module lane after the compiles it indexes, and at the invocation root
 * the model, workspace, tree and fixtures lanes, each step's body beside its predictor. Built once
 * per plan from what its {@link BuildPlanner.Ctx} holds; the lane run, the rules and the output lane
 * the packaging tails plan are {@link PlannerGuards}', the lane keys {@link GuardKeys}'.
 */
final class GuardSteps {

    private final PlannerGuards.LaneEnv env;
    private final GuardsPlan g;
    private final BuildPlanner.Inputs in;
    private final Cas cas;
    private final ActionCache actionCache;
    private final Supplier<EffortWeights.Plan> plan;

    GuardSteps(BuildPlanner.Ctx cx) {
        this.env = new PlannerGuards.LaneEnv(cx.guards(), cx.actionCache(), cx.in());
        this.g = cx.guards();
        this.in = cx.in();
        this.cas = cx.cas();
        this.actionCache = cx.actionCache();
        this.plan = cx.plan();
    }

    /**
     * @param packagesHere whether this plan packages (then the output lane hangs off the packaging
     *     tails, see {@link PlannerTails}); otherwise it follows the last root lane here
     */
    @Nullable
    String appendRootLanes(BuildPlan.Builder b, String after, boolean packagesHere, BuildStage laneStage) {
        // `jk compile` runs no lane, the root's included.
        if (!g.enabled() || in.compileOnly() || !PlannerResources.invocationRoot(in.dir())) {
            return null;
        }
        b.addTask(model());
        String last = TaskNames.GUARD_MODEL;
        boolean guard = PlannerResources.runGuardScripts(in);
        // Cross-module structure needs every module's facts at once: the workspace lane, at the
        // root, which the graph already orders after every member. A standalone project has no
        // second module to relate, so it has no such lane.
        if (hasMembers() && moduleLanesOnThisBuild(g, guard)) {
            b.addTask(workspace(laneStage, TaskNames.GUARD_MODEL, after));
            last = TaskNames.GUARD_WORKSPACE;
        }
        if (guard) {
            b.addTask(tree(last, after));
            b.addTask(fixtures(TaskNames.GUARD_TREE));
            last = TaskNames.GUARD_FIXTURES;
        }
        if (!packagesHere && outputLaneWanted(in)) {
            b.addTask(outputStep(env, last));
            last = TaskNames.GUARD_OUTPUT;
        }
        return last;
    }

    private boolean hasMembers() {
        try {
            return !WorkspaceModules.of(g.root()).isEmpty()
                    && !WorkspaceModules.of(g.root()).equals(List.of(g.root()));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * {@code guard-workspace}: every module's facts index read together, keyed on every index's
     * digest plus the rules and the baseline — N small indexes, never a class tree. Runs on every
     * build after the module lanes (the graph orders the root after its members).
     */
    Task workspace(BuildStage stage, String... requires) {
        return Task.builder(TaskNames.GUARD_WORKSPACE)
                .forecast(StepForecast.priced(this::forecastWorkspaceLane))
                .stage(stage)
                .label("Guards (workspace)")
                .kind(TaskKind.CPU)
                .requires(requires)
                .weight(1)
                .ticks(1)
                .execute(this::runWorkspaceLane)
                .build();
    }

    /** The workspace lane: every module's facts index, read together. */
    private void runWorkspaceLane(TaskContext ctx) throws Exception {
        List<Path> modules = WorkspaceModules.of(g.root());
        EvalContext ectx = new EvalContext(
                Lane.WORKSPACE,
                g.root(),
                "",
                null,
                modules,
                EvalContext.lazy(() -> WorkspaceFacts.merged(g.root(), modules)),
                () -> null,
                List::of);
        executeLane(
                ctx,
                env,
                Lane.WORKSPACE,
                ectx,
                () -> GuardKeys.workspaceLaneTokens(g.root(), modules),
                ActionKey.qualifiedTaskId(TaskNames.GUARD_WORKSPACE, g.root()),
                false,
                List.of(),
                null);
        ctx.progress(1);
    }

    /**
     * {@code guard}: this module's bytecode rules, after its compile(s) — and after compile-test when
     * the plan has one, since the lane indexes the test classes too ({@code indexTests}); the stage
     * follows the last step it waits for, because a step cannot require one from a later stage.
     */
    Task module(BuildStage stage, boolean indexTests, String... requires) {
        return Task.builder(TaskNames.GUARD)
                .forecast(StepForecast.priced(this::forecastModuleLane))
                .stage(stage)
                .label("Guards")
                .kind(TaskKind.CPU)
                .requires(requires)
                .weight(1)
                .ticks(1)
                .execute(ctx -> runModuleLane(ctx, indexTests))
                .build();
    }

    /** The module lane: this module's facts index, and its guard suite when it has one compiled. */
    private void runModuleLane(TaskContext ctx, boolean indexTests) throws Exception {
        Path moduleDir = in.dir();
        // The lane hangs off the compile steps, ahead of copy-resources, and runs the
        // guard suite on the test runtime classpath — sibling jars included.
        SiblingWaits.awaitSiblingTestRuntime(ctx, in);
        String module = WorkspaceModel.rel(g.root(), moduleDir);
        Path buildDir = ctx.require(LAYOUT).buildDir();
        FactsIndexing.Ensured main = FactsIndexing.ensure(mainClasses(ctx), FactsIndexing.indexPath(buildDir, "main"));
        Path testClasses = indexTests ? ctx.get(TEST_CLASSES).orElse(null) : null;
        FactsIndexing.Ensured test = testClasses != null && Files.isDirectory(testClasses)
                ? FactsIndexing.ensure(testClasses, FactsIndexing.indexPath(buildDir, "test"))
                : null;
        // Before this lane waits on anyone: a workspace suite elsewhere may be waiting on it.
        in.siblings().indexed();
        EvalContext ectx = new EvalContext(
                Lane.MODULE,
                g.root(),
                module,
                moduleDir,
                List.of(moduleDir),
                EvalContext.lazy(() -> FactsIndexing.load(main)),
                testFacts(test),
                () -> ctx.require(CLASSPATHS).main());
        // ---- the module's src/guard suite, if compiled: its guards join the lane's rules
        Path guardClasses = ctx.require(LAYOUT).guardClassesDir();
        List<Rule> suiteRules = List.of();
        Evaluation withSuite = null;
        List<String> suiteTokens = List.of();
        if (Files.isDirectory(guardClasses) && PlannerGuardSuite.declared(moduleDir, compact(moduleDir))) {
            FactsIndexing.Ensured suite =
                    FactsIndexing.ensure(guardClasses, FactsIndexing.indexPath(buildDir, "guard"));
            List<GuardSuites.Declared> declared = GuardSuites.declared(FactsIndexing.load(suite));
            List<Path> workspaceModules =
                    workspaceModuleDirs(g.root(), ctx.get(PROJECT).orElse(null));
            boolean workspace = GuardSuites.anyWorkspace(declared);
            // A workspace guard judges every module's index: each must describe what this
            // build compiled before the key is taken or the suite reads it.
            if (workspace) awaitIndexed(ctx, g.root(), workspaceModules);
            suiteTokens = GuardKeys.suiteTokens(suite.bodyDigest(), declared, g.root(), workspaceModules);
            List<String> loadErrors = GuardSuites.loadErrors(declared, rules(g).rules());
            if (!loadErrors.isEmpty()) {
                for (String e : loadErrors) ctx.error("guards", "GUARD suite  did not load\n  Observed: " + e);
                throw new GuardsRed(loadErrors.size() + " load errors in the guard suite");
            }
            List<Rule> built = new ArrayList<>();
            for (GuardSuites.Declared d : declared) built.add(GuardSuites.rule(d, g.root(), module));
            suiteRules = built;
            List<Path> factsIdx = new ArrayList<>();
            List<Path> testIdx = new ArrayList<>();
            List<Path> classDirs = new ArrayList<>();
            if (workspace) {
                // Every member's index as this jk reads it: one an older jk wrote is
                // rebuilt from its classes rather than handed to a suite that cannot read it.
                for (Path m : workspaceModules) {
                    Path bd = BuildLayout.moduleTargetDir(m);
                    FactsIndexing.readable(bd.resolve("classes"), FactsIndexing.indexPath(bd, "main"))
                            .ifPresent(factsIdx::add);
                    FactsIndexing.readable(bd.resolve("test-classes"), FactsIndexing.indexPath(bd, "test"))
                            .ifPresent(testIdx::add);
                    classDirs.add(bd.resolve("classes"));
                }
            } else {
                factsIdx.add(FactsIndexing.indexPath(buildDir, "main"));
                if (test != null) testIdx.add(FactsIndexing.indexPath(buildDir, "test"));
                classDirs.add(mainClasses(ctx));
            }
            Path library = GuardSuiteLibrary.locate(g.root(), cas).path();
            List<Path> runtimeCp = PlannerGuardSuite.classpath(
                    ctx.require(PROJECT),
                    ctx.require(LAYOUT),
                    ctx.require(CLASSPATHS).testRuntime(),
                    library);
            GuardSuiteRunner.Inputs inputs = new GuardSuiteRunner.Inputs(
                    g.root(),
                    module,
                    moduleDir,
                    ctx.require(LAYOUT),
                    ctx.require(JAVA_HOME),
                    runtimeCp,
                    in.cache(),
                    factsIdx,
                    testIdx,
                    classDirs,
                    workspace);
            // The fork and the evaluation share one turn on the report: a concurrent run
            // on this tree waits, so the report the test kind reads is this run's.
            withSuite = evaluate -> {
                try {
                    return GuardSuiteRunner.takingTurn(GuardSuiteRunner.report(inputs), () -> {
                        for (String p : GuardSuiteRunner.run(inputs, workspaceModules))
                            ctx.error("guards", "GUARD suite  scanner-failed\n  Observed: " + p);
                        return evaluate.get();
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("guard suite run interrupted", e);
                }
            };
        }
        String facts = main.bodyDigest();
        String testFacts = test == null ? null : test.bodyDigest();
        List<String> tokens = GuardKeys.moduleLaneTokens(module, facts, moduleDir, indexTests, testFacts, suiteTokens);
        // A module that compiled nothing (a resources-only module) has no site for any
        // bytecode rule: that is not blindness, it is an empty population. Record the
        // clean verdict so the forecast stops entering the module for its lane.
        executeLane(
                ctx,
                env,
                Lane.MODULE,
                ectx,
                () -> tokens,
                ActionKey.qualifiedTaskId(TaskNames.GUARD, moduleDir),
                main.classes() == 0 && suiteRules.isEmpty(),
                suiteRules,
                withSuite);
        ctx.progress(1);
    }

    /** {@code guard-model}: model rules at the invocation root, before any compile. */
    Task model() {
        return Task.builder(TaskNames.GUARD_MODEL)
                .forecast(StepForecast.priced(this::forecastModelLane))
                .stage(BuildStage.RESOLVE)
                .label("Guards (model)")
                .kind(TaskKind.CPU)
                .requires(TaskNames.RESOLVE_DEPS)
                .weight(1)
                .ticks(1)
                .execute(this::runModelLane)
                .build();
    }

    /** The model lane: the manifests and the lock at the invocation root. */
    private void runModelLane(TaskContext ctx) throws Exception {
        List<Path> modules = moduleDirs(g.root(), ctx.get(PROJECT).orElse(null));
        JkBuild rootBuild = ctx.get(PROJECT).orElse(null);
        List<String> tokens = rootBuild == null
                ? new ArrayList<>(List.of(GuardKeys.fileToken("manifest", ManifestPaths.manifestIn(g.root()))))
                : GuardKeys.modelTokens(g.root(), rootBuild);
        EvalContext ectx = new EvalContext(Lane.MODEL, g.root(), "", null, modules, noFacts(), () -> null, List::of);
        executeLane(
                ctx,
                env,
                Lane.MODEL,
                ectx,
                () -> tokens,
                ActionKey.qualifiedTaskId(TaskNames.GUARD_MODEL, g.root()),
                false,
                List.of(),
                null);
        ctx.progress(1);
    }

    /** {@code guard-fixtures}: {@code jk guard test} as a guard step — every fixture-bearing rule proven to bite. */
    Task fixtures(String... requires) {
        return Task.builder(TaskNames.GUARD_FIXTURES)
                .forecast(StepForecast.priced(this::forecastFixtures))
                .stage(BuildStage.TEST)
                .label("Guards (fixtures)")
                .kind(TaskKind.CPU)
                .requires(requires)
                .weight(1)
                .ticks(1)
                .execute(this::runFixtures)
                .build();
    }

    /** Every fixture-bearing rule proven to bite, unless the same proof is on record. */
    private void runFixtures(TaskContext ctx) throws Exception {
        // A proof over unchanged rules, fixtures and suites is a proof still: only a
        // green run is recorded, so a red one runs again.
        String taskId = ActionKey.qualifiedTaskId(TaskNames.GUARD_FIXTURES, g.root());
        List<String> tokens = GuardFixtures.keyTokens(g.root(), cas);
        String key = tokens == null ? null : GuardKeys.laneKey(taskId, tokens, "fixtures");
        var session = in.session().config();
        boolean useCache = !session.forceOr(false) && !session.rebuildOr(false);
        if (key != null && useCache && actionCache.lookup(key).isPresent()) {
            ctx.label("every rule bites (cached)");
            ctx.cached();
            ctx.progress(1);
            return;
        }
        GuardFixtures.Result r = GuardFixtures.run(g.root(), cas);
        if (r.verdicts().isEmpty() && r.loadErrors().isEmpty()) {
            ctx.label("no fixtures");
        } else {
            for (String line : r.text().stripTrailing().split("\n")) ctx.output(line);
            ctx.label(r.verdicts().size() + " fixture(s)"
                    + (r.ok() ? " · every rule bites" : " · " + r.failures() + " not proven"));
        }
        if (!r.ok()) throw new GuardsRed(r.failures() + " fixture(s) not proven");
        if (key != null && tokens != null) {
            actionCache.storeVerdict(taskId, key, inputsOf(tokens, "fixtures"));
        }
        ctx.progress(1);
    }

    /** {@code guard-tree}: the text scan at the root; {@code --guard} and {@code jk guard} only. */
    Task tree(String... requires) {
        return Task.builder(TaskNames.GUARD_TREE)
                .forecast(StepForecast.priced(this::forecastTreeLane))
                .stage(BuildStage.TEST)
                .label("Guards (tree)")
                .kind(TaskKind.CPU)
                .requires(requires)
                .weight(() -> plan.get().fullyCached() ? 0 : 2)
                .ticks(1)
                .execute(this::runTreeLane)
                .build();
    }

    /** The tree lane: the text scan over the workspace. */
    private void runTreeLane(TaskContext ctx) throws Exception {
        EvalContext.IoSupplier<List<String>> tokens = () -> GuardKeys.treeLaneTokens(g.root());
        List<Path> modules = moduleDirs(g.root(), ctx.get(PROJECT).orElse(null));
        EvalContext ectx = new EvalContext(Lane.TREE, g.root(), "", null, modules, noFacts(), () -> null, List::of);
        executeLane(
                ctx,
                env,
                Lane.TREE,
                ectx,
                tokens,
                ActionKey.qualifiedTaskId(TaskNames.GUARD_TREE, g.root()),
                false,
                List.of(),
                null);
        ctx.progress(1);
    }

    /**
     * Wait until every other module of {@code modules} has indexed its classes this build. Each lane
     * says so before it waits on anyone, so two workspace suites never wait on each other; the root,
     * which builds after every member, is left out.
     */
    private void awaitIndexed(TaskContext ctx, Path root, List<Path> modules) throws InterruptedException {
        Path self = in.dir().toAbsolutePath().normalize();
        Path top = root.toAbsolutePath().normalize();
        List<Path> judged = new ArrayList<>();
        for (Path m : modules) {
            Path dir = m.toAbsolutePath().normalize();
            if (dir.equals(self) || dir.equals(top)) continue;
            judged.add(m);
        }
        in.siblings().awaitIndexed(judged, ctx::cancelled);
    }

    private static Supplier<@Nullable FactsIndex> testFacts(FactsIndexing.@Nullable Ensured test) {
        if (test == null) return () -> null;
        Supplier<FactsIndex> loaded = EvalContext.lazy(() -> FactsIndexing.load(test));
        return loaded::get;
    }

    // ---- forecast ---------------------------------------------------------------------------

    /** The module lane's price: its verdict under the plan's lane key, stale whenever the compile runs. */
    private TaskForecast.@Nullable Task forecastModuleLane(StepForecast.Scope scope) throws Exception {
        ForecastScope s = ForecastScope.of(scope);
        return GuardKeys.forecastModuleLane(
                        s.dir, s.prepared().parsed().layout(), s.actionCache, s.force || s.compileDirty, s.testsIndexed)
                .orElse(null);
    }

    /** {@code guard-model}'s price at the invocation root: its verdict under the lane's key. */
    private TaskForecast.@Nullable Task forecastModelLane(StepForecast.Scope scope) {
        ForecastScope s = ForecastScope.of(scope);
        if (!rootLaneForecast(s)) return null;
        return GuardKeys.forecastModelLane(s.dir, s.project, s.actionCache).orElse(null);
    }

    /** {@code guard-workspace}'s price at the invocation root: its verdict under the lane's key. */
    private TaskForecast.@Nullable Task forecastWorkspaceLane(StepForecast.Scope scope) {
        ForecastScope s = ForecastScope.of(scope);
        if (!rootLaneForecast(s)) return null;
        return GuardKeys.forecastWorkspaceLane(s.dir, s.actionCache).orElse(null);
    }

    /** {@code guard-tree}'s price at the invocation root: its verdict under the lane's key. */
    private TaskForecast.@Nullable Task forecastTreeLane(StepForecast.Scope scope) {
        ForecastScope s = ForecastScope.of(scope);
        if (!rootLaneForecast(s)) return null;
        return GuardKeys.forecastTreeLane(s.dir, s.actionCache).orElse(null);
    }

    /** {@code guard-fixtures}' price at the invocation root: its verdict under the lane's key. */
    private TaskForecast.@Nullable Task forecastFixtures(StepForecast.Scope scope) {
        ForecastScope s = ForecastScope.of(scope);
        if (!rootLaneForecast(s)) return null;
        return GuardKeys.forecastFixtures(s.dir, s.actionCache, s.cas).orElse(null);
    }
}

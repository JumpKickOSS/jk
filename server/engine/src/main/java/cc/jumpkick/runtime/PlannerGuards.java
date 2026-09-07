// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.CLASSPATH;
import static cc.jumpkick.runtime.BuildPlanner.JAVA_HOME;
import static cc.jumpkick.runtime.BuildPlanner.LAYOUT;
import static cc.jumpkick.runtime.BuildPlanner.MAIN_CLASSES;
import static cc.jumpkick.runtime.BuildPlanner.PROJECT;
import static cc.jumpkick.runtime.BuildPlanner.TEST_CLASSES;
import static cc.jumpkick.runtime.BuildPlanner.TEST_RUNTIME_CP;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.baseline.Baselines;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.baseline.Reconciliation;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.eval.EvalContext;
import cc.jumpkick.guard.eval.GuardMessages;
import cc.jumpkick.guard.eval.GuardSuites;
import cc.jumpkick.guard.eval.LaneRun;
import cc.jumpkick.guard.eval.Outcome;
import cc.jumpkick.guard.eval.RuleReport;
import cc.jumpkick.guard.eval.WorkspaceModules;
import cc.jumpkick.guard.explain.BiteEvidence;
import cc.jumpkick.guard.explain.RuleSummaries;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadError;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.guard.validate.EngineValidations;
import cc.jumpkick.guard.validate.Fault;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The guard lanes as built-in tasks. {@link #detect} is the one plan-time decision: when it says
 * no, nothing here is touched again — no task, no file, no read. When it says yes, each lane is a
 * task keyed on its read set: the module lane on the module's facts digest, the model lane on every
 * manifest and the lock, the tree lane on the whole workspace; all of them on the rule and baseline
 * files. A lane stores a verdict only when every rule came back clean, so a red rule is re-evaluated
 * (and re-reported) on every build until it is fixed or frozen.
 */
final class PlannerGuards {

    private PlannerGuards() {}

    /** Carried on the planner {@link BuildPlanner.Ctx}; {@link #DISABLED} costs nothing downstream. */
    record GuardsPlan(boolean enabled, Path root, GuardsConfig config) {
        static final GuardsPlan DISABLED = new GuardsPlan(false, Path.of(""), GuardsConfig.ABSENT);
    }

    /**
     * One stat: does the root {@code jk-guards.toml} exist? Then the already-parsed manifest's
     * {@code [guards]} table. Nothing else.
     */
    static GuardsPlan detect(BuildPlanner.Inputs in) {
        // The workspace root, not lockDir: member plans carry their own directory there.
        return detectAt(WorkspaceScan.findRoot(in.dir())
                .orElse(in.dir())
                .toAbsolutePath()
                .normalize());
    }

    static GuardsPlan detectAt(Path root) {
        boolean rulesFile = Files.exists(GuardsPresence.rulesFile(root));
        GuardsConfig cfg;
        try {
            cfg = JkBuildParser.guardsConfig(root.resolve(ManifestPaths.MANIFEST));
        } catch (RuntimeException unparseable) {
            // parse-build reports the manifest error; the rule file alone still enables the lanes.
            cfg = GuardsConfig.ABSENT;
        }
        if (rulesFile || cfg.declared() || guardSuiteSeen(root)) return new GuardsPlan(true, root, cfg);
        return GuardsPlan.DISABLED;
    }

    /**
     * A {@code src/guard} suite anywhere in the workspace is guards declared: a project with guard
     * tests and no TOML still gets its lanes. One directory stat per module, from the already-memoised
     * root manifest.
     */
    static boolean guardSuiteSeen(Path root) {
        if (TestSuites.hasGuardSuite(root, ModuleLayout.isCompact(root))) return true;
        Path manifest = root.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(manifest)) return false;
        try {
            JkBuild build = JkBuildParser.parse(manifest);
            if (build.workspace() == null) return false;
            for (String m : build.workspace().modules()) {
                Path dir = root.resolve(m);
                if (Files.isDirectory(dir) && TestSuites.hasGuardSuite(dir, ModuleLayout.isCompact(dir))) return true;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }

    /** Whether {@code root} would enable guards — the graph asks before any plan exists. */
    static boolean enabledAt(Path root) {
        if (Files.exists(GuardsPresence.rulesFile(root)) || guardSuiteSeen(root)) return true;
        try {
            return JkBuildParser.guardsConfig(root.resolve(ManifestPaths.MANIFEST))
                    .declared();
        } catch (RuntimeException unparseable) {
            return false;
        }
    }

    // ---- tasks --------------------------------------------------------------------------------

    /**
     * Add the root lanes to this unit's plan when it is the invocation root and guards are on:
     * {@code guard-model} always, {@code guard-tree} when the session asked for the gate. Returns the
     * last lane added, or {@code null} when none was.
     */
    /**
     * Whether this build runs the module lanes: guards are enabled and either {@code [guards]
     * on-build} is true (the default) or the session asked for the gate. {@code on-build = false}
     * moves the module lanes to {@code --gate}; the model lane stays on every build.
     */
    static boolean moduleLanesOnThisBuild(GuardsPlan g, boolean gate) {
        return g.enabled() && (g.config().onBuild() || gate);
    }

    static boolean gateRequested() {
        var session = SessionContext.current();
        return session != null && session.testSelection().runGateScripts();
    }

    static @Nullable String appendRootLanes(BuildPlan.Builder b, BuildPlanner.Ctx cx, String after) {
        if (!cx.guards().enabled() || !PlannerResources.invocationRoot(cx.in().dir())) return null;
        b.addTask(modelStep(cx));
        String last = TaskNames.GUARD_MODEL;
        boolean gate = PlannerResources.runGateScripts(cx.in());
        // Cross-module structure needs every module's facts at once: the workspace lane, at the
        // root, which the graph already orders after every member. A standalone project has no
        // second module to relate, so it has no such lane.
        if (hasMembers(cx) && moduleLanesOnThisBuild(cx.guards(), gate)) {
            b.addTask(workspaceStep(cx, TaskNames.GUARD_MODEL, after));
            last = TaskNames.GUARD_WORKSPACE;
        }
        if (gate) {
            b.addTask(treeStep(cx, last, after));
            b.addTask(fixturesStep(cx, TaskNames.GUARD_TREE));
            last = TaskNames.GUARD_FIXTURES;
        }
        return last;
    }

    private static boolean hasMembers(BuildPlanner.Ctx cx) {
        try {
            return !WorkspaceModules.of(cx.guards().root()).isEmpty()
                    && !WorkspaceModules.of(cx.guards().root())
                            .equals(List.of(cx.guards().root()));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * {@code guard-workspace}: every module's facts index read together, keyed on every index's
     * digest plus the rules and the baseline — N small indexes, never a class tree. Runs on every
     * build after the module lanes (the graph orders the root after its members).
     */
    static Task workspaceStep(BuildPlanner.Ctx cx, String... requires) {
        GuardsPlan g = cx.guards();
        return Task.builder(TaskNames.GUARD_WORKSPACE)
                .stage(BuildStage.COMPILE)
                .label("Guards (workspace)")
                .kind(TaskKind.CPU)
                .requires(requires)
                .weight(1)
                .ticks(1)
                .execute(ctx -> {
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
                    execute(
                            ctx,
                            cx,
                            Lane.WORKSPACE,
                            ectx,
                            () -> GuardKeys.workspaceTokens(g.root(), modules),
                            ActionKey.qualifiedTaskId(TaskNames.GUARD_WORKSPACE, g.root()),
                            false);
                    ctx.progress(1);
                })
                .build();
    }

    private static boolean compact(Path moduleDir) {
        return ModuleLayout.isCompact(moduleDir);
    }

    /** {@code guard}: this module's bytecode rules, after its compile(s). */
    static Task moduleStep(BuildPlanner.Ctx cx, String... requires) {
        GuardsPlan g = cx.guards();
        return Task.builder(TaskNames.GUARD)
                .stage(BuildStage.COMPILE)
                .label("Guards")
                .kind(TaskKind.CPU)
                .requires(requires)
                .weight(1)
                .ticks(1)
                .execute(ctx -> {
                    Path moduleDir = cx.in().dir();
                    String module = relModule(g.root(), moduleDir);
                    Path buildDir = ctx.require(LAYOUT).buildDir();
                    FactsIndexing.Ensured main =
                            FactsIndexing.ensure(ctx.require(MAIN_CLASSES), FactsIndexing.indexPath(buildDir, "main"));
                    Path testClasses = ctx.get(TEST_CLASSES).orElse(null);
                    FactsIndexing.Ensured test = testClasses != null && Files.isDirectory(testClasses)
                            ? FactsIndexing.ensure(testClasses, FactsIndexing.indexPath(buildDir, "test"))
                            : null;
                    List<String> tokens = new ArrayList<>();
                    tokens.add("module:" + module);
                    tokens.add("facts:" + main.bodyDigest());
                    if (test != null) tokens.add("test-facts:" + test.bodyDigest());
                    EvalContext ectx = new EvalContext(
                            Lane.MODULE,
                            g.root(),
                            module,
                            moduleDir,
                            List.of(moduleDir),
                            EvalContext.lazy(() -> FactsIndexing.load(main)),
                            testFacts(test),
                            () -> ctx.get(CLASSPATH).orElse(List.of()));
                    // ---- the module's src/guard suite, if compiled: its guards join the lane's rules
                    Path guardClasses = ctx.require(LAYOUT).guardClassesDir();
                    List<Rule> suiteRules = List.of();
                    EvalContext.IoRunnable beforeEvaluate = null;
                    if (Files.isDirectory(guardClasses) && PlannerGuardSuite.declared(moduleDir, compact(moduleDir))) {
                        FactsIndexing.Ensured suite =
                                FactsIndexing.ensure(guardClasses, FactsIndexing.indexPath(buildDir, "guard"));
                        List<GuardSuites.Declared> declared = GuardSuites.declared(FactsIndexing.load(suite));
                        tokens.add("guard-suite:" + suite.bodyDigest());
                        List<Path> workspaceModules =
                                workspaceModuleDirs(g.root(), ctx.get(PROJECT).orElse(null));
                        boolean workspace = GuardSuites.anyWorkspace(declared);
                        if (workspace) tokens.addAll(GuardKeys.workspaceTokens(g.root(), workspaceModules));
                        // A guard that reads Text is a guard whose verdict moves with the tree's text: key on it.
                        if (GuardSuites.anyReadsText(declared))
                            tokens.addAll(BuildLogicSupport.workspaceInputTokens(g.root()));
                        List<String> loadErrors = GuardSuites.loadErrors(declared, rules(g).rules());
                        if (!loadErrors.isEmpty()) {
                            for (String e : loadErrors)
                                ctx.error("guards", "GUARD suite  did not load\n  Observed: " + e);
                            throw new GuardsRed(loadErrors.size() + " load errors in the guard suite");
                        }
                        List<Rule> built = new ArrayList<>();
                        for (GuardSuites.Declared d : declared) built.add(GuardSuites.rule(d, g.root(), module));
                        suiteRules = built;
                        List<Path> factsIdx = new ArrayList<>();
                        List<Path> testIdx = new ArrayList<>();
                        List<Path> classDirs = new ArrayList<>();
                        if (workspace) {
                            for (Path m : workspaceModules) {
                                Path bd = BuildLayout.moduleTargetDir(g.root(), m);
                                Path idx = FactsIndexing.indexPath(bd, "main");
                                if (Files.isRegularFile(idx)) factsIdx.add(idx);
                                Path tidx = FactsIndexing.indexPath(bd, "test");
                                if (Files.isRegularFile(tidx)) testIdx.add(tidx);
                                classDirs.add(bd.resolve("classes").resolve("main"));
                            }
                        } else {
                            factsIdx.add(FactsIndexing.indexPath(buildDir, "main"));
                            if (test != null) testIdx.add(FactsIndexing.indexPath(buildDir, "test"));
                            classDirs.add(ctx.require(MAIN_CLASSES));
                        }
                        Path library =
                                GuardSuiteLibrary.locate(g.root(), cx.cas()).path();
                        List<Path> runtimeCp = PlannerGuardSuite.classpath(
                                ctx.require(PROJECT),
                                ctx.require(LAYOUT),
                                ctx.get(TEST_RUNTIME_CP)
                                        .orElseGet(() -> ctx.get(CLASSPATH).orElse(List.of())),
                                library);
                        GuardSuiteRunner.Inputs inputs = new GuardSuiteRunner.Inputs(
                                g.root(),
                                module,
                                moduleDir,
                                ctx.require(LAYOUT),
                                ctx.require(JAVA_HOME),
                                runtimeCp,
                                cx.in().cache(),
                                factsIdx,
                                testIdx,
                                classDirs,
                                workspace);
                        beforeEvaluate = () -> {
                            List<String> problems;
                            try {
                                problems = GuardSuiteRunner.run(inputs, workspaceModules);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException("guard suite run interrupted", e);
                            }
                            for (String p : problems)
                                ctx.error("guards", "GUARD suite  scanner-failed\n  Observed: " + p);
                        };
                    }
                    // A module that compiled nothing (a resources-only module) has no site for any
                    // bytecode rule: that is not blindness, it is an empty population. Record the
                    // clean verdict so the forecast stops entering the module for its lane.
                    execute(
                            ctx,
                            cx,
                            Lane.MODULE,
                            ectx,
                            () -> tokens,
                            ActionKey.qualifiedTaskId(TaskNames.GUARD, moduleDir),
                            main.classes() == 0 && suiteRules.isEmpty(),
                            suiteRules,
                            beforeEvaluate);
                    ctx.progress(1);
                })
                .build();
    }

    /** {@code guard-model}: model rules at the invocation root, before any compile. */
    static Task modelStep(BuildPlanner.Ctx cx) {
        GuardsPlan g = cx.guards();
        return Task.builder(TaskNames.GUARD_MODEL)
                .stage(BuildStage.RESOLVE)
                .label("Guards (model)")
                .kind(TaskKind.CPU)
                .requires(TaskNames.RESOLVE_DEPS)
                .weight(1)
                .ticks(1)
                .execute(ctx -> {
                    List<Path> modules = moduleDirs(g.root(), ctx.get(PROJECT).orElse(null));
                    JkBuild rootBuild = ctx.get(PROJECT).orElse(null);
                    List<String> tokens = rootBuild == null
                            ? new ArrayList<>(List.of(
                                    GuardKeys.fileToken("manifest", g.root().resolve(ManifestPaths.MANIFEST))))
                            : GuardKeys.modelTokens(g.root(), rootBuild);
                    EvalContext ectx =
                            new EvalContext(Lane.MODEL, g.root(), "", null, modules, noFacts(), () -> null, List::of);
                    execute(
                            ctx,
                            cx,
                            Lane.MODEL,
                            ectx,
                            () -> tokens,
                            ActionKey.qualifiedTaskId(TaskNames.GUARD_MODEL, g.root()),
                            false);
                    ctx.progress(1);
                })
                .build();
    }

    /** {@code guard-fixtures}: {@code jk guard test} as a gate step — every fixture-bearing rule proven to bite. */
    static Task fixturesStep(BuildPlanner.Ctx cx, String... requires) {
        GuardsPlan g = cx.guards();
        return Task.builder(TaskNames.GUARD_FIXTURES)
                .stage(BuildStage.TEST)
                .label("Guards (fixtures)")
                .kind(TaskKind.CPU)
                .requires(requires)
                .weight(1)
                .ticks(1)
                .execute(ctx -> {
                    GuardFixtures.Result r = GuardFixtures.run(g.root(), cx.cas());
                    if (r.verdicts().isEmpty() && r.loadErrors().isEmpty()) {
                        ctx.label("no fixtures");
                    } else {
                        for (String line : r.text().stripTrailing().split("\n")) ctx.output(line);
                        ctx.label(r.verdicts().size() + " fixture(s)"
                                + (r.ok() ? " · every rule bites" : " · " + r.failures() + " not proven"));
                    }
                    if (!r.ok()) throw new GuardsRed(r.failures() + " fixture(s) not proven");
                    ctx.progress(1);
                })
                .build();
    }

    /** {@code guard-tree}: the text scan at the root; {@code --gate} and {@code jk guard} only. */
    static Task treeStep(BuildPlanner.Ctx cx, String... requires) {
        GuardsPlan g = cx.guards();
        return Task.builder(TaskNames.GUARD_TREE)
                .stage(BuildStage.TEST)
                .label("Guards (tree)")
                .kind(TaskKind.CPU)
                .requires(requires)
                .weight(() -> cx.plan().get().fullyCached() ? 0 : 2)
                .ticks(1)
                .execute(ctx -> {
                    EvalContext.IoSupplier<List<String>> tokens = () -> {
                        List<String> t = cx.buildLogicInputTokensRef().get();
                        if (t == null) {
                            t = BuildLogicSupport.workspaceInputTokens(g.root());
                            cx.buildLogicInputTokensRef().compareAndSet(null, t);
                        }
                        return t;
                    };
                    List<Path> modules = moduleDirs(g.root(), ctx.get(PROJECT).orElse(null));
                    EvalContext ectx =
                            new EvalContext(Lane.TREE, g.root(), "", null, modules, noFacts(), () -> null, List::of);
                    execute(
                            ctx,
                            cx,
                            Lane.TREE,
                            ectx,
                            tokens,
                            ActionKey.qualifiedTaskId(TaskNames.GUARD_TREE, g.root()),
                            false);
                    ctx.progress(1);
                })
                .build();
    }

    // ---- one lane run -------------------------------------------------------------------------

    private static void execute(
            TaskContext ctx,
            BuildPlanner.Ctx cx,
            Lane lane,
            EvalContext ectx,
            EvalContext.IoSupplier<List<String>> tokenSupplier,
            String taskId,
            boolean noClasses)
            throws IOException {
        execute(ctx, cx, lane, ectx, tokenSupplier, taskId, noClasses, List.of(), null);
    }

    /**
     * @param extraRules rules the lane owns beyond the TOML file — a module's guard tests
     * @param beforeEvaluate runs once the lane is known not to be cached, before evaluation — the
     *     suite's forked JUnit run, which leaves the report the test kind reads
     */
    private static void execute(
            TaskContext ctx,
            BuildPlanner.Ctx cx,
            Lane lane,
            EvalContext ectx,
            EvalContext.IoSupplier<List<String>> tokenSupplier,
            String taskId,
            boolean noClasses,
            List<Rule> extraRules,
            EvalContext.@Nullable IoRunnable beforeEvaluate)
            throws IOException {
        GuardsPlan g = cx.guards();
        LoadResult load = rules(g);
        if (lane == Lane.MODEL) {
            for (LoadError w : load.warnings()) ctx.warn("guards", w.render());
        }
        if (load.hasErrors()) {
            if (lane == Lane.MODEL) {
                StringBuilder sb = new StringBuilder("jk-guards.toml did not load; no guard ran\n");
                for (LoadError e : load.errors())
                    sb.append("  ").append(e.render()).append('\n');
                ctx.error("guards", sb.toString().stripTrailing());
                throw new GuardsRed(load.errors().size() + " load errors in jk-guards.toml");
            }
            ctx.label("rules did not load");
            return;
        }
        List<Rule> rules = new ArrayList<>(LaneRun.rulesFor(lane, load.rules(), ectx.module()));
        rules.addAll(extraRules);
        ectx = ectx.withRules(load.rules());
        Path baselineFile = GuardsPresence.baselineFile(g.root());
        Baseline baseline = BaselineFile.read(baselineFile);
        boolean orphans = false;
        if (lane == Lane.MODEL) {
            // Live ids are the TOML rules plus every guard the compiled suites declare.
            Set<String> live = new TreeSet<>(load.rules().rules().keySet());
            live.addAll(GuardSuites.declaredAcrossWorkspace(g.root()).keySet());
            for (String orphan : baseline.orphans(live)) {
                orphans = true;
                ctx.error(
                        orphan,
                        "GUARD " + orphan
                                + "  rule-removed\n  Observed: the baseline carries entries for a rule no source declares\n"
                                + "  Instead:  restore the rule, or `jk guard freeze --retire " + orphan
                                + " --reason \"…\"`");
            }
        }
        if (rules.isEmpty() && !EngineValidations.applies(lane)) {
            ctx.label("no rules for this lane");
            if (orphans) throw new GuardsRed("baseline names retired rules");
            return;
        }
        // The key's inputs are gathered only once there is a rule to key: a lane with nothing to
        // evaluate costs no stat, no walk.
        List<String> tokens = new ArrayList<>(tokenSupplier.get());
        GuardKeys.addRuleTokens(tokens, load);
        String baselineSha = GuardKeys.baselineSha(g.root());
        String key = GuardKeys.laneKey(taskId, tokens, baselineSha);
        ActionCache cache = cx.actionCache();
        boolean useCache = !cx.in().session().config().forceOr(false)
                && !cx.in().session().config().rebuildOr(false);
        if (useCache && cache.lookup(key).isPresent()) {
            ctx.label(rules.size() + (rules.size() == 1 ? " rule" : " rules") + " · clean (cached)");
            ctx.cached();
            return;
        }
        if (noClasses) {
            ctx.label("no classes · nothing to examine");
            cache.storeVerdict(taskId, key, inputsOf(tokens, baselineSha));
            return;
        }
        if (beforeEvaluate != null) beforeEvaluate.run();
        LaneRun.Result result;
        if (lane == Lane.MODULE) {
            // Module lanes are quick but each holds a facts index and a text pass in flight; a
            // workspace of thirty run with the build's parallelism would multiply that against the
            // engine's heap. A few at a time keep the peak bounded and the wall unchanged.
            LANES.acquireUninterruptibly();
            try {
                result = LaneRun.run(lane, rules, ectx, baseline);
            } finally {
                LANES.release();
            }
        } else {
            result = LaneRun.run(lane, rules, ectx, baseline);
        }
        // Engine validations ride the lane: invariants of the build model, not rules, so they
        // have no table and no baseline, and a workspace without guards runs none.
        List<Fault> faults = lane == Lane.MODEL
                ? EngineValidations.model(g.root())
                : lane == Lane.MODULE ? EngineValidations.module(g.root(), ectx.module(), ectx.testFacts()) : List.of();
        for (Fault f : faults) ctx.error(f.code(), EngineValidations.render(f));
        boolean ci = Baselines.ciMode();
        String storedBaselineSha = baselineSha;
        if (result.tightened() > 0) {
            if (ci) {
                ctx.error(
                        "guards",
                        "GUARD baseline  loose\n  Observed: " + result.tightened()
                                + " entries would tighten\n  Instead:  " + Baselines.CI_MESSAGE);
            } else {
                mergeTightened(baselineFile, result);
                storedBaselineSha = GuardKeys.baselineSha(g.root());
                ctx.output(result.tightened() + " baseline entries tightened");
            }
        }
        writeJsonl(g.root(), taskId, result);
        for (RuleReport r : result.redReports()) {
            if (r.outcome() == Outcome.VIOLATIONS) {
                for (Observation o : r.fresh()) ctx.error(r.id(), GuardMessages.site(r, o));
            } else {
                ctx.error(r.id(), GuardMessages.outcome(r));
            }
        }
        // The tree lane runs after every module lane: the one place must-bite can be judged for them.
        int noBite = 0;
        if (lane == Lane.TREE) {
            for (BiteEvidence.Missing m : BiteEvidence.missing(g.root(), load.rules())) {
                noBite++;
                ctx.error(m.rule().id(), GuardMessages.noBite(m.rule(), m.note()));
            }
        }
        ctx.label(GuardMessages.summary(result, rules.size()));
        if (noBite > 0 && !result.red()) {
            ctx.output(GuardMessages.TRAILER);
            throw new GuardsRed(noBite + (noBite == 1 ? " guard has" : " guards have") + " no bite evidence");
        }
        if (result.red() || !faults.isEmpty()) {
            ctx.output(GuardMessages.TRAILER);
            // The diagnostics above carry the detail; the throw is what fails the step.
            throw new GuardsRed(result.redReports().size() + " of " + rules.size() + " guards red"
                    + (faults.isEmpty() ? "" : ", " + faults.size() + " engine validation(s) failed"));
        }
        if (ci && result.tightened() > 0) throw new GuardsRed(Baselines.CI_MESSAGE);
        String storeKey = GuardKeys.laneKey(taskId, tokens, storedBaselineSha);
        cache.storeVerdict(taskId, storeKey, inputsOf(tokens, storedBaselineSha));
    }

    private static final Object BASELINE_LOCK = new Object();

    /** Module lanes in flight at once. */
    private static final Semaphore LANES = new Semaphore(4);

    /**
     * Module lanes run concurrently and each tightens only its own slice of a rule's baseline, so
     * the write re-reads the file and replaces just those slices: a lane never overwrites what
     * another lane tightened a moment ago.
     */
    private static void mergeTightened(Path baselineFile, LaneRun.Result result) throws IOException {
        synchronized (BASELINE_LOCK) {
            Baseline latest = BaselineFile.read(baselineFile);
            for (RuleReport r : result.reports()) {
                Reconciliation rec = r.reconciliation();
                if (rec == null || !rec.tighteningNeeded()) continue;
                RuleBaseline mine = rec.tightened();
                RuleBaseline theirs = latest.of(r.id());
                latest = latest.with(
                        r.id(), theirs.withLane(rec.lane(), mine.population(rec.lane()), mine.entries(rec.lane())));
            }
            BaselineFile.write(baselineFile, latest);
        }
    }

    /** A red lane: the diagnostics already say why; this only fails the step. */
    static final class GuardsRed extends RuntimeException {
        GuardsRed(String message) {
            super(message);
        }
    }

    private static Map<String, String> inputsOf(List<String> tokens, String baselineSha) {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (String t : tokens) {
            int i = t.indexOf(':');
            inputs.put(i < 0 ? t : t.substring(0, i), i < 0 ? "" : t.substring(i + 1));
        }
        inputs.put("baseline", baselineSha);
        return inputs;
    }

    /** The full list, every lane its own file: {@code target/jk-guards/<lane>.jsonl}. */
    private static void writeJsonl(Path root, String taskId, LaneRun.Result result) throws IOException {
        Path dir = root.resolve(BuildLayout.TARGET).resolve("jk-guards");
        Files.createDirectories(dir);
        String name = taskId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jsonl";
        StringBuilder sb = new StringBuilder();
        for (RuleReport r : result.reports()) {
            for (Observation o : r.fresh())
                sb.append(GuardMessages.jsonl(r, o, true)).append('\n');
            for (Observation o : r.baselined())
                sb.append(GuardMessages.jsonl(r, o, false)).append('\n');
        }
        Path file = dir.resolve(name);
        if (sb.length() == 0) {
            Files.deleteIfExists(file);
        } else {
            AtomicWrites.replace(file, sb.toString());
        }
        RuleSummaries.write(root, taskId, result);
    }

    // ---- rules memo ---------------------------------------------------------------------------

    private record Memo(long size, long mtime, LoadResult load) {}

    private static final Map<Path, Memo> RULES = new ConcurrentHashMap<>();

    /** The rule file parsed once per {@code (size, mtime)}; every lane of every module shares it. */
    static LoadResult rules(GuardsPlan g) throws IOException {
        Path file = GuardsPresence.rulesFile(g.root());
        long size = 0;
        long mtime = 0;
        if (Files.isRegularFile(file)) {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            size = a.size();
            mtime = a.lastModifiedTime().toMillis();
        }
        Memo m = RULES.get(file);
        if (m != null && m.size == size && m.mtime == mtime) return m.load;
        LoadResult load = GuardRules.load(g.root(), g.config());
        RULES.put(file, new Memo(size, mtime, load));
        return load;
    }

    // ---- helpers -------------------------------------------------------------------------------

    static String relModule(Path root, Path moduleDir) {
        Path r = root.toAbsolutePath().normalize();
        Path m = moduleDir.toAbsolutePath().normalize();
        return m.startsWith(r) ? r.relativize(m).toString().replace('\\', '/') : m.toString();
    }

    /**
     * The workspace's module directories from inside a member's step, where {@code PROJECT} is the
     * member's own manifest and knows nothing of its siblings: a WORKSPACE suite declared in a member
     * still sees the whole workspace.
     */
    private static List<Path> workspaceModuleDirs(Path root, @Nullable JkBuild project) {
        if (project != null && project.workspace() != null) return moduleDirs(root, project);
        Path manifest = root.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(manifest)) return List.of();
        try {
            return moduleDirs(root, JkBuildParser.parse(manifest));
        } catch (IOException | RuntimeException unparseable) {
            return List.of();
        }
    }

    private static List<Path> moduleDirs(Path root, @Nullable JkBuild rootBuild) {
        List<Path> out = new ArrayList<>();
        if (rootBuild != null && rootBuild.workspace() != null) {
            for (String m : rootBuild.workspace().modules()) out.add(root.resolve(m));
        }
        return out;
    }

    private static Supplier<@Nullable FactsIndex> testFacts(FactsIndexing.@Nullable Ensured test) {
        if (test == null) return () -> null;
        Supplier<FactsIndex> loaded = EvalContext.lazy(() -> FactsIndexing.load(test));
        return loaded::get;
    }

    private static Supplier<FactsIndex> noFacts() {
        return () -> FactsIndex.EMPTY;
    }
}

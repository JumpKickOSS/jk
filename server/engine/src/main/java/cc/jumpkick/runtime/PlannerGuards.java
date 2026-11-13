// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.guard.eval.GuardMessages.WITHHELD;
import static cc.jumpkick.guard.eval.GuardMessages.withheldNotice;
import static cc.jumpkick.runtime.BuildPlanner.MODULE;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.baseline.Baselines;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.baseline.Reconciliation;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.eval.EvalContext;
import cc.jumpkick.guard.eval.Evaluators;
import cc.jumpkick.guard.eval.GuardMessages;
import cc.jumpkick.guard.eval.GuardSuites;
import cc.jumpkick.guard.eval.GuardThrash;
import cc.jumpkick.guard.eval.LaneRun;
import cc.jumpkick.guard.eval.Outcome;
import cc.jumpkick.guard.eval.OutputArtifacts;
import cc.jumpkick.guard.eval.RuleReport;
import cc.jumpkick.guard.eval.WorkspaceModules;
import cc.jumpkick.guard.explain.BiteEvidence;
import cc.jumpkick.guard.explain.RuleSummaries;
import cc.jumpkick.guard.explain.SarifWriter;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadError;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.guard.validate.EngineValidations;
import cc.jumpkick.guard.validate.Fault;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ModuleIdentity;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.GuardBaselineMarker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The guard lanes as built-in tasks. {@link #detect} is the one plan-time decision: when it says
 * no, nothing here is touched again — no task, no file, no read. When it says yes, each lane is a
 * task keyed on its read set: the module lane on the module's facts digest, the model lane on every
 * manifest and the lock, the tree lane on the whole workspace; all of them on the rule and baseline
 * files. A lane stores a verdict only when every rule came back clean, so a red rule is re-evaluated
 * (and re-reported) on every build until it is fixed or frozen. One plan's lanes are {@link
 * GuardSteps}; the lane run, the rules and the output lane the packaging tails plan without a
 * planner context are here.
 */
final class PlannerGuards {

    private PlannerGuards() {}

    /** Carried on the planner {@link BuildPlanner.Ctx}; {@link #DISABLED} costs nothing downstream. */
    record GuardsPlan(boolean enabled, Path root, GuardsConfig config) {
        static final GuardsPlan DISABLED = new GuardsPlan(false, Path.of(""), GuardsConfig.ABSENT);
    }

    /**
     * One stat: does the root {@code jk-guards.toml} exist? Then the already-parsed manifest's
     * {@code [guards]} table, then the request's one scan for a guard suite. {@code --skip-tests} without {@code --guard} runs no
     * lane at all.
     */
    static GuardsPlan detect(BuildPlanner.Inputs in) {
        if (lanesSkipped(in.skipTests() && !in.testOnly(), PlannerResources.runGuardScripts(in)))
            return GuardsPlan.DISABLED;
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
            cfg = JkBuildParser.guardsConfig(ManifestPaths.manifestIn(root));
        } catch (RuntimeException unparseable) {
            // parse-build reports the manifest error; the rule file alone still enables the lanes.
            cfg = GuardsConfig.ABSENT;
        }
        if (rulesFile || cfg.declared() || guardSuiteSeen(root)) return new GuardsPlan(true, root, cfg);
        return GuardsPlan.DISABLED;
    }

    /**
     * A {@code src/guard} suite anywhere in the workspace is guards declared: a project with guard
     * tests and no TOML still gets its lanes. A directory stat per module, once per request: every
     * module's plan asks, and a workspace with no guards would otherwise pay modules squared stats.
     */
    static boolean guardSuiteSeen(Path root) {
        return RequestScope.current().get(new GuardSuiteSeen(root), k -> scanForGuardSuite(k.root()));
    }

    /** The request-scoped key of {@link #guardSuiteSeen}. */
    private record GuardSuiteSeen(Path root) {}

    private static boolean scanForGuardSuite(Path root) {
        if (TestSuites.hasGuardSuite(root, ModuleLayout.isCompact(root))) return true;
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) return false;
        JkBuild build;
        try {
            build = JkBuildParser.parse(manifest);
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (IOException | RuntimeException e) {
            return false; // parse-build reports it
        }
        // The build's expansion, outside the fallback: a glob that selects nothing is the build's error.
        for (Path dir : WorkspaceModules.members(root, build)) {
            if (Files.isDirectory(dir) && TestSuites.hasGuardSuite(dir, ModuleLayout.isCompact(dir))) return true;
        }
        return false;
    }

    /** Whether {@code root} would enable guards — the graph asks before any plan exists. */
    static boolean enabledAt(Path root) {
        if (Files.exists(GuardsPresence.rulesFile(root)) || guardSuiteSeen(root)) return true;
        try {
            return JkBuildParser.guardsConfig(ManifestPaths.manifestIn(root)).declared();
        } catch (RuntimeException unparseable) {
            return false;
        }
    }

    // ---- tasks --------------------------------------------------------------------------------

    /**
     * Add the root lanes to this unit's plan when it is the invocation root and guards are on:
     * {@code guard-model} always, {@code guard-tree} when the session asked for the guard. Returns the
     * last lane added, or {@code null} when none was.
     */
    /**
     * Whether this build runs the module lanes: guards are enabled and either {@code [guards]
     * on-build} is true (the default) or the session asked for the guard. {@code on-build = false}
     * moves the module lanes to {@code --guard}; the model lane stays on every build.
     */
    static boolean moduleLanesOnThisBuild(GuardsPlan g, boolean guard) {
        return g.enabled() && (g.config().onBuild() || guard);
    }

    /** {@code --skip-tests} skips the guard lanes too, unless the run asked for the guard. */
    static boolean lanesSkipped(boolean skipTests, boolean guard) {
        return skipTests && !guard;
    }

    static boolean guardRequested() {
        var session = SessionContext.current();
        return session != null && session.testSelection().runGuardScripts();
    }

    static boolean compact(Path moduleDir) {
        return ModuleLayout.isCompact(moduleDir);
    }

    /**
     * Whether this build's module lane indexes the test classes: only when the plan compiles them.
     * Under {@code --skip-tests} or {@code --compile-only} no compile-test runs, and {@code
     * target/test-classes} holds whatever a previous build left there — indexing it would judge bytecode
     * the sources no longer describe.
     */
    static boolean indexesTestClasses(BuildPlanner.Inputs in) {
        return !in.compileOnly() && !PlannerResources.skipJUnit(in);
    }

    /** What a lane needs of the planner: the guards plan, the action cache and the inputs. */
    record LaneEnv(GuardsPlan guards, ActionCache actionCache, BuildPlanner.Inputs in) {}

    /** From the inputs alone, for a step planned where no planner context exists (the packaging tails). */
    static LaneEnv env(BuildPlanner.Inputs in) {
        return new LaneEnv(
                detect(in), new ActionCache(JkStores.cacheCas(in.cache()), CacheTree.ACTIONS.under(in.cache())), in);
    }

    /**
     * Whether this build plans {@code guard-output}: guards enabled and at least one rule on the
     * output lane. Anything else costs the packaging tasks no new dependant and no key material.
     */
    static boolean outputLaneWanted(BuildPlanner.Inputs in) {
        GuardsPlan g = detect(in);
        if (!g.enabled()) return false;
        try {
            LoadResult load = rules(g);
            if (load.hasErrors()) return false;
            for (Rule r : load.rules().rules().values()) if (Evaluators.laneOf(r) == Lane.OUTPUT) return true;
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /**
     * {@code guard-output}: the artefact assertions and output measures at the root, keyed on the
     * artefacts' stamps, the coverage report, the rules and the baseline. Runs after packaging (and
     * the native tail) in a plan that packages; after the other root lanes otherwise.
     */
    static Task outputStep(LaneEnv env, String... requires) {
        GuardsPlan g = env.guards();
        return Task.builder(TaskNames.GUARD_OUTPUT)
                .forecast(StepForecast.priced(s -> forecastOutputLane(ForecastScope.of(s))))
                .stage(BuildStage.NATIVE)
                .label("Guards (output)")
                .kind(TaskKind.IO)
                .requires(requires)
                .weight(1)
                .ticks(1)
                .execute(ctx -> {
                    List<Path> modules = moduleDirs(
                            g.root(),
                            ctx.get(MODULE).map(ModuleIdentity::project).orElse(null));
                    EvalContext.IoSupplier<List<String>> tokens = () ->
                            GuardKeys.outputTokens(g.root(), modules, g.config().coverageReport());
                    EvalContext ectx =
                            new EvalContext(Lane.OUTPUT, g.root(), "", null, modules, noFacts(), () -> null, List::of);
                    executeLane(
                            ctx,
                            env,
                            Lane.OUTPUT,
                            ectx,
                            tokens,
                            ActionKey.qualifiedTaskId(TaskNames.GUARD_OUTPUT, g.root()),
                            false,
                            List.of(),
                            null);
                    ctx.progress(1);
                })
                .build();
    }

    // ---- one lane run -------------------------------------------------------------------------

    /**
     * The lane's evaluation wrapped in what must surround it: the module's suite forks first and
     * the report it leaves is read under the same turn on the tree.
     */
    interface Evaluation {
        LaneRun.Result run(EvalContext.IoSupplier<LaneRun.Result> evaluate) throws IOException;
    }

    /**
     * One lane, with its failure on record. The step's diagnostic carries a throwable's message
     * alone, so the trace — the one thing that names the comparator, the file or the rule behind a
     * lane that threw — goes to the engine log before the step fails. A red verdict is not a
     * failure of the lane and is already on the step's diagnostics.
     */
    static void executeLane(
            TaskContext ctx,
            LaneEnv env,
            Lane lane,
            EvalContext ectx,
            EvalContext.IoSupplier<List<String>> tokenSupplier,
            String taskId,
            boolean noClasses,
            List<Rule> extraRules,
            @Nullable Evaluation withSuite)
            throws IOException {
        try {
            evaluateLane(ctx, env, lane, ectx, tokenSupplier, taskId, noClasses, extraRules, withSuite);
        } catch (GuardsRed red) {
            throw red;
        } catch (IOException | RuntimeException e) {
            Log.warn("guard lane " + lane.name().toLowerCase(Locale.ROOT) + " threw", e);
            throw e;
        }
    }

    private static void evaluateLane(
            TaskContext ctx,
            LaneEnv env,
            Lane lane,
            EvalContext ectx,
            EvalContext.IoSupplier<List<String>> tokenSupplier,
            String taskId,
            boolean noClasses,
            List<Rule> extraRules,
            @Nullable Evaluation withSuite)
            throws IOException {
        GuardsPlan g = env.guards();
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
        if (lane == Lane.OUTPUT && !coverageAsked(env, ectx)) rules.removeIf(PlannerGuards::readsCoverage);
        // jk guard compiles no tests: a rule over them waits for a build that does, rather than
        // recording a population over no test classes and drifting the baseline between the two.
        if (lane == Lane.MODULE && !indexesTestClasses(env.in())) rules.removeIf(Evaluators::readsTestClasses);
        rules.addAll(extraRules);
        ectx = ectx.withRules(load.rules());
        Path baselineFile = GuardsPresence.baselineFile(g.root());
        Baseline baseline = BaselineFile.read(baselineFile);
        if (lane == Lane.MODEL && reportOrphans(ctx, g, load, baseline)) {
            throw new GuardsRed("baseline names retired rules");
        }
        if (!EngineValidations.judges(lane, rules)) {
            ctx.label("no rules for this lane");
            return;
        }
        // The key's inputs are gathered only once there is a rule to key: a lane with nothing to
        // evaluate costs no stat, no walk.
        List<String> tokens = new ArrayList<>(tokenSupplier.get());
        GuardKeys.addRuleTokens(tokens, load);
        String baselineSha = GuardKeys.slice(g, lane, ectx, load);
        String key = GuardKeys.laneKey(taskId, tokens, baselineSha);
        if (Perf.enabled()) Perf.note("guard-lane " + taskId, "key", key, "X", tokens);
        ActionCache cache = env.actionCache();
        boolean useCache = !env.in().session().config().forceOr(false)
                && !env.in().session().config().rebuildOr(false);
        Optional<ActionCache.ActionRecord> verdict = useCache ? cache.lookup(key) : Optional.empty();
        if (verdict.isPresent()) {
            // The lane's summary and observations ride the verdict, so a checkout that never ran
            // the lane still has the evidence the tree lane's no-bite judgement reads. A restore
            // that fails is a miss: the key would otherwise stay a permanent green with no evidence.
            boolean restored = verdict.get().outputs().isEmpty()
                    || cache.restoreArtifacts(verdict.get(), RuleSummaries.dir(g.root()));
            if (restored) {
                ctx.label(rules.size() + (rules.size() == 1 ? " rule" : " rules") + " · clean (cached)");
                ctx.cached();
                return;
            }
            Log.debug("guard lane: " + taskId + " verdict found, its evidence did not restore");
        }
        if (noClasses) {
            ctx.label("no classes · nothing to examine");
            cache.storeVerdict(taskId, key, inputsOf(tokens, baselineSha));
            return;
        }
        EvalContext laneCtx = ectx;
        LaneRun.Result result = withSuite == null
                ? runLane(lane, rules, laneCtx, baseline)
                : withSuite.run(() -> runLane(lane, rules, laneCtx, baseline));
        // Engine validations ride the lane: invariants of the build model, not rules, so they
        // have no table and no baseline, and a workspace without guards runs none.
        List<Fault> faults = lane == Lane.MODEL
                ? EngineValidations.model(g.root())
                : lane == Lane.MODULE ? EngineValidations.module(g.root(), ectx.module(), ectx.testFacts()) : List.of();
        for (Fault f : faults) ctx.error(f.code(), EngineValidations.render(f));
        boolean ci = Baselines.ciMode();
        String storedSlice = tightenBaseline(ctx, g, result, ci) ? GuardKeys.slice(g, lane, ectx, load) : baselineSha;
        if (!result.unseen().isEmpty())
            ctx.warn(WITHHELD, withheldNotice(lane, result.unseen(), result.unseenModules()));
        writeJsonl(g.root(), taskId, result);
        // The machine view of the whole run, re-rendered from every lane's output: cheap, and always
        // current after the lane that just wrote.
        try {
            SarifWriter.write(g.root(), load.rules(), BaselineFile.read(baselineFile));
        } catch (IOException e) {
            ctx.warn("guards", "could not write " + SarifWriter.SARIF_FILE + ": " + e.getMessage());
        }
        reportRed(ctx, taskId, result);
        // A guard that could not run here says so once, as a notice: the developer decides whether
        // to install the tool, and nothing is red for its absence.
        for (RuleReport r : result.skippedReports()) ctx.warn(r.id(), GuardMessages.outcome(r));
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
        // A skipped guard is not a verdict: the lane runs again next build, so installing the tool
        // is enough to have it judged — a cached green would outlive the reason it was green.
        if (!result.complete()) return;
        // Nor is a view missing modules: the run that builds them tightens the baseline, and until
        // then every run over the partial view says it left the baseline alone.
        if (!result.unseen().isEmpty()) return;
        String storeKey = GuardKeys.laneKey(taskId, tokens, storedSlice);
        List<Path> evidence = new ArrayList<>();
        for (Path f : List.of(RuleSummaries.file(g.root(), taskId), observationsFile(g.root(), taskId))) {
            if (Files.isRegularFile(f)) evidence.add(f);
        }
        if (evidence.isEmpty()) {
            cache.storeVerdict(taskId, storeKey, inputsOf(tokens, storedSlice));
        } else {
            cache.storeArtifacts(
                    taskId, storeKey, inputsOf(tokens, storedSlice), RuleSummaries.dir(g.root()), evidence);
        }
    }

    /**
     * Model lane only: a baseline entry for a rule no source declares is an error. Live ids are the
     * TOML rules, the rules held back for a newer jk and every {@code @Guard} id under {@code
     * src/guard}. True when any orphan was reported.
     */
    private static boolean reportOrphans(TaskContext ctx, GuardsPlan g, LoadResult load, Baseline baseline)
            throws IOException {
        Set<String> live =
                new HashSet<>(GuardSuites.liveIds(g.root(), load.rules().rules().keySet()));
        live.addAll(load.held()); // declared for a newer jk: its entries wait for it
        boolean orphans = false;
        for (String orphan : baseline.orphans(live)) {
            orphans = true;
            ctx.error(
                    orphan,
                    "GUARD " + orphan
                            + "  rule-removed\n  Observed: the baseline carries entries for a rule no source declares\n"
                            + "  Instead:  restore the rule, or `jk guard freeze -Z " + orphan
                            + " --reason \"…\"`");
        }
        return orphans;
    }

    /**
     * A {@code coverage.*} measure reads the reports a coverage run writes, so the lane asks it only
     * when the tree holds them and this run is not the one writing them: modules finish in any
     * order, and the root's lane would otherwise judge whatever subset exists mid-run. A plain
     * build with no report asked no coverage question and leaves the rules aside; {@code jk guard}
     * after {@code jk test -T} reads every module's report.
     */
    private static boolean coverageAsked(LaneEnv env, EvalContext ectx) {
        if (env.in().session().coverage()) return false;
        GuardsPlan g = env.guards();
        try {
            List<OutputArtifacts.Module> modules =
                    OutputArtifacts.of(g.root(), ectx.modules(), g.config().coverageReport());
            // A module whose manifest makes every test run a coverage run is writing its report
            // in this build too, unless the build skips tests.
            if (!env.in().skipTests() && modules.stream().anyMatch(m -> declaresCoverage(m.dir()))) return false;
            for (OutputArtifacts.Module m : modules) {
                if (m.existingCoverage() != null) return true;
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /** {@code [test] coverage = true} in the module's manifest; an unparseable manifest reads as false. */
    private static boolean declaresCoverage(Path moduleDir) {
        try {
            return JkBuildParser.parse(ManifestPaths.manifestIn(moduleDir))
                    .build()
                    .testCoverage();
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean readsCoverage(Rule rule) {
        return rule.kind() == Kind.METRIC
                && String.valueOf(rule.table().getString("measure")).startsWith("coverage.");
    }

    private static LaneRun.Result runLane(Lane lane, List<Rule> rules, EvalContext ectx, Baseline baseline)
            throws IOException {
        try (GuardFactsBudget.Held facts = FACTS.hold(factsFiles(lane, ectx))) {
            if (lane != Lane.MODULE) return LaneRun.run(lane, rules, ectx, baseline);
            LANES.acquireUninterruptibly();
            try {
                return LaneRun.run(lane, rules, ectx, baseline);
            } finally {
                LANES.release();
            }
        }
    }

    /** The facts indexes a lane may decode: its module's main and test indexes, or every module's main index. */
    static List<Path> factsFiles(Lane lane, EvalContext ectx) {
        return switch (lane) {
            case MODULE -> {
                Path moduleDir = ectx.moduleDir();
                if (moduleDir == null) yield List.of();
                Path buildDir = BuildLayout.moduleTargetDir(moduleDir);
                yield List.of(FactsIndexing.indexPath(buildDir, "main"), FactsIndexing.indexPath(buildDir, "test"));
            }
            case WORKSPACE -> {
                List<Path> out = new ArrayList<>();
                for (Path m : ectx.modules()) out.add(FactsIndexing.indexPath(BuildLayout.moduleTargetDir(m), "main"));
                yield out;
            }
            default -> List.of();
        };
    }

    /**
     * Entries that would tighten: merged into the baseline outside CI, an error in CI. True when
     * the baseline was rewritten, so the verdict is stored under the lane's new slice.
     */
    private static boolean tightenBaseline(TaskContext ctx, GuardsPlan g, LaneRun.Result result, boolean ci)
            throws IOException {
        if (result.tightened() <= 0) return false;
        if (ci) {
            ctx.error(
                    "guards",
                    "GUARD baseline  loose\n  Observed: " + result.tightened() + " entries would tighten\n  Instead:  "
                            + Baselines.CI_MESSAGE);
            return false;
        }
        mergeTightened(g.root(), result);
        // The pre-commit hook lets a baseline through only behind this marker; a tightening is the
        // engine's own write, so it leaves the marker exactly as a freeze does.
        GuardBaselineMarker.leave(g.root(), "jk guard (tightened)");
        ctx.output(result.tightened() + " baseline entries tightened");
        return true;
    }

    /** Same fingerprint red on consecutive runs of this lane: the message turns to stop-and-ask. */
    private static void reportRed(TaskContext ctx, String taskId, LaneRun.Result result) throws IOException {
        Map<String, Integer> streaks = GuardThrash.record(taskId, result.reports());
        for (RuleReport r : result.redReports()) {
            if (r.outcome() == Outcome.VIOLATIONS) {
                for (Observation o : r.fresh())
                    ctx.error(
                            r.id(),
                            GuardMessages.site(r, o, streaks.getOrDefault(GuardThrash.key(r.id(), o.key()), 1)));
            } else {
                ctx.error(r.id(), GuardMessages.outcome(r));
            }
        }
    }

    /**
     * Module lanes in flight at once: each holds a text pass beside its facts. A workspace of thirty
     * run with the build's parallelism would multiply that against the engine's heap.
     */
    private static final Semaphore LANES = new Semaphore(4);

    /** The decoded facts indexes lanes hold, bounded in bytes across every job of the engine. */
    private static final GuardFactsBudget FACTS = GuardFactsBudget.forRuntime();

    /**
     * Module lanes run concurrently and each tightens only its own slice of a rule's baseline, so
     * the write re-reads the file and replaces just those slices: a lane never overwrites what
     * another lane tightened a moment ago. The read and the write hold the {@link
     * GuardsPresence#baselineLock baseline lock}, which {@code jk guard freeze} holds too, in this
     * engine or another process.
     */
    static void mergeTightened(Path root, LaneRun.Result result) throws IOException {
        FileLocks.withLock(GuardsPresence.baselineLock(root), () -> {
            Baseline latest = BaselineFile.read(GuardsPresence.baselineFile(root));
            for (RuleReport r : result.reports()) {
                Reconciliation rec = r.reconciliation();
                if (rec == null || !rec.tighteningNeeded()) continue;
                RuleBaseline mine = rec.tightened();
                RuleBaseline theirs = latest.of(r.id());
                latest = latest.with(
                        r.id(), theirs.withLane(rec.lane(), mine.population(rec.lane()), mine.entries(rec.lane())));
            }
            BaselineFile.write(GuardsPresence.baselineFile(root), latest);
        });
    }

    /** A red lane: the diagnostics already say why; this only fails the step. */
    static final class GuardsRed extends RuntimeException {
        GuardsRed(String message) {
            super(message);
        }
    }

    static Map<String, String> inputsOf(List<String> tokens, String baselineSha) {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (String t : tokens) {
            int i = t.indexOf(':');
            inputs.put(i < 0 ? t : t.substring(0, i), i < 0 ? "" : t.substring(i + 1));
        }
        inputs.put("baseline", baselineSha);
        return inputs;
    }

    /** The observations of lane {@code taskId}: {@code target/jk-guards/<lane>.jsonl}. */
    private static Path observationsFile(Path root, String taskId) {
        return RuleSummaries.dir(root).resolve(taskId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jsonl");
    }

    /** The full list, every lane its own file: {@code target/jk-guards/<lane>.jsonl}. */
    private static void writeJsonl(Path root, String taskId, LaneRun.Result result) throws IOException {
        Files.createDirectories(RuleSummaries.dir(root));
        StringBuilder sb = new StringBuilder();
        for (RuleReport r : result.reports()) {
            for (Observation o : r.fresh())
                sb.append(GuardMessages.jsonl(r, o, true)).append('\n');
            for (Observation o : r.baselined())
                sb.append(GuardMessages.jsonl(r, o, false)).append('\n');
        }
        Path file = observationsFile(root, taskId);
        if (sb.length() == 0) {
            Files.deleteIfExists(file);
        } else {
            AtomicWrites.replace(file, sb.toString());
        }
        RuleSummaries.write(root, taskId, result);
    }

    // ---- rules memo ---------------------------------------------------------------------------

    /** The rule layers' stamp and the manifest's {@code [guards]} table, which the rule set carries. */
    private record RulesStamp(String layers, GuardsConfig config) {}

    private static final StampedMemo<Path, RulesStamp, LoadResult> RULES = StampedMemo.bounded(16);

    /** The rule layers parsed once per content stamp; every lane of every module shares the result. */
    static LoadResult rules(GuardsPlan g) throws IOException {
        return rules(g, () -> {
            GuardPackFetch.fetchMissing(g.root());
            return GuardRules.load(g.root(), g.config(), JkDirs.store());
        });
    }

    /**
     * As above with the loader injected. The memo is stored under the stamp taken before the load:
     * a save that lands during the load moves the stamp, so the next call reloads instead of
     * serving what the load read under the new stamp.
     */
    static LoadResult rules(GuardsPlan g, Supplier<LoadResult> loader) throws IOException {
        Path file = GuardsPresence.rulesFile(g.root());
        // Every layer the load reads is in the stamp: the root file, the lock (pack pins), the members'
        // files and the packs' unpack markers. A pack the lock pins but the store has not unpacked yet
        // changes the stamp when it lands, so the memo never outlives its inputs.
        RulesStamp stamp = new RulesStamp(GuardRules.stamp(g.root()), g.config());
        boolean[] loaded = {false};
        LoadResult load = RULES.get(file, stamp, () -> {
            loaded[0] = true;
            return loader.get();
        });
        if (loaded[0]) recordRulesHash(g.root(), file);
        return load;
    }

    /**
     * The digest of the root rules file this build loaded, under the build output: the record of
     * which rules this build enforced, for anything that compares it with the file in the tree; a
     * project with no rules file records nothing.
     */
    private static void recordRulesHash(Path root, Path rulesFile) throws IOException {
        if (!Files.isRegularFile(rulesFile)) return;
        Path out = root.resolve(BuildLayout.TARGET).resolve(GuardsPresence.RULES_HASH_FILE);
        Files.createDirectories(out.getParent());
        Files.writeString(out, Hashing.sha256Hex(rulesFile) + "\n");
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * The workspace's module directories from inside a member's step, where the plan's project is the
     * member's own manifest and knows nothing of its siblings: a WORKSPACE suite declared in a member
     * still sees the whole workspace.
     */
    static List<Path> workspaceModuleDirs(Path root, @Nullable JkBuild project) {
        if (project != null && project.workspace() != null) return moduleDirs(root, project);
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) return List.of();
        JkBuild build;
        try {
            build = JkBuildParser.parse(manifest);
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (IOException | RuntimeException unparseable) {
            return List.of(); // parse-build reports it
        }
        // Outside the fallback: a glob that selects nothing fails the guard as it fails the build.
        return moduleDirs(root, build);
    }

    static List<Path> moduleDirs(Path root, @Nullable JkBuild rootBuild) {
        return rootBuild == null ? new ArrayList<>() : new ArrayList<>(WorkspaceModules.members(root, rootBuild));
    }

    static Supplier<FactsIndex> noFacts() {
        return () -> FactsIndex.EMPTY;
    }

    /** Whether a root lane is forecast here: at the invocation root, unless the run skips the lanes. */
    static boolean rootLaneForecast(ForecastScope s) {
        return PlannerResources.invocationRoot(s.dir) && !s.guardLanesSkipped;
    }

    /** {@code guard-output}'s price at the invocation root: its verdict under the lane's key. */
    private static TaskForecast.@Nullable Task forecastOutputLane(ForecastScope s) {
        if (!rootLaneForecast(s)) return null;
        return GuardKeys.forecastOutputLane(s.dir, s.actionCache).orElse(null);
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.LAYOUT;
import static cc.jumpkick.runtime.BuildPlanner.MAIN_CLASSES;
import static cc.jumpkick.runtime.BuildPlanner.PROJECT;
import static cc.jumpkick.runtime.BuildPlanner.TEST_CLASSES;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.baseline.Baselines;
import cc.jumpkick.guard.eval.EvalContext;
import cc.jumpkick.guard.eval.GuardMessages;
import cc.jumpkick.guard.eval.LaneRun;
import cc.jumpkick.guard.eval.RuleReport;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadError;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.BuildIdentity;
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
import cc.jumpkick.task.FileHashMemo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        Path root = in.lockDir() != null ? in.lockDir() : in.dir();
        boolean rulesFile = Files.exists(GuardsPresence.rulesFile(root));
        GuardsConfig cfg;
        try {
            cfg = JkBuildParser.guardsConfig(root.resolve(ManifestPaths.MANIFEST));
        } catch (RuntimeException unparseable) {
            // parse-build reports the manifest error; the rule file alone still enables the lanes.
            cfg = GuardsConfig.ABSENT;
        }
        if (rulesFile || cfg.declared()) return new GuardsPlan(true, root, cfg);
        return GuardsPlan.DISABLED;
    }

    /** Whether {@code root} would enable guards — the graph asks before any plan exists. */
    static boolean enabledAt(Path root) {
        if (Files.exists(GuardsPresence.rulesFile(root))) return true;
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
    static @Nullable String appendRootLanes(BuildPlan.Builder b, BuildPlanner.Ctx cx, String after) {
        if (!cx.guards().enabled() || !PlannerResources.invocationRoot(cx.in().dir())) return null;
        b.addTask(modelStep(cx));
        String last = TaskNames.GUARD_MODEL;
        if (PlannerResources.runGateScripts(cx.in())) {
            b.addTask(treeStep(cx, TaskNames.GUARD_MODEL, after));
            last = TaskNames.GUARD_TREE;
        }
        return last;
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
                            testFacts(test));
                    execute(ctx, cx, Lane.MODULE, ectx, tokens, ActionKey.qualifiedTaskId(TaskNames.GUARD, moduleDir));
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
                    List<String> tokens = new ArrayList<>();
                    tokens.add(token("manifest", g.root().resolve(ManifestPaths.MANIFEST)));
                    for (Path m : modules)
                        tokens.add(token("manifest:" + relModule(g.root(), m), m.resolve(ManifestPaths.MANIFEST)));
                    tokens.add(token("lock", g.root().resolve(ManifestPaths.LOCK)));
                    EvalContext ectx = new EvalContext(Lane.MODEL, g.root(), "", null, modules, noFacts(), () -> null);
                    execute(
                            ctx,
                            cx,
                            Lane.MODEL,
                            ectx,
                            tokens,
                            ActionKey.qualifiedTaskId(TaskNames.GUARD_MODEL, g.root()));
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
                    List<String> tokens = cx.buildLogicInputTokensRef().get();
                    if (tokens == null) {
                        tokens = BuildLogicSupport.workspaceInputTokens(g.root());
                        cx.buildLogicInputTokensRef().compareAndSet(null, tokens);
                    }
                    List<Path> modules = moduleDirs(g.root(), ctx.get(PROJECT).orElse(null));
                    EvalContext ectx = new EvalContext(Lane.TREE, g.root(), "", null, modules, noFacts(), () -> null);
                    execute(
                            ctx,
                            cx,
                            Lane.TREE,
                            ectx,
                            new ArrayList<>(tokens),
                            ActionKey.qualifiedTaskId(TaskNames.GUARD_TREE, g.root()));
                    ctx.progress(1);
                })
                .build();
    }

    // ---- one lane run -------------------------------------------------------------------------

    private static void execute(
            TaskContext ctx, BuildPlanner.Ctx cx, Lane lane, EvalContext ectx, List<String> tokens, String taskId)
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
        List<Rule> rules = LaneRun.rulesFor(lane, load.rules(), ectx.module());
        Path baselineFile = GuardsPresence.baselineFile(g.root());
        Baseline baseline = BaselineFile.read(baselineFile);
        boolean orphans = false;
        if (lane == Lane.MODEL) {
            for (String orphan : baseline.orphans(load.rules().rules().keySet())) {
                orphans = true;
                ctx.error(
                        orphan,
                        "GUARD " + orphan
                                + "  rule-removed\n  Observed: the baseline carries entries for a rule no source declares\n"
                                + "  Instead:  restore the rule, or `jk guard freeze --retire " + orphan
                                + " --reason \"…\"`");
            }
        }
        if (rules.isEmpty()) {
            ctx.label("no rules for this lane");
            if (orphans) throw new GuardsRed("baseline names retired rules");
            return;
        }
        for (var e : load.rules().sourceDigests().entrySet()) tokens.add("rules:" + e.getKey() + ":" + e.getValue());
        String baselineSha = Files.isRegularFile(baselineFile) ? Hashing.sha256Hex(baselineFile) : "none";
        List<String> keyTokens = new ArrayList<>(tokens);
        keyTokens.add("baseline:" + baselineSha);
        String key = ActionKey.forArtifact(taskId, BuildIdentity.cacheKeyVersion(), keyTokens);
        ActionCache cache = cx.actionCache();
        boolean useCache = !cx.in().session().config().forceOr(false)
                && !cx.in().session().config().rebuildOr(false);
        if (useCache && cache.lookup(key).isPresent()) {
            ctx.label(rules.size() + (rules.size() == 1 ? " rule" : " rules") + " · clean (cached)");
            ctx.cached();
            return;
        }
        LaneRun.Result result = LaneRun.run(lane, rules, ectx, baseline);
        boolean ci = Baselines.ciMode();
        String storedBaselineSha = baselineSha;
        if (result.tightened() > 0) {
            if (ci) {
                ctx.error(
                        "guards",
                        "GUARD baseline  loose\n  Observed: " + result.tightened()
                                + " entries would tighten\n  Instead:  " + Baselines.CI_MESSAGE);
            } else {
                BaselineFile.write(baselineFile, result.baseline());
                storedBaselineSha = Files.isRegularFile(baselineFile) ? Hashing.sha256Hex(baselineFile) : "none";
                ctx.output(result.tightened() + " baseline entries tightened");
            }
        }
        for (RuleReport r : result.redReports()) ctx.error(r.id(), GuardMessages.render(r));
        ctx.label(GuardMessages.summary(result, rules.size()));
        if (result.red()) {
            ctx.output(GuardMessages.TRAILER);
            // The diagnostics above carry the detail; the throw is what fails the step.
            throw new GuardsRed(result.redReports().size() + " of " + rules.size() + " guards red");
        }
        if (ci && result.tightened() > 0) throw new GuardsRed(Baselines.CI_MESSAGE);
        List<String> storeTokens = new ArrayList<>(tokens);
        storeTokens.add("baseline:" + storedBaselineSha);
        String storeKey = ActionKey.forArtifact(taskId, BuildIdentity.cacheKeyVersion(), storeTokens);
        Map<String, String> inputs = new LinkedHashMap<>();
        for (String t : storeTokens) {
            int i = t.indexOf(':');
            inputs.put(i < 0 ? t : t.substring(0, i), i < 0 ? "" : t.substring(i + 1));
        }
        cache.storeVerdict(taskId, storeKey, inputs);
    }

    /** A red lane: the diagnostics already say why; this only fails the step. */
    static final class GuardsRed extends RuntimeException {
        GuardsRed(String message) {
            super(message);
        }
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

    private static List<Path> moduleDirs(Path root, @Nullable JkBuild rootBuild) {
        List<Path> out = new ArrayList<>();
        if (rootBuild != null && rootBuild.workspace() != null) {
            for (String m : rootBuild.workspace().modules()) out.add(root.resolve(m));
        }
        return out;
    }

    private static String token(String name, Path file) throws IOException {
        if (!Files.isRegularFile(file)) return name + ":absent";
        return name + ":" + FileHashMemo.contentHash(file);
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

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.guard.eval.Evaluators;
import cc.jumpkick.guard.eval.GuardSuites;
import cc.jumpkick.guard.eval.OutputArtifacts;
import cc.jumpkick.guard.eval.WorkspaceModel;
import cc.jumpkick.guard.eval.WorkspaceModules;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The one owner of a guard lane's action key, called by the lane ({@link PlannerGuards}) and by
 * the forecast ({@link TaskForecaster}). The forecast needs it because the dirty set is "a module
 * with a non-cached material step": without a {@code guard} step the forecast never enters a
 * module whose lane verdict is stale, and a rule edit changes nothing until something else does.
 * The forecast probes read-only — a stale facts index is a RUN, never an extraction.
 */
final class GuardKeys {

    private GuardKeys() {}

    /** The lane key: the lane's read-set tokens plus the baseline the verdict was judged against. */
    static String laneKey(String taskId, List<String> tokens, String baselineSha) {
        List<String> all = new ArrayList<>(tokens);
        all.add("baseline:" + baselineSha);
        String key = ActionKey.forArtifact(taskId, BuildIdentity.cacheKeyVersion(), all);
        return key;
    }

    static String baselineSha(Path root) throws IOException {
        Path f = GuardsPresence.baselineFile(root);
        return Files.isRegularFile(f) ? Hashing.sha256Hex(f) : "none";
    }

    static void addRuleTokens(List<String> tokens, LoadResult load) {
        for (var e : load.rules().sourceDigests().entrySet()) tokens.add("rules:" + e.getKey() + ":" + e.getValue());
    }

    /**
     * The forecast's {@code guard} step for one module, or empty when the workspace has no guards.
     * CACHED only when the facts index is current and the lane's verdict is in the cache; RUN
     * otherwise, which is what makes the module dirty.
     */
    static Optional<TaskForecast.Task> forecastModuleLane(
            Path dir, BuildLayout layout, ActionCache actionCache, boolean upstreamDirty) {
        Path root = WorkspaceScan.findRoot(dir).orElse(dir).toAbsolutePath().normalize();
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!PlannerGuards.moduleLanesOnThisBuild(g, PlannerGuards.guardRequested())) return Optional.empty();
        // A unit with no classes directory has no module lane (a sourceless root, a never-built module).
        if (!Files.isDirectory(layout.classesDir()) && !upstreamDirty) return Optional.empty();
        if (upstreamDirty) return Optional.of(run("guards · module recompiles"));
        try {
            LoadResult load = PlannerGuards.rules(g);
            if (load.hasErrors()) return Optional.of(run("guards · jk-guards.toml does not load"));
            Optional<String> main =
                    FactsIndexing.freshDigest(layout.classesDir(), FactsIndexing.indexPath(layout.buildDir(), "main"));
            if (main.isEmpty()) return Optional.of(run("guards · facts index stale"));
            List<String> tokens = new ArrayList<>();
            tokens.add("module:" + WorkspaceModel.rel(root, dir));
            tokens.add("facts:" + main.get());
            Path testClasses = layout.testClassesDir();
            if (Files.isDirectory(testClasses)) {
                Optional<String> test =
                        FactsIndexing.freshDigest(testClasses, FactsIndexing.indexPath(layout.buildDir(), "test"));
                if (test.isEmpty()) return Optional.of(run("guards · test facts index stale"));
                tokens.add("test-facts:" + test.get());
            }
            addRuleTokens(tokens, load);
            String key = laneKey(ActionKey.qualifiedTaskId(TaskNames.GUARD, dir), tokens, baselineSha(root));
            if (actionCache.lookup(key).isPresent()) {
                return Optional.of(
                        new TaskForecast.Task(TaskNames.GUARD, TaskForecast.Status.CACHED, "", key.substring(0, 8)));
            }
            return Optional.of(run("guards · " + load.rules().rules().size() + " rules to evaluate"));
        } catch (IOException e) {
            return Optional.of(run("guards · " + e.getMessage()));
        }
    }

    /**
     * Every module's main index digest, from the index headers alone: {@code facts:<module>:<digest>},
     * or {@code absent} for a module with no index yet (never built, or its compile failed).
     */
    static List<String> workspaceTokens(Path root, List<Path> modules) throws IOException {
        List<String> tokens = new ArrayList<>();
        for (Path m : modules) {
            Path buildDir = BuildLayout.moduleTargetDir(root, m);
            Path classes = buildDir.resolve("classes").resolve("main");
            Path idx = FactsIndexing.indexPath(buildDir, "main");
            String digest = Files.isDirectory(classes)
                    ? FactsIndexing.freshDigest(classes, idx).orElse("stale")
                    : "absent";
            tokens.add("facts:" + WorkspaceModel.rel(root, m) + ":" + digest);
        }
        return tokens;
    }

    /** The forecast's {@code guard-workspace} step at the workspace root, or empty. */
    static Optional<TaskForecast.Task> forecastWorkspaceLane(Path root, ActionCache actionCache) {
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!PlannerGuards.moduleLanesOnThisBuild(g, PlannerGuards.guardRequested())) return Optional.empty();
        try {
            List<Path> modules = WorkspaceModules.of(root);
            if (modules.isEmpty() || modules.equals(List.of(root))) return Optional.empty();
            LoadResult load = PlannerGuards.rules(g);
            if (load.hasErrors()) return Optional.of(workspace("guards · jk-guards.toml does not load"));
            List<String> tokens = workspaceTokens(root, modules);
            addRuleTokens(tokens, load);
            String key = laneKey(ActionKey.qualifiedTaskId(TaskNames.GUARD_WORKSPACE, root), tokens, baselineSha(root));
            if (actionCache.lookup(key).isPresent()) {
                return Optional.of(new TaskForecast.Task(
                        TaskNames.GUARD_WORKSPACE, TaskForecast.Status.CACHED, "", key.substring(0, 8)));
            }
            return Optional.of(workspace("guards · workspace rules to evaluate"));
        } catch (IOException e) {
            return Optional.of(workspace("guards · " + e.getMessage()));
        }
    }

    /**
     * The output lane's key material: each module's jar, POM, native binary and coverage report by
     * content, through the stat-memoized hash — the sidecar POM is rewritten on every packaging
     * pass and an untouched jar is never re-read, so only a real change moves the key.
     */
    static List<String> outputTokens(Path root, List<Path> modules, @Nullable String coverageReport)
            throws IOException {
        List<String> tokens = new ArrayList<>();
        for (OutputArtifacts.Module m : OutputArtifacts.of(root, modules, coverageReport)) {
            for (Path p : List.of(m.jar(), m.pom(), m.nativeBinary(), m.coverage())) {
                String stamp = "absent";
                if (Files.isRegularFile(p)) {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    stamp = FileHashMemo.contentHash(p.toAbsolutePath().normalize(), a);
                }
                tokens.add("out:" + WorkspaceModel.rel(root, p) + ":" + stamp);
            }
        }
        return tokens;
    }

    /** The forecast's {@code guard-output} step, or empty when no rule is on the output lane. */
    static Optional<TaskForecast.Task> forecastOutputLane(Path root, ActionCache actionCache) {
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!g.enabled()) return Optional.empty();
        try {
            LoadResult load = PlannerGuards.rules(g);
            if (load.hasErrors()) return Optional.empty();
            boolean any = false;
            for (Rule r : load.rules().rules().values()) if (Evaluators.laneOf(r) == Lane.OUTPUT) any = true;
            if (!any) return Optional.empty();
            List<String> tokens =
                    outputTokens(root, WorkspaceModules.of(root), g.config().coverageReport());
            addRuleTokens(tokens, load);
            String key = laneKey(ActionKey.qualifiedTaskId(TaskNames.GUARD_OUTPUT, root), tokens, baselineSha(root));
            if (actionCache.lookup(key).isPresent()) {
                return Optional.of(new TaskForecast.Task(
                        TaskNames.GUARD_OUTPUT, TaskForecast.Status.CACHED, "", key.substring(0, 8)));
            }
            return Optional.of(new TaskForecast.Task(
                    TaskNames.GUARD_OUTPUT, TaskForecast.Status.RUN, "guards · output rules to evaluate", ""));
        } catch (IOException e) {
            return Optional.of(new TaskForecast.Task(
                    TaskNames.GUARD_OUTPUT, TaskForecast.Status.RUN, "guards · " + e.getMessage(), ""));
        }
    }

    /** The root lanes' forecast, in plan order: model, workspace, tree (gate), fixtures (gate). */
    static List<TaskForecast.Task> forecastRootLanes(Path root, JkBuild project, ActionCache actionCache) {
        List<TaskForecast.Task> out = new ArrayList<>();
        forecastModelLane(root, project, actionCache).ifPresent(out::add);
        forecastWorkspaceLane(root, actionCache).ifPresent(out::add);
        forecastTreeLane(root, actionCache).ifPresent(out::add);
        forecastFixtures(root).ifPresent(out::add);
        forecastOutputLane(root, actionCache).ifPresent(out::add);
        return out;
    }

    /** {@code guard-tree}, gate only: the text scan's verdict, keyed on the tree's inputs, rules and baseline. */
    static Optional<TaskForecast.Task> forecastTreeLane(Path root, ActionCache actionCache) {
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!g.enabled() || !PlannerGuards.guardRequested()) return Optional.empty();
        try {
            LoadResult load = PlannerGuards.rules(g);
            if (load.hasErrors()) return Optional.of(tree("guards · jk-guards.toml does not load"));
            List<String> tokens = new ArrayList<>(BuildLogicSupport.workspaceInputTokens(root));
            addRuleTokens(tokens, load);
            String key = laneKey(ActionKey.qualifiedTaskId(TaskNames.GUARD_TREE, root), tokens, baselineSha(root));
            if (actionCache.lookup(key).isPresent()) {
                return Optional.of(new TaskForecast.Task(
                        TaskNames.GUARD_TREE, TaskForecast.Status.CACHED, "", key.substring(0, 8)));
            }
            return Optional.of(tree("guards · tree rules to evaluate"));
        } catch (IOException e) {
            return Optional.of(tree("guards · " + e.getMessage()));
        }
    }

    private static TaskForecast.Task tree(String why) {
        return new TaskForecast.Task(TaskNames.GUARD_TREE, TaskForecast.Status.RUN, why, "");
    }

    private static TaskForecast.Task workspace(String why) {
        return new TaskForecast.Task(TaskNames.GUARD_WORKSPACE, TaskForecast.Status.RUN, why, "");
    }

    /** The forecast's {@code guard-model} step at the invocation root, or empty. */
    static Optional<TaskForecast.Task> forecastModelLane(Path root, JkBuild rootBuild, ActionCache actionCache) {
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!g.enabled()) return Optional.empty();
        try {
            LoadResult load = PlannerGuards.rules(g);
            if (load.hasErrors()) return Optional.of(model("guards · jk-guards.toml does not load"));
            List<String> tokens = modelTokens(root, rootBuild);
            addRuleTokens(tokens, load);
            String key = laneKey(ActionKey.qualifiedTaskId(TaskNames.GUARD_MODEL, root), tokens, baselineSha(root));
            if (actionCache.lookup(key).isPresent()) {
                return Optional.of(new TaskForecast.Task(
                        TaskNames.GUARD_MODEL, TaskForecast.Status.CACHED, "", key.substring(0, 8)));
            }
            return Optional.of(model("guards · model rules to evaluate"));
        } catch (IOException e) {
            return Optional.of(model("guards · " + e.getMessage()));
        }
    }

    /** Manifest and lock digests, the model lane's read set; the lane and the forecast share it. */
    static List<String> modelTokens(Path root, JkBuild rootBuild) throws IOException {
        List<String> tokens = new ArrayList<>();
        tokens.add(fileToken("manifest", root.resolve(ManifestPaths.MANIFEST)));
        if (rootBuild.workspace() != null) {
            for (String m : rootBuild.workspace().modules()) {
                tokens.add(fileToken("manifest:" + m, root.resolve(m).resolve(ManifestPaths.MANIFEST)));
            }
        }
        tokens.add(fileToken("lock", root.resolve(ManifestPaths.LOCK)));
        return tokens;
    }

    /**
     * The gate's fixture proof: planned whenever the gate is asked for and any rule or guard test
     * names a fixture. It keys its own compile on the fixture sources, so it is cheap to forecast as
     * RUN; what matters is that a gate with fixtures is never "up to date" without running it.
     */
    static Optional<TaskForecast.Task> forecastFixtures(Path dir) {
        Path root = WorkspaceScan.findRoot(dir).orElse(dir).toAbsolutePath().normalize();
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!g.enabled() || !PlannerGuards.guardRequested()) return Optional.empty();
        try {
            LoadResult load = PlannerGuards.rules(g);
            boolean any = load.rules().rules().values().stream().anyMatch(r -> r.fixture() != null);
            if (!any) {
                for (GuardSuites.Located l :
                        GuardSuites.declaredAcrossWorkspace(root).values()) {
                    if (l.declared().fixture() != null) any = true;
                }
            }
            if (!any) return Optional.empty();
            return Optional.of(new TaskForecast.Task(
                    TaskNames.GUARD_FIXTURES,
                    TaskForecast.Status.RUN,
                    "guards · fixtures prove every rule bites",
                    null));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static String fileToken(String name, Path file) throws IOException {
        if (!Files.isRegularFile(file)) return name + ":absent";
        return name + ":" + FileHashMemo.contentHash(file);
    }

    private static TaskForecast.Task run(String text) {
        return new TaskForecast.Task(TaskNames.GUARD, TaskForecast.Status.RUN, text, null);
    }

    private static TaskForecast.Task model(String text) {
        return new TaskForecast.Task(TaskNames.GUARD_MODEL, TaskForecast.Status.RUN, text, null);
    }
}

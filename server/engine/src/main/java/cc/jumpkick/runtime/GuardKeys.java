// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.guard.eval.WorkspaceModules;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        if (!PlannerGuards.moduleLanesOnThisBuild(g, PlannerGuards.gateRequested())) return Optional.empty();
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
            tokens.add("module:" + PlannerGuards.relModule(root, dir));
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
            tokens.add("facts:" + PlannerGuards.relModule(root, m) + ":" + digest);
        }
        return tokens;
    }

    /** The forecast's {@code guard-workspace} step at the workspace root, or empty. */
    static Optional<TaskForecast.Task> forecastWorkspaceLane(Path root, ActionCache actionCache) {
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!PlannerGuards.moduleLanesOnThisBuild(g, PlannerGuards.gateRequested())) return Optional.empty();
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

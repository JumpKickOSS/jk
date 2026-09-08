// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.Task;
import cc.jumpkick.runtime.base.StepTimings;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.CacheRetention;
import cc.jumpkick.task.CacheRoots;
import cc.jumpkick.task.CasSweep;
import cc.jumpkick.task.TmpGc;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Cache-maintenance plans for {@code jk cache clean}, {@code jk cache nuke}, {@code jk storage
 * clean}, and project-scoped invalidation ({@code jk clean --force}). These mutate caches that
 * other plans read concurrently — the engine runs them only at idle boundaries.
 */
public final class CachePlans {

    private CachePlans() {}

    /** Files removed (or, on a dry run, that would be): temps, stamps, action keys, CAS blobs. */
    public static final BuildPlanKey<Long> FILES = BuildPlanKey.scalar("cache-files", Long.class);

    /** Bytes freed (or reclaimable, on a dry run). */
    public static final BuildPlanKey<Long> BYTES = BuildPlanKey.scalar("cache-bytes", Long.class);

    /**
     * Action-tier bytes left after the prune — what the scheduler records so a cache that fills up
     * between two cadence ticks is pruned when it fills, not when the interval next elapses.
     */
    public static final BuildPlanKey<Long> FINAL_ACTION_BYTES = BuildPlanKey.scalar("cache-final-bytes", Long.class);

    /**
     * Hygiene plan for the cache at {@code root}: leaked CAS temps, format stamps, step timings, an
     * unreferenced-blob sweep, and a tiered retention pass over whole action-cache entries down to
     * {@code cache.max-cache-size-gb}. Never touches the artifact store. {@code includeJkTmp} sweeps
     * {@code state/tmp} only for the default cache dir.
     */
    public static BuildPlan pruneBuildPlan(Path root, boolean dryRun, boolean includeJkTmp) {
        Task pruneStep = Task.builder("prune")
                .ticks(1)
                .execute(ctx -> {
                    // Drop retired engine jars and parked PATH binaries.
                    try {
                        var pruned = EngineInstall.current().gc();
                        if (!pruned.isEmpty()) {
                            ctx.warn("prune", "retired " + pruned.size() + " displaced jk install file(s)");
                        }
                    } catch (RuntimeException ignored) {
                        // install-file sweep is best-effort maintenance
                    }

                    ctx.label("Cleaning cache…");
                    long totalFiles = 0;
                    long totalBytes = 0;

                    // Cache-tier CAS temps under <cacheRoot>/sha256/
                    TempSweep cacheTemps = sweepCasTemps(CacheTree.CACHE_CAS.under(root), dryRun);
                    totalFiles += cacheTemps.files();
                    totalBytes += cacheTemps.bytes();

                    var timingsReport = StepTimings.prune(
                            root,
                            StepTimings.Limits.resolve(JkDirs.userConfigFile(), System::getenv),
                            System.currentTimeMillis(),
                            dryRun);
                    totalFiles += timingsReport.evictedByAge() + timingsReport.evictedBySize();

                    if (includeJkTmp) {
                        var tmpReport = TmpGc.sweep(JkDirs.tmp(), TmpGc.DEFAULT_TTL, dryRun);
                        totalFiles += tmpReport.deleted();
                        totalBytes += tmpReport.freedBytes();
                    }

                    // Reclaim unreferenced payloads before the budget prune: garbage the sweep frees
                    // is a shortfall the prune then does not have to cover by evicting live entries.
                    var cacheCas = JkStores.cacheCas(root);
                    var cacheLive = CacheRoots.collect(cacheCas, CacheTree.ACTIONS.under(root), root.resolve("tools"));
                    var cacheSweep = CasSweep.sweep(cacheCas, cacheLive, dryRun);
                    totalFiles += cacheSweep.deleted();
                    totalBytes += cacheSweep.freedBytes();

                    // Every tier under the cache root, plus a sweep of anything the table does not
                    // name. The sweep's victims are still on disk in a dry run, so hand them over:
                    // without that the action prune counts the same blob twice and dry-run totals
                    // diverge.
                    var retention = CacheRetention.sweep(root, cacheCas, cacheSweep.deletedShas(), dryRun);
                    totalFiles += retention.deletedFiles();
                    totalBytes += retention.freedBytes();
                    ctx.put(FINAL_ACTION_BYTES, retention.finalActionBytes());
                    if (!retention.unknownEntries().isEmpty()) {
                        ctx.warn(
                                "prune",
                                "reclaimed unrecognised cache entries: "
                                        + String.join(", ", retention.unknownEntries()));
                    }
                    long actionBudget = JkCacheConfig.resolve().maxCacheSizeBytes();
                    if (actionBudget > 0 && retention.finalActionBytes() > actionBudget) {
                        ctx.warn(
                                "prune",
                                "cache is still over budget — raise cache.max-cache-size-gb (or JK_MAX_CACHE_SIZE_GB)");
                    }

                    ctx.put(FILES, totalFiles);
                    ctx.put(BYTES, totalBytes);
                    ctx.progress(1);
                })
                .build();
        return BuildPlan.builder("cache-prune")
                .stateKeys(FILES, BYTES, FINAL_ACTION_BYTES)
                .addTask(pruneStep)
                .build();
    }

    /**
     * Build the purge plan: empty the cache root at an idle boundary. The artifact store is never
     * a child of this root — {@code JkStores} resolves it from {@code JK_STORE_DIR} whatever the
     * cache dir is — so there is nothing under here a nuke has to step around.
     */
    public static BuildPlan purgeBuildPlan(Path root) {
        Task purgeStep = Task.builder("purge")
                .execute(ctx -> {
                    ctx.label("Purging cache…");
                    purgeActionCache(root);
                })
                .build();
        return BuildPlan.builder("cache-purge")
                .stateKeys(FILES, BYTES)
                .addTask(purgeStep)
                .build();
    }

    /**
     * Empty the cache root: every entry, whether or not {@link CacheTree} names it. Totality is
     * the point — the retention sweep already reclaims unrecognised top-level entries, so a nuke
     * that spared what a prune takes would be the weaker of the two commands.
     *
     * <p>Two things survive here and neither is an exception to that: {@code root} itself, and the
     * {@link CacheTree#PRUNE_LOCK} file the caller holds open for the length of this pass
     * ({@code CacheMaintenanceLocks}) — a directory containing an open file cannot be removed on
     * every platform jk targets. Removing the root is the client's last step, once no process
     * holds anything under it; see {@code CacheCommand.removeCacheRoot}.
     */
    public static void purgeActionCache(Path root) throws IOException {
        if (!Files.isDirectory(root)) return;
        Path held = CacheTree.PRUNE_LOCK.under(root);
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                if (entry.equals(held)) continue;
                PathUtil.deleteRecursivelyOrThrow(entry);
            }
        }
    }

    /**
     * Build the store-sweep plan ({@code jk storage clean}): leaked download temps. Garbage-only
     * — the store's artifacts are never collected.
     */
    public static BuildPlan sweepBuildPlan(Path root, boolean dryRun) {
        Task sweepStep = Task.builder("sweep")
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("Cleaning store…");
                    SweepReport report = sweepStore(root, dryRun);
                    ctx.put(FILES, report.files());
                    ctx.put(BYTES, report.bytes());
                    ctx.progress(1);
                })
                .build();
        return BuildPlan.builder("storage-clean")
                .stateKeys(FILES, BYTES)
                .addTask(sweepStep)
                .build();
    }

    /** Totals for one store sweep ({@link #sweepStore}). */
    public record SweepReport(long files, long bytes) {}

    /**
     * Artifact-store GC: leaked {@code .put-} download temps, nothing else.
     * The store is never size-bounded and its blobs are never collected — {@code jk storage nuke} is
     * the only way to shrink it.
     */
    public static SweepReport sweepStore(Path root, boolean dryRun) throws IOException {
        long totalFiles = 0;
        long totalBytes = 0;

        TempSweep temps = sweepCasTemps(JkStores.resolve("sha256"), dryRun);
        totalFiles += temps.files();
        totalBytes += temps.bytes();

        // Reclaim leaked .put-*.tmp download temps under the Maven-layout store too — mirror=false
        // fetches (metadata / file:// POMs) return the temp and never delete it.
        TempSweep repoTemps = sweepCasTemps(JkStores.resolve("repos"), dryRun);
        totalFiles += repoTemps.files();
        totalBytes += repoTemps.bytes();

        return new SweepReport(totalFiles, totalBytes);
    }

    /**
     * Project-scoped action-cache invalidation ({@code jk clean --force}): drop action-cache
     * keys/tasks/incremental state for {@code projectDir} and its workspace modules (match by
     * output {@link ActionKey#taskTag} or INPUT path under a module). CAS blobs are left for a later
     * {@code jk cache clean}.
     */
    public static BuildPlan clearBuildPlan(Path cacheRoot, Path projectDir, boolean dryRun) {
        Task clearStep = Task.builder("clear")
                .ticks(1)
                .execute(ctx -> {
                    ctx.label(dryRun ? "Inspecting build cache…" : "Clearing build cache…");
                    long[] acc = {0L, 0L}; // {files, bytes}
                    List<Path> allModuleDirs = resolveModuleDirs(projectDir);
                    Path actionsDir = CacheTree.ACTIONS.under(cacheRoot);
                    if (Files.isDirectory(actionsDir)) {
                        List<Path> moduleDirs = allModuleDirs;
                        Set<String> tags = tagsFor(moduleDirs);
                        List<String> prefixes =
                                moduleDirs.stream().map(p -> p.toString()).toList();
                        Set<String> deletedTaskIds = new LinkedHashSet<>();

                        // 1) key records: match by qualified-task tag, or by an INPUT path under a module dir.
                        Path keysDir = ActionTree.KEYS.under(actionsDir);
                        if (Files.isDirectory(keysDir)) {
                            try (var stream = Files.list(keysDir)) {
                                for (Path key : (Iterable<Path>) stream::iterator) {
                                    if (!Files.isRegularFile(key)) continue;
                                    String content = Files.readString(key);
                                    String taskId = taskIdOf(content);
                                    boolean hit = (taskId != null && tags.contains(tagOf(taskId)))
                                            || inputsUnder(content, prefixes);
                                    if (!hit) continue;
                                    if (taskId != null) deletedTaskIds.add(taskId);
                                    acc[1] += Files.size(key);
                                    if (!dryRun) Files.deleteIfExists(key);
                                    acc[0]++;
                                }
                            }
                        }

                        // 2) task pointers + incremental state, keyed by the same qualified-task id.
                        deleteQualified(ActionTree.TASKS.under(actionsDir), tags, deletedTaskIds, dryRun, acc);
                        for (Path tree : ActionTree.incrementalUnder(actionsDir)) {
                            deleteQualified(tree, tags, deletedTaskIds, dryRun, acc);
                        }
                    }
                    // 3) preflight memos — their "clean" conclusions were derived from the
                    // action keys just deleted; a surviving memo turns clear into a no-op.
                    for (Path m : allModuleDirs) {
                        Path preflight =
                                m.resolve(BuildLayout.TARGET).resolve(".jk").resolve("preflight");
                        if (!Files.isDirectory(preflight)) continue;
                        try (var files = Files.list(preflight)) {
                            for (Path f : (Iterable<Path>) files::iterator) {
                                if (!Files.isRegularFile(f)) continue;
                                acc[1] += Files.size(f);
                                if (!dryRun) Files.deleteIfExists(f);
                                acc[0]++;
                            }
                        }
                    }
                    ctx.put(FILES, acc[0]);
                    ctx.put(BYTES, acc[1]);
                    ctx.progress(1);
                })
                .build();
        return BuildPlan.builder("cache-clear")
                .stateKeys(FILES, BYTES)
                .addTask(clearStep)
                .build();
    }

    /** The current project dir plus, if it's in a workspace, every {@code [workspace]} module dir. */
    private static List<Path> resolveModuleDirs(Path projectDir) {
        // Prefer realpath so tags/INPUT prefixes match BuildCommand (which realpaths before build).
        // macOS /var → /private/var (and similar symlink roots) otherwise miss every action-cache key.
        Path here;
        try {
            here = projectDir.toRealPath();
        } catch (Exception e) {
            here = projectDir.toAbsolutePath().normalize();
        }
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        dirs.add(here);
        try {
            JkBuild manifest = JkBuildParser.parse(here.resolve(ManifestPaths.MANIFEST));
            Path wsRoot = manifest.isWorkspaceRoot()
                    ? here
                    : WorkspaceLocator.findRoot(here).orElse(null);
            if (wsRoot != null) {
                try {
                    wsRoot = wsRoot.toRealPath();
                } catch (Exception ignored) {
                    wsRoot = wsRoot.toAbsolutePath().normalize();
                }
                dirs.add(wsRoot);
                JkBuild root =
                        wsRoot.equals(here) ? manifest : JkBuildParser.parse(wsRoot.resolve(ManifestPaths.MANIFEST));
                for (String module : root.workspaceOpt().map(Workspace::modules).orElse(List.of())) {
                    Path mod = wsRoot.resolve(module).normalize();
                    try {
                        mod = mod.toRealPath();
                    } catch (Exception ignored) {
                        // module may not exist on disk yet
                    }
                    dirs.add(mod);
                }
            }
        } catch (Exception ignored) {
            // Standalone project, or an unparseable manifest: just this dir.
        }
        return new ArrayList<>(dirs);
    }

    /** Recompute the {@link ActionKey#taskTag} of every build-output dir for each module. */
    private static Set<String> tagsFor(List<Path> moduleDirs) {
        Set<String> tags = new HashSet<>();
        for (Path dir : moduleDirs) {
            JkBuild project;
            try {
                project = JkBuildParser.parse(dir.resolve(ManifestPaths.MANIFEST));
            } catch (Exception e) {
                continue; // no/invalid manifest here — nothing to tag
            }
            BuildLayout layout = BuildLayout.of(dir, project);
            for (Path out : List.of(
                    layout.classesDir(),
                    layout.testClassesDir(),
                    layout.kotlinClassesDir(),
                    layout.kotlinTestClassesDir(),
                    layout.groovyClassesDir(),
                    layout.groovyTestClassesDir(),
                    layout.mainJar(),
                    layout.assemblyJar(),
                    layout.sourcesJar(),
                    layout.javadocJar(),
                    layout.nativeBinary(),
                    layout.nativeLibrary(),
                    layout.ociImageTar())) {
                tags.add(ActionKey.taskTag(out));
            }
        }
        return tags;
    }

    /** First {@code TASK <id>} line of an action record, or {@code null}. */
    private static @Nullable String taskIdOf(String recordContent) {
        for (String line : recordContent.split("\n", -1)) {
            if (line.startsWith("TASK "))
                return line.substring("TASK ".length()).trim();
        }
        return null;
    }

    /** The {@code @<tag>} suffix of a qualified task id, or {@code ""} for an unqualified name. */
    private static String tagOf(String taskId) {
        int at = taskId.lastIndexOf('@');
        return at < 0 ? "" : taskId.substring(at + 1);
    }

    /** True when any {@code INPUT} source/classpath path in the record is under one of {@code prefixes}. */
    private static boolean inputsUnder(String recordContent, List<String> prefixes) {
        for (String line : recordContent.split("\n", -1)) {
            if (!line.startsWith("INPUT ")) continue;
            String body = line.substring("INPUT ".length());
            int sp = body.indexOf(' ');
            if (sp < 0) continue;
            String pathKey = body.substring(sp + 1);
            if (pathKey.startsWith("cp:")) pathKey = pathKey.substring(3);
            else if (pathKey.startsWith("pp:")) pathKey = pathKey.substring(3);
            for (String prefix : prefixes) {
                if (pathKey.equals(prefix) || pathKey.startsWith(prefix + "/")) return true;
            }
        }
        return false;
    }

    /**
     * Delete every child of {@code dir} whose name is a qualified task id with a tag in {@code tags},
     * or whose name is in {@code alsoDelete}. Handles both plain files (task pointers) and directory
     * trees (incremental state), accumulating {@code {files, bytes}} into {@code acc}.
     */
    private static void deleteQualified(Path dir, Set<String> tags, Set<String> alsoDelete, boolean dryRun, long[] acc)
            throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                String name = child.getFileName().toString();
                if (!tags.contains(tagOf(name)) && !alsoDelete.contains(name)) continue;
                if (Files.isDirectory(child)) {
                    try (var tree = Files.walk(child)) {
                        List<Path> paths =
                                tree.sorted(Comparator.reverseOrder()).toList();
                        for (Path p : paths) {
                            if (Files.isRegularFile(p)) {
                                acc[1] += Files.size(p);
                                acc[0]++;
                            }
                            if (!dryRun) Files.deleteIfExists(p);
                        }
                    }
                } else {
                    acc[1] += Files.size(child);
                    if (!dryRun) Files.deleteIfExists(child);
                    acc[0]++;
                }
            }
        }
    }

    /** What one {@code .put-} temp sweep reclaimed. */
    record TempSweep(long files, long bytes) {}

    /**
     * Delete leftover {@code .put-} temps under one CAS {@code sha256/} tree. Same shape for the
     * cache tier and the artifact store — the only difference is which root resolves the dir.
     */
    static TempSweep sweepCasTemps(Path shaDir, boolean dryRun) throws IOException {
        if (!Files.isDirectory(shaDir)) return new TempSweep(0, 0);
        long files = 0;
        long bytes = 0;
        for (Path file : tempFiles(shaDir)) {
            bytes += Files.size(file);
            if (!dryRun) Files.deleteIfExists(file);
            files++;
        }
        return new TempSweep(files, bytes);
    }

    private static List<Path> tempFiles(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            // Free test first: the walk already paid for this entry, and isRegularFile re-resolves
            // the path for a fresh stat even for entries the name test discards.
            return stream.filter(p -> p.getFileName().toString().startsWith(".put-"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
    }
}

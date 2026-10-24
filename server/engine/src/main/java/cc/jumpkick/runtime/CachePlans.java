// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.StoreCasGc;
import cc.jumpkick.cache.ToolsGc;
import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.config.DiskPressure;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.repo.M2Backing;
import cc.jumpkick.repo.M2Clean;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.StoreRepoGc;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.Task;
import cc.jumpkick.runtime.base.StepTimings;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.CacheRetention;
import cc.jumpkick.task.CacheRoots;
import cc.jumpkick.task.CasSweep;
import cc.jumpkick.task.HeavyActionPolicy;
import cc.jumpkick.task.TmpGc;
import cc.jumpkick.util.AccessLedger;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.ToolUse;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;

/**
 * Cache-maintenance plans for {@code jk cache clean}, {@code jk cache nuke}, {@code jk storage
 * clean}, and project-scoped invalidation ({@code jk clean -F}). These mutate caches that
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
     * {@code cache.max-cache-size-gb}. For the default cache dir ({@code includeJkTmp}) it also sweeps
     * {@code state/tmp} and collects the artifact store's CAS ({@link StoreCasGc}).
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
                    } catch (RuntimeException e) {
                        // install-file sweep is best-effort maintenance
                        Log.debug("pruneBuildPlan: install-file sweep is best-effort maintenance", e);
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

                    // The artifact store's CAS has no other collector: what installs and syncs leave there
                    // ages out by what it is and how big, faster as the volume fills.
                    if (includeJkTmp) {
                        var space = JkCacheConfig.DiskSpace.probe(JkStores.store());
                        double pressure = DiskPressure.factor(space);
                        long now = System.currentTimeMillis();
                        Path toolsRoot = JkStores.store().resolve("tools");
                        AccessLedger toolLedger = ToolUse.ledger(toolsRoot);
                        var toolsGc = ToolsGc.run(toolsRoot, toolFamilies(), toolLedger.load(), pressure, now, dryRun);
                        totalFiles += toolsGc.deleted();
                        totalBytes += toolsGc.freedBytes();
                        if (!dryRun) {
                            toolLedger.compact(key -> ToolUse.exists(toolsRoot, key));
                        }
                        Path storeRoot = JkStores.store();
                        AccessLedger blobLedger = AccessLedger.at(storeRoot.resolve(".access"));
                        var storeGc = StoreCasGc.run(
                                JkStores.storeCas(),
                                StoreCasGc.pinnedShas(),
                                blobLedger.load(),
                                StoreCasGc.Policy.defaults(pressure).withBudget(DiskPressure.budgetBytes(space)),
                                now,
                                dryRun);
                        if (!dryRun) {
                            Cas untracked = new Cas(storeRoot);
                            blobLedger.compact(key -> key.startsWith("cas:") && untracked.contains(key.substring(4)));
                        }
                        totalFiles += storeGc.deleted();
                        totalBytes += storeGc.freedBytes();
                        var repoGc = StoreRepoGc.run(storeRoot, pressure, now, dryRun);
                        totalFiles += repoGc.deleted();
                        totalBytes += repoGc.freedBytes();
                        SweepReport shadowed = reclaimShadowedArtifacts(dryRun);
                        totalFiles += shadowed.files();
                        totalBytes += shadowed.bytes();
                        // Only on an admin's say-so: the local repository is shared, and jk never reclaims it
                        // on its own account.
                        if (JkM2Config.resolve().autoClean()) {
                            M2Clean.Report m2 = cleanM2(null, 0, dryRun);
                            totalFiles += m2.removed().size();
                            totalBytes += m2.freedBytes();
                        }
                        if (pressure < DiskPressure.NONE) {
                            ctx.warn(
                                    "prune",
                                    "disk space is low: store retention windows are scaled to "
                                            + Math.round(pressure * 100) + "%");
                        }
                    }
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
     * Remove what jk downloaded into the Maven local repository and no longer uses: files its own memos
     * prove it fetched ({@link M2Clean}), unused for {@code olderThanDays} (the machine's {@code [m2]
     * clean-after-days} when {@code 0}), holding back what the lockfile at {@code projectDir} still pins.
     * Never run on its own account: a request, or the machine's {@code [m2] auto-clean}.
     */
    public static BuildPlan m2CleanBuildPlan(@Nullable Path projectDir, int olderThanDays, boolean dryRun) {
        Task step = Task.builder("m2-clean")
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("Cleaning the Maven local repository…");
                    M2Clean.Report report = cleanM2(projectDir, olderThanDays, dryRun);
                    if (report.foreign() > 0) {
                        ctx.warn(
                                "m2",
                                report.foreign() + " file(s) jk downloaded have changed since and were left alone");
                    }
                    ctx.put(FILES, (long) report.removed().size());
                    ctx.put(BYTES, report.freedBytes());
                    ctx.progress(1);
                })
                .build();
        return BuildPlan.builder("cache-m2-clean")
                .stateKeys(FILES, BYTES)
                .addTask(step)
                .build();
    }

    /** One {@link M2Clean} pass over the machine's local repository, with the window and pins a request or the config names. */
    public static M2Clean.Report cleanM2(@Nullable Path projectDir, int olderThanDays, boolean dryRun)
            throws IOException {
        int days = olderThanDays > 0 ? olderThanDays : JkM2Config.resolve().cleanAfterDays();
        Set<String> pins = projectDir == null ? Set.of() : M2Clean.pinsOf(projectDir.resolve(ManifestNames.LOCK));
        return M2Clean.run(
                JkStores.store(), M2Dirs.localRepository(), Duration.ofDays(days), pins, Clock.SYSTEM.millis(), dryRun);
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
     * Artifact-store GC: leaked {@code .put-} download temps, legacy name-keyed repository stores
     * and artifacts the Maven local repository now owns ({@link #reclaimShadowedArtifacts}). The
     * store's CAS blobs are collected by the cache prune ({@link StoreCasGc}); {@code jk storage
     * nuke} removes everything.
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

        // A repository store keyed by a project's NAME for the repository is unreadable by design:
        // nothing can say which origin filled it, so no lookup reads it and the next resolve
        // re-fetches into the identity-keyed tree. Reclaiming it is the deliberate invalidation.
        for (Path legacy : RepoArtifactStore.legacyStores(JkStores.store())) {
            DiskUsage.Stats stats = DiskUsage.of(legacy);
            totalFiles += stats.files();
            totalBytes += stats.bytes();
            if (!dryRun) PathUtil.deleteRecursivelyOrThrow(legacy);
        }

        SweepReport shadowed = reclaimShadowedArtifacts(dryRun);
        totalFiles += shadowed.files();
        totalBytes += shadowed.bytes();

        return new SweepReport(totalFiles, totalBytes);
    }

    /** The tool families whose installs the collector manages: the ones the registry hands out and stamps. */
    private static Set<String> toolFamilies() {
        Set<String> slugs = new LinkedHashSet<>();
        for (BuildTool tool : BuildTool.values()) slugs.add(tool.slug());
        return slugs;
    }

    /**
     * Artifacts a store tree holds that the Maven local repository now owns. A repository whose
     * artifacts live in {@code ~/.m2} ({@link M2Backing}) keeps only {@code .jk} memos under {@code
     * repos/<id>/}; the jars an earlier layout left beside them are never read again, and are the
     * largest thing in the store. Trees that do keep their own artifacts, and a tree that <em>is</em>
     * the local repository (a test sandbox), are left alone.
     */
    public static SweepReport reclaimShadowedArtifacts(boolean dryRun) throws IOException {
        long files = 0;
        long bytes = 0;
        Path m2 = M2Dirs.localRepository();
        Path m2Real =
                Files.isDirectory(m2) ? m2.toRealPath() : m2.toAbsolutePath().normalize();
        for (RepoArtifactStore.Origin origin : RepoArtifactStore.describeAll(JkStores.store())) {
            String canonical = origin.origin();
            if (origin.isLegacy() || canonical == null) continue;
            if (M2Backing.rootFor(URI.create(canonical), true).isEmpty()) continue;
            Path tree = JkStores.resolve("repos").resolve(origin.id());
            if (tree.toRealPath().equals(m2Real)) continue;
            List<Path> stray = new ArrayList<>();
            PathUtil.forEachRegularFile(tree, (file, attrs) -> {
                String name = file.getFileName().toString();
                if (!name.endsWith(".jk") && !RepoArtifactStore.ORIGIN_FILE.equals(name)) stray.add(file);
            });
            for (Path file : stray) {
                files++;
                bytes += Files.size(file);
                if (!dryRun) Files.deleteIfExists(file);
            }
        }
        return new SweepReport(files, bytes);
    }

    /**
     * Project-scoped forgetting: drop the incremental compiler state and the preflight memos of
     * {@code projectDir} and its workspace modules, so the next build re-derives every verdict from
     * its keys. The action records themselves stay: they are content-keyed and every checkout of
     * the project shares them; removing them is {@code jk cache clean} / {@code nuke}.
     */
    public static BuildPlan clearBuildPlan(Path cacheRoot, Path projectDir, boolean dryRun) {
        Task clearStep = Task.builder("clear")
                .ticks(1)
                .execute(ctx -> {
                    ctx.label(dryRun ? "Inspecting build state…" : "Clearing build state…");
                    var acc = new Tally();
                    List<Path> allModuleDirs = resolveModuleDirs(projectDir);
                    Path actionsDir = CacheTree.ACTIONS.under(cacheRoot);
                    if (Files.isDirectory(actionsDir)) {
                        Set<String> tags = tagsFor(allModuleDirs);
                        List<Path> qualified = new ArrayList<>();
                        for (Path tree : ActionTree.incrementalUnder(actionsDir)) {
                            qualified.addAll(qualifiedChildren(tree, tags, Set.of()));
                        }
                        PathUtil.Removed removed = dryRun ? PathUtil.measureTrees(qualified) : deleteAll(qualified);
                        acc.add(removed);
                    }
                    // Preflight memos, both copies: a surviving memo would answer the next build
                    // from the conclusions this clear is there to drop.
                    for (Path m : allModuleDirs) {
                        for (Path preflight : List.of(
                                m.resolve(BuildLayout.TARGET).resolve(".jk").resolve("preflight"),
                                PreflightMemo.durablePreflightDir(m))) {
                            if (!Files.isDirectory(preflight)) continue;
                            try (var files = Files.list(preflight)) {
                                for (Path f : (Iterable<Path>) files::iterator) {
                                    if (!Files.isRegularFile(f)) continue;
                                    acc.file(Files.size(f));
                                    if (!dryRun) Files.deleteIfExists(f);
                                }
                            }
                        }
                    }
                    ctx.put(FILES, acc.files.sum());
                    ctx.put(BYTES, acc.bytes.sum());
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
        // Prefer realpath so tags/INPUT prefixes match the build verbs (which realpath before building).
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
            JkBuild manifest = JkBuildParser.parse(ManifestPaths.manifestIn(here));
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
                JkBuild root = wsRoot.equals(here) ? manifest : JkBuildParser.parse(ManifestPaths.manifestIn(wsRoot));
                for (String module : root.workspaceOpt().map(Workspace::modules).orElse(List.of())) {
                    Path mod = wsRoot.resolve(module).normalize();
                    try {
                        mod = mod.toRealPath();
                    } catch (Exception e) {
                        // module may not exist on disk yet
                        Log.debug("resolveModuleDirs: module may not exist on disk yet", e);
                    }
                    dirs.add(mod);
                }
            }
        } catch (Exception e) {
            // Standalone project, or an unparseable manifest: just this dir.
            Log.debug("resolveModuleDirs: Standalone project, or an unparseable manifest", e);
        }
        return new ArrayList<>(dirs);
    }

    /**
     * Recompute the tags of every build-output dir for each module: the {@link ActionKey#taskTag}
     * that names key records and task pointers, and the {@link ActionKey#checkoutTag} that names
     * this checkout's incremental state.
     */
    private static Set<String> tagsFor(List<Path> moduleDirs) {
        Set<String> tags = new HashSet<>();
        for (Path dir : moduleDirs) {
            JkBuild project;
            try {
                project = JkBuildParser.parse(ManifestPaths.manifestIn(dir));
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
                tags.add(ActionKey.checkoutTag(out));
            }
        }
        return tags;
    }

    /**
     * The {@code @<tag>} suffix of a qualified task id, or {@code ""} for an unqualified name. A
     * {@code tasks/} entry may be the pointer's generation list or that list's lock rather than the
     * pointer itself; both carry the pointer's tag.
     */
    static String tagOf(String taskId) {
        String name = taskId;
        for (String suffix : List.of(HeavyActionPolicy.GENS_LOCK_SUFFIX, HeavyActionPolicy.GENS_SUFFIX)) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        int at = name.lastIndexOf('@');
        return at < 0 ? "" : name.substring(at + 1);
    }

    /**
     * The children of {@code dir} whose name is a qualified task id with a tag in {@code tags}, or
     * whose name is in {@code alsoDelete}: plain files (task pointers) and directory trees
     * (incremental state) alike, each a root for one pooled delete.
     */
    private static List<Path> qualifiedChildren(Path dir, Set<String> tags, Set<String> alsoDelete) throws IOException {
        List<Path> hits = new ArrayList<>();
        PathUtil.forEachChild(dir, (child, attrs) -> {
            String name = child.getFileName().toString();
            if (tags.contains(tagOf(name)) || alsoDelete.contains(name)) hits.add(child);
            return true;
        });
        return hits;
    }

    private static PathUtil.Removed deleteAll(List<Path> roots) throws IOException {
        var removed = new PathUtil.Removed();
        PathUtil.deleteTrees(roots, removed);
        return removed;
    }

    /** Files and bytes the clear reclaimed, written from the pool's threads. */
    private static final class Tally {
        final LongAdder files = new LongAdder();
        final LongAdder bytes = new LongAdder();

        void file(long size) {
            files.increment();
            bytes.add(size);
        }

        void add(PathUtil.Removed removed) {
            files.add(removed.files());
            bytes.add(removed.bytes());
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

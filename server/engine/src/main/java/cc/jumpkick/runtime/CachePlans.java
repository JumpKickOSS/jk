// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.Task;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.CacheGc;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cache-maintenance plans for {@code jk cache prune}, {@code jk cache purge}, {@code jk repo
 * prune}, and {@code jk clean --cache}. Mutate caches plans may read concurrently — the engine
 * runs them only at idle boundaries.
 */
public final class CachePlans {

    private CachePlans() {}

    /** Files removed (or, on a dry run, that would be). {@code gc}: purged CAS blobs. */
    public static final BuildPlanKey<Long> FILES = BuildPlanKey.of("cache-files", Long.class);

    /** Bytes freed (or reclaimable, on a dry run). */
    public static final BuildPlanKey<Long> BYTES = BuildPlanKey.of("cache-bytes", Long.class);

    /**
     * Reachable cache-tier CAS objects the LRU evictor removed to fit {@code cache.max-cache-size-mb}
     * ({@code jk cache prune} only — {@code jk repo prune}'s store-tier sweep never evicts reachable
     * blobs).
     */
    public static final BuildPlanKey<Long> REACHABLE_EVICTED = BuildPlanKey.of("cache-reachable-evicted", Long.class);

    /** Repo-mirror links removed ({@code gc} only). */
    public static final BuildPlanKey<Long> REPO_LINKS = BuildPlanKey.of("cache-repo-links", Long.class);

    /**
     * Prune plan for the cache at {@code root}: expire stale entries, GC sidecar files, optional
     * CAS sweep, and cache-tier LRU eviction against {@code cache.max-cache-size-mb} (config, not a
     * CLI flag). {@code includeJkTmp} sweeps {@code state/tmp} only for the default cache dir.
     */
    public static BuildPlan pruneBuildPlan(
            Path root, int olderThanDays, boolean dryRun, boolean sweep, boolean includeJkTmp) {
        Task pruneStep = Task.builder("prune")
                .ticks(1)
                .execute(ctx -> {
                    // LRU-sweep materialized jk versions (keep running + recent; rest re-fetch).
                    try {
                        var ledger = cc.jumpkick.task.AccessLedger.atDefaultPath();
                        java.util.Map<String, Long> latest = ledger.latestByHash();
                        var prunedVersions = cc.jumpkick.cache.VersionStore.current()
                                .prune(
                                        cc.jumpkick.model.JkVersion.VERSION,
                                        java.time.Duration.ofDays(30),
                                        key -> latest.getOrDefault(key, 0L),
                                        cc.jumpkick.util.JkDirs.state());
                        for (String v : prunedVersions) ctx.warn("prune", "retired unused jk " + v);
                    } catch (java.io.IOException | RuntimeException ignored) {
                        // version sweep is best-effort maintenance
                    }

                    ctx.label("Pruning cache…");
                    long cutoffMillis = System.currentTimeMillis() - (long) olderThanDays * 24L * 60L * 60L * 1000L;
                    long totalFiles = 0;
                    long totalBytes = 0;
                    long reachableEvicted = 0;

                    // Cache-tier CAS temps under <cacheRoot>/sha256/
                    TempSweep cacheTemps = sweepCasTemps(root.resolve("sha256"), dryRun);
                    totalFiles += cacheTemps.files();
                    totalBytes += cacheTemps.bytes();
                    Path actionsDir = root.resolve("actions");
                    if (Files.isDirectory(actionsDir)) {
                        Path keysDir = actionsDir.resolve("keys");
                        if (Files.isDirectory(keysDir)) {
                            for (Path file : olderThan(keysDir, cutoffMillis)) {
                                long sz = Files.size(file);
                                if (!dryRun) Files.deleteIfExists(file);
                                totalFiles++;
                                totalBytes += sz;
                            }
                        }
                    }
                    // Run logs are store-tier (`jk repo storage`); their GC lives in sweepStore —
                    // running it here too double-counted dry-run FILES/BYTES on every
                    // prune-with-sweep (JK-1526).
                    var formatStampReport = cc.jumpkick.task.FormatStampGc.sweep(
                            root, cc.jumpkick.task.FormatStampGc.DEFAULT_TTL, dryRun);
                    totalFiles += formatStampReport.deleted();
                    totalBytes += formatStampReport.freedBytes();

                    var timingsReport = StepTimings.prune(
                            root,
                            StepTimings.Limits.resolve(cc.jumpkick.util.JkDirs.userConfigFile(), System::getenv),
                            System.currentTimeMillis(),
                            dryRun);
                    totalFiles += timingsReport.evictedByAge() + timingsReport.evictedBySize();

                    if (includeJkTmp) {
                        var tmpReport = cc.jumpkick.task.TmpGc.sweep(
                                cc.jumpkick.util.JkDirs.tmp(), cc.jumpkick.task.TmpGc.DEFAULT_TTL, dryRun);
                        totalFiles += tmpReport.deleted();
                        totalBytes += tmpReport.freedBytes();
                    }

                    // Always reclaim unreferenced action payloads from the cache CAS.
                    var cacheCas = cc.jumpkick.cache.JkStores.cacheCas(root);
                    var cacheLive = cc.jumpkick.task.CacheRoots.collect(
                            cacheCas, root.resolve("actions"), root.resolve("tools"));
                    var cacheSweep = cc.jumpkick.task.CasSweep.sweep(cacheCas, cacheLive, dryRun);
                    totalFiles += cacheSweep.deleted();
                    totalBytes += cacheSweep.freedBytes();
                    long cacheBudget =
                            cc.jumpkick.config.JkCacheConfig.resolve().maxCacheSizeBytes();
                    if (cacheBudget > 0) {
                        // Utilization surfaces (jk cache storage, /api/cache) measure index +
                        // stamps + blobs against this budget, but eviction can only shrink blobs.
                        // Aim the blob pool at what remains after the index overhead so a prune
                        // can actually bring utilization back under 100% (JK-1526).
                        long overheadBytes =
                                cc.jumpkick.cache.DiskUsage.of(actionsDir).bytes()
                                        + cc.jumpkick.cache.DiskUsage.of(root.resolve("format-stamps"))
                                                .bytes();
                        long blobBudget = Math.max(0, cacheBudget - overheadBytes);
                        var ledger = cc.jumpkick.task.AccessLedger.atDefaultPath();
                        var evict = cc.jumpkick.task.LruEvictor.evictDownTo(
                                cacheCas, blobBudget, cacheLive, ledger, dryRun, cacheSweep.deletedShas());
                        totalFiles += evict.deleted();
                        totalBytes += evict.freedBytes();
                        reachableEvicted += evict.reachableEvicted();
                    }

                    // Optional store-tier sweep (legacy --sweep on cache prune).
                    if (sweep) {
                        SweepReport storeReport = sweepStore(root, dryRun);
                        totalFiles += storeReport.files();
                        totalBytes += storeReport.bytes();
                        reachableEvicted += storeReport.reachableEvicted();
                    }
                    ctx.put(REACHABLE_EVICTED, reachableEvicted);

                    ctx.put(FILES, totalFiles);
                    ctx.put(BYTES, totalBytes);
                    ctx.progress(1);
                })
                .build();
        return BuildPlan.builder("cache-prune").addTask(pruneStep).build();
    }

    /**
     * Build the purge plan: wipe the entire cache tier under {@code root} ({@code actions/},
     * {@code format-stamps/}, cache {@code sha256/}). Artifact store trees ({@code repos/}, store
     * CAS) are never under this root in the ambient layout; hermetic collocated {@code repos/} is
     * kept.
     */
    public static BuildPlan purgeBuildPlan(Path root) {
        Task purgeStep = Task.builder("purge")
                .execute(ctx -> {
                    ctx.label("Purging cache…");
                    purgeActionCache(root);
                })
                .build();
        return BuildPlan.builder("cache-purge").addTask(purgeStep).build();
    }

    /**
     * Delete the cache-tier trees under {@code root}: action index, format stamps, and cache CAS
     * ({@code sha256/}). Leaves {@code repos/} and {@code runs/} alone.
     */
    public static void purgeActionCache(Path root) throws IOException {
        for (String tree : new String[] {"actions", "format-stamps", "sha256"}) {
            Path dir = root.resolve(tree);
            if (Files.isDirectory(dir)) deleteContents(dir);
        }
    }

    /**
     * Build the store-sweep plan ({@code jk repo prune}): artifact CAS temp cleanup, run-log TTL
     * GC, and unreferenced-blob sweep. Garbage-only — never evicts reachable blobs.
     */
    public static BuildPlan sweepBuildPlan(Path root, boolean dryRun) {
        Task sweepStep = Task.builder("sweep")
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("Sweeping store…");
                    SweepReport report = sweepStore(root, dryRun);
                    ctx.put(FILES, report.files());
                    ctx.put(BYTES, report.bytes());
                    ctx.put(REACHABLE_EVICTED, report.reachableEvicted());
                    ctx.progress(1);
                })
                .build();
        return BuildPlan.builder("repo-prune").addTask(sweepStep).build();
    }

    /** Totals for one store sweep ({@link #sweepStore}). */
    public record SweepReport(long files, long bytes, long reachableEvicted) {}

    /** Artifact-store GC: reclaims temps, expired run logs, and unreferenced store blobs only. Never evicts reachable blobs. */
    public static SweepReport sweepStore(Path root, boolean dryRun) throws IOException {
        long totalFiles = 0;
        long totalBytes = 0;

        TempSweep temps = sweepCasTemps(cc.jumpkick.cache.JkStores.resolve(root, "sha256"), dryRun);
        totalFiles += temps.files();
        totalBytes += temps.bytes();

        var runLogReport = cc.jumpkick.task.RunLogGc.sweep(root, cc.jumpkick.task.RunLogGc.DEFAULT_TTL, dryRun);
        totalFiles += runLogReport.deleted();
        totalBytes += runLogReport.freedBytes();

        cc.jumpkick.cache.Cas cas = cc.jumpkick.cache.JkStores.cas(root);
        Path toolsDir = cc.jumpkick.cache.JkStores.resolve(root, "tools");
        var liveRefs = cc.jumpkick.task.CacheRoots.collect(cas, root.resolve("actions"), toolsDir);
        var sweepReport = cc.jumpkick.task.CasSweep.sweep(cas, liveRefs, dryRun);
        totalFiles += sweepReport.deleted();
        totalBytes += sweepReport.freedBytes();
        return new SweepReport(totalFiles, totalBytes, 0L);
    }

    /** Build the GC plan ({@code jk clean --cache}): purge CAS blobs idle 90+ days via {@link CacheGc}. */
    public static BuildPlan gcBuildPlan(Path root) {
        Task gcStep = Task.builder("gc")
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("Collecting cache…");
                    CacheGc.Report report = CacheGc.run(root, false);
                    ctx.put(FILES, (long) report.purgedBlobs());
                    ctx.put(BYTES, report.freedBytes());
                    ctx.put(REPO_LINKS, (long) report.repoLinksRemoved());
                    ctx.progress(1);
                })
                .build();
        return BuildPlan.builder("cache-gc").addTask(gcStep).build();
    }

    /**
     * {@code jk cache clear}: drop action-cache keys/tasks/incremental state for {@code projectDir}
     * and its workspace modules (match by output {@link ActionKey#taskTag} or INPUT path under a
     * module). CAS blobs are left for a later prune.
     */
    public static BuildPlan clearBuildPlan(Path cacheRoot, Path projectDir, boolean dryRun) {
        Task clearStep = Task.builder("clear")
                .ticks(1)
                .execute(ctx -> {
                    ctx.label(dryRun ? "Inspecting build cache…" : "Clearing build cache…");
                    long[] acc = {0L, 0L}; // {files, bytes}
                    List<Path> allModuleDirs = resolveModuleDirs(projectDir);
                    Path actionsDir = cacheRoot.resolve("actions");
                    if (Files.isDirectory(actionsDir)) {
                        List<Path> moduleDirs = allModuleDirs;
                        Set<String> tags = tagsFor(moduleDirs);
                        List<String> prefixes =
                                moduleDirs.stream().map(p -> p.toString()).toList();
                        Set<String> deletedTaskIds = new LinkedHashSet<>();

                        // 1) key records: match by qualified-task tag, or by an INPUT path under a module dir.
                        Path keysDir = actionsDir.resolve("keys");
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
                        deleteQualified(actionsDir.resolve("tasks"), tags, deletedTaskIds, dryRun, acc);
                        deleteQualified(actionsDir.resolve("incremental-java"), tags, deletedTaskIds, dryRun, acc);
                        deleteQualified(actionsDir.resolve("incremental-kotlin"), tags, deletedTaskIds, dryRun, acc);
                    }
                    // 3) preflight memos — their "clean" conclusions were derived from the
                    // action keys just deleted; a surviving memo turns clear into a no-op.
                    for (Path m : allModuleDirs) {
                        Path preflight = m.resolve("target").resolve(".jk").resolve("preflight");
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
        return BuildPlan.builder("cache-clear").addTask(clearStep).build();
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
            JkBuild manifest = JkBuildParser.parse(here.resolve("jk.toml"));
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
                JkBuild root = wsRoot.equals(here) ? manifest : JkBuildParser.parse(wsRoot.resolve("jk.toml"));
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
                project = JkBuildParser.parse(dir.resolve("jk.toml"));
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
    private static String taskIdOf(String recordContent) {
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

    /** Recursively delete everything under {@code root}, keeping {@code root} itself. */
    public static void deleteContents(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(root))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private static List<Path> olderThan(Path dir, long cutoffMillis) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() < cutoffMillis;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
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
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(".put-"))
                    .toList();
        }
    }
}

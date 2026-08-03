// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineKey;
import cc.jumpkick.run.Step;
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
 * Cache-maintenance pipelines for {@code jk cache prune}, {@code jk cache purge}, {@code jk repo
 * prune}, and {@code jk clean --cache}. Mutate caches pipelines may read concurrently — the engine
 * runs them only at idle boundaries.
 */
public final class CachePipelines {

    private CachePipelines() {}

    /** Files removed (or, on a dry run, that would be). {@code gc}: purged CAS blobs. */
    public static final PipelineKey<Long> FILES = PipelineKey.of("cache-files", Long.class);

    /** Bytes freed (or reclaimable, on a dry run). */
    public static final PipelineKey<Long> BYTES = PipelineKey.of("cache-bytes", Long.class);

    /** Reachable CAS objects the LRU evictor removed to fit {@code --max-size} (prune only). */
    public static final PipelineKey<Long> REACHABLE_EVICTED = PipelineKey.of("cache-reachable-evicted", Long.class);

    /** Repo-mirror links removed ({@code gc} only). */
    public static final PipelineKey<Long> REPO_LINKS = PipelineKey.of("cache-repo-links", Long.class);

    /**
     * Prune pipeline for the cache at {@code root}: expire stale entries, GC sidecar files, optional
     * CAS sweep + LRU eviction. {@code includeJkTmp} sweeps {@code state/tmp} only for the default
     * cache dir.
     */
    public static Pipeline prunePipeline(
            Path root, int olderThanDays, boolean dryRun, boolean sweep, String maxSize, boolean includeJkTmp) {
        Step pruneStep = Step.builder("prune")
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

                    // sha256/ lives in the store, not under the cache root.
                    Path shaDir = cc.jumpkick.cache.JkStores.resolve(root, "sha256");
                    if (Files.isDirectory(shaDir)) {
                        for (Path file : tempFiles(shaDir)) {
                            long sz = Files.size(file);
                            if (!dryRun) Files.deleteIfExists(file);
                            totalFiles++;
                            totalBytes += sz;
                        }
                    }
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
                    var runLogReport =
                            cc.jumpkick.task.RunLogGc.sweep(root, cc.jumpkick.task.RunLogGc.DEFAULT_TTL, dryRun);
                    totalFiles += runLogReport.deleted();
                    totalBytes += runLogReport.freedBytes();

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

                    boolean doSweep = sweep || maxSize != null;
                    long budgetBytes = maxSize != null ? cc.jumpkick.task.LruEvictor.parseSize(maxSize) : -1L;
                    if (doSweep) {
                        // Blobs live in the store; reachability roots (actions/, tools/) stay with the cache.
                        cc.jumpkick.cache.Cas cas = cc.jumpkick.cache.JkStores.cas(root);
                        Path toolsDir = cc.jumpkick.cache.JkStores.resolve(root, "tools");
                        Path actionsDir2 = root.resolve("actions");
                        var liveRefs = cc.jumpkick.task.CacheRoots.collect(cas, actionsDir2, toolsDir);
                        var sweepReport = cc.jumpkick.task.CasSweep.sweep(cas, liveRefs, dryRun);
                        totalFiles += sweepReport.deleted();
                        totalBytes += sweepReport.freedBytes();
                        if (budgetBytes > 0) {
                            var ledger = cc.jumpkick.task.AccessLedger.atDefaultPath();
                            var evictReport =
                                    cc.jumpkick.task.LruEvictor.evictDownTo(cas, budgetBytes, liveRefs, ledger, dryRun);
                            totalFiles += evictReport.deleted();
                            totalBytes += evictReport.freedBytes();
                            ctx.put(REACHABLE_EVICTED, (long) evictReport.reachableEvicted());
                            if (!dryRun) {
                                try {
                                    ledger.compactIfLarge();
                                } catch (IOException ignored) {
                                }
                            }
                        }
                    }

                    ctx.put(FILES, totalFiles);
                    ctx.put(BYTES, totalBytes);
                    ctx.progress(1);
                })
                .build();
        return Pipeline.builder("cache-prune").addStep(pruneStep).build();
    }

    /**
     * Build the purge pipeline: delete the action cache under {@code root} ({@code actions/} +
     * {@code format-stamps/}). Store-side trees that may share the root in an explicit
     * {@code --cache-dir} layout ({@code sha256/}, {@code repos/}, {@code runs/}) are kept —
     * {@code jk repo prune} reclaims those.
     */
    public static Pipeline purgePipeline(Path root) {
        Step purgeStep = Step.builder("purge")
                .execute(ctx -> {
                    ctx.label("Purging cache…");
                    purgeActionCache(root);
                })
                .build();
        return Pipeline.builder("cache-purge").addStep(purgeStep).build();
    }

    /** Delete the action-cache trees under {@code root}: {@code actions/} and {@code format-stamps/}. */
    public static void purgeActionCache(Path root) throws IOException {
        for (String tree : new String[] {"actions", "format-stamps"}) {
            Path dir = root.resolve(tree);
            if (Files.isDirectory(dir)) deleteContents(dir);
        }
    }

    /**
     * Build the store-sweep pipeline ({@code jk repo prune}): CAS temp-file cleanup, run-log TTL GC,
     * unreferenced-blob sweep, and (with {@code maxSize}) LRU eviction down to the budget.
     */
    public static Pipeline sweepPipeline(Path root, boolean dryRun, String maxSize) {
        Step sweepStep = Step.builder("sweep")
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("Sweeping store…");
                    SweepReport report = sweepStore(root, dryRun, maxSize);
                    ctx.put(FILES, report.files());
                    ctx.put(BYTES, report.bytes());
                    ctx.put(REACHABLE_EVICTED, report.reachableEvicted());
                    ctx.progress(1);
                })
                .build();
        return Pipeline.builder("repo-prune").addStep(sweepStep).build();
    }

    /** Totals for one store sweep ({@link #sweepStore}). */
    public record SweepReport(long files, long bytes, long reachableEvicted) {}

    /**
     * Store-side reclamation for the cache at {@code root}: leftover CAS {@code .put-} temp files,
     * expired run logs, unreferenced CAS blobs, and (when {@code maxSize} is set) reachable-blob LRU
     * eviction down to the budget.
     */
    public static SweepReport sweepStore(Path root, boolean dryRun, String maxSize) throws IOException {
        long totalFiles = 0;
        long totalBytes = 0;
        long reachableEvicted = 0;

        Path shaDir = cc.jumpkick.cache.JkStores.resolve(root, "sha256");
        if (Files.isDirectory(shaDir)) {
            for (Path file : tempFiles(shaDir)) {
                long sz = Files.size(file);
                if (!dryRun) Files.deleteIfExists(file);
                totalFiles++;
                totalBytes += sz;
            }
        }

        var runLogReport = cc.jumpkick.task.RunLogGc.sweep(root, cc.jumpkick.task.RunLogGc.DEFAULT_TTL, dryRun);
        totalFiles += runLogReport.deleted();
        totalBytes += runLogReport.freedBytes();

        // Blobs live in the store; reachability roots (actions/, tools/) stay with the cache.
        cc.jumpkick.cache.Cas cas = cc.jumpkick.cache.JkStores.cas(root);
        Path toolsDir = cc.jumpkick.cache.JkStores.resolve(root, "tools");
        var liveRefs = cc.jumpkick.task.CacheRoots.collect(cas, root.resolve("actions"), toolsDir);
        var sweepReport = cc.jumpkick.task.CasSweep.sweep(cas, liveRefs, dryRun);
        totalFiles += sweepReport.deleted();
        totalBytes += sweepReport.freedBytes();

        long budgetBytes = maxSize != null ? cc.jumpkick.task.LruEvictor.parseSize(maxSize) : -1L;
        if (budgetBytes > 0) {
            var ledger = cc.jumpkick.task.AccessLedger.atDefaultPath();
            var evictReport = cc.jumpkick.task.LruEvictor.evictDownTo(cas, budgetBytes, liveRefs, ledger, dryRun);
            totalFiles += evictReport.deleted();
            totalBytes += evictReport.freedBytes();
            reachableEvicted = evictReport.reachableEvicted();
            if (!dryRun) {
                try {
                    ledger.compactIfLarge();
                } catch (IOException ignored) {
                }
            }
        }
        return new SweepReport(totalFiles, totalBytes, reachableEvicted);
    }

    /** Build the GC pipeline ({@code jk clean --cache}): purge CAS blobs idle 90+ days via {@link CacheGc}. */
    public static Pipeline gcPipeline(Path root) {
        Step gcStep = Step.builder("gc")
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
        return Pipeline.builder("cache-gc").addStep(gcStep).build();
    }

    /**
     * {@code jk cache clear}: drop action-cache keys/tasks/incremental state for {@code projectDir}
     * and its workspace modules (match by output {@link ActionKey#taskTag} or INPUT path under a
     * module). CAS blobs are left for a later prune.
     */
    public static Pipeline clearPipeline(Path cacheRoot, Path projectDir, boolean dryRun) {
        Step clearStep = Step.builder("clear")
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
        return Pipeline.builder("cache-clear").addStep(clearStep).build();
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

    private static List<Path> tempFiles(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(".put-"))
                    .toList();
        }
    }
}

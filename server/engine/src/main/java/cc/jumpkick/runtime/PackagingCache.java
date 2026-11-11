// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.DiskGuard;
import cc.jumpkick.config.HeavyTtl;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.HeavyActionPolicy;
import cc.jumpkick.task.InputGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The packaging steps' action cache: restore a packaged artifact for its key, or record a freshly
 * packaged one, with what the step said as it ran.
 */
final class PackagingCache {

    private PackagingCache() {}

    /**
     * Packaging cache (mirrors the compile {@link ActionCache} path, for artifacts). Returns {@code
     * true} when a cached artifact for {@code key} was hard-linked back into {@code baseDir} — the
     * caller then skips the (re)packaging work.
     *
     * <p>Invariant: every packaging step that writes a jar under {@code target/} also
     * {@link #storePackaged stores} an action record. A jar without a record only happens when the
     * action cache was deleted out of band while {@code target/} was kept — recovery is to
     * re-package (this miss path), not to trust the on-disk jar as authoritative.
     *
     * <p>{@code --redo}/{@code --force} skip <em>restore</em> (always re-package) but still
     * {@link #storePackaged store} — same contract as {@link cc.jumpkick.task.JavaCompile}: the next
     * {@code jk explain} / incremental build must see a CACHE_HIT, not a phantom repackage.
     */
    static boolean restorePackaged(Path cacheRoot, String key, @Nullable Path baseDir) throws IOException {
        return restorePackaged(cacheRoot, key, baseDir, null);
    }

    /**
     * As {@link #restorePackaged(Path, String, Path)}, and on a restore {@code said}, when given,
     * says again what the run that stored the record said.
     */
    static boolean restorePackaged(Path cacheRoot, String key, @Nullable Path baseDir, @Nullable StepDiagnostics said)
            throws IOException {
        // rebuildOr already subsumes force (JkConfig: force implies rebuild).
        if (SessionContext.current().config().rebuildOr(false)) return false;
        ActionCache ac = packagingActionCache(cacheRoot);
        var hit = ac.lookup(key);
        if (baseDir == null || hit.isEmpty() || !ac.restoreArtifacts(hit.get(), baseDir)) return false;
        if (said != null) said.replay(hit.get());
        return true;
    }

    /**
     * Record a freshly-produced packaging artifact so a later build / explain can skip it. Writes
     * even under {@code --redo} — redo only means "do not restore/skip work", not "do not
     * teach the cache" (parity with compile). {@code persist=false} is {@code jk verify}'s scratch
     * rebuild: packaging DOES run there (the artifact is what verify diffs), its keys embed the
     * unique scratch path so they can never recur, and a store would be a permanent orphan record
     * plus CAS copies on every verify run. {@code said} is what the packaging told the user as it
     * ran ({@link StepDiagnostics}), kept for a restore to say again.
     */
    static void storePackaged(
            TaskContext ctx,
            InputGuard guard,
            Path cacheRoot,
            String taskId,
            String key,
            List<String> tokens,
            @Nullable Path baseDir,
            List<Path> artifacts,
            boolean persist,
            List<String> said)
            throws IOException {
        if (!persist) return;
        // An input that moved while the artifact was written may be in it, and is not what the
        // key names: the artifact stands for this build, but no record vouches for it.
        List<Path> moved = guard.moved();
        if (!moved.isEmpty()) {
            ctx.warn("inputs-moved", InputGuard.describe(taskId.split("@", 2)[0], moved));
            return;
        }
        if (!admitted(ctx, cacheRoot, artifacts, taskId)) return;
        storeRecord(cacheRoot, taskId, key, tokens, baseDir, artifacts, said);
    }

    /**
     * True when {@code artifacts} may go into the cache under {@code cacheRoot}: jk never fills a
     * disk, so on a nearly full volume ({@link DiskGuard}) a heavy artifact stays in target/ only,
     * the step says so, and the engine is woken to reclaim space.
     */
    static boolean admitted(TaskContext ctx, Path cacheRoot, List<Path> artifacts, @Nullable String taskId) {
        // A heavy output enters the cache only while the machine keeps heavy outputs at all
        // ([cache] heavy-ttl, 0 on CI) and the module has not opted out ([build] cache-heavy-outputs).
        if (HeavyActionPolicy.classC(taskId) || largestOf(artifacts) >= DiskGuard.HEAVY_BYTES) {
            if (!HeavyTtl.caches(HeavyTtl.resolve())) return false;
            JkBuild project = ctx.get(BuildPlanner.PROJECT).orElse(null);
            if (project != null && !project.build().cacheHeavyOutputs()) return false;
        }
        if (DiskGuard.admits(cacheRoot, artifacts)) return true;
        ctx.warn(
                DISK_LOW,
                "not cached: the disk is nearly full (" + DiskGuard.describe(DiskGuard.space(cacheRoot))
                        + "); the output stays where it was built and jk is reclaiming space");
        return false;
    }

    /** The warning a packaging step carries when the disk guard kept its artifact out of the cache. */
    static final String DISK_LOW = "disk-low";

    private static long largestOf(List<Path> artifacts) {
        long largest = 0;
        for (Path a : artifacts) {
            try {
                if (Files.isRegularFile(a)) largest = Math.max(largest, Files.size(a));
            } catch (IOException unreadable) {
                // a file that cannot be sized decides nothing
            }
        }
        return largest;
    }

    private static void storeRecord(
            Path cacheRoot,
            String taskId,
            String key,
            List<String> tokens,
            @Nullable Path baseDir,
            List<Path> artifacts,
            List<String> said)
            throws IOException {
        packagingActionCache(cacheRoot)
                .storeArtifacts(taskId, key, Map.of("inputs", String.join(";", tokens)), baseDir, artifacts, said);
    }

    /** Action cache with store-CAS fallback for Class-C blobs promoted by release. */
    static ActionCache packagingActionCache(Path cacheRoot) {
        return new ActionCache(JkStores.cacheCas(cacheRoot), CacheTree.ACTIONS.under(cacheRoot), JkStores.storeCas());
    }

    /** Test hook: {@link #storePackaged} under a rebuild session must still persist; {@code said} as there. */
    static void storePackagedForTest(
            Path cacheRoot,
            String taskId,
            String key,
            List<String> tokens,
            Path baseDir,
            List<Path> artifacts,
            boolean persist,
            List<String> said)
            throws IOException {
        if (persist) storeRecord(cacheRoot, taskId, key, tokens, baseDir, artifacts, said);
    }
}

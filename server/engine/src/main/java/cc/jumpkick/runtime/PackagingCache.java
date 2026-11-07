// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.InputGuard;
import java.io.IOException;
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
        storeRecord(cacheRoot, taskId, key, tokens, baseDir, artifacts, said);
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

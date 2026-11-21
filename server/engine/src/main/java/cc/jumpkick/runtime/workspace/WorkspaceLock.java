// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.LockTimings;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.repo.LocalInstall;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.runtime.AutoLock;
import cc.jumpkick.runtime.FailureLocation;
import cc.jumpkick.runtime.LockFlow;
import cc.jumpkick.runtime.base.BuildMetrics;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Workspace lock-freshness guard for {@link BuildService}: re-lock when the root lock is
 * stale, estimate re-lock ETA, record successful lock walls.
 */
@NullMarked
public final class WorkspaceLock {

    private WorkspaceLock() {}

    public static BuildService.LockGuard ensureWorkspaceLockFresh(Path root, JkBuild rootBuild, Path cache) {
        Path rootLock = LockPaths.lockFile(root);
        return ensureWorkspaceLockFresh(root, cache, workspaceLockStale(root, rootBuild, rootLock));
    }

    /**
     * As {@link #ensureWorkspaceLockFresh(Path, JkBuild, Path)} with the staleness answer already
     * computed — callers that just priced the re-lock for the ETA pass it in instead of
     * re-hashing every manifest.
     */
    public static BuildService.LockGuard ensureWorkspaceLockFresh(Path root, Path cache, boolean stale) {
        return ensureWorkspaceLockFresh(root, cache, stale, null);
    }

    /** As above, resolving against {@code repoUrl} alone when given (a sync's test override). */
    public static BuildService.LockGuard ensureWorkspaceLockFresh(
            Path root, Path cache, boolean stale, @Nullable URI repoUrl) {
        if (!stale) return BuildService.LockGuard.OK;
        // A lock on disk that could not be updated is named as stale; with none yet, the
        // resolver's reason alone says why the first lock failed.
        boolean onDisk = Files.isRegularFile(LockPaths.lockFile(root));
        // Why it is stale, asked before the update rewrites it: a moved manifest, or an install.
        String why = onDisk ? AutoLock.staleReason(root, LockPaths.lockFile(root)) : null;
        String staleness = why == null ? LockFreshness.STALE : why;
        try {
            // noDefaultFeatures=false: every freshen resolves with the same feature selection as
            // explicit `jk lock`, so lock content never depends on which path freshened.
            LockFlow.Result r = LockFlow.run(root, cache, List.of(), false, repoUrl);
            if (r.status() == 0) return BuildService.LockGuard.OK;
            return new BuildService.LockGuard(
                    r.status(), onDisk ? AutoLock.RefreshFailed.message(staleness, r.error()) : r.error(), null, "");
        } catch (UnsatisfiableException e) {
            return new BuildService.LockGuard(
                    Exit.RESOLVE, e.getMessage(), FailureLocation.of(e, root), e.conflictJson());
        } catch (Exception e) {
            // Never carry on with a stale lock in silence: the build stops, naming why.
            String reason = Errors.text(e);
            return new BuildService.LockGuard(
                    Exit.RESOLVE, onDisk ? AutoLock.RefreshFailed.message(staleness, reason) : reason, null, "");
        }
    }

    /**
     * Remaining-work estimate for a workspace re-lock (ms). Composes host atomized rates from
     * {@link cc.jumpkick.cache.LockTimings} (graph/materialize per package + fixed overhead) scaled by
     * this project's known package count or declared roots. Project-specific whole-lock history is a
     * soft clamp only when the composed figure is absurdly low vs a stable prior of similar size.
     * Never 0 when a re-lock is needed (avoids pure count-up).
     */
    static long estimateLockMillis(Path entryDir, Path cache) {
        int packages = 0;
        int declared = 0;
        try {
            Path lockFile = LockPaths.lockFile(entryDir);
            if (Files.isRegularFile(lockFile)) {
                packages = LockfileReader.read(lockFile).artifacts().size();
            }
        } catch (Exception e) {
            // unknown package count
            Log.debug("estimateLockMillis: unknown package count", e);
        }
        try {
            if (entryDir != null) {
                Path toml = ManifestPaths.manifestIn(entryDir);
                if (Files.isRegularFile(toml)) {
                    JkBuild b = JkBuildParser.parseLocal(toml);
                    // Workspace root: merge is done at lock time; package count from the existing
                    // root lock (above) is the best size signal. Declared roots = rough cold seed.
                    for (var scope : Scope.values()) {
                        if (scope == Scope.PLATFORM || scope == Scope.MANAGED) continue;
                        declared += b.dependencies().of(scope).size();
                    }
                }
            }
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (Exception e) {
            // unknown declared count
            Log.debug("estimateLockMillis: unknown declared count", e);
        }
        long composed = LockTimings.estimateMillis(declared, packages);
        // Soft floor from this project's prior whole-lock walls (same dir) — only when composition
        // under-shoots a stable measured average by a wide margin (never pull a large monorepo down).
        try {
            String dir = entryDir == null
                    ? ""
                    : entryDir.toAbsolutePath().normalize().toString();
            BuildMetrics.Stats hist = BuildMetrics.current()
                    .invocation("lock", dir)
                    .map(BuildMetrics.Entry::ok)
                    .orElse(BuildMetrics.Stats.EMPTY);
            if (hist.count() >= 2 && hist.avgMillis() > composed * 2) {
                // Prefer composition for size-aware ETA; only lift when history says we routinely
                // take much longer (e.g. cold-ish CAS on this host for this graph).
                composed = Math.round(0.35 * hist.avgMillis() + 0.65 * composed);
            }
        } catch (RuntimeException e) {
            // ignore
            Log.debug("estimateLockMillis: ignore", e);
        }
        return Math.max(200, composed);
    }

    /**
     * True when {@code rootLock} is absent or older than the root manifest or any declared member
     * manifest — i.e. the merged workspace lock no longer reflects the manifests it was derived from.
     */
    public static boolean workspaceLockStale(Path root, JkBuild rootBuild, Path rootLock) {
        // rootBuild is unused for the check — member list comes from the live root manifest inside
        // LockFreshness (digest-aware, clone-safe). Kept on the signature for call-site compat.
        return LockFreshness.workspaceLockStale(root, rootLock)
                || LocalInstall.staleReason(rootLock, JkStores.store()) != null;
    }
}

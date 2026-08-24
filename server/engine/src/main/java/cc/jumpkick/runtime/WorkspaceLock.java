// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.LockTimings;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.NullMarked;

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
        if (!stale) return BuildService.LockGuard.OK;
        long t0 = System.nanoTime();
        try {
            // noDefaultFeatures=false: every freshen resolves with the same feature selection as
            // explicit `jk lock`, so lock content never depends on which path freshened.
            LockFlow.Result r = LockFlow.run(root, cache, List.of(), false, null, /* conservative */ true);
            if (r.status() == 0) {
                recordLockSuccess(root, (System.nanoTime() - t0) / 1_000_000L);
            }
            return r.status() != 0 ? new BuildService.LockGuard(r.status(), r.error()) : BuildService.LockGuard.OK;
        } catch (UnsatisfiableException e) {
            return new BuildService.LockGuard(6, e.getMessage());
        } catch (Exception e) {
            return BuildService.LockGuard.OK; // soft failure — let the per-module path surface real errors
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
        } catch (Exception ignored) {
            // unknown package count
        }
        try {
            if (entryDir != null) {
                Path toml = entryDir.resolve(ManifestPaths.MANIFEST);
                if (Files.isRegularFile(toml)) {
                    JkBuild b = JkBuildParser.parseLocal(toml);
                    // Workspace root: merge is done at lock time; package count from the existing
                    // root lock (above) is the best size signal. Declared roots = rough cold seed.
                    for (var scope : Scope.values()) {
                        if (scope == Scope.PLATFORM) continue;
                        declared += b.dependencies().of(scope).size();
                    }
                }
            }
        } catch (Exception ignored) {
            // unknown declared count
        }
        long composed = LockTimings.estimateMillis(declared, packages);
        // Soft floor from this project's prior whole-lock walls (same dir) — only when composition
        // under-shoots a stable measured average by a wide margin (never pull a large monorepo down).
        try {
            String dir = entryDir == null
                    ? ""
                    : entryDir.toAbsolutePath().normalize().toString();
            BuildMetrics.Stats hist = BuildMetrics.load(BuildMetrics.defaultFile())
                    .invocation("lock", dir)
                    .map(BuildMetrics.Entry::ok)
                    .orElse(BuildMetrics.Stats.EMPTY);
            if (hist.count() >= 2 && hist.avgMillis() > composed * 2) {
                // Prefer composition for size-aware ETA; only lift when history says we routinely
                // take much longer (e.g. cold-ish CAS on this host for this graph).
                composed = Math.round(0.35 * hist.avgMillis() + 0.65 * composed);
            }
        } catch (RuntimeException ignored) {
            // ignore
        }
        return Math.max(200, composed);
    }

    /** Fold a successful lock wall into BuildMetrics under kind {@code lock} (project tier). */
    private static void recordLockSuccess(Path entryDir, long millis) {
        if (millis <= 0 || entryDir == null) return;
        try {
            String dir = entryDir.toAbsolutePath().normalize().toString();
            BuildMetrics.record(
                    BuildMetrics.defaultFile(),
                    new BuildMetrics.Outcome("lock", dir, null, true, false, millis, List.of()),
                    System.currentTimeMillis());
        } catch (Exception ignored) {
            // never fail a build over metrics I/O
        }
    }

    /**
     * True when {@code rootLock} is absent or older than the root manifest or any declared member
     * manifest — i.e. the merged workspace lock no longer reflects the manifests it was derived from.
     */
    public static boolean workspaceLockStale(Path root, JkBuild rootBuild, Path rootLock) {
        // rootBuild is unused for the check — member list comes from the live root manifest inside
        // LockFreshness (digest-aware, clone-safe). Kept on the signature for call-site compat.
        return LockFreshness.workspaceLockStale(root, rootLock);
    }
}

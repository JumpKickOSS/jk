// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Auto-lock: when {@code jk.toml} no longer matches the lock's digest, transparently re-locks
 * before any command reads the lockfile. This is {@link LockPipeline} under {@link
 * LockMode.Freshen} — locked versions become soft preferences fed into PubGrub's candidate
 * ordering, so the solver selects the locked version first and only versions that conflict with a
 * new or changed constraint are bumped. Deps removed from {@code jk.toml} are dropped from the
 * lock; new deps resolve normally. {@code jk lock -F} and {@code jk update} are the other arm:
 * full fresh resolution, always the latest compatible versions.
 */
public final class AutoLock {

    private AutoLock() {}

    /**
     * Returns {@code true} when the lock no longer matches its manifests (content digest), or the
     * lock lacks a valid {@code manifests-sha256} (always re-lock). See
     * {@link LockFreshness#isStale}.
     */
    public static boolean isStale(Path dir, Path lockFile) {
        return LockFreshness.isStale(dir, lockFile);
    }

    /**
     * If {@code lockFile} is stale (jk.toml changed), performs a keep-pins re-lock, writes the
     * updated lock file, and returns the new {@link Lockfile}. Returns {@code null} if the lock is
     * up-to-date or if re-locking fails (the caller should fall back to reading the existing lock and
     * optionally surface a warning).
     *
     * @param dir project root (contains {@code jk.toml}); a workspace member redirects to its root
     * @param existing current contents of {@code jk-lock.toml} — the pins the solver prefers
     * @param lockFile path to {@code jk-lock.toml} (used for the staleness probe)
     * @param cache jk CAS directory
     * @param repoUrl optional single-URL override (tests / CI)
     * @param features active feature flags
     * @param withDefaults whether to include the project's default features
     * @param observer resolver progress callbacks
     * @param warn sink for a soft-failure warning (one line per call); the engine must NOT write to
     * {@code System.out}/{@code System.err}, so callers route this to the view layer (e.g. {@code
     * ctx::output}). May be {@code null} to discard.
     */
    public static @Nullable Lockfile maybeReLock(
            Path dir,
            Lockfile existing,
            Path lockFile,
            Path cache,
            @Nullable URI repoUrl,
            @Nullable Collection<String> features,
            boolean withDefaults,
            ResolveObserver observer,
            @Nullable Consumer<String> warn) {
        if (!isStale(dir, lockFile)) return null;
        // Serialize per lock dir; a concurrent job may have freshened while we waited.
        synchronized (LockGate.monitorFor(lockFile.toAbsolutePath().normalize().getParent())) {
            if (!isStale(dir, lockFile)) {
                try {
                    return LockfileReader.read(lockFile);
                } catch (Exception ignored) {
                    // unreadable — fall through and re-lock
                }
            }
            return reLock(dir, existing, cache, repoUrl, features, withDefaults, observer, warn);
        }
    }

    private static @Nullable Lockfile reLock(
            Path dir,
            Lockfile existing,
            Path cache,
            @Nullable URI repoUrl,
            @Nullable Collection<String> features,
            boolean withDefaults,
            ResolveObserver observer,
            @Nullable Consumer<String> warn) {
        try {
            // One lock scope, shared with every other lock entry point: a workspace member (or
            // root) resolves the merged union at the root — a module-scoped relock
            // must never overwrite the root jk-lock.toml with one module's closure.
            LockPlans.LockScope scope = LockPlans.lockScope(dir);
            LockPipeline pipeline = new LockPipeline(
                    scope.lockDir(),
                    scope.effective(),
                    cache,
                    repoUrl,
                    features == null ? List.of() : List.copyOf(features),
                    withDefaults,
                    new LockMode.Freshen());
            return pipeline.run(existing, observer, LockPipeline.Progress.SILENT);
        } catch (UnsatisfiableException e) {
            // Hard failure: dependencies are genuinely unsatisfiable — re-throw so
            // the build fails instead of silently continuing with a stale lock.
            throw e;
        } catch (Exception e) {
            // Soft failure (network, I/O, etc.): warn and fall back to the existing
            // lock so a transient connectivity issue doesn't block the build. The engine
            // is a server — route the warning through the caller's sink (the view layer
            // owns the terminal streams) instead of touching System.err.
            if (warn != null) {
                warn.accept("‼ jk: auto-lock warning — could not update jk-lock.toml: " + e.getMessage());
                warn.accept("    Run `jk lock` to resolve manually.");
            }
            return null;
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockRewriteGuard;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.StaleLock;
import cc.jumpkick.repo.LocalInstall;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.runtime.base.LockGate;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.task.ToolchainPolicy;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
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
     * Make the JDK a build runs on what {@link LockFreshness} holds a lock's Gradle variants to:
     * the one {@link LockPipeline} chooses them for.
     */
    public static void installBuildJdk() {
        LockFreshness.installBuildJdk(AutoLock::buildJdkLevel);
    }

    /** The feature version of the JDK the build at {@code owner} runs on; 0 when none resolves. */
    static int buildJdkLevel(Path owner) {
        try {
            return ToolchainPolicy.level(0, JavaHomes.resolveJavaHome(owner));
        } catch (RuntimeException unresolved) {
            Log.debug("buildJdkLevel: no JDK resolves", unresolved);
            return 0;
        }
    }

    /**
     * Returns {@code true} when the lock no longer matches its manifests (content digest), the
     * lock lacks a valid {@code manifests-sha256} (always re-lock), or a row this machine's
     * installs served no longer has its bytes there ({@link LocalInstall}). See {@link #staleReason}.
     */
    public static boolean isStale(Path dir, Path lockFile) {
        return staleReason(dir, lockFile) != null;
    }

    /**
     * Why the lock is stale, the sentence the build, {@code jk explain} and {@code jk doctor} give,
     * or null when it is not: {@link LockFreshness#STALE} for moved manifests, else the install a
     * row came from that changed ({@link LocalInstall#staleReason}).
     */
    public static @Nullable String staleReason(Path dir, Path lockFile) {
        return LocalInstall.lockStaleReason(dir, lockFile, JkStores.store());
    }

    /**
     * If {@code lockFile} is stale (jk.toml changed), performs a keep-pins re-lock, writes the
     * updated lock file, and returns the new {@link Lockfile}. Returns {@code null} if the lock is
     * up-to-date. A re-lock that cannot resolve (a host unreachable or denied, an artifact missing,
     * an empty store offline) throws {@link RefreshFailed} naming the stale lock and the reason: a
     * build never carries on silently with pins jk.toml no longer names. Offline, a lock whose pins
     * still cover jk.toml from the local store is kept, unstamped, with one warning
     * ({@link LockPipeline}).
     *
     * @param dir project root (contains {@code jk.toml}); a workspace member redirects to its root
     * @param existing current contents of {@code jk-lock.toml} — the pins the solver prefers
     * @param lockFile path to {@code jk-lock.toml} (used for the staleness probe)
     * @param cache jk CAS directory
     * @param repoUrl optional single-URL override (tests / CI)
     * @param features active feature flags
     * @param withDefaults whether to include the project's default features
     * @param observer resolver progress callbacks
     */
    public static @Nullable Lockfile maybeReLock(
            Path dir,
            Lockfile existing,
            Path lockFile,
            Path cache,
            @Nullable URI repoUrl,
            @Nullable Collection<String> features,
            boolean withDefaults,
            ResolveObserver observer) {
        if (!isStale(dir, lockFile)) return null;
        // Serialize per lock owner; a concurrent job may have freshened while we waited.
        synchronized (LockGate.monitorFor(LockPaths.lockOwnerDir(dir))) {
            String stale = staleReason(dir, lockFile);
            if (stale == null) {
                try {
                    return LockfileReader.read(lockFile);
                } catch (Exception e) {
                    // unreadable — fall through and re-lock
                    Log.debug("maybeReLock: unreadable", e);
                }
            }
            return reLock(
                    dir,
                    existing,
                    cache,
                    repoUrl,
                    features,
                    withDefaults,
                    observer,
                    stale == null ? LockFreshness.STALE : stale);
        }
    }

    private static Lockfile reLock(
            Path dir,
            Lockfile existing,
            Path cache,
            @Nullable URI repoUrl,
            @Nullable Collection<String> features,
            boolean withDefaults,
            ResolveObserver observer,
            String stale) {
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
        } catch (UnsatisfiableException | LockRewriteGuard.LockRewriteRefused | UserConfigParseException e) {
            // Their own failures, with their own message: dependencies genuinely unsatisfiable, a
            // lock a newer jk wrote, or a config.toml that does not parse (with its position).
            throw e;
        } catch (Exception e) {
            // Network, a denied host, a missing artifact, an empty store: the build stops here
            // rather than run on pins jk.toml no longer names. Carrying on cost a CI run its
            // cause: a test failed far from it, and the next checkout's forecast waited on the
            // same update.
            throw new RefreshFailed(stale, e);
        }
    }

    /** A stale lock the build could not update: the message names the lock, the reason and the way out. */
    public static final class RefreshFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RefreshFailed(String stale, Exception cause) {
            super(message(stale, Errors.text(cause)), cause);
        }

        /** What a build says when it stops on a stale lock it could not update for {@code reason}. */
        public static String message(@Nullable String reason) {
            return message(LockFreshness.STALE, reason);
        }

        /**
         * As {@link #message(String)} for a lock stale for {@code stale} ({@link #staleReason}): a
         * moved manifest, or an install a row came from that this machine no longer holds.
         */
        public static String message(String stale, @Nullable String reason) {
            return StaleLock.refreshFailed(stale, reason);
        }
    }
}

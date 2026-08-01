// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.resolver.LockOrchestrator;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.task.AccessLedger;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Auto-lock: when {@code jk.toml} is newer than {@code jk-lock.toml}, transparently re-locks with a
 * conservative strategy before any command reads the lockfile.
 *
 * <h3>Conservative vs explicit lock</h3>
 *
 * <ul>
 * <li><b>Auto (conservative)</b> — locked versions are used as soft preferences fed into
 * PubGrub's candidate ordering. The solver selects the locked version first; only versions
 * that conflict with a new or changed constraint are bumped. Deps removed from {@code
 * jk.toml} are dropped from the lock. New deps are resolved normally.
 * <li><b>Explicit {@code jk lock}</b> — full fresh resolution, no version preferences; always
 * picks the latest compatible versions.
 * </ul>
 */
public final class AutoLock {

    private AutoLock() {}

    /**
     * Returns {@code true} when the lock no longer matches its manifests (content digest), or the
     * lock lacks a valid {@code manifests-sha256} (always re-lock). See
     * {@link cc.jumpkick.lock.LockFreshness#isStale}.
     */
    public static boolean isStale(Path dir, Path lockFile) {
        return cc.jumpkick.lock.LockFreshness.isStale(dir, lockFile);
    }

    /**
     * If {@code lockFile} is stale (jk.toml newer), performs a conservative re-lock, writes the
     * updated lock file, and returns the new {@link Lockfile}. Returns {@code null} if the lock is
     * up-to-date or if re-locking fails (the caller should fall back to reading the existing lock and
     * optionally surface a warning).
     *
     * @param dir project root (contains {@code jk.toml})
     * @param existing current contents of {@code jk-lock.toml}
     * @param lockFile path to {@code jk-lock.toml} (will be overwritten)
     * @param cache jk CAS directory
     * @param repoUrl optional single-URL override (tests / CI)
     * @param jkVersion version string stamped in the lockfile header
     * @param features active feature flags
     * @param withDefaults whether to include the project's default features
     * @param observer resolver progress callbacks
     * @param warn sink for a soft-failure warning (one line per call); the engine must NOT write to
     * {@code System.out}/{@code System.err}, so callers route this to the view layer (e.g. {@code
     * ctx::output}). May be {@code null} to discard.
     */
    public static Lockfile maybeReLock(
            Path dir,
            Lockfile existing,
            Path lockFile,
            Path cache,
            URI repoUrl,
            String jkVersion,
            Collection<String> features,
            boolean withDefaults,
            ResolveObserver observer,
            Consumer<String> warn) {
        if (!isStale(dir, lockFile)) return null;
        // Serialize per lock dir (JK-1356); a concurrent job may have freshened while we waited.
        synchronized (LockGate.monitorFor(lockFile.toAbsolutePath().normalize().getParent())) {
            if (!isStale(dir, lockFile)) {
                try {
                    return LockfileReader.read(lockFile);
                } catch (Exception ignored) {
                    // unreadable — fall through and re-lock
                }
            }
            return reLock(dir, existing, lockFile, cache, repoUrl, jkVersion, features, withDefaults, observer, warn);
        }
    }

    private static Lockfile reLock(
            Path dir,
            Lockfile existing,
            Path lockFile,
            Path cache,
            URI repoUrl,
            String jkVersion,
            Collection<String> features,
            boolean withDefaults,
            ResolveObserver observer,
            Consumer<String> warn) {
        try {
            // One lock scope, shared with every other lock entry pointa workspace
            // member (or root) resolves the merged union at the root — a module-scoped
            // conservative relock must never overwrite the root jk-lock.toml with one module's
            // closure.
            LockPipelines.LockScope scope = LockPipelines.lockScope(dir);
            JkBuild effective = scope.effective();
            Path scopeDir = scope.lockDir();

            // Digest captured before resolving: a manifest edit mid-re-lock must leave a lock
            // that reads as stale (JK-1357).
            String manifestsSha = cc.jumpkick.lock.LockManifestDigest.compute(scopeDir);
            Cas cas = JkStores.cas(cache);
            cc.jumpkick.repo.RepoGroup repos =
                    RepoGroupBuilder.buildFor(effective, repoUrl, cas, cc.jumpkick.config.BuildEnv.forModule(scopeDir));
            LockOrchestrator orchestrator = new LockOrchestrator(repos)
                    .withProjectDir(scopeDir)
                    .withJvmEnvironment(PluginContributions.jvmEnvironment(effective, scopeDir))
                    .withPlatformPolicy(effective.build().platformPolicy())
                    .withUnmappedPolicy(effective.build().unmappedPolicy());

            Lockfile updated = orchestrator.lockConservative(
                    effective, existing, jkVersion, features == null ? List.of() : features, withDefaults, observer);

            // Stamp git-source provenance using already-locked SHAs (no re-fetch).
            java.util.Map<String, String> lockedShas = GitSourceResolution.lockedImmutableShas(existing);
            cc.jumpkick.runtime.GitSourceResolution.Prepared prep;
            try {
                prep = GitSourceResolution.prepare(
                        effective, repos, cas, JavaHomes.runningJavaHome(), jkVersion, lockedShas);
                updated = GitSourceResolution.stamp(updated, prep.gitInfoByKey());
            } catch (Exception ignored) {
                // Git-source stamping is best-effort in auto-lock
            }

            // Preserve Kotlin version if the lock already has one and the project
            // didn't change its kotlin selector.
            if (updated.kotlin() == null && existing.kotlin() != null) {
                updated = updated.withKotlin(existing.kotlin());
            }

            // Auto-relock fires precisely when jk.toml is newer than the lock — i.e. right
            // after identity edits. lockConservative carries the OLD lock's [[module]] pins
            // through, so stamp fresh identity like every explicit lock-write path does, or
            // a version/group bump stays frozen in the lockfile until a manual `jk lock`.
            updated = cc.jumpkick.lock.LockfileModules.stamp(updated, scopeDir);

            LockfileWriter.write(updated, lockFile, manifestsSha);
            AccessLedger.atDefaultPath().touchLock(updated);
            return updated;
        } catch (cc.jumpkick.resolver.pubgrub.UnsatisfiableException e) {
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

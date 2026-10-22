// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.manifest.PluginContributions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Whether {@code jk-lock.toml} still matches the manifests it was derived from.
 *
 * <p>Staleness is content-addressed via {@code manifests-sha256}: a live digest of every
 * {@code jk.toml} that feeds the lock is compared to the stamp in the lockfile. Survives fresh
 * git clones (equalized mtimes). Locks <em>without</em> a valid digest are always stale — re-lock
 * once to stamp one; there is no mtime fallback.
 *
 * <p>A clean digest is necessary but not sufficient: a lock can also be missing a <em>fact</em>
 * jk writes for the current manifests — the {@code [native] metadata-repository} pin, the {@code
 * [node]} pin, a {@code [[kotlin-plugins]]} jar's {@code [[plugin]]} row, a plugin tool's
 * {@code [[tool]]} row — and {@link #isStale} rules such a lock stale.
 */
public final class LockFreshness {

    private LockFreshness() {}

    /**
     * True when {@code lockFile} is missing, or when the lock no longer reflects manifests under
     * its owner directory (workspace root or standalone project).
     */
    public static boolean needsRefresh(Path projectDir) {
        Path owner = LockPaths.lockOwnerDir(projectDir);
        Path lockFile = LockPaths.lockFile(owner);
        if (!Files.isRegularFile(lockFile)) return true;
        return workspaceLockStale(owner, lockFile);
    }

    /**
     * True when {@code rootLock} is absent or no longer matches the root manifest or any declared
     * workspace member manifest. One digest comparison covers everything: the digest is
     * workspace-wide (owner = the lock's directory) and already folds member and path-dep
     * manifests in, so a per-module loop would recompute the identical digest N times.
     */
    public static boolean workspaceLockStale(Path root, Path rootLock) {
        if (!Files.exists(rootLock)) return true;
        return isStale(root, rootLock);
    }

    /**
     * Staleness of the lock against live manifests.
     *
     * <ul>
     *   <li>Valid {@code manifests-sha256} present: stale when the live digest of the lock owner
     *       differs.
     *   <li>Missing, blank, or invalid digest: <strong>always stale</strong> (force re-lock to stamp).
     *   <li>Digest clean but the manifests build a native image and the lock carries no
     *       {@code [native]} pin: stale (re-lock to stamp the pin).
     *   <li>Digest clean but a manifest declares {@code node} and the lock carries no {@code [node]}
     *       pin: stale.
     *   <li>Digest clean but a manifest declares a {@code [[kotlin-plugins]]} jar the lock pins no
     *       digest for: stale.
     *   <li>Digest clean but an active plugin fetches a tool artifact the lock has no {@code
     *       [[tool]]} row for: stale.
     * </ul>
     *
     * <p>Both the project {@code jk.toml} and the lock must exist for a non-stale answer; missing
     * lock is handled by {@link #needsRefresh}. Unreadable lock or digest recompute failure → stale
     * (fail closed).
     */
    public static boolean isStale(Path dir, Path lockFile) {
        try {
            Path buildFile = ManifestPaths.manifestIn(dir);
            if (!Files.exists(buildFile) || !Files.exists(lockFile)) return false;

            Lockfile lock = LockfileReader.read(lockFile);
            String stored = lock.manifestsSha256();
            if (stored == null || !isValidDigest(stored)) {
                return true; // no trustworthy stamp → re-lock
            }
            Path owner = LockPaths.lockOwnerDir(dir);
            String live = LockManifestDigest.compute(owner);
            if (!stored.equalsIgnoreCase(live)) return true;
            if (missingNativePin(owner, lock)) return true;
            Map<Path, JkBuild> modules = modules(owner);
            return missingNodePin(modules.values(), lock)
                    || missingKotlinPluginPin(modules.values(), lock)
                    || missingToolPin(owner, modules, lock);
        } catch (Exception e) {
            return true; // unreadable lock / digest failure → re-lock
        }
    }

    /**
     * True when the manifests under {@code owner} build a native image but {@code lock} carries no
     * {@code [native] metadata-repository} pin. Conflicting member selectors are also stale — fail
     * closed; the re-lock surfaces {@link LockNativePin}'s conflict message.
     */
    private static boolean missingNativePin(Path owner, Lockfile lock) throws IOException {
        if (lock.nativeMetadata() != null) return false;
        try {
            return LockNativePin.selector(owner).isPresent();
        } catch (IllegalStateException conflictingSelectors) {
            return true;
        }
    }

    /** The manifest under {@code owner} and, for a workspace root, every member's, by directory. */
    private static Map<Path, JkBuild> modules(Path owner) throws IOException {
        Path dir = owner.toAbsolutePath().normalize();
        JkBuild root = JkBuildParser.parseLocal(ManifestPaths.manifestIn(dir));
        Map<Path, JkBuild> out = new LinkedHashMap<>();
        out.put(dir, root);
        if (root.isWorkspaceRoot()) out.putAll(WorkspaceLoader.loadModules(dir, root));
        return out;
    }

    /** True when a manifest declares a Node.js version and {@code lock} pins none. */
    private static boolean missingNodePin(Collection<JkBuild> modules, Lockfile lock) {
        if (lock.node() != null) return false;
        for (JkBuild module : modules) {
            if (!module.project().nodeSpec().isEmpty()) return true;
        }
        return false;
    }

    /**
     * True when a manifest declares a {@code [[kotlin-plugins]]} coordinate {@code lock} has no
     * digest-pinned {@code [[plugin]]} row for. A coordinate without a version matches any row of
     * its {@code group:artifact}: its version is the locked compiler's.
     */
    private static boolean missingKotlinPluginPin(Collection<JkBuild> modules, Lockfile lock) {
        for (JkBuild module : modules) {
            for (BuildBlock.KotlinPluginDecl decl : module.build().kotlinPlugins()) {
                String[] parts = decl.coordinate().split(":");
                String ga = parts[0] + ":" + parts[1];
                boolean pinned = lock.plugins().stream()
                        .anyMatch(row -> row.coordinate().equals(ga)
                                && row.sha256Hex() != null
                                && (parts.length < 3 || row.version().equals(parts[2])));
                if (!pinned) return true;
            }
        }
        return false;
    }

    /**
     * True when an active plugin of a module fetches a tool artifact by coordinate that {@code lock}
     * has no {@code [[tool]]} row for — the module, version and type; a floating version matches any
     * row of its module. Which platforms a row covers is the build's question, not freshness's.
     */
    private static boolean missingToolPin(Path owner, Map<Path, JkBuild> modules, Lockfile lock) {
        Path root = owner.toAbsolutePath().normalize();
        for (Map.Entry<Path, JkBuild> module : modules.entrySet()) {
            Path dir = module.getKey().toAbsolutePath().normalize();
            Lockfile view = dir.equals(root)
                    ? lock
                    : lock.forMember(root.relativize(dir).toString().replace('\\', '/'));
            for (PluginContributions.LockedArtifact tool :
                    PluginContributions.lockedArtifacts(module.getValue(), dir, view.platformPins())) {
                if (!tool.host()) continue;
                Coordinate coord = Coordinate.parse(tool.coordinate());
                String exact =
                        VersionSelector.parse(coord.version()) instanceof VersionSelector.Exact e ? e.version() : null;
                boolean pinned = lock.tools().stream()
                        .anyMatch(row -> row.coordinate().equals(coord.module())
                                && row.type().equals(coord.type())
                                && (exact == null || row.version().equals(exact)));
                if (!pinned) return true;
            }
        }
        return false;
    }

    /** {@code true} when {@code hex} is a 64-char hex SHA-256 (case-insensitive). */
    static boolean isValidDigest(@Nullable String hex) {
        if (hex == null || hex.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = hex.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lower = c >= 'a' && c <= 'f';
            boolean upper = c >= 'A' && c <= 'F';
            if (!digit && !lower && !upper) return false;
        }
        return true;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content digest of every {@code jk.toml} that feeds a workspace (or standalone) lock.
 *
 * <p>Survives fresh git clones where mtimes are equalized and would hide a lock that no longer
 * matches its manifests. Stored as {@code manifests-sha256} in {@code jk-lock.toml} (schema v1
 * additive field).
 */
public final class LockManifestDigest {

    private LockManifestDigest() {}

    /**
     * Hex SHA-256 of the sorted set of relative path → file bytes for every manifest that
     * contributes to the lock owned by {@code lockOwnerDir}.
     *
     * <p>Workspace root: root {@code jk.toml} plus each {@code workspace.modules} member's
     * {@code jk.toml}. Standalone: just that project's {@code jk.toml}.
     */
    public static String compute(Path lockOwnerDir) throws IOException {
        Path owner = lockOwnerDir.toAbsolutePath().normalize();
        Map<String, byte[]> parts = new LinkedHashMap<>();
        Path rootToml = owner.resolve("jk.toml");
        if (Files.isRegularFile(rootToml)) {
            parts.put("jk.toml", Files.readAllBytes(rootToml));
            try {
                JkBuild root = JkBuildParser.parseLocal(rootToml);
                if (root.isWorkspaceRoot()) {
                    for (Path modDir : WorkspaceLoader.loadModules(owner, root).keySet()) {
                        Path mt = modDir.resolve("jk.toml");
                        if (!Files.isRegularFile(mt)) continue;
                        String rel = owner.relativize(mt).toString().replace('\\', '/');
                        parts.put(rel, Files.readAllBytes(mt));
                    }
                }
            } catch (RuntimeException e) {
                // parse failure: still digest whatever files we found
            }
        }
        return hashParts(parts);
    }

    /** Stable digest of path → content map (sorted by path). */
    static String hashParts(Map<String, byte[]> parts) {
        List<String> keys = new ArrayList<>(parts.keySet());
        keys.sort(Comparator.naturalOrder());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String key : keys) {
                md.update(key.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(parts.get(key));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }

    /** Stamp {@code lock} with a live digest of {@code lockOwnerDir}'s manifests. */
    public static Lockfile stamp(Lockfile lock, Path lockOwnerDir) {
        try {
            return lock.withManifestsSha256(compute(lockOwnerDir));
        } catch (Exception e) {
            return lock;
        }
    }
}

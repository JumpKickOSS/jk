// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import java.io.ByteArrayOutputStream;
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
 * Content digest of every {@code jk.toml} that feeds a workspace (or standalone) lock — the owner
 * manifest, each {@code workspace.modules} member's manifest, and first-level path-source
 * dependency manifests. Bytes are CRLF-normalized so mixed-OS checkouts hash identically.
 *
 * <p>Survives fresh git clones where mtimes are equalized and would hide a lock that no longer
 * matches its manifests. Stored as {@code manifests-sha256} in {@code jk-lock.toml} (schema v1
 * additive field).
 */
public final class LockManifestDigest {

    private LockManifestDigest() {}

    /**
     * Hex SHA-256 of the sorted set of relative path → normalized file bytes for every manifest
     * that contributes to the lock owned by {@code lockOwnerDir}.
     *
     * @throws IOException when a contributing manifest exists but cannot be read — callers must
     *     surface this rather than write an unstamped (permanently-stale) lock
     */
    public static String compute(Path lockOwnerDir) throws IOException {
        Path owner = lockOwnerDir.toAbsolutePath().normalize();
        Map<String, byte[]> parts = new LinkedHashMap<>();
        Path rootToml = owner.resolve("jk.toml");
        if (Files.isRegularFile(rootToml)) {
            parts.put("jk.toml", normalized(Files.readAllBytes(rootToml)));
            try {
                JkBuild root = JkBuildParser.parseLocal(rootToml);
                addPathDepManifests(parts, owner, owner, root);
                if (root.isWorkspaceRoot()) {
                    for (Map.Entry<Path, JkBuild> member :
                            WorkspaceLoader.loadModules(owner, root).entrySet()) {
                        Path mt = member.getKey().resolve("jk.toml");
                        if (!Files.isRegularFile(mt)) continue;
                        String rel = owner.relativize(mt).toString().replace('\\', '/');
                        parts.put(rel, normalized(Files.readAllBytes(mt)));
                        addPathDepManifests(parts, owner, member.getKey(), member.getValue());
                    }
                }
            } catch (RuntimeException e) {
                // parse failure: still digest whatever files we found
            }
        }
        // The workspace catalog layer (jk-libs.toml) changes how short names resolve to
        // group:artifact, so a pin edit must flip staleness exactly like a manifest edit —
        // without this the lock kept resolving the old GA while looking fresh.
        Path libs = owner.resolve("jk-libs.toml");
        if (Files.isRegularFile(libs)) {
            parts.put("jk-libs.toml", normalized(Files.readAllBytes(libs)));
        }
        return hashParts(parts);
    }

    /**
     * First-level path-source dependency manifests: their versions and deps feed the lock exactly
     * like member manifests do, so an edit must flip staleness.
     */
    private static void addPathDepManifests(Map<String, byte[]> parts, Path owner, Path declaringDir, JkBuild build)
            throws IOException {
        for (List<Dependency> deps : build.dependencies().byScope().values()) {
            for (Dependency d : deps) {
                if (!d.isPath()) continue;
                Path toml = declaringDir
                        .resolve(d.pathSource().rawPath())
                        .normalize()
                        .resolve("jk.toml");
                if (!Files.isRegularFile(toml)) continue;
                String key;
                try {
                    key = owner.relativize(toml).toString().replace('\\', '/');
                } catch (IllegalArgumentException e) {
                    // Outside the workspace root: key by the declared path (machine-stable).
                    key = "path:" + d.pathSource().rawPath().replace('\\', '/');
                }
                if (!parts.containsKey(key)) parts.put(key, normalized(Files.readAllBytes(toml)));
            }
        }
    }

    /** CRLF → LF so autocrlf checkouts hash identically to the committed LF form. */
    static byte[] normalized(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '\r' && i + 1 < raw.length && raw[i + 1] == '\n') continue;
            out.write(raw[i]);
        }
        return out.toByteArray();
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

    /**
     * Stamp {@code lock} with a live digest of {@code lockOwnerDir}'s manifests.
     *
     * @throws IOException when the digest cannot be computed — never silently return an unstamped
     *     lock (it would read as permanently stale and force a full re-lock on every command)
     */
    public static Lockfile stamp(Lockfile lock, Path lockOwnerDir) throws IOException {
        return lock.withManifestsSha256(compute(lockOwnerDir));
    }
}

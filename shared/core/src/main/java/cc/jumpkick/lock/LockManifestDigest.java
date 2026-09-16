// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PathSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

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
        Cached hit = MEMO.get(owner);
        if (hit != null && hit.stillMatches()) return hit.digest();

        Map<String, byte[]> parts = new LinkedHashMap<>();
        // Every file whose bytes enter the digest, with the stamp it was read at. That set is what
        // the memo re-validates, so a member added to [workspace] is caught by the root manifest's
        // own stamp changing and a member edited in place by its own.
        List<Stamp> inputs = new ArrayList<>();
        Path rootToml = ManifestPaths.manifestIn(owner);
        if (!Files.isRegularFile(rootToml)) {
            absent(rootToml, inputs);
        } else {
            parts.put(ManifestPaths.MANIFEST, normalized(read(rootToml, inputs)));
            try {
                JkBuild root = JkBuildParser.parseLocal(rootToml);
                addPathDepManifests(parts, owner, owner, root, inputs);
                if (root.isWorkspaceRoot()) {
                    for (Map.Entry<Path, JkBuild> member :
                            WorkspaceLoader.loadModules(owner, root).entrySet()) {
                        Path mt = ManifestPaths.manifestIn(member.getKey());
                        if (!Files.isRegularFile(mt)) {
                            absent(mt, inputs);
                            continue;
                        }
                        String rel = owner.relativize(mt).toString().replace('\\', '/');
                        parts.put(rel, normalized(read(mt, inputs)));
                        addPathDepManifests(parts, owner, member.getKey(), member.getValue(), inputs);
                    }
                }
            } catch (RuntimeException e) {
                // parse failure: still digest whatever files we found
                Log.debug("compute: parse failure", e);
            }
        }
        // The workspace catalog layer (jk-libs.toml) changes how short names resolve to
        // group:artifact, so a pin edit must flip staleness exactly like a manifest edit —
        // without this the lock kept resolving the old GA while looking fresh.
        Path libs = owner.resolve(ManifestPaths.LIBRARIES);
        if (Files.isRegularFile(libs)) {
            parts.put(ManifestPaths.LIBRARIES, normalized(read(libs, inputs)));
        } else {
            absent(libs, inputs);
        }
        String digest = hashParts(parts);
        MEMO.put(owner, new Cached(List.copyOf(inputs), digest));
        return digest;
    }

    /**
     * Memo of {@link #compute(Path)} per lock-owner directory.
     *
     * <p>{@code LockFreshness}'s own javadoc says the digest is workspace-wide, "so a per-module loop
     * would recompute the identical digest N times" — and three per-module call sites did exactly
     * that, on top of the three per build. Each run read <em>every</em> contributing manifest in full.
     * Re-validating the recorded stamps costs one {@code readAttributes} per file instead
     *.
     *
     * <p>Not a {@code StampedMemo}: the stamp here is the set of files the previous run discovered,
     * which is only known after doing the work, so validation has to walk the recorded set rather
     * than be computed up front from a key.
     */
    private static final ConcurrentMap<Path, Cached> MEMO = new ConcurrentHashMap<>();

    /**
     * One path the digest consulted, and what was observed there.
     *
     * <p>Absence is recorded too, with {@code size == ABSENT}. That is not symmetry for its own sake:
     * the first version of this memo only re-stat'ed files that had <em>existed</em>, so creating
     * {@code jk-libs.toml} — which changes short-name resolution and must stale the lock — was
     * invisible to it, and {@code LockFreshnessTest} caught it. Every fixed-path probe and every
     * probed-but-missing member or path-dep manifest is recorded, so a file appearing is a mismatch.
     */
    private record Stamp(Path file, long size, FileTime modified) {

        /** {@link #size} for a path that was consulted and found absent. */
        private static final long ABSENT = -1L;

        /** Distrust size+mtime for a file touched within this window; see {@code JkBuildParser}. */
        private static final long SETTLE_MS = 2_000;

        static Stamp absent(Path file) {
            return new Stamp(file, ABSENT, FileTime.fromMillis(0));
        }

        boolean matchesDisk() {
            try {
                BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
                if (size == ABSENT) return false; // appeared since
                if (a.size() != size || !a.lastModifiedTime().equals(modified)) return false;
                // A same-length edit inside one coarse mtime tick is invisible to size+mtime, and on
                // Windows an editor save followed by a build hits that window. Recompute instead.
                return System.currentTimeMillis() - a.lastModifiedTime().toMillis() >= SETTLE_MS;
            } catch (IOException e) {
                return size == ABSENT; // still absent is a match; unreadable is not
            }
        }
    }

    /** A digest and the exact inputs that produced it. */
    private record Cached(List<Stamp> inputs, String digest) {

        /**
         * Whether every consulted path still looks exactly as it did — same bytes-identity where a
         * file was read, still absent where one was probed and missing. Any doubt recomputes.
         */
        boolean stillMatches() {
            for (Stamp s : inputs) {
                if (!s.matchesDisk()) return false;
            }
            return true;
        }
    }

    /** Record that {@code file} was consulted and found absent. */
    private static void absent(Path file, List<Stamp> inputs) {
        inputs.add(Stamp.absent(file));
    }

    /** Read {@code file}'s bytes and record the identity they were read at. */
    private static byte[] read(Path file, List<Stamp> inputs) throws IOException {
        MANIFEST_READS.incrementAndGet();
        BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
        inputs.add(new Stamp(file, a.size(), a.lastModifiedTime()));
        return Files.readAllBytes(file);
    }

    /** Test seam: drop every memoized digest. */
    public static void clearCache() {
        MEMO.clear();
        MANIFEST_READS.set(0);
    }

    private static final AtomicLong MANIFEST_READS = new AtomicLong();

    /**
     * Test seam: manifest byte-reads since the last {@link #clearCache()}.
     *
     * <p>The property this ticket is about is not the digest's value — that was always right — but how
     * many full reads producing it costs. A memo that recomputed every time would pass every
     * correctness test here.
     */
    public static long manifestReads() {
        return MANIFEST_READS.get();
    }

    /**
     * First-level path-source dependency manifests: their versions and deps feed the lock exactly
     * like member manifests do, so an edit must flip staleness.
     */
    private static void addPathDepManifests(
            Map<String, byte[]> parts, Path owner, Path declaringDir, JkBuild build, List<Stamp> inputs)
            throws IOException {
        for (List<Dependency> deps : build.dependencies().byScope().values()) {
            for (Dependency d : deps) {
                PathSource source = d.pathSource();
                if (!d.isPath() || source == null) continue;
                Path toml = declaringDir.resolve(source.rawPath()).normalize().resolve(ManifestPaths.MANIFEST);
                if (!Files.isRegularFile(toml)) {
                    absent(toml, inputs);
                    continue;
                }
                String key;
                try {
                    key = owner.relativize(toml).toString().replace('\\', '/');
                } catch (IllegalArgumentException e) {
                    // Outside the workspace root: key by the declared path (machine-stable).
                    key = "path:" + source.rawPath().replace('\\', '/');
                }
                if (!parts.containsKey(key)) parts.put(key, normalized(read(toml, inputs)));
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
        MessageDigest md = Hashing.newSha256();
        for (String key : keys) {
            md.update(key.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(parts.get(key));
            md.update((byte) 0);
        }
        return Hashing.hex(md.digest());
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

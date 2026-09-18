// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;

/**
 * Running jk identity for cache keys and engine election. Releases key by {@link JkVersion#VERSION}
 * plus the cache-key salt; {@code -SNAPSHOT} builds also fold in the jar sha256 (12 hex). Empty
 * {@link #buildId()} means no jar identity.
 */
public final class BuildIdentity {

    /**
     * Cache-key salt: turn this in the same commit that changes what any action key hashes. It
     * folds into {@link #cacheKeyVersion()} on the release branch too, where the jar id does not —
     * a rebuilt release otherwise shares the official release's whole cache namespace, and a
     * key-shape change would collide with records the old shape wrote. Not a second product
     * version: never printed, never parsed, never compared on its own.
     */
    static final int CACHE_KEY_SALT = 4;

    private static volatile @Nullable String cachedBuildId;

    /** A stand-in for the derived id; null means derive. */
    private static volatile @Nullable String overrideBuildId;

    private BuildIdentity() {}

    /**
     * Content identity of the running code archive, or {@code ""} when none is derivable. Every
     * artifact-shaped action key ({@code ActionKey.forArtifact}) folds this in as the identity of
     * the code that produced the artifact, so an engine rebuilt from other sources under the same
     * version never restores what the previous engine packaged.
     */
    public static String buildId() {
        String override = overrideBuildId;
        if (override != null) return override;
        String local = cachedBuildId;
        if (local != null) return local;
        synchronized (BuildIdentity.class) {
            if (cachedBuildId == null) cachedBuildId = computeBuildId();
            return cachedBuildId;
        }
    }

    /**
     * Pretend the running code is the archive {@code id} names; {@code null} returns to the
     * derived identity. A test uses it to be "another engine" for one build and watch a packaging
     * key miss; unit tests otherwise run from a classes dir and have no identity to move.
     */
    public static void overrideBuildIdForTests(@Nullable String id) {
        overrideBuildId = id;
    }

    /**
     * The version string to key caches by: {@link JkVersion#VERSION} plus {@link #CACHE_KEY_SALT}
     * for releases; {@code -SNAPSHOT} builds also fold the build id in so a rebuilt engine never
     * restores an older engine's results under the same key.
     */
    public static String cacheKeyVersion() {
        return compose(JkVersion.VERSION, buildId());
    }

    /** Pure composition rule, separated for tests. */
    static String compose(String version, String id) {
        return compose(version, id, CACHE_KEY_SALT);
    }

    /** As {@link #compose(String, String)} with the salt explicit, so tests can turn it. */
    static String compose(String version, String id, int salt) {
        String salted = version + "#" + salt;
        return version.endsWith("-SNAPSHOT") && !id.isEmpty() ? salted + "+" + id : salted;
    }

    private static String computeBuildId() {
        String sha = codeSha256();
        return sha.isEmpty() ? "" : sha.substring(0, 12);
    }

    private static volatile @Nullable String cachedCodeSha;

    /**
     * The full sha256 of the running code archive — what an engine pointer records for its jar —
     * or {@code ""} when none is derivable (a classes directory, a native image). The engine
     * stamps it on every artifact it shelves so a later {@code jk doctor} can compare the shelf's
     * packager with the engine the home names; {@link #buildId()} is its 12-character prefix.
     */
    public static String codeSha256() {
        String local = cachedCodeSha;
        if (local != null) return local;
        synchronized (BuildIdentity.class) {
            if (cachedCodeSha == null) cachedCodeSha = computeCodeSha();
            return cachedCodeSha;
        }
    }

    /**
     * The manifest attribute the packaging writes into the assembly of the module jk installs as
     * its own product lib: the commit time of the checkout the jar was built from, as an ISO-8601
     * instant. Two builds of one version have no order in their digests; this is what orders them,
     * and a reinstall leaves it where a file's mtime would move.
     */
    public static final String BUILD_TIME_ATTRIBUTE = "Build-Time";

    /**
     * When the running code was built — the {@value #BUILD_TIME_ATTRIBUTE} attribute of the code
     * archive's manifest — or {@code null} when the code runs from no archive or from one the
     * packaging did not stamp.
     */
    public static @Nullable Instant builtAt() {
        Path location = codeArchive();
        if (location == null) return null;
        try (JarFile jar = new JarFile(location.toFile())) {
            Manifest manifest = jar.getManifest();
            return manifest == null ? null : builtAt(manifest);
        } catch (IOException e) {
            return null;
        }
    }

    /** {@link #builtAt()} read from {@code manifest}: the attribute as an instant, or null when absent or not one. */
    static @Nullable Instant builtAt(Manifest manifest) {
        String stamp = manifest.getMainAttributes().getValue(BUILD_TIME_ATTRIBUTE);
        if (stamp == null || stamp.isBlank()) return null;
        try {
            return Instant.parse(stamp.trim());
        } catch (DateTimeParseException notAnInstant) {
            return null;
        }
    }

    /** The jar the running code was loaded from, or {@code null} for a classes directory or a native image. */
    private static @Nullable Path codeArchive() {
        try {
            var source = BuildIdentity.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            Path location = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(location) || !location.toString().endsWith(".jar")) return null;
            return location;
        } catch (Exception e) {
            return null;
        }
    }

    private static String computeCodeSha() {
        Path location = codeArchive();
        if (location == null) return ""; // classes dir (tests) or a native image — no jar identity
        try {
            return Hashing.sha256Hex(location);
        } catch (Exception e) {
            return ""; // identity is best-effort; the version-string rule still applies
        }
    }
}

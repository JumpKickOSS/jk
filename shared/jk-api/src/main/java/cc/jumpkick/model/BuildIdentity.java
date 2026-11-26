// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import cc.jumpkick.host.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * Running jk identity for cache keys and engine election. Action keys open with {@link
 * JkVersion#VERSION} plus the cache-key salt ({@link #cacheKeyVersion()}), on a {@code -SNAPSHOT}
 * build too; the running archive's identity ({@link #buildId()}, {@link #keyId()}) tells one
 * installed engine from another and keys the engine's own preflight memo. Empty {@link #buildId()}
 * means no jar identity.
 */
public final class BuildIdentity {

    /**
     * Cache-key salt: turn this in the same commit that changes what any action key hashes, or what
     * a result recorded under an unchanged key means. It folds into every key through {@link
     * #cacheKeyVersion()}: a key-shape change would otherwise collide with records the old shape
     * wrote. Not a second product version: never printed, never parsed, never compared on its own.
     */
    static final int CACHE_KEY_SALT = 15;

    private static volatile @Nullable String cachedBuildId;

    /** A stand-in for the derived id; null means derive. */
    private static volatile @Nullable String overrideBuildId;

    private BuildIdentity() {}

    /**
     * Content identity of the running code archive, or {@code ""} when none is derivable: how a
     * client tells one installed engine from another. No action key folds it in.
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
     * derived identity. A test uses it to be "another engine" for one build and watch every action
     * key hold; unit tests otherwise run from a classes dir and have no identity to move. It stands
     * in for {@link #keyId()} too.
     */
    public static void overrideBuildIdForTests(@Nullable String id) {
        overrideBuildId = id;
    }

    private static volatile @Nullable String cachedKeyId;

    /**
     * The running code's identity: {@link #buildId()} without the stamps the packaging writes into
     * the archive's manifest ({@value #BUILD_COMMIT_ATTRIBUTE} and {@value #BUILD_TIME_ATTRIBUTE}),
     * 12 hex, or {@code ""} when no archive is derivable. Those stamps name the checkout the jar was
     * packaged from and move with every commit that repackages the engine while its code stays byte
     * for byte what it was. The preflight memo keys by it: the memo vouches that no key of the build
     * moved, and any engine code may spell a key differently. {@link #buildId()} stays the whole
     * archive's: a client tells one installed engine from another by it.
     */
    public static String keyId() {
        String override = overrideBuildId;
        if (override != null) return override;
        String local = cachedKeyId;
        if (local != null) return local;
        synchronized (BuildIdentity.class) {
            if (cachedKeyId == null) cachedKeyId = computeKeyId();
            return cachedKeyId;
        }
    }

    /**
     * The version string every action key opens with: {@link JkVersion#VERSION} plus {@link
     * #CACHE_KEY_SALT}, and nothing of the running code, even on a {@code -SNAPSHOT} build. A key
     * names the program that executes its action by content (the compiler worker, the test runner, a
     * plugin's jar, GraalVM) beside every input it reads, and the engine code that produces or judges
     * a result itself is named by its contract's digest ({@code KeyContract}); so an engine rebuilt
     * from other sources, installed with {@code jk install -S}, replays every compile, suite, package
     * and lane its change does not reach.
     */
    public static String cacheKeyVersion() {
        String pinned = overrideKeyVersion;
        if (pinned != null) return pinned;
        return compose(JkVersion.VERSION);
    }

    private static volatile @Nullable String overrideKeyVersion;

    /**
     * Pretend to be a jk whose action keys are all different: what an upgrade does, since keys hash
     * the version. {@code null} returns to the derived version.
     */
    public static void overrideCacheKeyVersionForTests(@Nullable String version) {
        overrideKeyVersion = version;
    }

    /** Pure composition rule, separated for tests. */
    static String compose(String version) {
        return compose(version, CACHE_KEY_SALT);
    }

    /** As {@link #compose(String)} with the salt explicit, so tests can turn it. */
    static String compose(String version, int salt) {
        return version + "#" + salt;
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

    /**
     * The manifest attribute the packaging writes beside {@value #BUILD_TIME_ATTRIBUTE}: the commit
     * the jar was built from, 40 hex digits. Like the time, it is the checkout's HEAD and says nothing
     * of uncommitted changes, so a commit packages the same bytes on every host and an edit the jar
     * does not contain leaves its stamp, and so its key, where they were.
     */
    public static final String BUILD_COMMIT_ATTRIBUTE = "Build-Commit";

    /** The system property a native image of jk's client is linked with to carry the same stamp. */
    public static final String COMMIT_PROPERTY = "jk.build.commit";

    /**
     * The commit stamp of the running code: linked into a native image, or the {@value
     * #BUILD_COMMIT_ATTRIBUTE} of the code archive's manifest; {@code null} for a classes
     * directory, an archive the packaging did not stamp, or a build outside a git checkout.
     */
    public static @Nullable String builtFrom() {
        return RunningStamp.VALUE;
    }

    /** Read once: the running code does not change under its process. */
    private static final class RunningStamp {
        static final @Nullable String VALUE = readBuiltFrom();
    }

    private static @Nullable String readBuiltFrom() {
        if (!BuildStamp.COMMIT.isBlank()) return BuildStamp.COMMIT.strip();
        Path location = codeArchive();
        if (location == null) return null;
        try (JarFile jar = new JarFile(location.toFile())) {
            Manifest manifest = jar.getManifest();
            return manifest == null ? null : builtFrom(manifest);
        } catch (IOException e) {
            return null;
        }
    }

    /** {@link #builtFrom()} read from {@code manifest}: the attribute, or null when absent or blank. */
    static @Nullable String builtFrom(Manifest manifest) {
        String stamp = manifest.getMainAttributes().getValue(BUILD_COMMIT_ATTRIBUTE);
        return stamp == null || stamp.isBlank() ? null : stamp.strip();
    }

    /** A stamp as people read it: its first 12 hex digits; {@code "unknown"} for no stamp. */
    public static String describeCommit(@Nullable String stamp) {
        if (stamp == null || stamp.isBlank()) return "unknown";
        return stamp.length() > 12 ? stamp.substring(0, 12) : stamp;
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

    private static String computeKeyId() {
        Path location = codeArchive();
        if (location == null) return "";
        try {
            return codeIdentity(location).substring(0, 12);
        } catch (IOException | RuntimeException e) {
            // Best-effort, as the whole archive's sha is: the whole archive then names it.
            return buildId();
        }
    }

    /**
     * The sha256 of what {@code jar} holds, its packaging stamps aside: each entry by name, CRC-32
     * and size as the archive's central directory records them, in name order, and the manifest by
     * its content without {@value #BUILD_COMMIT_ATTRIBUTE} and {@value #BUILD_TIME_ATTRIBUTE}. The
     * central directory answers without inflating an entry, so this costs a few milliseconds on a
     * 20 MB engine where hashing every entry's bytes costs a few hundred, on the first key of every
     * engine start.
     */
    static String codeIdentity(Path jar) throws IOException {
        MessageDigest md = Hashing.newSha256();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<ZipEntry> entries = new ArrayList<>(Collections.list(zip.entries()));
            entries.sort(Comparator.comparing(ZipEntry::getName));
            for (ZipEntry e : entries) {
                String line;
                if (e.getName().equalsIgnoreCase(JarFile.MANIFEST_NAME)) {
                    Manifest m;
                    try (InputStream in = zip.getInputStream(e)) {
                        m = new Manifest(in);
                    }
                    m.getMainAttributes().remove(new Attributes.Name(BUILD_COMMIT_ATTRIBUTE));
                    m.getMainAttributes().remove(new Attributes.Name(BUILD_TIME_ATTRIBUTE));
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    m.write(out);
                    line = e.getName() + " manifest " + Hashing.sha256Hex(out.toByteArray());
                } else {
                    line = e.getName() + " " + Long.toHexString(e.getCrc()) + " " + e.getSize();
                }
                md.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        return Hashing.hex(md.digest());
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

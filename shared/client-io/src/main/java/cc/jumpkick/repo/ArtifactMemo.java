// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One jk-owned memo next to a Maven-layout artifact under {@code JK_STORE_DIR/repos/<name>/}.
 * Filename is {@code <artifact-version>.jk} for jars/aars (extension stripped) and {@code
 * <filename>.jk} for anything else (POMs) so it does not collide with the jar memo.
 *
 * <p>Four newline-delimited lines: {@code g:a:v}, mtime epoch millis, size in bytes, sha256 hex —
 * and, for an artifact the engine shelved from a build, a fifth: the sha256 of the engine jar
 * that packaged it — or last verified that the shelf already held its bytes — so {@code jk
 * doctor} can tell a shelf no install under the home's engine has touched. A sixth line,
 * {@code fetched}, says jk itself downloaded the bytes: it is what lets a cleanup of the Maven
 * local repository prove a file is jk's to remove (the memo still describes it by size, mtime and
 * digest) rather than one Maven or the user put there. Lives only under the jk store — never in
 * the Maven local repository.
 */
public record ArtifactMemo(
        String coordinate,
        long mtimeMillis,
        long size,
        String sha256,
        @Nullable String packagedBy,
        boolean fetched) {

    public ArtifactMemo {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(sha256, "sha256");
        sha256 = sha256.strip().toLowerCase(Locale.ROOT);
        if (packagedBy != null) {
            packagedBy = packagedBy.strip().toLowerCase(Locale.ROOT);
            if (packagedBy.isEmpty()) packagedBy = null;
        }
    }

    /** A memo that records no packaging engine and no download (a client-installed or adopted artifact). */
    public ArtifactMemo(String coordinate, long mtimeMillis, long size, String sha256) {
        this(coordinate, mtimeMillis, size, sha256, null, false);
    }

    /** A memo that records {@code packagedBy} and no download. */
    public ArtifactMemo(String coordinate, long mtimeMillis, long size, String sha256, @Nullable String packagedBy) {
        this(coordinate, mtimeMillis, size, sha256, packagedBy, false);
    }

    /**
     * Memo path for a Maven-relative artifact path. The path comes from a lock row, so it is
     * resolved through {@link MavenLayout#safeResolve}: a memo is a write, and a write sink
     * never takes a path that escapes its root.
     */
    public static Path jkPath(Path storeRoot, String relativePath) {
        Path artifact = MavenLayout.safeResolve(storeRoot, relativePath);
        return artifact.resolveSibling(jkFileName(artifact.getFileName().toString()));
    }

    public static String jkFileName(String artifactFileName) {
        // The stripped form (foo-1.0.jar → foo-1.0.jk) would collide if two of {.jar,.aar,.zip} shared
        // a base name in one version dir. That cannot happen under Maven layout: a coordinate has one
        // packaging, so PubGrub never places two primary artifacts in the same group/artifact/version
        // dir. POMs and other extensions keep their full name (foo-1.0.pom.jk).
        String n = artifactFileName;
        if (n.endsWith(".jar") || n.endsWith(".aar") || n.endsWith(".zip")) {
            return n.substring(0, n.lastIndexOf('.')) + ".jk";
        }
        return n + ".jk";
    }

    public static Optional<ArtifactMemo> read(Path jkFile) {
        if (jkFile == null || !Files.isRegularFile(jkFile)) return Optional.empty();
        try {
            List<String> lines = Files.readAllLines(jkFile);
            if (lines.size() < 4) return Optional.empty();
            String coord = lines.get(0).strip();
            long mtime = Long.parseLong(lines.get(1).strip());
            long size = Long.parseLong(lines.get(2).strip());
            String sha = lines.get(3).strip().toLowerCase(Locale.ROOT);
            if (coord.isBlank() || sha.length() != 64) return Optional.empty();
            String packagedBy =
                    lines.size() > 4 && !lines.get(4).isBlank() ? lines.get(4).strip() : null;
            boolean fetched = lines.size() > 5 && FETCHED.equals(lines.get(5).strip());
            return Optional.of(new ArtifactMemo(coord, mtime, size, sha, packagedBy, fetched));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static ArtifactMemo ofBlob(Path blob, String coordinate, String sha256) throws IOException {
        return ofBlob(blob, coordinate, sha256, null);
    }

    /** As {@link #ofBlob(Path, String, String)} recording {@code packagedBy}, the sha256 of the engine jar that built the blob. */
    public static ArtifactMemo ofBlob(Path blob, String coordinate, String sha256, @Nullable String packagedBy)
            throws IOException {
        return ofBlob(blob, coordinate, sha256, packagedBy, false);
    }

    /** As {@link #ofBlob(Path, String, String, String)}, saying whether jk downloaded {@code blob}. */
    public static ArtifactMemo ofBlob(
            Path blob, String coordinate, String sha256, @Nullable String packagedBy, boolean fetched)
            throws IOException {
        return new ArtifactMemo(
                coordinate, Files.getLastModifiedTime(blob).toMillis(), Files.size(blob), sha256, packagedBy, fetched);
    }

    /** This memo with {@code packagedBy} as its packaging engine. */
    public ArtifactMemo withPackagedBy(@Nullable String engineSha256) {
        return new ArtifactMemo(coordinate, mtimeMillis, size, sha256, engineSha256, fetched);
    }

    /** The sixth line's word for a download jk made. */
    private static final String FETCHED = "fetched";

    public void write(Path jkFile) throws IOException {
        Files.createDirectories(jkFile.getParent());
        String body = coordinate + "\n" + mtimeMillis + "\n" + size + "\n" + sha256 + "\n";
        if (packagedBy != null || fetched) body = body + (packagedBy == null ? "" : packagedBy) + "\n";
        if (fetched) body = body + FETCHED + "\n";
        AtomicWrites.replace(jkFile, body);
    }

    /**
     * True when {@code blob} is a file jk put on disk and has not changed since: the memo says jk
     * downloaded it and its size, mtime and digest are the ones the memo recorded. The proof a
     * Maven-local-repository cleanup demands before it removes anything.
     */
    public boolean provesFetchedBy(Path blob) throws IOException {
        return fetched && describes(blob);
    }

    /**
     * True when {@code blob} still matches {@code expectedSha256} on this memo's word: the memo
     * names that digest, and the file's size and mtime are the ones the memo recorded. One stat,
     * no read; false says nothing about the bytes until a re-hash has looked at them.
     */
    public boolean stillMatches(Path blob, String expectedSha256) throws IOException {
        return sha256.equalsIgnoreCase(expectedSha256) && describes(blob);
    }

    /** True when {@code blob} is a regular file of the size and mtime this memo recorded (one stat). */
    public boolean describes(Path blob) throws IOException {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(blob, BasicFileAttributes.class);
        } catch (NoSuchFileException absent) {
            return false;
        }
        return attrs.isRegularFile()
                && attrs.size() == size
                && attrs.lastModifiedTime().toMillis() == mtimeMillis;
    }

    /**
     * Verify {@code blob} against {@code expectedSha256}, using {@code jkFile} as a size+mtime
     * memo: a memo that names that digest and still describes the file answers without a read;
     * otherwise the file is hashed and, on a match, the memo rewritten. Empty coordinate is stored
     * as {@code -}.
     */
    public static boolean verify(Path blob, Path jkFile, String coordinate, String expectedSha256) throws IOException {
        if (!Files.isRegularFile(blob) || expectedSha256 == null || expectedSha256.isBlank()) return false;
        String want = expectedSha256.strip().toLowerCase(Locale.ROOT);
        Optional<ArtifactMemo> memo = read(jkFile);
        if (memo.isPresent() && memo.get().stillMatches(blob, want)) return true;
        String actual = Hashing.sha256Hex(blob);
        if (!actual.equalsIgnoreCase(want)) return false;
        String gav = coordinate == null || coordinate.isBlank() ? "-" : coordinate;
        // A re-hash refreshes size and mtime; who packaged the blob, and whether jk downloaded
        // it, have not changed — unless the bytes did, which the digest match above rules out.
        ofBlob(
                        blob,
                        gav,
                        actual,
                        memo.map(ArtifactMemo::packagedBy).orElse(null),
                        memo.map(m -> m.sha256.equalsIgnoreCase(actual) && m.fetched)
                                .orElse(false))
                .write(jkFile);
        return true;
    }
}

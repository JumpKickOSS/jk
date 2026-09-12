// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One jk-owned memo next to a Maven-layout artifact under {@code JK_STORE_DIR/repos/<name>/}.
 * Filename is {@code <artifact-version>.jk} for jars/aars (extension stripped) and {@code
 * <filename>.jk} for anything else (POMs) so it does not collide with the jar memo.
 *
 * <p>Four newline-delimited lines: {@code g:a:v}, mtime epoch millis, size in bytes, sha256 hex.
 * Lives only under the jk store — never in the Maven local repository.
 */
public record ArtifactMemo(String coordinate, long mtimeMillis, long size, String sha256) {

    public ArtifactMemo {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(sha256, "sha256");
        sha256 = sha256.strip().toLowerCase(Locale.ROOT);
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
        // dir. POMs and other extensions keep their full name (foo-1.0.pom.jk). (The sibling-deleting
        // removeShas path that made this dangerous was removed with.)
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
            return Optional.of(new ArtifactMemo(coord, mtime, size, sha));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static ArtifactMemo ofBlob(Path blob, String coordinate, String sha256) throws IOException {
        return new ArtifactMemo(coordinate, Files.getLastModifiedTime(blob).toMillis(), Files.size(blob), sha256);
    }

    public void write(Path jkFile) throws IOException {
        Files.createDirectories(jkFile.getParent());
        AtomicWrites.replace(jkFile, coordinate + "\n" + mtimeMillis + "\n" + size + "\n" + sha256 + "\n");
    }

    /**
     * True when {@code blob} still matches {@code expectedSha256}. Trusts this memo when size and
     * mtime still match; otherwise re-hashes and, on success, the caller should rewrite the memo.
     */
    public boolean stillMatches(Path blob, String expectedSha256) throws IOException {
        if (!Files.isRegularFile(blob)) return false;
        if (!sha256.equalsIgnoreCase(expectedSha256)) return false;
        return Files.size(blob) == size && Files.getLastModifiedTime(blob).toMillis() == mtimeMillis;
    }

    /**
     * Verify {@code blob} against {@code expectedSha256}, using {@code jkFile} as a size+mtime
     * memo. On a successful re-hash, rewrites the memo. Empty coordinate is stored as {@code -}.
     */
    public static boolean verify(Path blob, Path jkFile, String coordinate, String expectedSha256) throws IOException {
        if (!Files.isRegularFile(blob) || expectedSha256 == null || expectedSha256.isBlank()) return false;
        String want = expectedSha256.strip().toLowerCase(Locale.ROOT);
        Optional<ArtifactMemo> memo = read(jkFile);
        if (memo.isPresent() && memo.get().stillMatches(blob, want)) return true;
        String actual = Hashing.sha256Hex(blob);
        if (!actual.equalsIgnoreCase(want)) return false;
        String gav = coordinate == null || coordinate.isBlank() ? "-" : coordinate;
        ofBlob(blob, gav, actual).write(jkFile);
        return true;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Writes Maven-compatible artifacts and sidecars into {@code ~/.m2/repository}. SHA-1 and MD5 are
 * named here because Maven's sidecar contract names them — they are the {@code .sha1} / {@code .md5}
 * files Maven clients refuse to read an artifact without, not jk's own content hash. {@link #streamToM2}
 * is atomic (temp-then-rename) and propagates I/O errors; sidecar / {@code _remote.repositories}
 * writes are best-effort and must never fail a successful download.
 */
public final class M2CompatWriter {

    private M2CompatWriter() {}

    /** Hashes and size from {@link #streamToM2}. */
    public record StreamResult(String sha256, String sha1, String md5, long size) {}

    /**
     * Stream {@code in} to {@code target} with SHA-256/SHA-1/MD5 in one pass (temp-then-rename).
     */
    public static StreamResult streamToM2(InputStream in, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        // Unique temp so concurrent writers of the same artifact don't share an inode (JK-2292).
        Path tmp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".part");
        MessageDigest sha256 = Hashing.newSha256();
        MessageDigest sha1 = Hashing.newDigest("SHA-1");
        MessageDigest md5 = Hashing.newDigest("MD5");
        long size = 0;
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha256.update(buf, 0, n);
                    sha1.update(buf, 0, n);
                    md5.update(buf, 0, n);
                    size += n;
                }
            }
            AtomicWrites.moveInto(tmp, target);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw e;
        }
        return new StreamResult(
                Hashing.hex(sha256.digest()), Hashing.hex(sha1.digest()), Hashing.hex(md5.digest()), size);
    }

    /**
     * Write {@code .sha1} and {@code .md5} files alongside {@code artifact}. Maven clients refuse
     * to use local repository artifacts without matching sidecar checksums; Gradle also validates
     * them when {@code --verify-checksums} is active. Both files are written via temp-then-rename.
     * I/O errors are silently swallowed — sidecar failure must not fail the fetch.
     */
    public static void writeMavenSidecars(Path artifact, String sha1, String md5) {
        writeSidecar(artifact.resolveSibling(artifact.getFileName() + ".sha1"), sha1);
        writeSidecar(artifact.resolveSibling(artifact.getFileName() + ".md5"), md5);
    }

    /**
     * Write (or overwrite) the {@code _remote.repositories} file in {@code versionDir} to record
     * that {@code filename} was fetched from {@code repoName}. Format follows Maven Resolver's
     * convention: one {@code <filename>><repo-id>=} line per artifact. Best-effort; I/O errors are
     * swallowed. jk never reads this file — it is a courtesy hint for Maven and tools that inspect
     * {@code ~/.m2}.
     */
    public static void writeRemoteRepositories(Path versionDir, String repoName, String filename) {
        try {
            Path target = versionDir.resolve("_remote.repositories");
            // Build a minimal well-formed _remote.repositories entry.
            // The format tolerates multiple calls (e.g. JAR then POM); we rewrite the file each
            // time, which is fine because jk tracks provenance in repos/<name>/ sidecars instead.
            String content = "#NOTE: This is a jk-written provenance hint for Maven tooling.\n" + filename + ">"
                    + repoName + "=\n";
            AtomicWrites.replace(target, content);
        } catch (IOException ignored) {
        }
    }

    /**
     * Write {@code content} bytes to {@code target} atomically and return the Maven hashes.
     * Equivalent to {@link #copyToM2AndHash} but for in-memory content (e.g. a generated POM).
     */
    public static MavenHashes writeBytesToM2(byte[] content, Path target) throws IOException {
        MessageDigest sha1 = Hashing.newDigest("SHA-1");
        MessageDigest md5 = Hashing.newDigest("MD5");
        sha1.update(content);
        md5.update(content);
        AtomicWrites.replace(target, content);
        return new MavenHashes(Hashing.hex(sha1.digest()), Hashing.hex(md5.digest()));
    }

    /**
     * Maven-compatible hashes computed from an existing file: SHA-1 and MD5. Both are needed for
     * the {@code .sha1} / {@code .md5} sidecar files that Maven and Gradle expect.
     */
    public record MavenHashes(String sha1, String md5) {}

    /**
     * Copy {@code source} to {@code target} (atomic temp-rename), then compute and return the
     * SHA-1 and MD5 of the bytes. Always a copy, never a hard link — jk does not own writes to
     * the Maven local repository. I/O errors in the copy propagate; errors in sidecar writing are
     * swallowed.
     */
    public static MavenHashes copyToM2AndHash(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        // Unique temp so concurrent writers of the same artifact don't share an inode (JK-2292).
        Path tmp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".part");
        MessageDigest sha1 = Hashing.newDigest("SHA-1");
        MessageDigest md5 = Hashing.newDigest("MD5");
        try {
            try (InputStream in = Files.newInputStream(source);
                    OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha1.update(buf, 0, n);
                    md5.update(buf, 0, n);
                }
            }
            AtomicWrites.moveInto(tmp, target);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw e instanceof IOException ie ? ie : new IOException(e);
        }
        return new MavenHashes(Hashing.hex(sha1.digest()), Hashing.hex(md5.digest()));
    }

    // -------------------------------------------------------------------------

    private static void writeSidecar(Path sidecar, String hex) {
        try {
            AtomicWrites.replace(sidecar, hex);
        } catch (IOException ignored) {
        }
    }
}

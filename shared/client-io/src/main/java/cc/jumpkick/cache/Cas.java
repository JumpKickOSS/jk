// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SHA-256-keyed content-addressed store ({@code <root>/sha256/AB/CD/<rest>}). Atomic writes;
 * reads verify the hash. Puts never hard-link external paths into the store (see {@link #putFile}).
 */
public final class Cas {

    /**
     * Staging-name discriminator. Every writer stages under {@code sha256/} as {@code
     * .put-<hex>-<pid>-<n>.tmp} beside its target — {@link #putStream}, which learns its hash last,
     * as {@code .put-stream-<pid>-<n>.tmp} at the top of the tree. Unique by construction, so the
     * create needs none of the random-name generation and collision retry {@link Files#createTempFile}
     * does, which is measurable when one build deposits thousands of outputs. The {@code .put-} prefix
     * is the one shape the cache GC and {@code CasSweep} recognise as a leaked temp; the pid keeps two
     * engines staging the same blob off each other's file.
     */
    private static final AtomicLong STAGING_SEQ = new AtomicLong();

    private static final long PID = ProcessHandle.current().pid();

    private final Path root;

    public Cas(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public Path root() {
        return root;
    }

    public boolean contains(String sha256Hex) {
        return Files.exists(pathFor(sha256Hex));
    }

    /** Stable path for a hash. May or may not exist on disk. */
    public Path pathFor(String sha256Hex) {
        if (sha256Hex.length() < 4) {
            throw new IllegalArgumentException("sha256 hex must be at least 4 chars, got: " + sha256Hex);
        }
        return root.resolve("sha256")
                .resolve(sha256Hex.substring(0, 2))
                .resolve(sha256Hex.substring(2, 4))
                .resolve(sha256Hex.substring(4));
    }

    /**
     * True when {@code path} fits the {@code …/sha256/AB/CD/<60-hex>} object layout of any store,
     * without knowing the store root. Blob paths are content-addressed names, not artifacts: they
     * carry no {@code .jar} suffix and no Maven coordinate, so callers that need artifact
     * semantics (classpath resolution, POM lookup) must treat them specially.
     */
    public static boolean isBlobPath(Path path) {
        if (path == null || path.getNameCount() < 4) return false;
        int n = path.getNameCount();
        return "sha256".equals(path.getName(n - 4).toString())
                && Hashing.isHex(path.getName(n - 3).toString(), 2)
                && Hashing.isHex(path.getName(n - 2).toString(), 2)
                && Hashing.isHex(path.getName(n - 1).toString(), 60);
    }

    /**
     * Inverse of {@link #pathFor}: extract the hex hash from a path that looks like a CAS object
     * location, or {@link java.util.Optional#empty} if it doesn't fit the layout. Used by the sweep
     * when scanning tool env JSONs and action records — any absolute path under {@code
     * <root>/sha256/AA/BB/<rest>} contributes its hash to the reachable set, regardless of the file
     * format it came from.
     */
    public Optional<String> hashFromPath(Path candidate) {
        Path normalised = candidate.toAbsolutePath().normalize();
        Path shaRoot = root.resolve("sha256");
        if (!normalised.startsWith(shaRoot)) return Optional.empty();
        Path rel = shaRoot.relativize(normalised);
        if (rel.getNameCount() != 3) return Optional.empty();
        String aa = rel.getName(0).toString();
        String bb = rel.getName(1).toString();
        String rest = rel.getName(2).toString();
        if (aa.length() != 2 || bb.length() != 2) return Optional.empty();
        String hex = aa + bb + rest;
        if (!Hashing.isHex(hex)) return Optional.empty();
        return Optional.of(hex);
    }

    /**
     * Write data into the CAS. Returns the on-disk path. Idempotent — if the blob is already present
     * and matches, the existing path is returned without re-writing.
     */
    public Path put(byte[] data) throws IOException {
        return put(data, Hashing.sha256Hex(data));
    }

    /**
     * As {@link #put(byte[])} with the content hash already computed by the caller (verified
     * downloads hash the payload anyway) — the CAS trusts it and skips a second full hash.
     */
    public Path put(byte[] data, String hex) throws IOException {
        Path target = pathFor(hex);
        if (Files.exists(target)) {
            return target;
        }
        Path shard = target.getParent();
        ensureShard(shard);
        Path tmp = staging(shard, hex);
        boolean moved = false;
        try {
            Files.write(tmp, data);
            AtomicWrites.moveInto(tmp, target);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
        return target;
    }

    /**
     * Stream {@code in} into the CAS, hashing as the bytes flow through a fixed buffer so the full
     * payload is never resident in memory — the memory-safe counterpart to {@link #put(byte[])} for
     * large artifacts fetched off the network. The content's own SHA-256 becomes its key, so the hash
     * isn't known until the stream is drained: bytes land in a temp file first, then move atomically
     * into place. The caller owns closing {@code in}.
     *
     * <p>Idempotent — if a blob with the computed hash is already present the temp file is discarded
     * and the existing entry returned.
     */
    public Stored putStream(InputStream in) throws IOException {
        // Staged under sha256/, not the CAS root: that is the tree the temp sweep reads, and the
        // final move stays within one directory tree.
        Path shaRoot = root.resolve("sha256");
        Files.createDirectories(shaRoot);
        Path tmp = staging(shaRoot, "stream");
        MessageDigest digest = Hashing.newSha256();
        long size = 0;
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                    size += n;
                }
            }
            String hex = Hashing.hex(digest.digest());
            Path target = pathFor(hex);
            if (Files.exists(target)) {
                Files.deleteIfExists(tmp);
                return new Stored(target, hex, size);
            }
            ensureShard(target.getParent());
            AtomicWrites.moveInto(tmp, target);
            return new Stored(target, hex, size);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** A blob stored in the CAS: its on-disk path, hex hash, and byte size. */
    public record Stored(Path path, String sha256, long size) {}

    /** The staging path for {@code hex} in {@code dir}: unique by construction, not yet created. */
    private static Path staging(Path dir, String hex) {
        return dir.resolve(".put-" + hex + "-" + PID + "-" + STAGING_SEQ.getAndIncrement() + ".tmp");
    }

    /**
     * Create the two-level shard directory. {@link Files#createDirectories} walks and stats every
     * ancestor on each call; the shard almost always exists already, and with 65,536 buckets it is
     * almost never the same one twice, so the walk is paid per blob and never amortised. One
     * {@code createDirectory} answers the common case in a single syscall.
     */
    private static void ensureShard(Path shard) throws IOException {
        try {
            Files.createDirectory(shard);
        } catch (FileAlreadyExistsException present) {
            // The common case on a warm store.
        } catch (NoSuchFileException coldRoot) {
            Files.createDirectories(shard);
        }
    }

    /**
     * Store {@code source} under {@code hex} by HARD LINK, falling back to a copy when the filesystem
     * refuses (different device, or a filesystem without links).
     *
     * <p>Opt-in only, and never for anything jk writes: a link means the blob shares an inode with a
     * file jk does not own, so an in-place rewrite by another tool would mutate content the CAS believes
     * it has already hashed. The one sanctioned use is adopting an artifact out of {@code ~/.m2} after a
     * remotely-fetched checksum has confirmed it, where the caller has explicitly chosen to
     * trade that risk for the disk saving.
     */
    public Path linkFile(Path source, String hex) throws IOException {
        Path target = pathFor(hex);
        if (Files.exists(target)) {
            return target;
        }
        ensureShard(target.getParent());
        try {
            Files.createLink(target, source);
            return target;
        } catch (IOException | UnsupportedOperationException e) {
            return putFile(source, hex);
        }
    }

    /**
     * Store {@code source}'s bytes under {@code hex} by COPY (temp + atomic move). Never hard-links:
     * compilers rewrite class files in place and must not mutate CAS blobs. Idempotent if {@code
     * hex} is already present.
     */
    public Path putFile(Path source, String hex) throws IOException {
        Path target = pathFor(hex);
        if (Files.exists(target)) {
            return target;
        }
        Path shard = target.getParent();
        ensureShard(shard);
        Path tmp = staging(shard, hex);
        // Cleanup on the failure path only: moveInto consumed tmp on success, so a finally unlink
        // is a guaranteed miss once per blob.
        boolean moved = false;
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            AtomicWrites.moveInto(tmp, target);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
        return target;
    }

    /**
     * Read bytes for a hash. Verifies the content actually hashes to the expected value; throws if
     * the on-disk blob is corrupted.
     */
    public byte[] read(String sha256Hex) throws IOException {
        Path file = pathFor(sha256Hex);
        if (!Files.exists(file)) {
            throw new IOException("blob not present in CAS: " + sha256Hex);
        }
        byte[] data = Files.readAllBytes(file);
        String actual = Hashing.sha256Hex(data);
        if (!actual.equals(sha256Hex)) {
            throw new IOException("CAS corruption: expected " + sha256Hex + " but got " + actual);
        }
        return data;
    }
}

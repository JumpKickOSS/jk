// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.AccessLedger;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * SHA-256-keyed content-addressed store ({@code <root>/sha256/AB/CD/<rest>}). Atomic writes;
 * reads verify the hash. Puts never hard-link external paths into the store (see {@link
 * #putFile(Path)}).
 */
public final class Cas {

    /**
     * How long a freshly-written CAS object, key record or incremental tree is unconditionally
     * protected from the sweep and the prune, even if no surviving root references it.
     *
     * <p>Closes the window between "writer A puts an object into the CAS" and "writer B writes the
     * root that references it" — most relevantly a {@code jk sync} that fetches deps but has no
     * associated build yet, or any future CAS writer that hasn't been taught to root its outputs. One
     * hour is generous enough to cover an interactive workflow but short enough that genuine garbage
     * doesn't survive long.
     */
    public static final Duration MIN_AGE_FOR_SWEEP = Duration.ofHours(1);

    /**
     * Staging-name discriminator. Every writer stages under {@code sha256/} as {@code
     * .put-<hex>-<pid>-<n>.tmp} beside its target — {@link #putStream} and {@link #putFile(Path)},
     * which learn their hash last, as {@code .put-stream-<pid>-<n>.tmp} and {@code
     * .put-file-<pid>-<n>.tmp} at the top of the tree. Unique by construction, so the
     * create needs none of the random-name generation and collision retry {@link Files#createTempFile}
     * does, which is measurable when one build deposits thousands of outputs. The {@code .put-} prefix
     * is the one shape the cache GC and {@code CasSweep} recognise as a leaked temp; the pid keeps two
     * engines staging the same blob off each other's file.
     */
    private static final AtomicLong STAGING_SEQ = new AtomicLong();

    private static final long PID = ProcessHandle.current().pid();

    private final Path root;

    private final @Nullable AccessLedger ledger;

    public Cas(Path root) {
        this.root = Objects.requireNonNull(root, "root");
        this.ledger = null;
    }

    private Cas(Path root, AccessLedger ledger) {
        this.root = root;
        this.ledger = ledger;
    }

    /**
     * A CAS that records each blob it is asked for in the access ledger kept under {@code root}, which
     * is what lets {@link StoreCasGc} weigh how often a blob is reached for and not only how old it is.
     * The action-cache CAS is not tracked: its own budgeted pruning decides what stays.
     */
    public static Cas tracked(Path root) {
        Objects.requireNonNull(root, "root");
        return new Cas(root, AccessLedger.at(root.resolve(".access")));
    }

    public Path root() {
        return root;
    }

    public boolean contains(String sha256Hex) {
        return Files.exists(pathFor(sha256Hex));
    }

    /**
     * Stable path for a hash. May or may not exist on disk. Only hex digits name a blob: anything
     * else (a separator, a {@code ..}) would resolve outside the store, so it is refused. Either
     * case names the same blob: the hash is lowercased, as blobs are stored.
     */
    public Path pathFor(String sha256Hex) {
        String hex = lowerHex(sha256Hex);
        if (ledger != null) ledger.touch(StoreCasGc.key(hex));
        return root.resolve("sha256")
                .resolve(hex.substring(0, 2))
                .resolve(hex.substring(2, 4))
                .resolve(hex.substring(4));
    }

    /** {@code sha256Hex} lowercased; refused unless it is at least 4 hex digits. */
    private static String lowerHex(String sha256Hex) {
        if (sha256Hex.length() < 4 || !Hashing.isHex(sha256Hex)) {
            throw new IllegalArgumentException("sha256 hex must be at least 4 hex digits, got: " + sha256Hex);
        }
        return sha256Hex.toLowerCase(Locale.ROOT);
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
        if (reused(target)) return target;
        Path shard = shardOf(target);
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
        return stream(in, null);
    }

    /**
     * As {@link #putStream}, for bytes another machine sent under {@code expectedHex}: stored only
     * when they hash to it. Bytes that hash to anything else are discarded and refused with {@link
     * DigestMismatch}, leaving nothing in the CAS; the caller's digest is never trusted.
     */
    public Stored putVerified(InputStream in, String expectedHex) throws IOException {
        if (!Hashing.isHex(expectedHex, 64)) throw new IllegalArgumentException("not a sha256: " + expectedHex);
        return stream(in, expectedHex.toLowerCase(Locale.ROOT));
    }

    /** Bytes that hash to another digest than the one they were stored under. */
    public static final class DigestMismatch extends IOException {
        public DigestMismatch(String expected, String actual, long size) {
            super("blob refused: " + size + " bytes sent as " + expected + " hash to " + actual);
        }
    }

    private Stored stream(InputStream in, @Nullable String expected) throws IOException {
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
                // Until end of stream: a read of no bytes is not the end, though it ends a "> 0" loop.
                while ((n = in.read(buf)) >= 0) {
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                    size += n;
                }
            }
            String hex = Hashing.hex(digest.digest());
            if (expected != null && !expected.equals(hex)) throw new DigestMismatch(expected, hex, size);
            return place(tmp, hex, size);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** Move the staged {@code tmp}, whose bytes hash to {@code hex}, to its path; drop it when the blob is there. */
    private Stored place(Path tmp, String hex, long size) throws IOException {
        Path target = pathFor(hex);
        if (reused(target)) {
            Files.deleteIfExists(tmp);
            return new Stored(target, hex, size);
        }
        ensureShard(shardOf(target));
        AtomicWrites.moveInto(tmp, target);
        return new Stored(target, hex, size);
    }

    /** A blob stored in the CAS: its on-disk path, hex hash, and byte size. */
    public record Stored(Path path, String sha256, long size) {}

    /**
     * How old a blob's mtime may grow before a store that finds it refreshes it: half the prune's
     * grace ({@link #MIN_AGE_FOR_SWEEP}), so a blob reused since a prune scanned its keys looks
     * younger than the grace and is not deleted with the key it was first stored under.
     */
    public static final Duration REUSE_REFRESH = MIN_AGE_FOR_SWEEP.dividedBy(2);

    /**
     * True when {@code target} is already stored, its mtime refreshed when it is {@link
     * #REUSE_REFRESH} or more old: one stat a hit, at most one write per blob per window.
     * Best-effort: a read-only root keeps the mtime it has.
     */
    private static boolean reused(Path target) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(target).toMillis();
        } catch (NoSuchFileException absent) {
            return false;
        } catch (IOException unreadable) {
            return Files.exists(target);
        }
        long now = Clock.SYSTEM.millis();
        if (now - mtime >= REUSE_REFRESH.toMillis()) {
            try {
                Files.setLastModifiedTime(target, FileTime.fromMillis(now));
            } catch (IOException ignored) {
                // A ranking hint for the prune, never correctness.
            }
        }
        return true;
    }

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
    /** The shard directory of a store path; {@link #pathFor} always places a blob one level down. */
    private static Path shardOf(Path target) {
        return Objects.requireNonNull(target.getParent(), "store path has no shard");
    }

    private static void ensureShard(Path shard) throws IOException {
        try {
            Files.createDirectory(shard);
        } catch (FileAlreadyExistsException present) {
            // The common case on a warm store.
        } catch (NoSuchFileException coldRoot) {
            Files.createDirectories(shard);
        }
    }

    /** The blob stored under {@code hex}, refreshed as a put that finds it refreshes it; empty when absent. */
    public Optional<Path> reuse(String hex) {
        Path target = pathFor(hex);
        return reused(target) ? Optional.of(target) : Optional.empty();
    }

    /**
     * Store {@code source}'s bytes by COPY under the hash of what was copied. The copy is staged
     * first and the staged file, which nothing else writes, is hashed, so a source rewritten
     * during the put is stored under the hash of the bytes the copy took, never under another.
     * Never hard-links: compilers rewrite class files in place and must not mutate CAS blobs.
     * {@code Files.copy} clones where the filesystem can (FICLONE on Linux btrfs and XFS, clonefile
     * on macOS APFS, block cloning on Windows ReFS): the blob shares the source's extents and costs
     * no space while the source lives, and an in-place rewrite of either side diverges from the
     * other. Idempotent if the hash is already present.
     */
    public Stored putFile(Path source) throws IOException {
        return putFile(source, () -> {});
    }

    /** As {@link #putFile(Path)}, running {@code staged} between the copy and its hash: a test seam. */
    Stored putFile(Path source, Runnable staged) throws IOException {
        Path shaRoot = root.resolve("sha256");
        Files.createDirectories(shaRoot);
        Path tmp = staging(shaRoot, "file");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            staged.run();
            return place(tmp, Hashing.sha256Hex(tmp), Files.size(tmp));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /**
     * As {@link #putFile(Path)} under a hash the caller already holds, which is trusted and not
     * checked: only for a source nothing writes between that hash and this put (a store file, a
     * private temp, the outputs of an action that has finished). Skips the copy when {@code hex}
     * is already present.
     */
    public Path putFile(Path source, String hex) throws IOException {
        Path target = pathFor(hex);
        if (reused(target)) return target;
        Path shard = shardOf(target);
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
        if (!actual.equalsIgnoreCase(sha256Hex)) {
            throw new IOException("CAS corruption: expected " + sha256Hex + " but got " + actual);
        }
        return data;
    }
}

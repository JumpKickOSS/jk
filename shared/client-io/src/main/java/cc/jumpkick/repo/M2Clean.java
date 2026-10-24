// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.util.AccessLedger;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Removes files jk put in the Maven local repository, and only those.
 *
 * <p>The local repository is shared with Maven, Gradle and the user, so jk never reclaims it on its
 * own account. This runs when asked — {@code jk cache clean --m2}, the MCP tool — or when the
 * machine's {@code [m2] auto-clean} setting says an admin wants {@code ~/.m2} bounded. Either way a
 * file is removed only when jk can <em>prove</em> it is jk's: the store's memo for it says jk
 * downloaded it, and the file still has that memo's size and mtime and hashes to its sha256. A file
 * Maven wrote, one the user edited, or one jk only verified is reported and left.
 *
 * <p>Eligible files are further held to an age (since the last use), and to the pins the caller
 * names — typically the digests a lockfile in use still needs.
 */
public final class M2Clean {

    private M2Clean() {}

    /** One file that is (or would be) removed. */
    public record Removed(String relativePath, long bytes) {}

    /**
     * What a pass found. {@code foreign} counts memo'd files it could not prove were jk's (changed since,
     * or only verified), {@code pinned} those a pin kept, {@code young} those inside the window.
     */
    public record Report(List<Removed> removed, long freedBytes, int foreign, int pinned, int young) {}

    /** The digests {@code lockFile} still needs, as lower-case hex; empty when it cannot be read. */
    public static Set<String> pinsOf(Path lockFile) {
        Set<String> pins = new HashSet<>();
        try {
            Lockfile lock = LockfileReader.read(lockFile);
            for (Lockfile.Artifact artifact : lock.artifacts()) {
                String sha = artifact.checksumHex();
                if (sha != null) pins.add(sha.toLowerCase(Locale.ROOT));
                String sources = artifact.sourcesChecksumHex();
                if (sources != null) pins.add(sources.toLowerCase(Locale.ROOT));
            }
        } catch (IOException | RuntimeException unreadable) {
            // a lockfile that does not read pins nothing
        }
        return pins;
    }

    /**
     * Walk the memos under {@code storeRoot/repos/} and remove the files in {@code m2Root} they prove
     * jk downloaded, older than {@code olderThan} and not in {@code pinned}.
     *
     * @param dryRun report what would go without deleting
     */
    public static Report run(
            Path storeRoot, Path m2Root, Duration olderThan, Set<String> pinned, long nowMillis, boolean dryRun)
            throws IOException {
        Path repos = storeRoot.resolve("repos");
        List<Removed> removed = new ArrayList<>();
        long[] freed = {0};
        int[] counts = new int[3]; // foreign, pinned, young
        if (!Files.isDirectory(repos) || !Files.isDirectory(m2Root)) return new Report(removed, 0, 0, 0, 0);

        List<Path> trees = new ArrayList<>();
        PathUtil.forEachChild(repos, (child, attrs) -> {
            if (attrs.isDirectory()) trees.add(child);
            return true;
        });
        Path m2 = m2Root.toAbsolutePath().normalize();
        for (Path tree : trees) {
            // A tree that keeps its own artifacts is not the local repository's: those are the store's to age.
            if (tree.toAbsolutePath().normalize().equals(m2)) continue;
            Map<String, AccessLedger.Use> uses =
                    AccessLedger.at(tree.resolve(RepoArtifactStore.ACCESS_DIR)).load();
            List<Path> memos = new ArrayList<>();
            PathUtil.forEachRegularFile(tree, (file, attrs) -> {
                if (file.getFileName().toString().endsWith(".jk")) memos.add(file);
            });
            for (Path memoFile : memos) {
                consider(tree, memoFile, m2, olderThan, pinned, uses, nowMillis, dryRun, removed, freed, counts);
            }
            // The ledger follows the tree: a key whose memo is gone has nothing left to count.
            if (!dryRun) {
                AccessLedger.at(tree.resolve(RepoArtifactStore.ACCESS_DIR))
                        .compact(key -> key.startsWith("m2:") && Files.exists(tree.resolve(key.substring(3))));
            }
        }
        return new Report(removed, freed[0], counts[0], counts[1], counts[2]);
    }

    private static void consider(
            Path tree,
            Path memoFile,
            Path m2,
            Duration olderThan,
            Set<String> pinned,
            Map<String, AccessLedger.Use> uses,
            long now,
            boolean dryRun,
            List<Removed> removed,
            long[] freed,
            int[] counts)
            throws IOException {
        var memo = ArtifactMemo.read(memoFile);
        if (memo.isEmpty() || !memo.get().fetched()) return;
        Path relativeMemo = tree.relativize(memoFile);
        Path artifact = artifactFor(m2, relativeMemo);
        if (artifact == null) return;
        // The proof: the file is the one jk downloaded, byte for byte.
        if (!memo.get().provesFetchedBy(artifact)
                || !Hashing.sha256Hex(artifact).equalsIgnoreCase(memo.get().sha256())) {
            counts[0]++;
            return;
        }
        if (pinned.contains(memo.get().sha256())) {
            counts[1]++;
            return;
        }
        // Age is since the last use, which the memo file's mtime records (RepoArtifactStore#verify), and
        // an artifact used on many different days earns a longer window, up to three times.
        AccessLedger.Use use = uses.get(RepoArtifactStore.useKey(tree, memoFile));
        double bonus =
                use == null || use.days() <= 1 ? 1.0 : Math.min(3.0, 1.0 + 0.5 * Math.log(use.days()) / Math.log(2));
        long idle = now - Files.getLastModifiedTime(memoFile).toMillis();
        if (use != null) idle = Math.min(idle, now - use.lastMillis());
        if (idle < (long) (olderThan.toMillis() * bonus)) {
            counts[2]++;
            return;
        }
        long size = Files.size(artifact);
        removed.add(new Removed(m2.relativize(artifact).toString().replace('\\', '/'), size));
        freed[0] += size;
        if (!dryRun) delete(m2, artifact, memoFile);
    }

    /** The file in {@code m2} the memo at {@code relativeMemo} describes, or null when there is none. */
    static @Nullable Path artifactFor(Path m2, Path relativeMemo) {
        String name = relativeMemo.getFileName().toString();
        String base = name.substring(0, name.length() - ".jk".length());
        Path dir = relativeMemo.getParent() == null ? m2 : m2.resolve(relativeMemo.getParent());
        for (String candidate : List.of(base, base + ".jar", base + ".aar", base + ".zip")) {
            Path file = dir.resolve(candidate);
            if (Files.isRegularFile(file)) return file;
        }
        return null;
    }

    /** The file, its memo, the checksum files Maven keeps beside it and its line in {@code _remote.repositories}. */
    static void delete(Path m2, Path artifact, Path memoFile) throws IOException {
        Files.deleteIfExists(artifact);
        Files.deleteIfExists(memoFile);
        for (String ext : List.of(".sha1", ".md5", ".sha256", ".sha512")) {
            Files.deleteIfExists(artifact.resolveSibling(artifact.getFileName() + ext));
        }
        forgetRemote(artifact);
        Path dir = artifact.getParent();
        while (dir != null && !dir.equals(m2) && dir.startsWith(m2) && isEmpty(dir)) {
            Files.delete(dir);
            dir = dir.getParent();
        }
    }

    /** Drop {@code artifact}'s line from its directory's {@code _remote.repositories}; the file goes with its last one. */
    private static void forgetRemote(Path artifact) throws IOException {
        Path hint = artifact.resolveSibling("_remote.repositories");
        if (!Files.isRegularFile(hint)) return;
        String own = artifact.getFileName() + ">";
        List<String> kept = new ArrayList<>();
        boolean entries = false;
        for (String line : Files.readAllLines(hint)) {
            String entry = line.strip();
            if (entry.startsWith(own)) continue;
            kept.add(line);
            if (!entry.isEmpty() && !entry.startsWith("#")) entries = true;
        }
        if (entries) AtomicWrites.replace(hint, String.join("\n", kept) + "\n");
        else Files.deleteIfExists(hint);
    }

    private static boolean isEmpty(Path dir) throws IOException {
        boolean[] empty = {true};
        PathUtil.forEachChild(dir, (child, attrs) -> {
            empty[0] = false;
            return false;
        });
        return empty[0];
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Per-named-repository Maven-layout store under {@code <store>/repos/<name>/}.
 *
 * <p>Each artifact is a real {@code .jar}/{@code .pom}/… file plus one {@link ArtifactMemo}
 * {@code .jk} file. Writers use temp + atomic replace. This tree is jk-owned; the Maven local
 * repository (when {@code m2integration} is on) is a separate candidate cache and never holds
 * {@code .jk} files.
 */
public final class RepoArtifactStore {

    /** No-op store for callers that don't participate in per-repo storage. */
    public static final RepoArtifactStore NONE = new RepoArtifactStore((Path) null);

    private final Path root; // <cache>/repos/<name>/

    private RepoArtifactStore(Path root) {
        this.root = root;
    }

    /** Artifact and sidecar both live under {@code repos/<repoName>/}. */
    public RepoArtifactStore(Path cacheRoot, String repoName) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(repoName, "repoName");
        // A repo name is a raw substring from the project's config/lockfile; refuse one that would
        // escape repos/ into an attacker-chosen directory.
        MavenLayout.requireSafeSegment(repoName, "repository name");
        migrateLegacyLocal(cacheRoot);
        this.root = cacheRoot.resolve("repos").resolve(repoName);
    }

    /**
     * Marker in {@code repos/}: this store has been through the local → jk-local rename. Before
     * the marker exists, a {@code repos/local} directory can only be the pre-rename first-party
     * store (older jk reserved the name and never fetched a remote into it); once the marker is
     * written, {@code repos/local} is an ordinary user-named remote and must be left alone.
     */
    private static final String LEGACY_LOCAL_MARKER = ".jk-local-renamed";

    /** Roots already migrated (or confirmed clean) this process — elides the per-construction probe. */
    private static final Set<Path> LEGACY_LOCAL_MIGRATED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Test seam: forget which roots this process already migrated, as a fresh process would. */
    static void clearLegacyMigrationMemoForTest() {
        LEGACY_LOCAL_MIGRATED.clear();
    }

    /** True while a pre-rename {@code repos/local} may still exist (migration not yet completed). */
    public static boolean legacyLocalPending(Path cacheRoot) {
        Path reposDir = cacheRoot.resolve("repos");
        return !Files.exists(reposDir.resolve(LEGACY_LOCAL_MARKER)) && Files.isDirectory(reposDir.resolve("local"));
    }

    /**
     * Fold a pre-rename {@code repos/local} first-party store into {@code repos/jk-local} so
     * installs made before the rename stay resolvable (and stay out of LRU eviction, which only
     * exempts the new name). Runs from every store construction, memoized per process and gated by
     * {@link #LEGACY_LOCAL_MARKER} on disk, so the real work happens once per store lifetime — and
     * a {@code repos/local} created <em>after</em> the marker (a user remote actually named
     * {@code local}, legal since the rename) is never touched. Whole-directory atomic move when
     * the new store doesn't exist yet; per-file merge otherwise, keeping the jk-local copy on
     * collision — later writes went there, and every read hash-verifies, so dropping the older
     * duplicate can never serve wrong bytes. Best-effort: a failure leaves both trees readable and
     * retries on a later construction.
     */
    public static void migrateLegacyLocal(Path cacheRoot) {
        Path reposDir = cacheRoot.resolve("repos");
        if (!LEGACY_LOCAL_MIGRATED.add(reposDir)) return;
        if (Files.exists(reposDir.resolve(LEGACY_LOCAL_MARKER))) return;
        Path legacy = reposDir.resolve("local");
        try {
            if (!Files.isDirectory(legacy)) {
                writeLegacyMarker(reposDir);
                return;
            }
            Path target = reposDir.resolve(RepoArtifactResolver.JK_LOCAL);
            if (!Files.exists(target)) {
                try {
                    Files.move(legacy, target, StandardCopyOption.ATOMIC_MOVE);
                    writeLegacyMarker(reposDir);
                    return;
                } catch (IOException raceOrFs) {
                    // Concurrent creator or a filesystem that refuses the directory move —
                    // fall through to the per-file merge.
                }
            }
            try (Stream<Path> files = Files.walk(legacy)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (!Files.isRegularFile(file)) continue;
                    Path dest = target.resolve(legacy.relativize(file));
                    if (Files.exists(dest)) {
                        Files.deleteIfExists(file); // duplicate — jk-local's copy wins
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.move(file, dest);
                    }
                }
            }
            // Bottom-up sweep of the emptied skeleton; a leftover file means a concurrent writer
            // on the OLD layout (an older jk still running) — leave the tree and retry later.
            boolean emptied = true;
            try (Stream<Path> dirs = Files.walk(legacy)) {
                List<Path> ordered = new ArrayList<>();
                dirs.filter(Files::isDirectory).forEach(ordered::add);
                for (int i = ordered.size() - 1; i >= 0; i--) {
                    try {
                        Files.deleteIfExists(ordered.get(i));
                    } catch (IOException notEmpty) {
                        emptied = false;
                    }
                }
            }
            if (emptied) {
                writeLegacyMarker(reposDir);
            } else {
                LEGACY_LOCAL_MIGRATED.remove(reposDir);
            }
        } catch (IOException e) {
            LEGACY_LOCAL_MIGRATED.remove(reposDir); // retry from a later construction
        }
    }

    private static void writeLegacyMarker(Path reposDir) throws IOException {
        Files.createDirectories(reposDir);
        Path marker = reposDir.resolve(LEGACY_LOCAL_MARKER);
        if (!Files.exists(marker)) {
            Files.writeString(marker, "repos/local was folded into repos/jk-local (or never existed)\n");
        }
    }

    /** Factory: the full store for {@code repoName} under {@code cacheRoot}. */
    public static RepoArtifactStore forRepoName(Path cacheRoot, String repoName) {
        return new RepoArtifactStore(cacheRoot, repoName);
    }

    // -------------------------------------------------------------------------
    // Presence and location
    // -------------------------------------------------------------------------

    /**
     * True when the artifact at {@code relativePath} has been fully stored — {@code .jk} memo exists
     * AND the artifact file is present.
     */
    public boolean contains(String relativePath) {
        if (root == null) return false;
        if (!Files.isRegularFile(sidecarPath(relativePath))) return false;
        return Files.isRegularFile(artifactPath(relativePath));
    }

    /**
     * Verification state of a stored artifact against an expected (lockfile-pinned) hash — see
     * {@link #verify}.
     */
    public enum IndexState {
        /** Sidecar or artifact file missing — never (fully) stored. */
        ABSENT,
        /** Present, and the stored hash equals the expected hash. */
        VERIFIED,
        /** Present, but the stored hash differs — the artifact was overwritten or corrupted. */
        MISMATCH
    }

    /**
     * Verify the artifact at {@code relativePath} against {@code expectedSha256} (the lock pin).
     * Uses the {@code .jk} memo's size+mtime fast path and re-hashes on doubt.
     */
    public IndexState verify(String relativePath, String expectedSha256) {
        if (root == null) return IndexState.ABSENT;
        Path artifact = artifactPath(relativePath);
        if (!Files.isRegularFile(artifact)) return IndexState.ABSENT;
        try {
            boolean ok =
                    ArtifactMemo.verify(artifact, sidecarPath(relativePath), inferGav(relativePath), expectedSha256);
            return ok ? IndexState.VERIFIED : IndexState.MISMATCH;
        } catch (IOException unreadable) {
            return IndexState.MISMATCH;
        }
    }

    /**
     * As {@link #locate(String)} but hash-verified: resolves only when {@link #verify} says the
     * stored hash matches {@code expectedSha256}.
     */
    public Optional<Path> locate(String relativePath, String expectedSha256) {
        return verify(relativePath, expectedSha256) == IndexState.VERIFIED ? locate(relativePath) : Optional.empty();
    }

    /**
     * The stored artifact path if the Maven-layout file is present. A missing {@code .jk} memo is
     * written from the file bytes when possible (installLocal / leftover {@code .sha256} trees).
     */
    public Optional<Path> locate(String relativePath) {
        if (root == null) return Optional.empty();
        Path artifact = artifactPath(relativePath);
        if (!Files.isRegularFile(artifact)) return Optional.empty();
        Path sidecar = sidecarPath(relativePath);
        if (!Files.isRegularFile(sidecar)) {
            try {
                writeMemo(relativePath, artifact, Hashing.sha256Hex(artifact));
            } catch (IOException ignored) {
                // the jar is on disk; callers can still use it
            }
        }
        return Optional.of(artifact);
    }

    /** SHA-256 hex from the materialised sidecar, if fully stored. */
    public Optional<String> storedSha256(String relativePath) {
        if (root == null || !contains(relativePath)) return Optional.empty();
        return readSha256Sidecar(relativePath);
    }

    /**
     * SHA-256 from the {@code .jk} memo without re-statting the artifact.
     */
    public Optional<String> readSha256Sidecar(String relativePath) {
        if (root == null) return Optional.empty();
        return ArtifactMemo.read(sidecarPath(relativePath)).map(ArtifactMemo::sha256);
    }

    // -------------------------------------------------------------------------
    // Write paths
    // -------------------------------------------------------------------------

    // A {@code .jk} memo may point at a blob outside this store (Maven local repo). locate() only
    // returns files that live here; ArtifactLocator checks the memo against ~/.m2 first.

    /**
     * Copy {@code source} into this store at {@code relativePath} and write the {@code .jk} memo.
     * Idempotent when the destination already verifies as {@code sha256}. Source may be the
     * destination (memo-only refresh).
     */
    public void materialize(String relativePath, Path source, String sha256) {
        if (root == null || source == null || !Files.isRegularFile(source)) return;
        Path artifact = MavenLayout.safeResolve(root, relativePath);
        try {
            if (Files.isRegularFile(artifact)
                    && verify(relativePath, sha256) == IndexState.VERIFIED
                    && Files.isSameFile(artifact, source)) {
                return;
            }
            Files.createDirectories(artifact.getParent());
            boolean same = Files.isRegularFile(artifact) && Files.isSameFile(source, artifact);
            if (!same) {
                // Unique temp per writer: a shared fixed ".part" name let two concurrent fetchers
                // (two engines on one ~/.jk, or two syncs in one engine) interleave writes to the
                // same inode and install corrupt bytes, which the memo then blessed as VERIFIED.
                // A unique temp + atomic move makes the published file exactly the (already
                // caller-verified) source bytes, so the memo's pinned sha describes them correctly.
                Path tmp = Files.createTempFile(artifact.getParent(), "." + artifact.getFileName() + ".", ".part");
                try {
                    Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                    AtomicWrites.moveInto(tmp, artifact);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            writeMemo(relativePath, artifact, sha256);
        } catch (IOException | RuntimeException e) {
            // Best-effort store write: the caller re-checks presence and fails loudly if nothing
            // landed. Surface the cause so a disk-full/permissions failure is diagnosable
            // rather than silent.
            System.err.println("jk: warning: could not store " + relativePath + " under " + root + ": " + e);
        }
    }

    /** Write or refresh the {@code .jk} memo for {@code blob} (which may live outside this store). */
    public void writeMemo(String relativePath, Path blob, String sha256) throws IOException {
        if (root == null || blob == null || !Files.isRegularFile(blob)) return;
        ArtifactMemo.ofBlob(blob, inferGav(relativePath), sha256).write(sidecarPath(relativePath));
    }

    // -------------------------------------------------------------------------
    // Offline helpers
    // -------------------------------------------------------------------------

    /**
     * Version directories present in this index — directories under
     * {@code repos/<name>/<group>/<artifact>/} that hold at least one tracked sidecar.
     */
    public List<String> versions(String group, String artifact) {
        if (root == null) return List.of();
        Path dir = root.resolve(group.replace('.', '/')).resolve(artifact);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(this::hasTrackedFile)
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** A cached module and the versions held locally for it. */
    public record Module(String group, String artifact, List<String> versions) {
        public String moduleKey() {
            return group + ":" + artifact;
        }
    }

    /**
     * Every {@code group:artifact} tracked by this store, with the versions held for each. Powers
     * offline cache search ({@code jk repo search}, {@code jk library search --offline}). Empty
     * for {@link #NONE} or a cold store. Versions are in directory-listing order — callers wanting
     * newest-first should sort.
     */
    public List<Module> modules() {
        if (root == null || !Files.isDirectory(root)) return List.of();
        // Group tracked files by their version directory (the file's parent).
        Map<Path, Boolean> versionDirs = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isMemoName(p.getFileName().toString()))
                    .forEach(p -> versionDirs.put(p.getParent(), Boolean.TRUE));
        } catch (IOException e) {
            return List.of();
        }
        Map<String, List<String>> versionsByModule = new TreeMap<>();
        Map<String, String[]> ga = new HashMap<>();
        for (Path versionDir : versionDirs.keySet()) {
            Path rel = root.relativize(versionDir);
            int n = rel.getNameCount();
            if (n < 3) continue; // need at least one group segment + artifact + version
            String version = rel.getName(n - 1).toString();
            String artifact = rel.getName(n - 2).toString();
            StringBuilder group = new StringBuilder();
            for (int i = 0; i < n - 2; i++) {
                if (i > 0) group.append('.');
                group.append(rel.getName(i));
            }
            String key = group + ":" + artifact;
            ga.putIfAbsent(key, new String[] {group.toString(), artifact});
            versionsByModule.computeIfAbsent(key, k -> new ArrayList<>()).add(version);
        }
        List<Module> out = new ArrayList<>();
        for (var e : versionsByModule.entrySet()) {
            String[] parts = ga.get(e.getKey());
            out.add(new Module(parts[0], parts[1], List.copyOf(e.getValue())));
        }
        return out;
    }

    /**
     * Names of every per-repo store under {@code <cacheRoot>/repos/} — the repos jk has fetched
     * through so far. Empty for a cold cache.
     */
    public static List<String> repoNames(Path cacheRoot) {
        Path reposDir = cacheRoot.resolve("repos");
        if (!Files.isDirectory(reposDir)) return List.of();
        try (Stream<Path> entries = Files.list(reposDir)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Every {@code group:artifact} cached under any named repo in {@code cacheRoot}, merged by
     * module key — the repo-agnostic view {@code jk repo search} and {@code jk library search
     * --offline} want, since neither is scoped to one particular declared repository.
     */
    public static List<Module> allModules(Path cacheRoot) {
        Map<String, String[]> ga = new LinkedHashMap<>();
        Map<String, Set<String>> versionsByModule = new LinkedHashMap<>();
        for (String repoName : repoNames(cacheRoot)) {
            for (Module m : forRepoName(cacheRoot, repoName).modules()) {
                ga.putIfAbsent(m.moduleKey(), new String[] {m.group(), m.artifact()});
                versionsByModule
                        .computeIfAbsent(m.moduleKey(), k -> new LinkedHashSet<>())
                        .addAll(m.versions());
            }
        }
        List<Module> out = new ArrayList<>();
        for (var e : versionsByModule.entrySet()) {
            String[] parts = ga.get(e.getKey());
            out.add(new Module(parts[0], parts[1], List.copyOf(e.getValue())));
        }
        return out;
    }

    /**
     * Versions of {@code group:artifact} cached under any named repo in {@code cacheRoot}, merged
     * and deduplicated — the repo-agnostic counterpart to {@link #versions(String, String)}.
     */
    public static List<String> allVersions(Path cacheRoot, String group, String artifact) {
        Set<String> out = new LinkedHashSet<>();
        for (String repoName : repoNames(cacheRoot)) {
            out.addAll(forRepoName(cacheRoot, repoName).versions(group, artifact));
        }
        return List.copyOf(out);
    }

    /** All relative m2 paths stored (non-sidecar files for full store). Empty for NONE. */
    public List<String> allRelativePaths() {
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<String> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isMemoName(p.getFileName().toString()))
                    // Leaked .put-*.tmp download temps are not stored artifacts.
                    .filter(p -> !p.getFileName().toString().startsWith(".put-")
                            && !p.getFileName().toString().endsWith(".tmp"))
                    .forEach(p -> result.add(root.relativize(p).toString()));
        } catch (IOException ignored) {
        }
        return result;
    }

    /** One evictable repos/ artifact: its owning repo name, name-relative path, size, and LRU time. */
    private record ReposEntry(String repoName, String relPath, long size, long atimeMillis) {}

    /** Outcome of {@link #evictReposDownTo}. */
    public record EvictReport(int deleted, long freedBytes, long remainingBytes) {}

    /**
     * LRU-evict downloaded artifacts under {@code <cacheRoot>/repos/} down to {@code maxBytes}, keyed
     * by last-access from {@code atimeByHash} (sha → millis; unknown = coldest). {@code
     * repos/jk-local} is exempt — first-party, no re-fetch source. Re-fetchable third-party jars are
     * fair game — this is the size bound the store budget promises. Best-effort; never throws.
     */
    public static EvictReport evictReposDownTo(
            Path cacheRoot, long maxBytes, Map<String, Long> atimeByHash, boolean dryRun) {
        migrateLegacyLocal(cacheRoot);
        Path reposDir = cacheRoot.resolve("repos");
        if (!Files.isDirectory(reposDir)) return new EvictReport(0, 0L, 0L);
        List<ReposEntry> entries = new ArrayList<>();
        long total = 0;
        try (Stream<Path> named = Files.list(reposDir)) {
            for (Path nameDir : (Iterable<Path>) named::iterator) {
                String name = nameDir.getFileName().toString();
                // Before the rename marker lands, repos/local is (or may still hold) the
                // pre-rename first-party store — never evict it. After the marker it is an
                // ordinary user remote and fair game.
                if (!Files.isDirectory(nameDir)
                        || RepoArtifactResolver.isFirstPartyStoreName(name)
                        || ("local".equals(name) && legacyLocalPending(cacheRoot))) {
                    continue; // never evict first-party
                }
                RepoArtifactStore store = new RepoArtifactStore(cacheRoot, name);
                for (String rel : store.allRelativePaths()) {
                    Path file = nameDir.resolve(rel);
                    long size;
                    try {
                        size = Files.size(file);
                    } catch (IOException e) {
                        continue;
                    }
                    String sha = store.readSha256Sidecar(rel).orElse("");
                    long atime = atimeByHash.getOrDefault(sha, 0L);
                    entries.add(new ReposEntry(name, rel, size, atime));
                    total += size;
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        if (total <= maxBytes) return new EvictReport(0, 0L, total);

        entries.sort(Comparator.comparingLong(ReposEntry::atimeMillis)
                .thenComparing(Comparator.comparingLong(ReposEntry::size).reversed()));
        int deleted = 0;
        long freed = 0;
        long remaining = total;
        for (ReposEntry e : entries) {
            if (remaining <= maxBytes) break;
            if (!dryRun) {
                new RepoArtifactStore(cacheRoot, e.repoName()).evict(e.relPath());
            }
            deleted++;
            freed += e.size();
            remaining -= e.size();
        }
        return new EvictReport(deleted, freed, remaining);
    }

    /** The root directory ({@code <cache>/repos/<name>}), or {@code null} for {@link #NONE}. */
    public Path root() {
        return root;
    }

    /**
     * Drop the store entry for {@code relativePath} — artifact and {@code .jk} memo — so the
     * next resolve re-fetches it.
     *
     * <p>The escape hatch for jk's first-write-wins mirror contract: a mirror hit otherwise serves
     * the bytes first stored for a coordinate forever, which is wrong in the rare case where
     * upstream really did republish.
     * Removing the sidecar first keeps the "sidecar present ⇒ fully stored" invariant true at every
     * instant, so a concurrent reader sees a miss rather than a half-evicted entry.
     *
     * @return true when anything was removed
     */
    public boolean evict(String relativePath) {
        if (root == null || relativePath == null || relativePath.isBlank()) return false;
        boolean removed = false;
        try {
            removed = Files.deleteIfExists(sidecarPath(relativePath));
            removed |= Files.deleteIfExists(artifactPath(relativePath));
        } catch (IOException e) {
            return removed;
        }
        return removed;
    }

    // -------------------------------------------------------------------------

    private Path artifactPath(String relativePath) {
        return MavenLayout.safeResolve(root, relativePath);
    }

    private Path sidecarPath(String relativePath) {
        MavenLayout.safeResolve(root, relativePath); // reject traversal before deriving the sidecar
        return ArtifactMemo.jkPath(root, relativePath);
    }

    /** {@code g:a:v} from a Maven-relative path, or {@code -} when the path is too short. */
    static String inferGav(String relativePath) {
        Path rel = Path.of(relativePath);
        int n = rel.getNameCount();
        if (n < 3) return "-";
        String version = rel.getName(n - 2).toString();
        String artifact = rel.getName(n - 3).toString();
        StringBuilder group = new StringBuilder();
        for (int i = 0; i < n - 3; i++) {
            if (i > 0) group.append('.');
            group.append(rel.getName(i));
        }
        return group + ":" + artifact + ":" + version;
    }

    private boolean hasTrackedFile(Path versionDir) {
        if (!Files.isDirectory(versionDir)) return false;
        try (Stream<Path> entries = Files.list(versionDir)) {
            return entries.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }

    private void pruneEmptyParents(Path dir) {
        Path cur = dir;
        while (cur != null && root != null && cur.startsWith(root) && !cur.equals(root)) {
            try {
                Files.delete(cur);
            } catch (IOException stop) {
                return;
            }
            cur = cur.getParent();
        }
    }
    /**
     * Write a file directly into {@code repos/jk-local/} as a full-store entry (actual JAR on disk) —
     * the local-install write path shared by the engine's install plan and the client's
     * {@code jk install <file.jar>} mode (a local, content-addressed write, like {@code
     * Cas.putByLink} — no network).
     */
    public static void writeToLocalStore(Path artifactRoot, String relativePath, Path source) throws IOException {
        // The caller picks the root deliberately: the engine install plan passes the
        // store (where resolvers read since the cache/store split); plugin install-local may pass
        // an isolated --cache-dir root on purpose.
        Path target = MavenLayout.safeResolve(
                artifactRoot.resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL), relativePath);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".part");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            AtomicWrites.moveInto(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
        String hex = Hashing.sha256Hex(target);
        ArtifactMemo.ofBlob(target, inferGav(relativePath), hex)
                .write(target.resolveSibling(
                        ArtifactMemo.jkFileName(target.getFileName().toString())));
    }

    private static boolean isMemoName(String fileName) {
        return fileName.endsWith(".jk") || fileName.endsWith(".sha256");
    }
}

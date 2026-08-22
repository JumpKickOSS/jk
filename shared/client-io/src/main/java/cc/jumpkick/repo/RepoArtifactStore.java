// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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
        this.root = cacheRoot.resolve("repos").resolve(repoName);
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
     * The stored artifact path if fully materialised (sidecar and artifact file both present),
     * else empty.
     */
    public Optional<Path> locate(String relativePath) {
        if (root == null) return Optional.empty();
        Path sidecar = sidecarPath(relativePath);
        if (!Files.isRegularFile(sidecar)) return Optional.empty();
        Path artifact = artifactPath(relativePath);
        if (!Files.isRegularFile(artifact)) return Optional.empty();
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

    // Note: there is deliberately no sidecar-only write here. Every repo is a full store now — a
    // sidecar without a backing artifact file is never a state this store intentionally creates.
    // See materialize() below.

    /**
     * Copy {@code source} into this store at {@code relativePath} and write the {@code .jk} memo.
     * Idempotent when the destination already verifies as {@code sha256}. Source may be the
     * destination (memo-only refresh).
     */
    public void materialize(String relativePath, Path source, String sha256) {
        if (root == null || source == null || !Files.isRegularFile(source)) return;
        Path artifact = root.resolve(relativePath);
        Path tmp = artifact.resolveSibling(artifact.getFileName() + ".part");
        try {
            if (Files.isRegularFile(artifact)
                    && verify(relativePath, sha256) == IndexState.VERIFIED
                    && Files.isSameFile(artifact, source)) {
                return;
            }
            Files.createDirectories(artifact.getParent());
            boolean same = Files.isRegularFile(artifact) && Files.isSameFile(source, artifact);
            if (!same) {
                Files.deleteIfExists(tmp);
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                AtomicWrites.moveInto(tmp, artifact);
            }
            writeMemo(relativePath, artifact, sha256);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
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
                    .forEach(p -> result.add(root.relativize(p).toString()));
        } catch (IOException ignored) {
        }
        return result;
    }

    /**
     * Remove entries hashing to {@code shas} from EVERY named repo store under
     * {@code <cacheRoot>/repos/} — the shared tail of cache GC / sweep / LRU eviction.
     *
     * <p>Deletes the {@code .sha256} sidecar <em>and</em> the artifact file (the hard-link or
     * legacy copy of the CAS blob). Callers that delete CAS paths must invoke this for the same
     * sha set: with hard-linked materialization, removing only {@code sha256/…} leaves a live
     * nlink under {@code repos/} and the GC does not reclaim disk. Never touches an opt-in
     * {@code ~/.m2} mirror (jk doesn't GC Maven's store; see {@code m2install}).
     * Best-effort; returns entries removed. Never throws.
     */
    public static int removeShasFromAll(Path cacheRoot, Set<String> shas, boolean dryRun) {
        if (shas.isEmpty()) return 0;
        Path reposDir = cacheRoot.resolve("repos");
        if (!Files.isDirectory(reposDir)) return 0;
        int removed = 0;
        try (Stream<Path> named = Files.list(reposDir)) {
            for (Path nameDir : (Iterable<Path>) named::iterator) {
                if (!Files.isDirectory(nameDir)) continue;
                removed +=
                        new RepoArtifactStore(cacheRoot, nameDir.getFileName().toString()).removeShas(shas, dryRun);
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return removed;
    }

    /**
     * Drop artifact + sidecar for each entry whose sidecar hash is in {@code shas}. The artifact
     * path is typically a hard link to the CAS blob — unlinking it is half of disk reclaim (the
     * CAS path is the other half, deleted by the GC caller).
     */
    public int removeShas(Set<String> shas, boolean dryRun) {
        if (root == null || shas.isEmpty() || !Files.isDirectory(root)) return 0;
        // Collect BEFORE deleting: pruning directories under a still-lazy Files.walk iterator
        // throws NoSuchFileException from the stream.
        List<Path> sidecars;
        try (Stream<Path> walk = Files.walk(root)) {
            sidecars =
                    walk.filter(p -> p.getFileName().toString().endsWith(".jk")).toList();
        } catch (IOException e) {
            return 0;
        }
        int removed = 0;
        for (Path sidecar : sidecars) {
            try {
                String hash =
                        ArtifactMemo.read(sidecar).map(ArtifactMemo::sha256).orElse("");
                if (!shas.contains(hash)) continue;
                if (!dryRun) {
                    Files.deleteIfExists(sidecar);
                    String memoName = sidecar.getFileName().toString();
                    String stem = memoName.endsWith(".jk") ? memoName.substring(0, memoName.length() - 3) : memoName;
                    Path dir = sidecar.getParent();
                    for (String ext : List.of(".jar", ".aar", ".pom", ".zip")) {
                        Files.deleteIfExists(dir.resolve(stem + ext));
                    }
                    if (stem.endsWith(".pom")) {
                        Files.deleteIfExists(dir.resolve(stem));
                    }
                    pruneEmptyParents(sidecar.getParent());
                }
                removed++;
            } catch (IOException ignored) {
            }
        }
        return removed;
    }

    /** The root directory ({@code <cache>/repos/<name>}), or {@code null} for {@link #NONE}. */
    public Path root() {
        return root;
    }

    /**
     * Drop the mirror entry for {@code relativePath} — artifact and {@code .sha256} sidecar — so the
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
        return root.resolve(relativePath);
    }

    private Path sidecarPath(String relativePath) {
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
     * Write a file directly into {@code repos/local/} as a full-store entry (actual JAR on disk) —
     * the local-install write path shared by the engine's install plan and the client's
     * {@code jk install <file.jar>} mode (a local, content-addressed write, like {@code
     * Cas.putByLink} — no network).
     */
    public static void writeToLocalStore(Path artifactRoot, String relativePath, Path source) throws IOException {
        // The caller picks the root deliberately: the engine install plan passes the
        // store (where resolvers read since the cache/store split); plugin install-local may pass
        // an isolated --cache-dir root on purpose.
        Path target = artifactRoot.resolve("repos/local/" + relativePath);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
        AtomicWrites.moveInto(tmp, target);
        String hex = Hashing.sha256Hex(target);
        ArtifactMemo.ofBlob(target, inferGav(relativePath), hex)
                .write(target.resolveSibling(
                        ArtifactMemo.jkFileName(target.getFileName().toString())));
    }

    private static boolean isMemoName(String fileName) {
        return fileName.endsWith(".jk") || fileName.endsWith(".sha256");
    }
}

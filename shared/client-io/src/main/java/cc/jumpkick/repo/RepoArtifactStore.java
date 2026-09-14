// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.lock.RepoSource;
import cc.jumpkick.lock.RepoStoreDirs;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
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
import org.jspecify.annotations.Nullable;

/**
 * Per-repository Maven-layout store under {@code <store>/repos/<id>/}, keyed by the repository's
 * identity ({@link RepoIdentity#storeId}: where its bytes come from), never by the name a project
 * gives it. The name is kept beside the tree as a label ({@link #ORIGIN_FILE}) for {@code jk
 * storage} and {@code jk doctor} to show.
 *
 * <p>Each artifact is a real {@code .jar}/{@code .pom}/… file plus one {@link ArtifactMemo}
 * {@code .jk} file. Writers use temp + atomic replace. This tree is jk-owned; the Maven local
 * repository (when {@code m2integration} is on) is a separate candidate cache and never holds
 * {@code .jk} files.
 *
 * <p>The first-party shelf {@code repos/jk-local} is not a remote and has no origin; the three
 * public origins jk ships with keep their reserved directories ({@code central}, {@code google},
 * {@code jumpkick}). Any other directory that carries no {@link #ORIGIN_FILE} was keyed by a
 * repository <em>name</em> and nothing can say which origin filled it: it is a legacy store, read
 * by nothing, listed as such, and removed by {@code jk storage clean}.
 */
public final class RepoArtifactStore {

    /** No-op store for callers that don't participate in per-repo storage. */
    public static final RepoArtifactStore NONE = new RepoArtifactStore(null, null, null);

    /**
     * Beside each identity-keyed tree: {@code origin = "<canonical origin>"} and {@code name =
     * "<the label the first project to fill it used>"}. Written on the first write.
     */
    public static final String ORIGIN_FILE = ManifestPaths.REPO_ORIGIN;

    private final @Nullable Path root; // <store>/repos/<id>/
    private final @Nullable String label;
    private final @Nullable String origin;

    private RepoArtifactStore(@Nullable Path root, @Nullable String label, @Nullable String origin) {
        this.root = root;
        this.label = label;
        this.origin = origin;
    }

    /**
     * The tree at {@code repos/<storeId>/} as it is on disk. For a remote, {@link #forRepository}
     * derives the id; this constructor is for the first-party shelf ({@link RepositorySpec#JK_LOCAL})
     * and for walking ids {@link #storeIds} listed.
     */
    public RepoArtifactStore(Path cacheRoot, String storeId) {
        this(dirFor(cacheRoot, storeId), null, null);
    }

    private static Path dirFor(Path cacheRoot, String storeId) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(storeId, "storeId");
        // An id may echo a raw substring of the project's config or lockfile; refuse one that
        // would escape repos/ into an attacker-chosen directory.
        MavenLayout.requireSafeSegment(storeId, "store id");
        return cacheRoot.resolve("repos").resolve(storeId);
    }

    /** The tree at {@code repos/<storeId>/}; see the constructor. */
    public static RepoArtifactStore forStoreId(Path cacheRoot, String storeId) {
        return new RepoArtifactStore(cacheRoot, storeId);
    }

    /**
     * The store for the repository at {@code origin}, whatever a project calls it. A repository
     * served out of this very store ({@code file:} at {@code <cacheRoot>/repos/<id>}, the view the
     * worker launcher reads through) is that tree itself, not a copy of it.
     */
    public static RepoArtifactStore forRepository(Path cacheRoot, String name, URI origin) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(origin, "origin");
        String self = selfView(cacheRoot, origin);
        if (self != null) return new RepoArtifactStore(cacheRoot, self);
        String canonical = RepoIdentity.canonicalOrigin(origin);
        return new RepoArtifactStore(dirFor(cacheRoot, RepoIdentity.storeId(origin)), name, canonical);
    }

    /**
     * The store a lockfile {@code source} ({@code "<name>+<url>"}) names: the identity of its URL
     * for a named remote, the first-party shelf for {@code jk-local} and synthetic sources.
     */
    public static RepoArtifactStore forSource(Path cacheRoot, String source) {
        RepoSource parsed = RepoSource.parse(source);
        String name = parsed.name();
        if (!RepoArtifactResolver.isNamedRemote(name)) return forStoreId(cacheRoot, RepoArtifactResolver.JK_LOCAL);
        String url = parsed.url();
        try {
            return forRepository(cacheRoot, Objects.requireNonNull(name), new URI(url.strip()));
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            return new RepoArtifactStore(
                    dirFor(cacheRoot, RepoIdentity.storeId(url)), name, RepoIdentity.canonicalOrigin(url));
        }
    }

    /**
     * The stores every first-party lookup probes, in order: the {@code jk-local} shelf a checkout
     * installs onto, the official repository at its configured URL, then Maven Central.
     */
    public static List<RepoArtifactStore> firstParty(Path cacheRoot) {
        return List.of(
                forStoreId(cacheRoot, RepoArtifactResolver.JK_LOCAL),
                forRepository(cacheRoot, RepositorySpec.JUMPKICK_NAME, RepositorySpec.officialUrl()),
                forRepository(cacheRoot, RepositorySpec.CENTRAL, RepositorySpec.MAVEN_CENTRAL.url()));
    }

    /** The store id when {@code origin} is a {@code file:} URI at {@code <cacheRoot>/repos/<id>}; else null. */
    private static @Nullable String selfView(Path cacheRoot, URI origin) {
        if (!"file".equalsIgnoreCase(origin.getScheme()) || origin.getPath() == null) return null;
        Path repos = cacheRoot.resolve("repos").toAbsolutePath().normalize();
        Path dir;
        try {
            dir = Path.of(origin).toAbsolutePath().normalize();
        } catch (IllegalArgumentException | FileSystemNotFoundException notLocal) {
            return null;
        }
        if (dir.getParent() == null || !dir.getParent().equals(repos)) return null;
        return String.valueOf(dir.getFileName());
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
            recordOrigin();
            boolean same = Files.isRegularFile(artifact) && Files.isSameFile(source, artifact);
            if (!same) {
                // Unique temp per writer: a shared fixed ".part" name let two concurrent fetchers
                // (two engines on one ~/.jk, or two syncs in one engine) interleave writes to the
                // same inode and install corrupt bytes, which the memo then blessed as VERIFIED.
                // A unique temp + atomic move makes the published file exactly the (already
                // caller-verified) source bytes, so the memo's pinned sha describes them correctly.
                Path tmp = Files.createTempFile(artifact.getParent(), "." + artifact.getFileName() + ".", ".part");
                // Failure path only — moveInto consumed tmp on success.
                boolean moved = false;
                try {
                    Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                    AtomicWrites.moveInto(tmp, artifact);
                    moved = true;
                } finally {
                    if (!moved) Files.deleteIfExists(tmp);
                }
            }
            writeMemo(relativePath, artifact, sha256);
        } catch (IOException | RuntimeException e) {
            // Best-effort store write: the caller re-checks presence and fails loudly if nothing
            // landed. Surface the cause so a disk-full/permissions failure is diagnosable
            // rather than silent.
            Log.warn("jk: warning: could not store " + relativePath + " under " + root + ": " + e);
        }
    }

    /**
     * Write or refresh the {@code .jk} memo for {@code blob} (which may live outside this store).
     * A refresh of the same bytes keeps the packager the shelf recorded: which engine built the
     * artifact is nothing a re-hash or a Maven-local write-through knows better, and losing it
     * would silence the shelf row {@code jk doctor} draws from it. Different bytes record none.
     */
    public void writeMemo(String relativePath, Path blob, String sha256) throws IOException {
        if (root == null || blob == null || !Files.isRegularFile(blob)) return;
        Path sidecar = sidecarPath(relativePath);
        Files.createDirectories(Objects.requireNonNull(sidecar.getParent(), "sidecar dir"));
        recordOrigin();
        @Nullable
        String packagedBy = ArtifactMemo.read(sidecar)
                .filter(memo -> memo.sha256().equalsIgnoreCase(sha256))
                .map(ArtifactMemo::packagedBy)
                .orElse(null);
        ArtifactMemo.ofBlob(blob, inferGav(relativePath), sha256, packagedBy).write(sidecar);
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /** What a store is on disk: its id, the label it was first filled under, and its canonical origin. */
    public record Origin(
            String id, @Nullable String name, @Nullable String origin) {
        /** True when nothing says which origin filled the tree: keyed by name, read by nothing. */
        public boolean isLegacy() {
            return origin == null && !RepoArtifactResolver.JK_LOCAL.equals(id);
        }
    }

    /**
     * Write {@link #ORIGIN_FILE} once for a store that knows its origin. A reserved tree gets one
     * too, so a listing does not have to know the reserved table; the first-party shelf has none.
     */
    private void recordOrigin() {
        if (root == null || origin == null) return;
        Path marker = root.resolve(ORIGIN_FILE);
        if (Files.exists(marker)) return;
        try {
            Files.createDirectories(root);
            String text = "origin = \"" + origin + "\"\n" + (label == null ? "" : "name = \"" + label + "\"\n");
            Path tmp = Files.createTempFile(root, ".origin.", ".part");
            boolean moved = false;
            try {
                Files.writeString(tmp, text);
                AtomicWrites.moveInto(tmp, marker);
                moved = true;
            } finally {
                if (!moved) Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("jk: warning: could not record the origin of " + root + ": " + e);
        }
    }

    /** This store's on-disk identity; {@code null} for {@link #NONE}. */
    public @Nullable Origin origin() {
        return root == null ? null : describe(root);
    }

    /** The identity of the tree at {@code dir}: its marker, else the reserved table, else legacy ({@link RepoStoreDirs}). */
    static Origin describe(Path dir) {
        String id = String.valueOf(dir.getFileName());
        String name = null;
        String origin = null;
        Path marker = dir.resolve(ORIGIN_FILE);
        if (Files.isRegularFile(marker)) {
            try {
                for (String line : Files.readAllLines(marker)) {
                    String[] kv = line.split("=", 2);
                    if (kv.length != 2) continue;
                    String key = kv[0].strip();
                    String value = kv[1].strip();
                    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (key.equals("origin")) origin = value;
                    else if (key.equals("name")) name = value;
                }
            } catch (IOException unreadable) {
                // fall through to the reserved table
            }
        }
        if (origin == null) origin = RepoIdentity.reservedOrigin(id);
        if (name == null && (origin != null || RepoArtifactResolver.JK_LOCAL.equals(id))) name = id;
        return new Origin(id, name, origin);
    }

    /**
     * Every store under {@code <cacheRoot>/repos/} with what is known of its identity, sorted by
     * id — the listing {@code jk storage usage} and {@code jk doctor} print. Legacy trees are
     * included, flagged; see {@link Origin#isLegacy}.
     */
    public static List<Origin> describeAll(Path cacheRoot) {
        List<Path> dirs = new ArrayList<>();
        try {
            PathUtil.forEachChild(cacheRoot.resolve("repos"), (child, attrs) -> {
                if (attrs.isDirectory()) dirs.add(child);
                return true;
            });
        } catch (IOException e) {
            return List.of();
        }
        dirs.sort(null);
        return dirs.stream().map(RepoArtifactStore::describe).toList();
    }

    // -------------------------------------------------------------------------
    // Offline helpers
    // -------------------------------------------------------------------------

    /**
     * Version directories present in this index — directories under
     * {@code repos/<id>/<group>/<artifact>/} that hold at least one tracked sidecar.
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
            // Free test first: the walk already paid for this entry, and isRegularFile re-resolves
            // the path for a fresh stat even for entries the name test discards.
            walk.filter(p -> !isMemoName(p.getFileName().toString()))
                    .filter(Files::isRegularFile)
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
            String[] parts = Objects.requireNonNull(ga.get(e.getKey()));
            out.add(new Module(parts[0], parts[1], List.copyOf(e.getValue())));
        }
        return out;
    }

    /**
     * Ids of every store under {@code <cacheRoot>/repos/} whose origin is known — the first-party
     * shelf, the reserved public origins, and every identity-keyed tree. A legacy name-keyed tree
     * is not among them: nothing reads it. Empty for a cold cache.
     */
    public static List<String> storeIds(Path cacheRoot) {
        return describeAll(cacheRoot).stream()
                .filter(o -> !o.isLegacy())
                .map(Origin::id)
                .toList();
    }

    /** The legacy name-keyed trees under {@code <cacheRoot>/repos/}; {@code jk storage clean} removes them. */
    public static List<Path> legacyStores(Path cacheRoot) {
        return describeAll(cacheRoot).stream()
                .filter(Origin::isLegacy)
                .map(o -> cacheRoot.resolve("repos").resolve(o.id()))
                .toList();
    }

    /**
     * Every {@code group:artifact} cached under any known store in {@code cacheRoot}, merged by
     * module key — the repo-agnostic view {@code jk repo search} and {@code jk library search
     * --offline} want, since neither is scoped to one particular declared repository.
     */
    public static List<Module> allModules(Path cacheRoot) {
        Map<String, String[]> ga = new LinkedHashMap<>();
        Map<String, Set<String>> versionsByModule = new LinkedHashMap<>();
        for (String storeId : storeIds(cacheRoot)) {
            for (Module m : forStoreId(cacheRoot, storeId).modules()) {
                ga.putIfAbsent(m.moduleKey(), new String[] {m.group(), m.artifact()});
                versionsByModule
                        .computeIfAbsent(m.moduleKey(), k -> new LinkedHashSet<>())
                        .addAll(m.versions());
            }
        }
        List<Module> out = new ArrayList<>();
        for (var e : versionsByModule.entrySet()) {
            String[] parts = Objects.requireNonNull(ga.get(e.getKey()));
            out.add(new Module(parts[0], parts[1], List.copyOf(e.getValue())));
        }
        return out;
    }

    /**
     * Versions of {@code group:artifact} cached under any known store in {@code cacheRoot}, merged
     * and deduplicated — the repo-agnostic counterpart to {@link #versions(String, String)}.
     */
    public static List<String> allVersions(Path cacheRoot, String group, String artifact) {
        Set<String> out = new LinkedHashSet<>();
        for (String storeId : storeIds(cacheRoot)) {
            out.addAll(forStoreId(cacheRoot, storeId).versions(group, artifact));
        }
        return List.copyOf(out);
    }

    /** The root directory ({@code <store>/repos/<id>}), or {@code null} for {@link #NONE}. */
    public @Nullable Path root() {
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
        return MavenLayout.safeResolve(rootOf(this), relativePath);
    }

    private Path sidecarPath(String relativePath) {
        Path dir = rootOf(this);
        MavenLayout.safeResolve(dir, relativePath); // reject traversal before deriving the sidecar
        return ArtifactMemo.jkPath(dir, relativePath);
    }

    /** The root of a store that has one; every path derivation is reached behind a {@code root == null} guard. */
    private static Path rootOf(RepoArtifactStore store) {
        return Objects.requireNonNull(store.root, "the NONE store has no root");
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

    /**
     * Write a file directly into {@code repos/jk-local/} as a full-store entry (actual JAR on disk) —
     * the local-install write path shared by the engine's install plan and the client's
     * {@code jk install <file.jar>} mode (a local, content-addressed write, like {@code
     * Cas.putByLink} — no network).
     */
    public static void writeToLocalStore(Path artifactRoot, String relativePath, Path source) throws IOException {
        writeToLocalStore(artifactRoot, relativePath, source, null);
    }

    /**
     * As {@link #writeToLocalStore(Path, String, Path)}, recording {@code packagedBy} — the sha256
     * of the engine jar that built the artifact — in its memo, so the shelf can later be compared
     * with the engine the home names. Null records none (a client-side file install).
     */
    public static void writeToLocalStore(
            Path artifactRoot, String relativePath, Path source, @Nullable String packagedBy) throws IOException {
        // The caller picks the root deliberately: the engine install plan passes the
        // store (where resolvers read since the cache/store split); plugin install-local may pass
        // an isolated --cache-dir root on purpose.
        Path target = MavenLayout.safeResolve(
                artifactRoot.resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL), relativePath);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".part");
        // Failure path only — moveInto consumed tmp on success.
        boolean moved = false;
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            AtomicWrites.moveInto(tmp, target);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
        String hex = Hashing.sha256Hex(target);
        ArtifactMemo.ofBlob(target, inferGav(relativePath), hex, packagedBy)
                .write(target.resolveSibling(
                        ArtifactMemo.jkFileName(target.getFileName().toString())));
    }

    private static boolean isMemoName(String fileName) {
        return fileName.endsWith(".jk") || fileName.endsWith(".sha256");
    }
}

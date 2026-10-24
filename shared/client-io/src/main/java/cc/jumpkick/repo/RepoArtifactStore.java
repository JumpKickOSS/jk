// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.lock.RepoSource;
import cc.jumpkick.lock.RepoStoreDirs;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.AccessLedger;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.PoolUse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Per-repository Maven-layout store under {@code <store>/repos/<id>/}, keyed by the repository's
 * identity ({@link RepoIdentity#storeId}: where its bytes come from), never by the name a project
 * gives it. The name is kept beside the tree as a label ({@link #ORIGIN_FILE}) for {@code jk
 * storage} and {@code jk doctor} to show.
 *
 * <p>Each artifact is a real {@code .jar}/{@code .pom}/… file plus one {@link ArtifactMemo}
 * {@code .jk} file. Writers use temp + atomic replace. The memos always live in this tree. The
 * artifacts live here too, except for a repository {@link M2Backing} says Maven shares (Central,
 * or one {@code settings.xml} declares): its artifacts live in the Maven local repository, once,
 * and this tree holds only their memos. The memo is how jk knows a file there is the one it
 * verified or downloaded; the local repository itself never holds a {@code .jk} file.
 *
 * <p>The first-party shelf {@code repos/jk-local} is not a remote and has no origin; the three
 * public origins jk ships with keep their reserved directories ({@code central}, {@code google},
 * {@code jumpkick}). Any other directory that carries no {@link #ORIGIN_FILE} was keyed by a
 * repository <em>name</em> and nothing can say which origin filled it: it is a legacy store, read
 * by nothing, listed as such, and removed by {@code jk storage clean}.
 */
public final class RepoArtifactStore {

    /** No-op store for callers that don't participate in per-repo storage. */
    public static final RepoArtifactStore NONE = new RepoArtifactStore(null, null, null, null, false);

    /**
     * Beside each identity-keyed tree: {@code origin = "<canonical origin>"} and {@code name =
     * "<the label the first project to fill it used>"}. Written on the first write.
     */
    public static final String ORIGIN_FILE = ManifestPaths.REPO_ORIGIN;

    private final @Nullable Path root; // <store>/repos/<id>/ — the memos, and the artifacts unless m2-backed
    private final @Nullable Path m2; // the Maven local repository holding the artifacts, else null
    private final @Nullable String label;
    private final @Nullable String origin;

    /** A loopback origin: the tree is one process's, see {@link #claim}. */
    private final boolean loopback;

    /**
     * This process's claim on the loopback trees it touches, written into their markers. A
     * loopback origin names whatever process holds the port right now — a test's stub, a proxy under
     * development — so a tree another process filled under it holds a predecessor's artifacts.
     */
    private static final String PROCESS_TOKEN = processToken(ProcessHandle.current());

    /** The loopback trees this process has claimed; a claim runs once per tree, others wait on it. */
    private static final ConcurrentHashMap<Path, Boolean> CLAIMS = new ConcurrentHashMap<>();

    private RepoArtifactStore(
            @Nullable Path root, @Nullable Path m2, @Nullable String label, @Nullable String origin, boolean loopback) {
        this.root = root;
        this.m2 = m2;
        this.label = label;
        this.origin = origin;
        this.loopback = loopback;
    }

    /**
     * The tree at {@code repos/<storeId>/} as it is on disk. For a remote, {@link #forRepository}
     * derives the id; this constructor is for the first-party shelf ({@link RepositorySpec#JK_LOCAL})
     * and for walking ids {@link #storeIds} listed.
     */
    public RepoArtifactStore(Path cacheRoot, String storeId) {
        this(dirFor(cacheRoot, storeId), null, null, null, false);
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
     * worker launcher reads through) is that tree itself, not a copy of it. A loopback origin's tree
     * is this process's alone: the first touch here removes what a previous process stored under it.
     */
    public static RepoArtifactStore forRepository(Path cacheRoot, String name, URI origin) {
        return forRepository(cacheRoot, name, origin, true);
    }

    /**
     * As {@link #forRepository(Path, String, URI)}; {@code m2integration} false (a project that
     * opted out of the Maven local repository) keeps every origin's artifacts in its own tree.
     */
    public static RepoArtifactStore forRepository(Path cacheRoot, String name, URI origin, boolean m2integration) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(origin, "origin");
        String self = selfView(cacheRoot, origin);
        if (self != null) return new RepoArtifactStore(cacheRoot, self);
        if (isM2View(origin)) {
            // The local repository read as a file: repository is Central's view of it; the memos
            // are keyed by path, so a second name for the same tree shares them.
            return new RepoArtifactStore(
                    dirFor(cacheRoot, RepositorySpec.CENTRAL),
                    M2Dirs.localRepository(),
                    name,
                    RepoIdentity.canonicalOrigin(RepositorySpec.MAVEN_CENTRAL.url()),
                    false);
        }
        String canonical = RepoIdentity.canonicalOrigin(origin);
        boolean loopback = RepositorySpec.loopback(origin.getHost());
        RepoArtifactStore store = new RepoArtifactStore(
                dirFor(cacheRoot, RepoIdentity.storeId(origin)),
                M2Backing.rootFor(origin, m2integration).orElse(null),
                name,
                canonical,
                loopback);
        if (loopback) store.claim();
        return store;
    }

    /** The pid and start instant of {@code process}: distinct from every other process this machine ran. */
    static String processToken(ProcessHandle process) {
        String started = process.info()
                .startInstant()
                .map(instant -> Long.toHexString(instant.toEpochMilli()))
                .orElse("0");
        return Long.toHexString(process.pid()) + "-" + started;
    }

    /**
     * Make a loopback tree this process's: once per tree per process, a tree whose marker carries
     * another process's token (or none) is removed whole, so a stub that reappears on a reused port
     * in a later run never serves a predecessor's artifacts from disk; the first write then marks the
     * tree with this process's token, and a tree so marked is left as it is — within one process the
     * port is one holder. A tree nothing is written to is not created.
     */
    private void claim() {
        Path tree = Objects.requireNonNull(root, "a loopback store has a root");
        CLAIMS.computeIfAbsent(tree, t -> {
            if (PROCESS_TOKEN.equals(describe(t).process())) return Boolean.TRUE;
            try {
                if (Files.isDirectory(t)) PathUtil.deleteRecursivelyOrThrow(t);
            } catch (IOException e) {
                Log.warn("jk: warning: could not empty the loopback repository tree " + t + ": " + e);
            }
            return Boolean.TRUE;
        });
    }

    /**
     * The store a lockfile {@code source} ({@code "<name>+<url>"}) names: the identity of its URL
     * for a named remote, the first-party shelf for {@code jk-local} and synthetic sources.
     */
    public static RepoArtifactStore forSource(Path cacheRoot, String source) {
        return forSource(cacheRoot, source, true);
    }

    /** As {@link #forSource(Path, String)} for a project whose {@code m2integration} is {@code m2integration}. */
    public static RepoArtifactStore forSource(Path cacheRoot, String source, boolean m2integration) {
        RepoSource parsed = RepoSource.parse(source);
        String name = parsed.name();
        if (!RepoArtifactResolver.isNamedRemote(name)) return forStoreId(cacheRoot, RepoArtifactResolver.JK_LOCAL);
        String url = parsed.url();
        try {
            return forRepository(cacheRoot, Objects.requireNonNull(name), new URI(url.strip()), m2integration);
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            return new RepoArtifactStore(
                    dirFor(cacheRoot, RepoIdentity.storeId(url)), null, name, RepoIdentity.canonicalOrigin(url), false);
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

    /** True when {@code origin} is a {@code file:} URI at the Maven local repository. */
    private static boolean isM2View(URI origin) {
        if (!"file".equalsIgnoreCase(origin.getScheme()) || origin.getPath() == null) return false;
        try {
            return Path.of(origin)
                    .toAbsolutePath()
                    .normalize()
                    .equals(M2Dirs.localRepository().toAbsolutePath().normalize());
        } catch (IllegalArgumentException | FileSystemNotFoundException notLocal) {
            return false;
        }
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
     * Verify the artifact at {@code relativePath} against {@code expectedSha256} (the lock pin). A
     * {@code .jk} memo that still describes the file (size and mtime, one stat) answers for its
     * bytes; otherwise the file is re-hashed and the memo rewritten on a match.
     */
    public IndexState verify(String relativePath, String expectedSha256) {
        if (root == null) return IndexState.ABSENT;
        Path artifact = artifactPath(relativePath);
        if (!Files.isRegularFile(artifact)) return IndexState.ABSENT;
        try {
            Path sidecar = sidecarPath(relativePath);
            boolean ok = ArtifactMemo.verify(artifact, sidecar, inferGav(relativePath), expectedSha256);
            if (ok) stampUse(root, sidecar);
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
     * written from the file bytes when possible (jk install / leftover {@code .sha256} trees) —
     * except in the Maven local repository, where a file without a memo is one jk has not verified
     * and is not blessed by being looked at.
     */
    public Optional<Path> locate(String relativePath) {
        if (root == null) return Optional.empty();
        Path artifact = artifactPath(relativePath);
        if (!Files.isRegularFile(artifact)) return Optional.empty();
        Path sidecar = sidecarPath(relativePath);
        if (m2 == null && !Files.isRegularFile(sidecar)) {
            try {
                writeMemo(relativePath, artifact, Hashing.sha256Hex(artifact));
            } catch (IOException ignored) {
                // the jar is on disk; callers can still use it
            }
        }
        return Optional.of(artifact);
    }

    /** How stale a memo's mtime may get before a use refreshes it: coarse enough to be one stat a build, not a write. */
    private static final long USE_STAMP_GRANULARITY_MILLIS = 12L * 60 * 60 * 1000;

    /**
     * Record that the artifact was just used by touching its memo. The memo's contents describe the
     * artifact and do not change on a use; its mtime is the last-use clock retention reads, since an
     * artifact's own mtime is its download time and its atime is not something jk can rely on.
     */
    private static void stampUse(Path root, Path memo) {
        AccessLedger.at(root.resolve(ACCESS_DIR)).touch(useKey(root, memo));
        try {
            long now = Clock.SYSTEM.millis();
            if (now - Files.getLastModifiedTime(memo).toMillis() > USE_STAMP_GRANULARITY_MILLIS) {
                Files.setLastModifiedTime(memo, FileTime.fromMillis(now));
            }
        } catch (IOException e) {
            Log.debug("stampUse: a use that cannot be recorded costs retention a little accuracy", e);
        }
    }

    /** Under a repository tree: the ledger of how often its artifacts are used. */
    public static final String ACCESS_DIR = ".access";

    /** True for a tree's ledger directory, which a walk of the tree's artifacts must not enter. */
    public static boolean isAccessDir(Path dir) {
        return ACCESS_DIR.equals(String.valueOf(dir.getFileName()));
    }

    /** The ledger key of the artifact whose memo is {@code memo}, a file under the tree {@code root}. */
    public static String useKey(Path root, Path memo) {
        return "m2:" + root.relativize(memo).toString().replace('\\', '/');
    }

    /** SHA-256 hex from the materialised sidecar, if fully stored. */
    public Optional<String> storedSha256(String relativePath) {
        if (root == null || !contains(relativePath)) return Optional.empty();
        return readSha256Sidecar(relativePath);
    }

    /**
     * The {@code .jk} memos this process has read, by path: a lock asks for the same POM's digest
     * once per repository probe and per parent walk, and a sidecar changes only when its blob does.
     */
    private static final StoreFileMemo<String> SIDECARS = new StoreFileMemo<>(16_384, sha -> 1);

    /** Drop the sidecar memo and return how many went; force, tests and the idle engine. */
    public static int dropSidecarMemo() {
        return SIDECARS.clear();
    }

    /** How many sidecar files this process has read: the seam that proves the memo serves a repeat. */
    static long sidecarReads() {
        return SIDECARS.reads();
    }

    /**
     * SHA-256 from the {@code .jk} memo without re-statting the artifact. An unreadable memo answers
     * empty, as a missing one does.
     */
    public Optional<String> readSha256Sidecar(String relativePath) {
        if (root == null) return Optional.empty();
        try {
            return SIDECARS.get(sidecarPath(relativePath), file -> ArtifactMemo.read(file)
                            .map(ArtifactMemo::sha256)
                            .orElse(""))
                    .filter(sha -> !sha.isEmpty());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Write paths
    // -------------------------------------------------------------------------

    // A {@code .jk} memo may point at a blob outside this store (Maven local repo). locate() only
    // returns files that live here; ArtifactLocator checks the memo against ~/.m2 first.

    /**
     * Copy {@code source} into this store at {@code relativePath} and write the {@code .jk} memo.
     * Idempotent when the destination already verifies as {@code sha256}. Source may be the
     * destination (memo-only refresh). A stored file whose bytes hash to {@code sha256} is kept as it
     * is when {@code source} is another copy of them: a re-fetch rewrites neither it nor its memo.
     */
    public void materialize(String relativePath, Path source, String sha256) {
        materialize(relativePath, source, sha256, false);
    }

    /**
     * As {@link #materialize(String, Path, String)}; {@code fetched} says {@code source} is a
     * download jk just made (not an adoption or a copy), which the memo records.
     *
     * <p>In the Maven local repository a file jk did not download is not jk's to replace: a slot
     * holding other bytes stays as it is, and {@link #verify} then says it does not match. A file
     * jk downloaded and that no longer verifies is its own damage, and is replaced.
     */
    public void materialize(String relativePath, Path source, String sha256, boolean fetched) {
        if (root == null || source == null || !Files.isRegularFile(source)) return;
        Path artifact = MavenLayout.safeResolve(artifactDir(), relativePath);
        try {
            if (m2 != null && Files.isRegularFile(artifact) && !Files.isSameFile(artifact, source)) {
                boolean same = Hashing.sha256Hex(artifact).equalsIgnoreCase(sha256);
                boolean ours = ArtifactMemo.read(sidecarPath(relativePath))
                        .map(memo -> memo.fetched())
                        .orElse(false);
                if (!same && !ours) {
                    Log.warn("jk: warning: " + artifact + " holds other bytes than " + sha256
                            + "; it is not jk's file, so it is left as it is");
                    return;
                }
            }
            if (Files.isRegularFile(artifact)
                    && verify(relativePath, sha256) == IndexState.VERIFIED
                    && Files.isSameFile(artifact, source)) {
                return;
            }
            // Hashed rather than judged by the memo's stat, so a store copy corrupted in place under
            // the same size and mtime is still replaced by the fetch.
            if (Files.isRegularFile(artifact)
                    && !Files.isSameFile(artifact, source)
                    && Hashing.sha256Hex(artifact).equalsIgnoreCase(sha256)) {
                verify(relativePath, sha256);
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
            writeMemo(relativePath, artifact, sha256, fetched);
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
        writeMemo(relativePath, blob, sha256, false);
    }

    /** As {@link #writeMemo(String, Path, String)}; {@code fetched} records a download jk made, and a refresh of the same bytes keeps one already recorded. */
    public void writeMemo(String relativePath, Path blob, String sha256, boolean fetched) throws IOException {
        if (root == null || blob == null || !Files.isRegularFile(blob)) return;
        Path sidecar = sidecarPath(relativePath);
        Files.createDirectories(Objects.requireNonNull(sidecar.getParent(), "sidecar dir"));
        recordOrigin();
        Optional<ArtifactMemo> held =
                ArtifactMemo.read(sidecar).filter(memo -> memo.sha256().equalsIgnoreCase(sha256));
        @Nullable String packagedBy = held.map(ArtifactMemo::packagedBy).orElse(null);
        boolean downloaded = fetched || held.map(ArtifactMemo::fetched).orElse(false);
        ArtifactMemo.ofBlob(blob, inferGav(relativePath), sha256, packagedBy, downloaded)
                .write(sidecar);
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /**
     * What a store is on disk: its id, the label it was first filled under, its canonical origin,
     * and — a loopback tree — the token of the process that claimed it.
     */
    public record Origin(
            String id,
            @Nullable String name,
            @Nullable String origin,
            @Nullable String process) {
        /** True when nothing says which origin filled the tree: keyed by name, read by nothing. */
        public boolean isLegacy() {
            return origin == null && !RepoArtifactResolver.JK_LOCAL.equals(id);
        }
    }

    /**
     * Write {@link #ORIGIN_FILE} once for a store that knows its origin. A reserved tree gets one
     * too, so a listing does not have to know the reserved table; the first-party shelf has none. A
     * loopback tree's marker carries the claiming process's token as well.
     */
    private void recordOrigin() {
        if (root == null || origin == null) return;
        Path marker = root.resolve(ORIGIN_FILE);
        if (Files.exists(marker)) return;
        try {
            Files.createDirectories(root);
            String text = "origin = \"" + origin + "\"\n"
                    + (label == null ? "" : "name = \"" + label + "\"\n")
                    + (loopback ? "process = \"" + PROCESS_TOKEN + "\"\n" : "");
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
        String process = null;
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
                    else if (key.equals("process")) process = value;
                }
            } catch (IOException unreadable) {
                // fall through to the reserved table
            }
        }
        if (origin == null) origin = RepoIdentity.reservedOrigin(id);
        if (name == null && (origin != null || RepoArtifactResolver.JK_LOCAL.equals(id))) name = id;
        return new Origin(id, name, origin, process);
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
                if (RepoStoreDirs.isTree(child, attrs)) dirs.add(child);
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
     * offline cache search ({@code jk repo search}, {@code jk library search -o}). Empty
     * for {@link #NONE} or a cold store. Versions are in directory-listing order — callers wanting
     * newest-first should sort.
     */
    public List<Module> modules() {
        if (root == null || !Files.isDirectory(root)) return List.of();
        // The real path: a tree linked in from a shared pool is walked, not visited as one entry.
        Path tree;
        try {
            tree = root.toRealPath();
        } catch (IOException e) {
            return List.of();
        }
        // Group tracked files by their version directory (the file's parent).
        Map<Path, Boolean> versionDirs = new TreeMap<>();
        try {
            // A tree another process prunes while this walks loses that entry, not the whole store.
            PathUtil.forEachRegularFile(tree, RepoArtifactStore::isAccessDir, (file, attrs) -> {
                if (!isBookkeeping(file.getFileName().toString())) versionDirs.put(file.getParent(), Boolean.TRUE);
            });
        } catch (IOException e) {
            return List.of();
        }
        Map<String, List<String>> versionsByModule = new TreeMap<>();
        Map<String, String[]> ga = new HashMap<>();
        for (Path versionDir : versionDirs.keySet()) {
            Path rel = tree.relativize(versionDir);
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
            // In the Maven local repository the file is removed only when jk put it there: the
            // memo is read before it goes, since it is the proof.
            boolean ours = m2 == null
                    || ArtifactMemo.read(sidecarPath(relativePath))
                            .map(ArtifactMemo::fetched)
                            .orElse(false);
            removed = Files.deleteIfExists(sidecarPath(relativePath));
            if (ours) removed |= Files.deleteIfExists(artifactPath(relativePath));
        } catch (IOException e) {
            return removed;
        }
        return removed;
    }

    // -------------------------------------------------------------------------

    /** The artifact's file; its version directory is stamped as in use in a pooled tree ({@link PoolUse}). */
    private Path artifactPath(String relativePath) {
        Path dir = artifactDir();
        Path artifact = MavenLayout.safeResolve(dir, relativePath);
        if (m2 == null) PoolUse.touch(dir, artifact.getParent());
        return artifact;
    }

    /** Where this store's artifacts are: the Maven local repository when it keeps them, else its own tree. */
    private Path artifactDir() {
        return m2 != null ? m2 : rootOf(this);
    }

    /** True when this store's artifacts live in the Maven local repository, not its own tree. */
    public boolean m2Backed() {
        return m2 != null;
    }

    /**
     * The directory the {@code relativePath}s of this store's artifacts resolve under: the Maven
     * local repository for an {@linkplain #m2Backed m2-backed} one, else {@link #root()}. Readers
     * that build a path to a jar start here, never at {@link #root()}, which holds only memos for
     * the former.
     */
    public @Nullable Path artifactRoot() {
        return root == null ? null : artifactDir();
    }

    /** True when jk downloaded the artifact at {@code relativePath} and it is still the file it downloaded. */
    public boolean fetchedByJk(String relativePath) {
        if (root == null) return false;
        Path artifact = artifactPath(relativePath);
        try {
            return ArtifactMemo.read(sidecarPath(relativePath))
                    .map(memo -> {
                        try {
                            return memo.provesFetchedBy(artifact);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The SHA-256 the memo records for {@code relativePath} while it still describes the artifact
     * on disk (one stat, no read); empty when there is no memo, no file, or either has moved on.
     */
    public Optional<String> unchangedSha256(String relativePath) {
        if (root == null) return Optional.empty();
        Path artifact = artifactPath(relativePath);
        return ArtifactMemo.read(sidecarPath(relativePath)).flatMap(memo -> {
            try {
                return memo.stillMatches(artifact, memo.sha256()) ? Optional.of(memo.sha256()) : Optional.empty();
            } catch (IOException e) {
                return Optional.empty();
            }
        });
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
    public static String inferGav(String relativePath) {
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
     * Cas.putFile} — no network).
     *
     * @return the sha256 of the published bytes, lower-case hex
     */
    public static String writeToLocalStore(Path artifactRoot, String relativePath, Path source) throws IOException {
        return writeToLocalStore(artifactRoot, relativePath, source, null);
    }

    /** The lock every shelf publish under one store takes; lives at the shelf root. */
    public static final String SHELF_LOCK_NAME = ".shelf.lock";

    /**
     * As {@link #writeToLocalStore(Path, String, Path)}, recording {@code packagedBy} — the sha256
     * of the engine jar that built the artifact — in its memo, so the shelf can later be compared
     * with the engine the home names. Null records none (a client-side file install).
     *
     * <p>The bytes go into the store's artifact CAS before the shelf names them, so an engine
     * whose install pinned this sha is served from there once another install has replaced the
     * shelf path. The jar and its memo are published under one lock, the
     * memo describing the bytes that were staged: two installs interleaving on one slot leave a
     * memo that names the jar beside it, never the other install's.
     */
    public static String writeToLocalStore(
            Path artifactRoot, String relativePath, Path source, @Nullable String packagedBy) throws IOException {
        // The caller picks the root deliberately: the engine install plan passes the
        // store (where resolvers read since the cache/store split); plugin install-local may pass
        // an isolated --cache-dir root on purpose.
        Path shelf = artifactRoot.resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL);
        Path target = MavenLayout.safeResolve(shelf, relativePath);
        Path memoFile = target.resolveSibling(
                ArtifactMemo.jkFileName(target.getFileName().toString()));
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".part");
        // Failure path only — moveInto consumed tmp on success.
        boolean moved = false;
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            String hex = Hashing.sha256Hex(tmp);
            new Cas(artifactRoot).putFile(tmp, hex);
            // The move keeps the inode, so the staged copy's size and mtime are the published file's.
            ArtifactMemo memo = new ArtifactMemo(
                    inferGav(relativePath),
                    Files.getLastModifiedTime(tmp).toMillis(),
                    Files.size(tmp),
                    hex,
                    packagedBy);
            boolean[] published = new boolean[1];
            FileLocks.withLock(shelf.resolve(SHELF_LOCK_NAME), () -> {
                AtomicWrites.moveInto(tmp, target);
                published[0] = true;
                memo.write(memoFile);
            });
            moved = published[0];
            return hex;
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
    }

    /** A jk-owned side file of the tree, not an artifact: the origin marker, a checksum sidecar, the shelf lock. */
    private static boolean isBookkeeping(String fileName) {
        return ORIGIN_FILE.equals(fileName) || fileName.endsWith(".sha256") || SHELF_LOCK_NAME.equals(fileName);
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.config.TomlScan;
import cc.jumpkick.discovery.ProbeSupport;
import cc.jumpkick.discovery.ToolHealth;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Inventory of JumpKick-managed JDKs plus the Java / Graal defaults, at {@code
 * $JK_STATE_DIR/jk-jdks.toml}. Trees stay under {@link JkDirs#jdks()}; this file is the
 * cross-platform record (no data-dir symlinks).
 */
public final class JdkInventory {

    public static final String FILE_NAME = "jk-jdks.toml";

    private static final String DEFAULT_KEY = "default";
    private static final String GRAAL_DEFAULT_KEY = "graal-default";
    private static final String LEGACY_DEFAULT_KEY = "default-jdk";
    private static final String LEGACY_DEFAULT_HOME_KEY = "default-jdk-home";
    private static final String LEGACY_GRAAL_KEY = "default-graal-jdk";
    private static final String LEGACY_GRAAL_HOME_KEY = "default-graal-jdk-home";

    private static final ConcurrentHashMap<String, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path jdksRoot;
    private final Path file;
    private final Path userConfigFile;
    private final Path dataDir;

    public static JdkInventory current() {
        return of(JkDirs.jdks());
    }

    /** Inventory file in {@link JkDirs#state()}, trees under {@code jdksRoot}. */
    public static JdkInventory of(Path jdksRoot) {
        return SHARED.computeIfAbsent(
                jdksRoot.toAbsolutePath().normalize(),
                root -> new JdkInventory(
                        root, JkDirs.state().resolve(FILE_NAME), JkDirs.userConfigFile(), JkDirs.data()));
    }

    /**
     * One inventory per JDK root for the life of the process.
     *
     * <p>{@link #snapshot()} memoizes on {@code (size, mtime)} in <em>instance</em> fields, and this
     * factory built a fresh instance on every call — so the memo never survived, exactly the shape
     * JK-1033 fixed for {@code JdkRegistry}. {@code jk hook-env} runs on every shell prompt and asks
     * for defaultId, graalId, defaultHome and graalHome; a per-call instance re-read and re-parsed the
     * file for each (JK-1048).
     *
     * <p>Correctness is unchanged: the snapshot still re-stats on every read and re-parses when the
     * file moves, so sharing the instance shares the memo, not a stale answer.
     */
    private static final ConcurrentMap<Path, JdkInventory> SHARED = new ConcurrentHashMap<>();

    /** Test seam: forget every shared inventory, so the next {@link #of} re-reads. */
    public static void resetShared() {
        SHARED.clear();
    }

    /** Test seam: inventory file + jdks root, no config/symlink migration. */
    public JdkInventory(Path jdksRoot, Path file) {
        this(jdksRoot, file, null, null);
    }

    public JdkInventory(Path jdksRoot, Path file, Path userConfigFile, Path dataDir) {
        this.jdksRoot = Objects.requireNonNull(jdksRoot, "jdksRoot");
        this.file = Objects.requireNonNull(file, "file");
        this.userConfigFile = userConfigFile;
        this.dataDir = dataDir;
    }

    public Path file() {
        return file;
    }

    public Path jdksRoot() {
        return jdksRoot;
    }

    /** Identifier stored as {@code default}, if any. */
    public Optional<String> defaultId() {
        return Optional.ofNullable(snapshot().defaultId);
    }

    /** Identifier stored as {@code graal-default}, if any. */
    public Optional<String> graalId() {
        return Optional.ofNullable(snapshot().graalId);
    }

    /**
     * JAVA_HOME of the default JDK when the tree is still a compiler install. Empty when unset or
     * the recorded id no longer resolves.
     */
    public Optional<Path> defaultHome() {
        return defaultId().flatMap(this::homeOf);
    }

    /** JAVA_HOME of the default GraalVM (see {@link #defaultHome()}). */
    public Optional<Path> graalHome() {
        return graalId().flatMap(this::homeOf);
    }

    public void setDefault(InstalledJdk jdk) throws IOException {
        Objects.requireNonNull(jdk, "jdk");
        withExclusiveLock(() -> {
            Snapshot snap = loadLocked();
            snap = snap.upsert(rowFor(jdk, snap.row(jdk.identifier()), false)).withDefaultId(jdk.identifier());
            writeLocked(snap);
        });
    }

    public void setGraal(InstalledJdk jdk) throws IOException {
        Objects.requireNonNull(jdk, "jdk");
        withExclusiveLock(() -> {
            Snapshot snap = loadLocked();
            snap = snap.upsert(rowFor(jdk, snap.row(jdk.identifier()), false)).withGraalId(jdk.identifier());
            writeLocked(snap);
        });
    }

    public void clearDefault() throws IOException {
        withExclusiveLock(() -> writeLocked(loadLocked().withDefaultId(null)));
    }

    public void clearGraal() throws IOException {
        withExclusiveLock(() -> writeLocked(loadLocked().withGraalId(null)));
    }

    /**
     * Upsert a managed install. {@code computeHash} is true after extract/update; false for default
     * retargets that must not stall on a tree walk.
     */
    public void record(InstalledJdk jdk, boolean computeHash) throws IOException {
        Objects.requireNonNull(jdk, "jdk");
        // Fingerprint before taking the lock — the full-tree walk of a fresh install must not
        // block every other inventory reader/writer for its duration.
        Row row = rowFor(jdk, snapshot().row(jdk.identifier()), computeHash);
        withExclusiveLock(() -> writeLocked(loadLocked().upsert(row)));
    }

    /** Drop a row. Default ids that still name it are left for the caller to retarget. */
    public void remove(String id) throws IOException {
        if (id == null || id.isBlank()) return;
        withExclusiveLock(() -> {
            Snapshot snap = loadLocked();
            Map<String, Row> rows = new LinkedHashMap<>(snap.rows);
            rows.remove(id);
            writeLocked(new Snapshot(snap.defaultId, snap.graalId, rows));
        });
    }

    public List<Finding> verify() throws IOException {
        // Plain read: writes are atomic replaces, so verify must not hold the exclusive lock
        // across multi-hundred-MB fingerprint walks (it would starve installs and hook reads).
        Snapshot snap = snapshot();
        List<Finding> out = new ArrayList<>();
        for (Row row : snap.rows.values()) {
            out.add(checkRow(row));
        }
        for (Path dir : ownedInstallDirs()) {
            String id = dir.getFileName().toString();
            if (!snap.rows.containsKey(id)) {
                out.add(new Finding(Finding.Kind.UNTRACKED, id, dir, "owned tree is not in the inventory"));
            }
        }
        out.sort(Comparator.comparing((Finding f) -> f.kind().ordinal()).thenComparing(Finding::id));
        return out;
    }

    /**
     * Add untracked owned trees (hashed), drop rows whose tree is gone, fill empty hashes, keep
     * non-owned rows (external homes, IDE installs in a shared root) as identity rows, and keep
     * defaults whose row survived. Fingerprints are computed once, outside the lock — the walk
     * covers every install tree and must not starve concurrent installs or hook reads — and the
     * findings reuse that same pass instead of re-hashing.
     */
    public List<Finding> repair() throws IOException {
        Snapshot before = snapshot();
        Map<String, Row> owned = new LinkedHashMap<>();
        for (Path dir : ownedInstallDirs()) {
            String id = dir.getFileName().toString();
            Path home = IntellijJdkDir.javaHome(dir);
            owned.put(id, rowFor(new InstalledJdk(id, home), before.row(id), true));
        }
        Snapshot next = withExclusiveLockGet(() -> {
            Snapshot snap = loadLocked();
            Map<String, Row> rows = new LinkedHashMap<>(owned);
            for (Row row : snap.rows.values()) {
                if (rows.containsKey(row.id)) continue;
                Path dir = row.home != null ? IntellijJdkDir.installDirOf(row.home) : installDir(row.id);
                if (Files.isDirectory(dir) && hasJavac(IntellijJdkDir.javaHome(dir))) {
                    rows.put(row.id, row); // not jk-owned but alive: identity row, never dropped
                }
            }
            String def = snap.defaultId != null && rows.containsKey(snap.defaultId) ? snap.defaultId : null;
            String graal = snap.graalId != null && rows.containsKey(snap.graalId) ? snap.graalId : null;
            Snapshot merged = new Snapshot(def, graal, rows);
            writeLocked(merged);
            return merged;
        });
        List<Finding> out = new ArrayList<>();
        for (Row row : next.rows.values()) {
            if (owned.containsKey(row.id)) {
                // Fingerprinted moments ago in this very pass — re-walking the tree to compare
                // the hash against itself is pure cost. A null sha means that walk failed.
                out.add(
                        row.sha256 != null && !row.sha256.isBlank()
                                ? new Finding(Finding.Kind.OK, row.id, installDir(row.id), null)
                                : new Finding(Finding.Kind.UNHASHED, row.id, installDir(row.id), "fingerprint failed"));
            } else {
                out.add(checkRow(row)); // identity-only rows: cheap presence probe
            }
        }
        out.sort(Comparator.comparing((Finding f) -> f.kind().ordinal()).thenComparing(Finding::id));
        return out;
    }

    public Optional<Path> homeOf(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        // The row's recorded home is WHICH install the user chose — an external install can
        // share a basename with a tree under the jdks root, and probing the root first would
        // silently resolve to the wrong one (the invariant the old home-keyed scheme kept).
        Row row = snapshot().row(id);
        if (row != null && row.home != null) {
            Path home = IntellijJdkDir.javaHome(row.home);
            if (hasJavac(home)) return Optional.of(real(home));
            // Recorded home is gone; fall through — a same-id tree under the root may remain.
        }
        Path underRoot = IntellijJdkDir.javaHome(jdksRoot.resolve(id));
        if (hasJavac(underRoot)) return Optional.of(real(underRoot));
        return Optional.empty();
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private Finding checkRow(Row row) {
        Path dir = row.home != null ? IntellijJdkDir.installDirOf(row.home) : installDir(row.id);
        if (!Files.isDirectory(dir)) {
            return new Finding(Finding.Kind.MISSING, row.id, dir, "install directory is gone");
        }
        if (!JdkOwnership.isJkOwned(dir)) {
            // Not a jk-written tree: an external home (`jk jdk default ~/.sdkman/...`) or an
            // IDE-installed JDK in the shared jdks root. jk never fingerprinted it, other tools
            // legitimately touch it, and repair could not re-baseline what jk doesn't own —
            // presence and a working javac are the whole contract.
            return hasJavac(IntellijJdkDir.javaHome(dir))
                    ? new Finding(Finding.Kind.OK, row.id, dir, null)
                    : new Finding(Finding.Kind.MISSING, row.id, dir, "no working javac in tree");
        }
        if (row.sha256 == null || row.sha256.isBlank()) {
            return new Finding(Finding.Kind.UNHASHED, row.id, dir, "no sha256 recorded yet");
        }
        try {
            String actual = JdkFingerprint.compute(dir);
            if (!row.sha256.equalsIgnoreCase(actual)) {
                return new Finding(Finding.Kind.TAMPERED, row.id, dir, "sha256 mismatch");
            }
        } catch (IOException e) {
            return new Finding(Finding.Kind.TAMPERED, row.id, dir, "fingerprint failed: " + e.getMessage());
        }
        return new Finding(Finding.Kind.OK, row.id, dir, null);
    }

    private Path installDir(String id) {
        return jdksRoot.resolve(id);
    }

    private boolean isUnderJdksRoot(Path dir) {
        try {
            Path root = Files.exists(jdksRoot)
                    ? jdksRoot.toRealPath()
                    : jdksRoot.toAbsolutePath().normalize();
            Path canonical = dir.toRealPath();
            return canonical.startsWith(root);
        } catch (IOException e) {
            return dir.toAbsolutePath()
                    .normalize()
                    .startsWith(jdksRoot.toAbsolutePath().normalize());
        }
    }

    private Row rowFor(InstalledJdk jdk, Row existing, boolean computeHash) {
        Path home = jdk.home();
        Path dir = IntellijJdkDir.installDirOf(home);
        JdkHit hit = ProbeSupport.discoverJdk(home, "jk").orElse(null);
        JdkVendor vendor = hit != null ? hit.vendor() : JdkVendor.fromRelease(home);
        String version = hit != null ? hit.version() : (existing != null ? existing.version : "");
        boolean graal = vendor == JdkVendor.ORACLE_GRAALVM || vendor == JdkVendor.GRAALVM_CE;
        String token = vendor.jbPrefix().orElse("unknown");
        Instant touched = touchedOf(home);
        String sha = existing != null ? existing.sha256 : null;
        if (computeHash && isUnderJdksRoot(dir) && !JdkFingerprint.isAliasDir(dir)) {
            try {
                sha = JdkFingerprint.compute(dir);
            } catch (IOException ignored) {
                // Row still records identity; verify reports UNHASHED/TAMPERED.
            }
        }
        Path storedHome = isUnderJdksRoot(dir) ? null : home;
        return new Row(jdk.identifier(), token, version == null ? "" : version, graal, touched, sha, storedHome);
    }

    static Instant touchedOf(Path javaHome) {
        Path javac = JdkFingerprint.javac(javaHome);
        try {
            return Files.getLastModifiedTime(javac).toInstant().truncatedTo(ChronoUnit.SECONDS);
        } catch (IOException e) {
            return null;
        }
    }

    private void ensureMigrated() {
        if (Files.isRegularFile(file)) return;
        try {
            withExclusiveLock(() -> {
                if (Files.isRegularFile(file)) return;
                writeLocked(migrateLocked());
            });
        } catch (IOException ignored) {
            // Hook path: a failed migrate leaves TomlScan reading empty.
        }
    }

    private Snapshot cachedSnapshot;
    private long cachedSize = -1;
    private FileTime cachedModified;

    /**
     * Read-path snapshot, memoized on (size, mtime): {@code jk hook-env} runs on every shell
     * prompt and used to re-read and re-parse this file up to six times per invocation — one stat
     * plus at most one parse now serves defaultId/graalId/defaultHome/graalHome together.
     */
    private synchronized Snapshot snapshot() {
        ensureMigrated();
        try {
            // One readAttributes, not isRegularFile-then-readAttributes: it answers presence, size and
            // mtime together, and this runs on every shell prompt via `jk hook-env` (JK-1033).
            var attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) return Snapshot.empty();
            if (cachedSnapshot != null
                    && attrs.size() == cachedSize
                    && attrs.lastModifiedTime().equals(cachedModified)) {
                return cachedSnapshot;
            }
            Snapshot snap = parse(Files.readString(file, StandardCharsets.UTF_8));
            cachedSnapshot = snap;
            cachedSize = attrs.size();
            cachedModified = attrs.lastModifiedTime();
            return snap;
        } catch (IOException e) {
            return Snapshot.empty();
        }
    }

    private Snapshot loadLocked() throws IOException {
        if (!Files.isRegularFile(file)) return migrateLocked();
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    private Snapshot migrateLocked() throws IOException {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (Path dir : ownedInstallDirs()) {
            String id = dir.getFileName().toString();
            Path home = IntellijJdkDir.javaHome(dir);
            rows.put(id, rowFor(new InstalledJdk(id, home), null, false));
        }
        String def = null;
        String graal = null;
        if (userConfigFile != null && Files.isRegularFile(userConfigFile)) {
            var scan = TomlScan.scan(
                    userConfigFile,
                    LEGACY_DEFAULT_KEY,
                    LEGACY_DEFAULT_HOME_KEY,
                    LEGACY_GRAAL_KEY,
                    LEGACY_GRAAL_HOME_KEY);
            def = matchLegacy(scan.get(LEGACY_DEFAULT_HOME_KEY), scan.get(LEGACY_DEFAULT_KEY), rows);
            graal = matchLegacy(scan.get(LEGACY_GRAAL_HOME_KEY), scan.get(LEGACY_GRAAL_KEY), rows);
            stripLegacyKeys(userConfigFile);
        }
        deleteLeftoverSymlinks();
        return new Snapshot(def, graal, rows);
    }

    private String matchLegacy(String home, String id, Map<String, Row> rows) {
        if (home != null && !home.isBlank()) {
            Path recorded = Path.of(home);
            for (Row row : rows.values()) {
                Path rowHome = IntellijJdkDir.javaHome(installDir(row.id));
                try {
                    if (Files.exists(recorded) && rowHome.toRealPath().equals(recorded.toRealPath())) {
                        return row.id;
                    }
                } catch (IOException ignored) {
                    if (rowHome.equals(recorded)) return row.id;
                }
            }
            Path asId = installDirOfHome(recorded);
            if (asId != null && rows.containsKey(asId.getFileName().toString())) {
                return asId.getFileName().toString();
            }
            // The old scheme recorded the home precisely because a default can live outside the
            // owned trees (sdkman, system, IntelliJ). If it still works, synthesize the row
            // setDefault would have written instead of stranding an id nothing resolves.
            if (hasJavac(recorded)) {
                Path dir = installDirOfHome(recorded);
                String rid;
                if (dir != null && isUnderJdksRoot(dir)) {
                    rid = dir.getFileName() != null ? dir.getFileName().toString() : null;
                } else {
                    rid = id != null && !id.isBlank()
                            ? id
                            : (dir != null && dir.getFileName() != null
                                    ? dir.getFileName().toString()
                                    : null);
                }
                if (rid != null && !rid.isBlank()) {
                    rows.put(rid, rowFor(new InstalledJdk(rid, recorded), null, false));
                    return rid;
                }
            }
        }
        if (id != null && !id.isBlank() && rows.containsKey(id)) return id;
        return (id != null && !id.isBlank()) ? id : null;
    }

    private static Path installDirOfHome(Path home) {
        try {
            if (!Files.exists(home)) return null;
            return IntellijJdkDir.installDirOf(home.toRealPath());
        } catch (IOException e) {
            return IntellijJdkDir.installDirOf(home);
        }
    }

    private List<Path> ownedInstallDirs() {
        if (!Files.isDirectory(jdksRoot)) return List.of();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> entries = Files.list(jdksRoot)) {
            entries.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !JdkFingerprint.isAliasDir(p))
                    .filter(JdkOwnership::isJkOwned)
                    .forEach(out::add);
        } catch (IOException ignored) {
            return List.of();
        }
        return out;
    }

    private void deleteLeftoverSymlinks() {
        if (dataDir == null) return;
        for (String name : List.of("default-jdk", "current-jdk", "default-graal-jdk")) {
            try {
                Files.deleteIfExists(dataDir.resolve(name));
            } catch (IOException ignored) {
                // best-effort leftover cleanup
            }
        }
    }

    /**
     * Drop the four legacy default keys, and ONLY them: this can run from the shell hook on any
     * machine, so an untouched config must round-trip byte-for-byte (the old whole-file blank-line
     * collapse rewrote configs that had no legacy keys at all). Atomic replace — a concurrent
     * config writer must never observe a torn file.
     */
    private static void stripLegacyKeys(Path configFile) throws IOException {
        if (!Files.exists(configFile)) return;
        String existing = Files.readString(configFile, StandardCharsets.UTF_8);
        List<String> legacyKeys =
                List.of(LEGACY_DEFAULT_KEY, LEGACY_DEFAULT_HOME_KEY, LEGACY_GRAAL_KEY, LEGACY_GRAAL_HOME_KEY);
        List<String> kept = new ArrayList<>();
        boolean matched = false;
        for (String line : existing.split("\n", -1)) {
            String stripped = line.strip();
            boolean legacy = legacyKeys.stream()
                    .anyMatch(k -> stripped.startsWith(k)
                            && stripped.substring(k.length()).stripLeading().startsWith("="));
            if (legacy) {
                matched = true;
                continue;
            }
            kept.add(line);
        }
        if (!matched) return;
        AtomicWrites.replace(configFile, String.join("\n", kept));
    }

    private void writeLocked(Snapshot snap) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, render(snap));
        synchronized (this) {
            cachedSnapshot = null; // the (size, mtime) key alone could false-hit a same-second write
        }
    }

    static String render(Snapshot snap) {
        StringBuilder sb = new StringBuilder();
        sb.append("# JumpKick-managed JDK inventory. Written by `jk jdk`.\n");
        if (snap.defaultId != null && !snap.defaultId.isBlank()) {
            sb.append(DEFAULT_KEY)
                    .append(" = ")
                    .append(MinimalToml.quote(snap.defaultId))
                    .append('\n');
        }
        if (snap.graalId != null && !snap.graalId.isBlank()) {
            sb.append(GRAAL_DEFAULT_KEY)
                    .append(" = ")
                    .append(MinimalToml.quote(snap.graalId))
                    .append('\n');
        }
        List<Row> rows = new ArrayList<>(snap.rows.values());
        rows.sort(Comparator.comparing(r -> r.id));
        for (Row row : rows) {
            sb.append('\n').append("[[jdk]]\n");
            sb.append("id = ").append(MinimalToml.quote(row.id)).append('\n');
            sb.append("vendor = ").append(MinimalToml.quote(row.vendor)).append('\n');
            sb.append("version = ").append(MinimalToml.quote(row.version)).append('\n');
            sb.append("graal = ").append(row.graal).append('\n');
            if (row.touched != null) {
                sb.append("touched = ")
                        .append(row.touched.truncatedTo(ChronoUnit.SECONDS))
                        .append('\n');
            }
            if (row.sha256 != null && !row.sha256.isBlank()) {
                sb.append("sha256 = ").append(MinimalToml.quote(row.sha256)).append('\n');
            }
            if (row.home != null) {
                sb.append("home = ")
                        .append(MinimalToml.quote(row.home.toString()))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    static Snapshot parse(String body) {
        String defaultId = null;
        String graalId = null;
        Map<String, Row> rows = new LinkedHashMap<>();
        String section = "";
        RowAccum acc = new RowAccum();
        for (String raw : body.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[[")) {
                acc.flush(rows);
                acc.reset();
                section = "jdk";
                continue;
            }
            if (line.startsWith("[")) {
                acc.flush(rows);
                acc.reset();
                section = "";
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).strip();
            String value = unquote(line.substring(eq + 1).strip());
            if (section.isEmpty()) {
                if (DEFAULT_KEY.equals(key)) defaultId = value;
                else if (GRAAL_DEFAULT_KEY.equals(key)) graalId = value;
                continue;
            }
            switch (key) {
                case "id" -> acc.id = value;
                case "vendor" -> acc.vendor = value;
                case "version" -> acc.version = value;
                case "graal" -> acc.graal = Boolean.parseBoolean(value);
                case "touched" -> {
                    try {
                        acc.touched = Instant.parse(value);
                    } catch (RuntimeException ignored) {
                        acc.touched = null;
                    }
                }
                case "sha256" -> acc.sha256 = value;
                case "home" -> acc.home = Path.of(value);
                default -> {
                    // ignore additive fields
                }
            }
        }
        acc.flush(rows);
        return new Snapshot(blankToNull(defaultId), blankToNull(graalId), rows);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * Strip TOML quotes and decode basic-string escapes — the inverse of the
     * {@link MinimalToml#quote} this file's writer uses, so a Windows {@code home} path
     * ({@code C:\\Users\\…} on disk) reads back with single separators.
     */
    static String unquote(String v) {
        return MinimalToml.unquote(v);
    }

    private static boolean hasJavac(Path home) {
        return home != null && Files.isDirectory(home.resolve("bin")) && ToolHealth.hasJavac(home);
    }

    private void withExclusiveLock(IoRunnable body) throws IOException {
        withExclusiveLockGet(() -> {
            body.run();
            return null;
        });
    }

    private <T> T withExclusiveLockGet(IoSupplier<T> body) throws IOException {
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        Object jvmLock =
                JVM_LOCKS.computeIfAbsent(lockFile.toAbsolutePath().normalize().toString(), k -> new Object());
        synchronized (jvmLock) {
            if (lockFile.getParent() != null) Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock lock = channel.lock()) {
                return body.get();
            }
        }
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static final class RowAccum {
        String id;
        String vendor = "";
        String version = "";
        boolean graal;
        Instant touched;
        String sha256;
        Path home;

        void reset() {
            id = null;
            vendor = "";
            version = "";
            graal = false;
            touched = null;
            sha256 = null;
            home = null;
        }

        void flush(Map<String, Row> rows) {
            if (id == null || id.isBlank()) return;
            rows.put(id, new Row(id, vendor, version, graal, touched, sha256, home));
        }
    }

    static final class Snapshot {
        final String defaultId;
        final String graalId;
        final Map<String, Row> rows;

        Snapshot(String defaultId, String graalId, Map<String, Row> rows) {
            this.defaultId = defaultId;
            this.graalId = graalId;
            this.rows = rows;
        }

        static Snapshot empty() {
            return new Snapshot(null, null, Map.of());
        }

        Row row(String id) {
            return id == null ? null : rows.get(id);
        }

        Snapshot upsert(Row row) {
            Map<String, Row> next = new LinkedHashMap<>(rows);
            next.put(row.id, row);
            return new Snapshot(defaultId, graalId, next);
        }

        Snapshot withDefaultId(String id) {
            return new Snapshot(id, graalId, rows);
        }

        Snapshot withGraalId(String id) {
            return new Snapshot(defaultId, id, rows);
        }
    }

    public record Row(
            String id, String vendor, String version, boolean graal, Instant touched, String sha256, Path home) {}

    /**
     * One verify result. {@link Kind#OK} is a match; any other kind is a problem {@code jk jdk
     * verify} reports as a non-zero exit.
     */
    public record Finding(Kind kind, String id, Path installDir, String detail) {
        public enum Kind {
            OK,
            UNHASHED,
            UNTRACKED,
            MISSING,
            TAMPERED
        }

        public boolean ok() {
            return kind == Kind.OK;
        }
    }
}

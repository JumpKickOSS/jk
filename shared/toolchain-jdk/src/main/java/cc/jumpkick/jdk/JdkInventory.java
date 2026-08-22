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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        return new JdkInventory(jdksRoot, JkDirs.state().resolve(FILE_NAME), JkDirs.userConfigFile(), JkDirs.data());
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
        ensureMigrated();
        return topLevel(DEFAULT_KEY);
    }

    /** Identifier stored as {@code graal-default}, if any. */
    public Optional<String> graalId() {
        ensureMigrated();
        return topLevel(GRAAL_DEFAULT_KEY);
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
        withExclusiveLock(() -> {
            Snapshot snap = loadLocked();
            writeLocked(snap.upsert(rowFor(jdk, snap.row(jdk.identifier()), computeHash)));
        });
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
        ensureMigrated();
        Snapshot snap = withExclusiveLockGet(this::loadLocked);
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
     * Add untracked owned trees (hashed), drop missing rows, fill empty hashes, keep defaults only
     * when those ids still exist.
     */
    public List<Finding> repair() throws IOException {
        return withExclusiveLockGet(() -> {
            Snapshot snap = loadLocked();
            Map<String, Row> rows = new LinkedHashMap<>();
            for (Path dir : ownedInstallDirs()) {
                String id = dir.getFileName().toString();
                Path home = IntellijJdkDir.javaHome(dir);
                rows.put(id, rowFor(new InstalledJdk(id, home), snap.row(id), true));
            }
            for (Row row : snap.rows.values()) {
                if (row.home != null && hasJavac(IntellijJdkDir.javaHome(row.home))) {
                    rows.putIfAbsent(row.id, row);
                }
            }
            String def = snap.defaultId != null && rows.containsKey(snap.defaultId) ? snap.defaultId : null;
            String graal = snap.graalId != null && rows.containsKey(snap.graalId) ? snap.graalId : null;
            Snapshot next = new Snapshot(def, graal, rows);
            writeLocked(next);
            List<Finding> out = new ArrayList<>();
            for (Row row : next.rows.values()) out.add(checkRow(row));
            return out;
        });
    }

    public Optional<Path> homeOf(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        Path underRoot = IntellijJdkDir.javaHome(jdksRoot.resolve(id));
        if (hasJavac(underRoot)) return Optional.of(real(underRoot));
        Row row = loadQuiet().row(id);
        if (row != null && row.home != null) {
            Path home = IntellijJdkDir.javaHome(row.home);
            if (hasJavac(home)) return Optional.of(real(home));
        }
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
        if (!JdkOwnership.isJkOwned(dir) && (row.home == null || isUnderJdksRoot(dir))) {
            return new Finding(Finding.Kind.UNOWNED, row.id, dir, "missing " + JdkOwnership.MARKER);
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

    private Optional<String> topLevel(String key) {
        String v = TomlScan.scan(file, key).get(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
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

    private Snapshot loadQuiet() {
        try {
            ensureMigrated();
            if (!Files.isRegularFile(file)) return Snapshot.empty();
            return parse(Files.readString(file, StandardCharsets.UTF_8));
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

    private static void stripLegacyKeys(Path configFile) throws IOException {
        if (!Files.exists(configFile)) return;
        String existing = Files.readString(configFile, StandardCharsets.UTF_8);
        String updated = existing;
        for (String key :
                List.of(LEGACY_DEFAULT_KEY, LEGACY_DEFAULT_HOME_KEY, LEGACY_GRAAL_KEY, LEGACY_GRAAL_HOME_KEY)) {
            Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s*=\\s*.*$")
                    .matcher(updated);
            updated = m.replaceAll("");
        }
        updated = updated.replaceAll("(?m)^\\s*\\R", "");
        if (!updated.equals(existing)) {
            Files.writeString(configFile, updated, StandardCharsets.UTF_8);
        }
    }

    private void writeLocked(Snapshot snap) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, render(snap));
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

    /** Strip TOML basic/literal quotes; drop a trailing same-line comment on unquoted values. */
    static String unquote(String v) {
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
            char quote = v.charAt(0);
            int end = v.indexOf(quote, 1);
            return end > 0 ? v.substring(1, end) : v.substring(1);
        }
        int hash = v.indexOf('#');
        return (hash >= 0 ? v.substring(0, hash) : v).strip();
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
            UNOWNED,
            TAMPERED
        }

        public boolean ok() {
            return kind == Kind.OK;
        }
    }
}

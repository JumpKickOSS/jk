// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.AotCacheFiles;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.util.AotManifest;
import cc.jumpkick.util.AppInstallConfig;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The live JumpKick engine install: jars under {@code <product-lib>/jk-engine/} and a pointer file
 * {@code jk-engine.toml} beside them. The PATH {@code jk} is paired with this jar at install/update
 * time.
 *
 * <p>Materialize never renames or overwrites an existing jar (Windows cannot move a file {@code
 * java.exe} has memory-mapped). A free canonical name {@code jk-engine-<version>.jar} is used when
 * possible; a collision with different bytes publishes {@code jk-engine-<version>.<epochMillis>.jar}
 * beside the old file. {@link #gc} deletes every engine jar except the live name, plus parked PATH
 * binaries ({@code jk.old} / {@code jk.exe.old}).
 */
public final class EngineInstall {

    public static final String BIN_NAME = "jk-engine";
    public static final String LOCK_NAME = ".jk-engine.lock";
    public static final String POINTER_NAME = "jk-engine.toml";

    /**
     * {@code jk-engine-<version>.jar} or {@code jk-engine-<version>.<10-13 digit epoch>.jar}. The
     * epoch is {@link System#currentTimeMillis()} at publish, so it cannot collide with a version
     * segment.
     */
    private static final Pattern ENGINE_JAR_NAME = Pattern.compile("^jk-engine-(.+?)(?:\\.(\\d{10,13}))?\\.jar$");

    private final Path productLib;
    private final LongSupplier clock;

    public EngineInstall(Path productLibDir) {
        this(productLibDir, System::currentTimeMillis);
    }

    EngineInstall(Path productLibDir, LongSupplier clock) {
        this.productLib = productLibDir;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Rooted at the product lib, {@code <home>/lib}. */
    public static EngineInstall current() {
        return new EngineInstall(JkDirs.productLib());
    }

    /** One usable engine on disk. {@code root} is the engine home ({@code …/lib/jk-engine}). */
    public record Materialized(String version, Path root, Path engineJar, String engineSha) {}

    /** Product lib root ({@code …/lib}). */
    public Path libDir() {
        return productLib;
    }

    /** {@code <product-lib>/jk-engine}. */
    public Path engineHome() {
        return productLib.resolve(BIN_NAME);
    }

    /** Live pointer beside the jars. */
    public Path configFile() {
        return engineHome().resolve(POINTER_NAME);
    }

    /**
     * Live jar path from the pointer ({@code jar =}) or inferred under the engine home. May not
     * exist when the install is incomplete.
     */
    public Path engineJarPath() {
        return currentInstall().map(Materialized::engineJar).orElse(engineHome().resolve("jk-engine.jar"));
    }

    /** The live engine when the pointer + jar are present, or a single inferable jar. */
    public Optional<Materialized> currentInstall() {
        return readLive();
    }

    /**
     * The engine paired with product version {@code v}: the live jar when it is that version, else
     * a leftover jar of {@code v} still in the engine home (drain window until GC).
     */
    public Optional<Materialized> resolve(String v) {
        Optional<Materialized> live = currentInstall();
        if (live.isPresent() && live.get().version().equals(v)) return live;
        return inferVersion(v);
    }

    public Optional<Materialized> newest() {
        return currentInstall();
    }

    public Optional<String> engineSha(String version) {
        return resolve(version).map(Materialized::engineSha).filter(s -> !s.isBlank());
    }

    /**
     * Best-effort removal of retired engine jars, leftover temp/old names, parked PATH binaries,
     * and AOT caches that are not for the live product version. Never throws.
     */
    public List<Path> gc() {
        List<Path> removed = gc(JkDirs.binDir(), JkDirs.state());
        sweepAbandonedConfig(JkDirs.current().configDir(), removed);
        return removed;
    }

    /**
     * @param binDir PATH install directory ({@code jk.old} / {@code jk.exe.old}); {@code null} skips
     * @param stateDir engine state (+ AOT); {@code null} skips AOT
     */
    public List<Path> gc(@Nullable Path binDir, @Nullable Path stateDir) {
        List<Path> removed = new ArrayList<>();
        sweepRetiredJars(removed);
        sweepParkedClients(binDir, removed);
        sweepSupersededAot(stateDir, removed);
        return removed;
    }

    /**
     * Install {@code version}'s engine jar from CAS as {@code jk-engine-<version>.jar} when that
     * path is free, else {@code jk-engine-<version>.<epoch>.jar}. Identical live bytes are a no-op.
     * Refuses to replace a <em>newer</em> live install with an older version, newer being
     * {@link Versions#compare} — the one version order in the product.
     */
    public Materialized materialize(String version, Cas cas, String engineJarSha) throws IOException {
        Optional<Materialized> existing = currentInstall();
        if (sameLive(existing, version, engineJarSha) && pointerComplete(existing.get())) {
            return existing.get();
        }

        Files.createDirectories(engineHome());
        Path lockPath = engineHome().resolve(LOCK_NAME);
        try (FileChannel lockCh = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = lockCh.lock()) {
            Optional<Materialized> raced = currentInstall();
            if (sameLive(raced, version, engineJarSha)) {
                return confirmLive(raced.get(), version, engineJarSha);
            }
            if (raced.isPresent() && Versions.compare(raced.get().version(), version) > 0) {
                throw new IOException(
                        "refusing to replace jk-engine " + raced.get().version() + " with older " + version);
            }
            return materializeLocked(version, cas, engineJarSha);
        }
    }

    /**
     * Ingest {@code engineJar} into the CAS, then materialize.
     */
    public Materialized materializeFromFiles(String version, Cas cas, Path engineJar) throws IOException {
        String engineSha = Hashing.sha256Hex(engineJar);
        cas.putFile(engineJar, engineSha);
        return materialize(version, cas, engineSha);
    }

    public static Optional<Path> displaceToOld(Path live) throws IOException {
        if (live == null || !Files.exists(live)) return Optional.empty();
        Path old = parkedName(live);
        try {
            Files.deleteIfExists(old);
        } catch (IOException ignored) {
            // still mapped; park beside it below
        }
        try {
            Files.move(live, old, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(old);
        } catch (IOException occupied) {
            Path alt = live.resolveSibling(live.getFileName() + ".old-" + System.nanoTime());
            Files.move(live, alt, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(alt);
        }
    }

    public static Path parkedName(Path live) {
        return live.resolveSibling(live.getFileName() + ".old");
    }

    public static void installBinaries(Path clientSource, Path binDir) throws IOException {
        installBinaries(clientSource, binDir, Os.isWindows());
    }

    static void installBinaries(Path clientSource, Path binDir, boolean windows) throws IOException {
        Files.createDirectories(binDir);
        String jkName = windows ? "jk.exe" : "jk";
        String jkxName = windows ? "jkx.exe" : "jkx";
        Path jk = binDir.resolve(jkName);
        Path jkx = binDir.resolve(jkxName);
        displaceToOld(jk);
        displaceToOld(jkx);
        Path tmp = jk.resolveSibling("." + jkName + "-new");
        Files.deleteIfExists(tmp);
        // A copy, never a link: the source is a CAS blob whose bytes its digest vouches for, and a
        // linked bin/jk would share its inode — the chmod below, or any later rewrite of the PATH
        // client, would then change the verified blob in place.
        Files.copy(clientSource, tmp, StandardCopyOption.REPLACE_EXISTING);
        makeExecutable(tmp);
        AtomicWrites.moveInto(tmp, jk);
        try {
            Files.createLink(jkx, jk);
        } catch (IOException | UnsupportedOperationException noHardlink) {
            Files.copy(jk, jkx, StandardCopyOption.REPLACE_EXISTING);
        }
        makeExecutable(jkx);
    }

    public static int deleteSupersededEngineAot(Path aotDir, String keepVersion) {
        return wipeAotDirectory(aotDir, keepVersion);
    }

    public static int wipeAotDirectory(@Nullable Path aotDir) {
        return wipeAotDirectory(aotDir, null);
    }

    public static int wipeAotDirectory(@Nullable Path aotDir, @Nullable String keepVersion) {
        return wipeAotDirectory(aotDir, keepVersion, null);
    }

    static int wipeAotDirectory(@Nullable Path aotDir, @Nullable String keepVersion, @Nullable List<Path> removedOut) {
        if (aotDir == null || !Files.isDirectory(aotDir)) return 0;
        String kept = keepVersion == null || keepVersion.isBlank() ? null : keepVersion;
        boolean keepAny = kept != null;
        int aotFiles = 0;
        List<String> removedPrimaries = new ArrayList<>();
        try (var stream = Files.list(aotDir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".lock")) continue;
                if (name.equals(AotManifest.FILE_NAME)) {
                    continue;
                }
                if (!isAotArtifactName(name)) continue;
                if (kept != null && belongsToProductVersion(name, kept)) continue;
                if (!tryDelete(p, removedOut)) continue;
                boolean primary = isPrimaryAotCacheName(name);
                if (primary) {
                    aotFiles++;
                    removedPrimaries.add(name);
                } else if (AotCacheFiles.isMarker(name)) {
                    String primaryName = Objects.requireNonNull(AotCacheFiles.cacheOf(name));
                    if (isPrimaryAotCacheName(primaryName)) removedPrimaries.add(primaryName);
                } else if (name.endsWith(AotCacheFiles.CACHE + AotCacheFiles.CONFIG)) {
                    String primaryName = name.substring(0, name.length() - AotCacheFiles.CONFIG.length());
                    if (isPrimaryAotCacheName(primaryName)) removedPrimaries.add(primaryName);
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        if (!removedPrimaries.isEmpty()) {
            AotManifest.remove(aotDir, removedPrimaries);
            AotManifest.reconcile(aotDir);
        }
        if (!keepAny || !hasPrimaryAot(aotDir)) {
            try {
                Files.deleteIfExists(aotDir.resolve(AotManifest.FILE_NAME));
            } catch (IOException ignored) {
            }
        }
        return aotFiles;
    }

    static boolean belongsToProductVersion(String name, String ver) {
        if (name == null || ver == null || ver.isBlank()) return false;
        String needle = "-" + ver + "-";
        int i = name.indexOf(needle);
        while (i >= 0) {
            int keyStart = i + needle.length();
            if (keyStart + 16 <= name.length()) {
                String key = name.substring(keyStart, keyStart + 16);
                if (key.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                    char after = keyStart + 16 < name.length() ? name.charAt(keyStart + 16) : '\0';
                    if (after == '\0' || after == '.') return true;
                }
            }
            i = name.indexOf(needle, i + 1);
        }
        return false;
    }

    static boolean isPrimaryAotCacheName(String name) {
        return name != null
                && name.endsWith(AotCacheFiles.CACHE)
                && name.length() > AotCacheFiles.CACHE.length()
                && !name.contains(AotCacheFiles.CACHE + ".");
    }

    /**
     * Everything the AOT directory can hold for one key. The bare {@link AotCacheFiles#MARKER} test
     * rather than {@link AotCacheFiles#isMarker} is deliberate: markers written under the retired
     * {@code <stem>.noaot} spelling are orphans no reader recognises, and a wipe is the one sweep
     * that should still reclaim them.
     */
    static boolean isAotArtifactName(String name) {
        if (name == null || name.isBlank()) return false;
        return name.endsWith(AotCacheFiles.CACHE) || AotCacheFiles.isSidecar(name);
    }

    static boolean isParkedClientName(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith(".jk-old") || n.startsWith(".jkx-old")) return true;
        if (n.equals("jk.old.exe") || n.equals("jkx.old.exe")) return true;
        return n.startsWith("jk.old")
                || n.startsWith("jk.exe.old")
                || n.startsWith("jk.bat.old")
                || n.startsWith("jk.cmd.old")
                || n.startsWith("jkx.old")
                || n.startsWith("jkx.exe.old")
                || n.startsWith("jkx.bat.old")
                || n.startsWith("jkx.cmd.old");
    }

    /**
     * Product version encoded in {@code jk-engine-<version>.jar} or {@code
     * jk-engine-<version>.<epoch>.jar}.
     */
    public static Optional<String> versionFromJarName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        Matcher m = ENGINE_JAR_NAME.matcher(name);
        if (!m.matches()) return Optional.empty();
        String version = m.group(1);
        return version == null || version.isBlank() ? Optional.empty() : Optional.of(version);
    }

    static boolean isEngineJarName(String name) {
        return name != null && ENGINE_JAR_NAME.matcher(name).matches();
    }

    // ---- internals -------------------------------------------------------

    private Materialized materializeLocked(String version, Cas cas, String engineJarSha) throws IOException {
        Path dest = allocateJarPath(version);
        Path jarTmp = Files.createTempFile(engineHome(), ".jk-engine-", ".tmp");
        try {
            Files.copy(cas.pathFor(engineJarSha), jarTmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(jarTmp, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(jarTmp, dest);
            }
        } finally {
            Files.deleteIfExists(jarTmp);
        }
        writePointer(version, engineJarSha, dest.getFileName().toString());
        return currentInstall()
                .orElseThrow(() -> new IOException("materialization of " + version + " left no engine jar"));
    }

    /**
     * Canonical {@code jk-engine-<version>.jar} when that path does not exist; otherwise {@code
     * jk-engine-<version>.<epoch>.jar}, incrementing the epoch until the name is free. Never
     * returns a path that already exists.
     */
    Path allocateJarPath(String version) throws IOException {
        Path canonical = engineHome().resolve("jk-engine-" + version + ".jar");
        if (!Files.exists(canonical)) return canonical;
        long epoch = clock.getAsLong();
        for (int i = 0; i < 10_000; i++) {
            Path candidate = engineHome().resolve("jk-engine-" + version + "." + (epoch + i) + ".jar");
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IOException("could not allocate a unique engine jar name for " + version);
    }

    private void writePointer(String version, String engineJarSha, String jarFileName) throws IOException {
        // jk-config.* properties are the base; the COMPUTED identity keys are written last so they
        // always win. A stray -Djk-config.engine-sha256 could otherwise poison the digest and make
        // every command see a build-id mismatch and respawn the engine.
        Map<String, String> keys = new LinkedHashMap<>(AppInstallConfig.jkConfigProperties());
        keys.put("version", version);
        keys.put("engine-sha256", engineJarSha);
        keys.put("protocol", "1");
        keys.put("jar", jarFileName);
        // Durably: a pointer torn by a power loss names no jar, and every later command sees an
        // install it cannot start rather than one it can re-derive.
        AtomicWrites.replaceDurably(configFile(), AppInstallConfig.render(keys));
    }

    private Optional<Materialized> readLive() {
        Path pointer = configFile();
        if (Files.isRegularFile(pointer)) {
            try {
                Map<String, String> cfg = AppInstallConfig.parse(Files.readString(pointer));
                Optional<Materialized> named = fromPointer(cfg);
                if (named.isPresent()) return named;
            } catch (IOException ignored) {
                // fall through to directory inference
            }
        }
        return inferNewest();
    }

    private Optional<Materialized> fromPointer(Map<String, String> cfg) {
        String version = cfg.get("version");
        String sha = cfg.getOrDefault("engine-sha256", "");
        String jarName = cfg.get("jar");
        Path jar;
        if (jarName != null && !jarName.isBlank()) {
            jar = engineHome().resolve(jarName);
        } else {
            return Optional.empty();
        }
        if (!Files.isRegularFile(jar)) return Optional.empty();
        if (version == null || version.isBlank()) {
            version = versionFromJarName(jar.getFileName().toString()).orElse(null);
            if (version == null) return Optional.empty();
        }
        return Optional.of(new Materialized(version, engineHome(), jar, sha));
    }

    private Optional<Materialized> inferNewest() {
        return newestEngineJar().flatMap(this::materializedFromJar);
    }

    private Optional<Materialized> inferVersion(String version) {
        if (version == null || version.isBlank()) return Optional.empty();
        Path home = engineHome();
        if (!Files.isDirectory(home)) return Optional.empty();
        Path best = null;
        long bestMtime = Long.MIN_VALUE;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(home, "jk-engine-*.jar")) {
            for (Path p : entries) {
                if (!Files.isRegularFile(p)) continue;
                if (!version.equals(
                        versionFromJarName(p.getFileName().toString()).orElse(null))) continue;
                long mtime = mtime(p);
                if (best == null || mtime >= bestMtime) {
                    best = p;
                    bestMtime = mtime;
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return best == null ? Optional.empty() : materializedFromJar(best);
    }

    private Optional<Materialized> materializedFromJar(Path jar) {
        String version = versionFromJarName(jar.getFileName().toString()).orElse(null);
        if (version == null) return Optional.empty();
        return Optional.of(new Materialized(version, engineHome(), jar, ""));
    }

    private Optional<Path> newestEngineJar() {
        Path home = engineHome();
        if (!Files.isDirectory(home)) return Optional.empty();
        Path best = null;
        long bestMtime = Long.MIN_VALUE;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(home, "jk-engine-*.jar")) {
            for (Path p : entries) {
                if (!Files.isRegularFile(p) || !isEngineJarName(p.getFileName().toString())) continue;
                long mtime = mtime(p);
                if (best == null || mtime >= bestMtime) {
                    best = p;
                    bestMtime = mtime;
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(best);
    }

    private Materialized confirmLive(Materialized m, String version, String sha) throws IOException {
        if (pointerComplete(m) && sha.equalsIgnoreCase(m.engineSha())) return m;
        writePointer(version, sha, m.engineJar().getFileName().toString());
        return new Materialized(version, m.root(), m.engineJar(), sha);
    }

    private boolean pointerComplete(Materialized m) {
        return Files.isRegularFile(configFile())
                && m.engineSha() != null
                && !m.engineSha().isBlank();
    }

    private boolean sameLive(Optional<Materialized> existing, String version, String engineJarSha) {
        if (existing.isEmpty()) return false;
        Materialized m = existing.get();
        if (!m.version().equals(version) || !Files.isRegularFile(m.engineJar())) return false;
        if (engineJarSha != null && engineJarSha.equalsIgnoreCase(m.engineSha())) return true;
        if (m.engineSha() == null || m.engineSha().isBlank()) {
            try {
                return engineJarSha != null && engineJarSha.equalsIgnoreCase(Hashing.sha256Hex(m.engineJar()));
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    private void sweepRetiredJars(List<Path> removed) {
        Path home = engineHome();
        if (!Files.isDirectory(home)) return;
        String liveName = currentInstall()
                .map(m -> m.engineJar().getFileName().toString())
                .orElse(null);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(home)) {
            for (Path p : entries) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (LOCK_NAME.equals(name) || POINTER_NAME.equals(name)) continue;
                if (liveName != null && liveName.equals(name)) continue;
                if (isEngineJarName(name) || name.endsWith(".tmp") || name.endsWith(".old") || name.contains(".old-")) {
                    tryDelete(p, removed);
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Drop {@code <config>/jk-engine/} — the live pointer is {@code jk-engine.toml} beside the jars. */
    static void sweepAbandonedConfig(Path configDir, List<Path> removed) {
        if (configDir == null) return;
        Path dir = configDir.resolve(BIN_NAME);
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                if (Files.isRegularFile(p)) tryDelete(p, removed);
            }
        } catch (IOException ignored) {
            // best-effort
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // still contains something we could not delete
        }
    }

    private static void sweepParkedClients(@Nullable Path binDir, List<Path> removed) {
        if (binDir == null || !Files.isDirectory(binDir)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(binDir)) {
            for (Path p : entries) {
                if (!Files.isRegularFile(p) && !Files.isSymbolicLink(p)) continue;
                if (isParkedClientName(p.getFileName().toString())) tryDelete(p, removed);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static boolean tryDelete(@Nullable Path p, @Nullable List<Path> removed) {
        if (p == null) return false;
        try {
            if (!Files.exists(p)) return false;
            Files.deleteIfExists(p);
            if (Files.exists(p)) return false;
            if (removed != null) removed.add(p);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void sweepSupersededAot(@Nullable Path stateDir, List<Path> removed) {
        if (stateDir == null) return;
        String keep = currentInstall().map(Materialized::version).orElse(null);
        if (keep == null || keep.isBlank()) return;
        wipeAotDirectory(stateDir.resolve("aot"), keep, removed);
    }

    private static boolean hasPrimaryAot(Path aotDir) {
        try (var stream = Files.list(aotDir)) {
            return stream.map(p -> p.getFileName().toString()).anyMatch(EngineInstall::isPrimaryAotCacheName);
        } catch (IOException e) {
            return false;
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void makeExecutable(Path p) {
        try {
            var perms = Files.getPosixFilePermissions(p);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / restricted FS
        }
    }
}

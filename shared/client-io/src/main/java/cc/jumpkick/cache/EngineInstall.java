// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.util.AppInstallConfig;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.Hashing;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
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
import java.util.Optional;

/**
 * The live JumpKick engine install: jar under {@code <product-lib>/jk-engine/} and metadata in
 * {@code <config>/jk-engine/config.toml} ({@link AppInstallConfig}). The PATH {@code jk} is paired
 * with this jar at install/update time.
 *
 * <p>An upgrade parks the previous jar as {@code <name>.jar.old} (and {@code config.toml.old}) so a
 * draining engine can keep its mapped bytes for a few minutes. {@link #gc} deletes parked files and
 * parked PATH binaries ({@code jk.old} / {@code jk.exe.old}).
 */
public final class EngineInstall {

    public static final String BIN_NAME = "jk-engine";
    public static final String LOCK_NAME = ".jk-engine.lock";

    private final Path productLib;
    private final JkDirs dirs;

    public EngineInstall(Path productLibDir) {
        this(productLibDir, JkDirs.current());
    }

    public EngineInstall(Path productLibDir, JkDirs dirs) {
        this.productLib = productLibDir;
        this.dirs = dirs;
    }

    /** Rooted at {@code $JK_HOME/lib} (or {@code <data>/lib}). */
    public static EngineInstall current() {
        return new EngineInstall(JkDirs.productLib(), JkDirs.current());
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

    public Path configFile() {
        return AppInstallConfig.path(dirs, BIN_NAME);
    }

    public Path configFileOld() {
        return parkedName(configFile());
    }

    /**
     * Live jar path from config ({@code jar =}) or inferred under the engine home. May not exist
     * when the install is incomplete.
     */
    public Path engineJarPath() {
        return resolveLiveJarPath().orElse(engineHome().resolve("jk-engine.jar"));
    }

    /** Parked previous live jar, if any (drain window). */
    public Path engineJarOldPath() {
        Optional<Materialized> parked = readParked();
        if (parked.isPresent()) return parked.get().engineJar();
        return engineHome().resolve("jk-engine.jar.old");
    }

    /** The live engine when config + jar are present. */
    public Optional<Materialized> currentInstall() {
        return readLive();
    }

    /**
     * The engine paired with product version {@code v}: the live jar when it is that version, else
     * the parked jar when that is {@code v} (drain window for a just-replaced client).
     */
    public Optional<Materialized> resolve(String v) {
        Optional<Materialized> live = currentInstall();
        if (live.isPresent() && live.get().version().equals(v)) return live;
        Optional<Materialized> parked = readParked();
        if (parked.isPresent() && parked.get().version().equals(v)) return parked;
        return Optional.empty();
    }

    public Optional<Materialized> newest() {
        return currentInstall();
    }

    public Optional<String> engineSha(String version) {
        return resolve(version).map(Materialized::engineSha).filter(s -> !s.isBlank());
    }

    /**
     * Best-effort removal of parked engine jar/config, parked PATH binaries, and AOT caches that
     * are not for the live product version. Never throws.
     */
    public List<Path> gc() {
        return gc(JkDirs.binDir(), JkDirs.state());
    }

    /**
     * @param binDir PATH install directory ({@code jk.old} / {@code jk.exe.old}); {@code null} skips
     * @param stateDir engine state (+ AOT); {@code null} skips AOT
     */
    public List<Path> gc(Path binDir, Path stateDir) {
        List<Path> removed = new ArrayList<>();
        tryDelete(engineJarOldPath(), removed);
        tryDelete(configFileOld(), removed);
        sweepParkedJarsInEngineHome(removed);
        sweepParkedClients(binDir, removed);
        sweepSupersededAot(stateDir, removed);
        return removed;
    }

    /** @deprecated use {@link #gc(Path, Path)}; legacyVersions is ignored. */
    @Deprecated
    public List<Path> gc(Path binDir, Path legacyVersions, Path stateDir) {
        return gc(binDir, stateDir);
    }

    /**
     * Install {@code version}'s engine jar from CAS using the default release basename
     * {@code jk-engine-<version>.jar}.
     */
    public Materialized materialize(String version, Cas cas, String engineJarSha) throws IOException {
        return materialize(version, cas, engineJarSha, "jk-engine-" + version + ".jar");
    }

    /**
     * Install {@code version}'s engine jar from CAS as {@code jarFileName} under the engine home.
     * Parks a different live jar as {@code .old}. Identical bytes are a no-op. Refuses to replace a
     * <em>newer</em> live install with an older version.
     */
    public Materialized materialize(String version, Cas cas, String engineJarSha, String jarFileName)
            throws IOException {
        Optional<Materialized> existing = currentInstall();
        if (existing.isPresent()
                && existing.get().version().equals(version)
                && hasContent(existing.get(), engineJarSha)
                && existing.get().engineJar().getFileName().toString().equals(jarFileName)) {
            return existing.get();
        }

        Files.createDirectories(engineHome());
        Path lockPath = engineHome().resolve(LOCK_NAME);
        try (FileChannel lockCh = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = lockCh.lock()) {
            Optional<Materialized> raced = currentInstall();
            if (raced.isPresent()
                    && raced.get().version().equals(version)
                    && hasContent(raced.get(), engineJarSha)
                    && raced.get().engineJar().getFileName().toString().equals(jarFileName)) {
                return raced.get();
            }
            if (raced.isPresent() && compare(raced.get().version(), version) > 0) {
                throw new IOException(
                        "refusing to replace jk-engine " + raced.get().version() + " with older " + version);
            }
            return materializeLocked(version, cas, engineJarSha, jarFileName);
        }
    }

    /**
     * Ingest {@code engineJar} into the CAS, then materialize using the source file's basename.
     */
    public Materialized materializeFromFiles(String version, Cas cas, Path engineJar) throws IOException {
        String engineSha = Hashing.sha256Hex(engineJar);
        cas.putFile(engineJar, engineSha);
        String jarName = engineJar.getFileName().toString();
        return materialize(version, cas, engineSha, jarName);
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
        installBinaries(clientSource, binDir, windowsOs());
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
        try {
            Files.createLink(tmp, clientSource);
        } catch (IOException | UnsupportedOperationException noHardlink) {
            Files.copy(clientSource, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
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

    public static int wipeAotDirectory(Path aotDir) {
        return wipeAotDirectory(aotDir, null);
    }

    public static int wipeAotDirectory(Path aotDir, String keepVersion) {
        return wipeAotDirectory(aotDir, keepVersion, null);
    }

    static int wipeAotDirectory(Path aotDir, String keepVersion, List<Path> removedOut) {
        if (aotDir == null || !Files.isDirectory(aotDir)) return 0;
        boolean keepAny = keepVersion != null && !keepVersion.isBlank();
        int aotFiles = 0;
        List<String> removedPrimaries = new ArrayList<>();
        try (var stream = Files.list(aotDir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".lock")) continue;
                if (name.equals(cc.jumpkick.util.AotManifest.FILE_NAME)) {
                    continue;
                }
                if (!isAotArtifactName(name)) continue;
                if (keepAny && belongsToProductVersion(name, keepVersion)) continue;
                if (!tryDelete(p, removedOut)) continue;
                boolean primary = isPrimaryAotCacheName(name);
                if (primary) {
                    aotFiles++;
                    removedPrimaries.add(name);
                } else if (name.endsWith(".aot.noaot") || name.endsWith(".aot.config")) {
                    String primaryName = name.endsWith(".aot.noaot")
                            ? name.substring(0, name.length() - ".noaot".length())
                            : name.substring(0, name.length() - ".config".length());
                    if (isPrimaryAotCacheName(primaryName)) removedPrimaries.add(primaryName);
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        if (!removedPrimaries.isEmpty()) {
            cc.jumpkick.util.AotManifest.remove(aotDir, removedPrimaries);
            cc.jumpkick.util.AotManifest.reconcile(aotDir);
        }
        if (!keepAny || !hasPrimaryAot(aotDir)) {
            try {
                Files.deleteIfExists(aotDir.resolve(cc.jumpkick.util.AotManifest.FILE_NAME));
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
        return name != null && name.endsWith(".aot") && name.length() > 4 && !name.contains(".aot.");
    }

    static boolean isAotArtifactName(String name) {
        if (name == null || name.isBlank()) return false;
        return name.endsWith(".aot")
                || name.endsWith(".noaot")
                || name.endsWith(".config")
                || name.endsWith(".training")
                || name.contains(".tmp-");
    }

    public static int compare(String a, String b) {
        String[] an = a.split("-", 2);
        String[] bn = b.split("-", 2);
        String[] as = an[0].split("\\.");
        String[] bs = bn[0].split("\\.");
        for (int i = 0; i < Math.max(as.length, bs.length); i++) {
            long av = i < as.length ? parse(as[i]) : 0;
            long bv = i < bs.length ? parse(bs[i]) : 0;
            if (av != bv) return Long.compare(av, bv);
        }
        boolean aq = an.length > 1;
        boolean bq = bn.length > 1;
        if (aq != bq) return aq ? -1 : 1;
        if (!aq) return 0;
        return an[1].compareTo(bn[1]);
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

    // ---- internals -------------------------------------------------------

    private Materialized materializeLocked(String version, Cas cas, String engineJarSha, String jarFileName)
            throws IOException {
        Optional<Materialized> live = currentInstall();
        if (live.isPresent()) {
            displaceToOld(live.get().engineJar());
            displaceToOld(configFile());
        } else {
            try {
                Path incomplete = resolveLiveJarPath().orElse(null);
                if (incomplete != null) Files.deleteIfExists(incomplete);
                Files.deleteIfExists(configFile());
            } catch (IOException e) {
                Path incomplete = resolveLiveJarPath().orElse(null);
                if (incomplete != null) displaceToOld(incomplete);
                displaceToOld(configFile());
            }
        }
        Path dest = engineHome().resolve(jarFileName);
        Path jarTmp = Files.createTempFile(engineHome(), ".jk-engine-", ".tmp");
        try {
            Files.copy(cas.pathFor(engineJarSha), jarTmp, StandardCopyOption.REPLACE_EXISTING);
            AtomicWrites.moveInto(jarTmp, dest);
        } finally {
            Files.deleteIfExists(jarTmp);
        }
        writeConfig(version, engineJarSha, jarFileName);
        return currentInstall()
                .orElseThrow(() -> new IOException("materialization of " + version + " left no engine jar"));
    }

    private void writeConfig(String version, String engineJarSha, String jarFileName) throws IOException {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("version", version);
        keys.put("engine-sha256", engineJarSha);
        keys.put("protocol", "1");
        keys.put("jar", jarFileName);
        keys.putAll(AppInstallConfig.jkConfigProperties());
        AppInstallConfig.write(dirs, BIN_NAME, keys);
    }

    private Optional<Materialized> readLive() {
        Map<String, String> cfg = AppInstallConfig.read(dirs, BIN_NAME);
        return materializeFromConfig(cfg, false);
    }

    private Optional<Materialized> readParked() {
        Path oldCfg = configFileOld();
        if (!Files.isRegularFile(oldCfg)) return Optional.empty();
        try {
            Map<String, String> cfg = AppInstallConfig.parse(Files.readString(oldCfg));
            return materializeFromConfig(cfg, true);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<Materialized> materializeFromConfig(Map<String, String> cfg, boolean parked) {
        String version = cfg.get("version");
        String sha = cfg.getOrDefault("engine-sha256", "");
        String jarName = cfg.get("jar");
        Path jar;
        if (jarName != null && !jarName.isBlank()) {
            jar = engineHome().resolve(jarName);
            if (parked) jar = parkedName(jar);
        } else {
            jar = inferJar(parked).orElse(null);
            if (jar == null) return Optional.empty();
        }
        if (!Files.isRegularFile(jar)) return Optional.empty();
        if (version == null || version.isBlank()) {
            // The install metadata is gone (e.g. `jk self nuke --config` deleted <config>/jk-engine/
            // config.toml) but the jar survives under product-lib as the nuke promised. Recover the
            // version from the jar name so the kept engine still resolves instead of being orphaned
            // (JK-2294); sha stays blank, which engineSha()/spawn already tolerate.
            version = versionFromJarName(jar.getFileName().toString()).orElse(null);
            if (version == null) return Optional.empty();
        }
        return Optional.of(new Materialized(version, engineHome(), jar, sha));
    }

    /** Extract {@code <version>} from a {@code jk-engine-<version>.jar} (or parked {@code ….jar.old}) name. */
    public static Optional<String> versionFromJarName(String name) {
        String prefix = "jk-engine-";
        if (name == null || !name.startsWith(prefix)) return Optional.empty();
        String rest = name.substring(prefix.length());
        int dotJar = rest.indexOf(".jar");
        if (dotJar <= 0) return Optional.empty();
        return Optional.of(rest.substring(0, dotJar));
    }

    private Optional<Path> resolveLiveJarPath() {
        Map<String, String> cfg = AppInstallConfig.read(dirs, BIN_NAME);
        String jarName = cfg.get("jar");
        if (jarName != null && !jarName.isBlank())
            return Optional.of(engineHome().resolve(jarName));
        return inferJar(false);
    }

    private Optional<Path> inferJar(boolean parked) {
        Path home = engineHome();
        if (!Files.isDirectory(home)) return Optional.empty();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(home, "*.jar")) {
            List<Path> hits = new ArrayList<>();
            for (Path p : entries) {
                String name = p.getFileName().toString();
                boolean isOld = name.endsWith(".old") || name.contains(".old-");
                if (parked == isOld) hits.add(p);
            }
            if (hits.size() == 1) return Optional.of(hits.get(0));
            return hits.stream()
                    .filter(p -> p.getFileName().toString().startsWith("jk-engine-"))
                    .max((a, b) ->
                            Long.compare(a.toFile().lastModified(), b.toFile().lastModified()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private boolean hasContent(Materialized m, String engineJarSha) {
        return engineJarSha != null && engineJarSha.equalsIgnoreCase(m.engineSha());
    }

    private void sweepParkedJarsInEngineHome(List<Path> removed) {
        Path home = engineHome();
        if (!Files.isDirectory(home)) return;
        String liveName = currentInstall()
                .map(m -> m.engineJar().getFileName().toString())
                .orElse(null);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(home)) {
            for (Path p : entries) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (LOCK_NAME.equals(name)) continue;
                if (liveName != null && liveName.equals(name)) continue;
                if (name.endsWith(".old") || name.contains(".old-") || name.endsWith(".tmp")) {
                    tryDelete(p, removed);
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void sweepParkedClients(Path binDir, List<Path> removed) {
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

    private static boolean tryDelete(Path p, List<Path> removed) {
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

    private void sweepSupersededAot(Path stateDir, List<Path> removed) {
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

    private static long parse(String seg) {
        try {
            return Long.parseLong(seg);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean windowsOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
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

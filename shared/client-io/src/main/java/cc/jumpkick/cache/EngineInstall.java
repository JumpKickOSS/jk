// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The live JumpKick engine install: {@code <product-home>/lib/jk-engine.jar} plus a sidecar
 * {@code jk-engine.toml} (version + content hash). The PATH {@code jk} is paired with this jar at
 * install/update time.
 *
 * <p>An upgrade parks the previous jar as {@code jk-engine.jar.old} so a draining engine can keep
 * its mapped bytes for a few minutes. {@link #gc} deletes parked files, leftover versioned
 * {@code jk-engine-*.jar} names, a leftover {@code versions/} tree, and parked PATH binaries
 * ({@code jk.old} / {@code jk.exe.old}). A file still mapped on Windows is left for a later cycle.
 */
public final class EngineInstall {

    public static final String ENGINE_JAR = "jk-engine.jar";
    public static final String ENGINE_JAR_OLD = "jk-engine.jar.old";
    public static final String MANIFEST = "jk-engine.toml";
    public static final String MANIFEST_OLD = "jk-engine.toml.old";

    private static final String LOCK_NAME = ".jk-engine.lock";

    private final Path libDir;

    public EngineInstall(Path productLibDir) {
        this.libDir = productLibDir;
    }

    /** Rooted at {@code $JK_HOME/lib} (or {@code <data>/lib}); migrates a leftover {@code versions/} tree. */
    public static EngineInstall current() {
        EngineInstall install = new EngineInstall(JkDirs.productLib());
        install.tryMigrate(JkDirs.versions());
        return install;
    }

    /** One usable engine on disk. */
    public record Materialized(String version, Path root, Path engineJar, String engineSha) {}

    public Path libDir() {
        return libDir;
    }

    public Path engineJarPath() {
        return libDir.resolve(ENGINE_JAR);
    }

    public Path engineJarOldPath() {
        return libDir.resolve(ENGINE_JAR_OLD);
    }

    /** The live engine when complete on disk (jar + toml). */
    public Optional<Materialized> currentInstall() {
        return read(engineJarPath(), libDir.resolve(MANIFEST));
    }

    /**
     * The engine paired with product version {@code v}: the live jar when it is that version, else
     * the parked {@code .old} jar when that is {@code v} (drain window for a just-replaced client).
     */
    public Optional<Materialized> resolve(String v) {
        Optional<Materialized> live = currentInstall();
        if (live.isPresent() && live.get().version().equals(v)) return live;
        Optional<Materialized> parked = read(engineJarOldPath(), libDir.resolve(MANIFEST_OLD));
        if (parked.isPresent() && parked.get().version().equals(v)) return parked;
        return Optional.empty();
    }

    /** Live install; empty when none is complete. */
    public Optional<Materialized> newest() {
        return currentInstall();
    }

    /**
     * Recorded {@code engine-sha256} for {@code version} (live or parked). Empty when that version
     * is not installed — spawn then has no SNAPSHOT identity to compare.
     */
    public Optional<String> engineSha(String version) {
        return resolve(version).map(Materialized::engineSha).filter(s -> !s.isBlank());
    }

    /**
     * Best-effort removal of displaced install files: parked engine jar, leftover versioned jar
     * names, a leftover {@code versions/} tree, parked PATH binaries ({@code jk.old} /
     * {@code jk.exe.old}), and AOT caches that are not for the live product version. A file still
     * mapped by a draining engine is left for a later cycle. Never throws.
     *
     * @return paths successfully removed
     */
    public List<Path> gc() {
        return gc(JkDirs.binDir(), JkDirs.versions(), JkDirs.state());
    }

    /**
     * @param binDir PATH install directory ({@code jk.old} / {@code jk.exe.old}); {@code null} skips
     * @param legacyVersions leftover {@code versions/} tree; {@code null} skips
     * @param stateDir engine state (legacy {@code state/engine/<v>/} + AOT); {@code null} skips AOT
     */
    public List<Path> gc(Path binDir, Path legacyVersions, Path stateDir) {
        List<Path> removed = new ArrayList<>();
        tryDelete(engineJarOldPath(), removed);
        tryDelete(libDir.resolve(MANIFEST_OLD), removed);
        sweepProductLibCruft(removed);
        sweepLegacyVersions(legacyVersions, stateDir, removed);
        sweepParkedClients(binDir, removed);
        sweepSupersededAot(stateDir, removed);
        return removed;
    }

    /**
     * Install {@code version}'s engine jar from CAS. Parks a different live jar as {@code .old}.
     * Identical bytes are a no-op. Refuses to replace a <em>newer</em> live install with an older
     * version (newer always wins).
     */
    public Materialized materialize(String version, Cas cas, String engineJarSha) throws IOException {
        Optional<Materialized> existing = currentInstall();
        if (existing.isPresent()
                && existing.get().version().equals(version)
                && hasContent(existing.get(), engineJarSha)) {
            return existing.get();
        }

        Files.createDirectories(libDir);
        Path lockPath = libDir.resolve(LOCK_NAME);
        try (FileChannel lockCh = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = lockCh.lock()) {
            Optional<Materialized> raced = currentInstall();
            if (raced.isPresent() && raced.get().version().equals(version) && hasContent(raced.get(), engineJarSha)) {
                return raced.get();
            }
            if (raced.isPresent() && compare(raced.get().version(), version) > 0) {
                throw new IOException(
                        "refusing to replace jk-engine " + raced.get().version() + " with older " + version);
            }
            return materializeLocked(version, cas, engineJarSha);
        }
    }

    /**
     * Ingest {@code engineJar} into the CAS, then {@link #materialize}. The CAS stays the verified
     * byte store; the launchable copy is {@link #engineJarPath()}.
     */
    public Materialized materializeFromFiles(String version, Cas cas, Path engineJar) throws IOException {
        String engineSha = Hashing.sha256Hex(engineJar);
        cas.putFile(engineJar, engineSha);
        return materialize(version, cas, engineSha);
    }

    /**
     * Park {@code live} as {@code <name>.old} so the original name is free. Tries to delete an
     * existing parked file first; if that fails, parks beside it with a unique suffix.
     *
     * @return the path the file moved to, or empty when {@code live} did not exist
     */
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

    /** {@code jk} → {@code jk.old}; {@code jk.exe} → {@code jk.exe.old}. */
    public static Path parkedName(Path live) {
        return live.resolveSibling(live.getFileName() + ".old");
    }

    /**
     * Park the live PATH client (and {@code jkx}) and install {@code clientSource} as the new
     * {@code jk} / {@code jk.exe}. {@code jkx} is a hard link when the filesystem allows it.
     */
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

    /**
     * Drop AOT artifacts that do not belong to the live product version when a generation becomes
     * primary. Names are {@code engine-<ver>-<key>.aot} and {@code <tool>-<ver>-<key>.aot}; anything
     * without {@code -<keepVersion>-} before a 16-hex key is deleted (including legacy unversioned
     * worker names). The live version's caches are kept so a respawn does not throw away a
     * just-trained engine/worker AOT.
     *
     * <p>Displaced engines must not retrain ({@link cc.jumpkick.util.AotSettings#suppressTraining()}).
     * Best-effort; never throws. Called from primary claim, install materialize, and {@link #gc}.
     *
     * @return number of primary {@code *.aot} cache files removed
     */
    public static int deleteSupersededEngineAot(Path aotDir, String keepVersion) {
        return wipeAotDirectory(aotDir, keepVersion);
    }

    /**
     * Delete AOT artifacts under {@code aotDir} that are not for {@code keepVersion}. When
     * {@code keepVersion} is null/blank, deletes everything (install without a version pin).
     * Leaves the directory and any {@code *.lock} files.
     *
     * @return number of primary {@code *.aot} cache files removed
     */
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

    /**
     * True when {@code name} is an AOT artifact for product version {@code ver}: a 16-hex key
     * immediately after {@code -}<ver>{@code -}. Does not match a longer qualifier (e.g. keep
     * {@code 0.12.0} does not match {@code engine-0.12.0-SNAPSHOT-…}).
     */
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

    /** Primary cache: ends with {@code .aot} — not {@code .aot.noaot} or {@code .aot.config}. */
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

    /**
     * Version ordering: dotted numeric segments compare numerically; a qualifier
     * ({@code -SNAPSHOT}, {@code -rc1}, …) sorts BELOW its release; qualifiers compare
     * lexicographically among themselves.
     */
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

    // ---- internals -------------------------------------------------------

    private Materialized materializeLocked(String version, Cas cas, String engineJarSha) throws IOException {
        Optional<Materialized> live = currentInstall();
        if (live.isPresent()) {
            displaceToOld(engineJarPath());
            displaceToOld(libDir.resolve(MANIFEST));
        } else {
            // Torn live files (jar without toml, or the reverse) are not launchable.
            try {
                Files.deleteIfExists(engineJarPath());
                Files.deleteIfExists(libDir.resolve(MANIFEST));
            } catch (IOException ignored) {
                displaceToOld(engineJarPath());
                displaceToOld(libDir.resolve(MANIFEST));
            }
        }
        Path jarTmp = Files.createTempFile(libDir, ".jk-engine-", ".tmp");
        try {
            Files.copy(cas.pathFor(engineJarSha), jarTmp, StandardCopyOption.REPLACE_EXISTING);
            AtomicWrites.moveInto(jarTmp, engineJarPath());
        } finally {
            Files.deleteIfExists(jarTmp);
        }
        AtomicWrites.replace(
                libDir.resolve(MANIFEST),
                "version = \"" + version + "\"\n" + "engine-sha256 = \"" + engineJarSha + "\"\n" + "protocol = 1\n");
        return currentInstall()
                .orElseThrow(() -> new IOException("materialization of " + version + " left no engine jar"));
    }

    /**
     * Copy the newest complete leftover {@code versions/<v>/} engine into the product lib when the
     * product lib is empty. Leaves the old tree for {@link #gc} (a draining engine may still map it).
     */
    void tryMigrate(Path versionsDir) {
        if (currentInstall().isPresent()) return;
        if (versionsDir == null || !Files.isDirectory(versionsDir)) return;
        Optional<Legacy> newest = newestLegacy(versionsDir);
        if (newest.isEmpty()) return;
        try {
            Files.createDirectories(libDir);
            Files.copy(newest.get().jar, engineJarPath(), StandardCopyOption.REPLACE_EXISTING);
            String sha = parseField(newest.get().manifest, "engine-sha256");
            if (sha == null || sha.isBlank()) sha = Hashing.sha256Hex(newest.get().jar);
            AtomicWrites.replace(
                    libDir.resolve(MANIFEST),
                    "version = \"" + newest.get().version + "\"\n"
                            + "engine-sha256 = \"" + sha + "\"\n"
                            + "protocol = 1\n");
        } catch (IOException ignored) {
            // leave product lib empty; spawn will fetch / materialize
        }
    }

    private record Legacy(String version, Path jar, Path manifest) {}

    private static Optional<Legacy> newestLegacy(Path versionsDir) {
        List<Legacy> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(versionsDir)) {
            for (Path p : entries) {
                if (!Files.isDirectory(p)) continue;
                Path jar = p.resolve("lib").resolve(ENGINE_JAR);
                Path manifest = p.resolve("manifest.toml");
                if (!Files.isRegularFile(jar) || !Files.isRegularFile(manifest)) continue;
                String v = parseField(manifest, "version");
                if (v == null || v.isBlank()) v = p.getFileName().toString();
                found.add(new Legacy(v, jar, manifest));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return found.stream().max((a, b) -> compare(a.version, b.version));
    }

    private static Optional<Materialized> read(Path jar, Path manifest) {
        if (!Files.isRegularFile(jar) || !Files.isRegularFile(manifest)) return Optional.empty();
        String version = parseField(manifest, "version");
        if (version == null || version.isBlank()) return Optional.empty();
        String sha = parseField(manifest, "engine-sha256");
        Path root = jar.getParent();
        return Optional.of(new Materialized(version, root, jar, sha == null ? "" : sha));
    }

    private static boolean hasContent(Materialized m, String engineJarSha) {
        return engineJarSha != null && engineJarSha.equalsIgnoreCase(m.engineSha());
    }

    private static String parseField(Path toml, String key) {
        try {
            String prefix = key + " = \"";
            for (String line : Files.readAllLines(toml)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(prefix)) {
                    String v = trimmed.substring(prefix.length());
                    int q = v.indexOf('"');
                    if (q > 0) return v.substring(0, q);
                }
            }
        } catch (IOException ignored) {
            // unreadable
        }
        return null;
    }

    private void sweepProductLibCruft(List<Path> removed) {
        if (!Files.isDirectory(libDir)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(libDir)) {
            for (Path p : entries) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (ENGINE_JAR.equals(name) || MANIFEST.equals(name) || LOCK_NAME.equals(name)) continue;
                if (isEngineCruftName(name)) tryDelete(p, removed);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    static boolean isEngineCruftName(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.equals(ENGINE_JAR_OLD) || name.equals(MANIFEST_OLD)) return true;
        if (name.startsWith(ENGINE_JAR + ".old") || name.startsWith(MANIFEST + ".old")) return true;
        // leftover dist / side-by-side names: jk-engine-0.12.0.jar
        return name.startsWith("jk-engine-") && name.endsWith(".jar");
    }

    private void sweepLegacyVersions(Path versionsDir, Path stateDir, List<Path> removed) {
        if (versionsDir == null || !Files.exists(versionsDir)) return;
        // The leftover tree is the only copy until product-lib materialize/migrate succeeds.
        if (currentInstall().isEmpty()) return;
        Path aot = stateDir == null ? null : stateDir.resolve("aot");
        if (Files.isDirectory(versionsDir)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(versionsDir)) {
                for (Path p : entries) {
                    if (!Files.isDirectory(p)) continue;
                    String v = p.getFileName().toString();
                    if (stateDir != null) {
                        deleteRecursively(stateDir.resolve("engine").resolve(v));
                        deleteEngineAotFiles(aot, v);
                    }
                    deleteRecursively(p);
                    if (!Files.exists(p)) removed.add(p);
                }
            } catch (IOException ignored) {
                // best-effort
            }
        }
        deleteRecursively(versionsDir);
        if (!Files.exists(versionsDir)) removed.add(versionsDir);
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

    private static boolean tryDelete(Path p, List<Path> removed) {
        if (p == null) return false;
        try {
            if (!Files.exists(p)) return false;
            Files.deleteIfExists(p);
            if (Files.exists(p)) return false;
            if (removed != null) removed.add(p);
            return true;
        } catch (IOException ignored) {
            // mapped by a draining process — retry on a later GC
            return false;
        }
    }

    /** Drop AOT caches that are not for the live product version. No-op without a live install. */
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

    /**
     * Delete version {@code v}'s engine AOT artifacts ({@code engine-<v>-<16-hex-key>.*}) from the
     * shared {@code state/aot/} dir. The key-shape check keeps a version whose name extends this
     * one ({@code 0.10.0} vs {@code 0.10.1}) out of the blast radius.
     */
    static void deleteEngineAotFiles(Path aotDir, String v) {
        if (aotDir == null || v == null || v.isBlank() || !Files.isDirectory(aotDir)) return;
        String prefix = "engine-" + v + "-";
        List<String> removed = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(aotDir, "engine-*")) {
            for (Path p : entries) {
                String name = p.getFileName().toString();
                if (name.startsWith(prefix) && name.substring(prefix.length()).matches("[0-9a-f]{16}\\..*")) {
                    if (name.endsWith(".aot")) removed.add(name);
                    else if (name.endsWith(".noaot") && name.length() > ".noaot".length()) {
                        String stem = name.substring(0, name.length() - ".noaot".length());
                        removed.add(stem.endsWith(".aot") ? stem : stem + ".aot");
                    }
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException ignored) {
            // best-effort maintenance
        }
        if (!removed.isEmpty()) {
            cc.jumpkick.util.AotManifest.remove(aotDir, removed);
            cc.jumpkick.util.AotManifest.reconcile(aotDir);
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
            // Windows / restricted FS: executability is not permission-borne there.
        }
    }

    private static void deleteRecursively(Path root) {
        cc.jumpkick.util.PathUtil.deleteRecursively(root);
    }
}

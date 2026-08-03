// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.Hashing;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Side-by-side materialized jk versions under {@code ~/.local/share/jk/versions/<v>/} (client, engine jar,
 * {@code manifest.toml}). Immutable bytes live in {@link Cas}; materialization is copy-then-atomic-
 * rename (no manifest → incomplete, ignored by readers).
 */
public final class VersionStore {

    /** Marker completing a materialization; a version dir without it is ignored. */
    public static final String MANIFEST = "manifest.toml";

    private final Path root;

    public VersionStore(Path versionsDir) {
        this.root = versionsDir;
    }

    /** Rooted at the live {@code ~/.local/share/jk/versions} (honors {@code JK_HOME}). */
    public static VersionStore current() {
        return new VersionStore(JkDirs.versions());
    }

    /** One usable version on disk. {@code clientBin} is absent for engine-only materializations. */
    public record Materialized(String version, Path root, Optional<Path> clientBin, Path engineJar) {}

    public Path versionsDir() {
        return root;
    }

    /** The materialized version {@code v}, when complete on disk. */
    public Optional<Materialized> resolve(String v) {
        return read(versionsDir().resolve(v), v);
    }

    /** Ledger key for a version's last use — feeds {@link #prune} exactly like CAS blobs. */
    public static String ledgerKey(String version) {
        return "jk-version:" + version;
    }

    /**
     * Remove versions that are neither {@code keep} nor used within {@code retention} per the
     * ledger, including version-scoped AOT state and legacy {@code state/engine/<v>/}.
     */
    public List<String> prune(
            String keep,
            java.time.Duration retention,
            java.util.function.ToLongFunction<String> lastUsedMillis,
            Path stateDir) {
        List<String> pruned = new ArrayList<>();
        Path dir = versionsDir();
        if (!Files.isDirectory(dir)) return pruned;
        long cutoff = System.currentTimeMillis() - retention.toMillis();
        try (var entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                if (!Files.isDirectory(p)) continue;
                String v = p.getFileName().toString();
                if (v.equals(keep)) continue;
                long lastUsed = lastUsedMillis.applyAsLong(ledgerKey(v));
                if (lastUsed >= cutoff) continue;
                deleteRecursively(p);
                if (stateDir != null) {
                    deleteRecursively(stateDir.resolve("engine").resolve(v)); // pre-1.0 legacy home
                    deleteEngineAotFiles(stateDir.resolve("aot"), v);
                }
                pruned.add(v);
            }
        } catch (IOException ignored) {
            // best-effort maintenance
        }
        return pruned;
    }

    /**
     * Engine AOT file names: {@code engine-<jk-version>-<16hex>.…}. Version may contain hyphens
     * ({@code 0.11.0-SNAPSHOT}); the 16-hex key is always the last dash-separated segment before
     * the first extension.
     */
    private static final Pattern ENGINE_AOT_NAME =
            Pattern.compile("^engine-(.+)-([0-9a-f]{16})(\\..+)$");

    /**
     * Free AOT disk for a generation that just became primary ({@code keepVersion}):
     *
     * <ul>
     *   <li><b>Engine</b> — delete every {@code engine-<v>-*} whose {@code v} ≠ keep
     *   <li><b>Workers</b> — delete {@code java-compiler-*}/{@code kotlinc-*}/… caches whose
     *       recorded classpath is unusable for this product line: any path missing on disk, or
     *       any first-party {@code jk-*-&lt;other-version&gt;.jar} (e.g. after upgrade the lib
     *       dir only holds {@code jk-java-compiler-0.11.0.jar} but {@code …-0.10.1.aot} remains)
     * </ul>
     *
     * <p>Call from materialize / endpoint claim / ensure. Best-effort; never throws. Empty or
     * missing {@code aotDir} is a no-op.
     *
     * @return number of primary {@code .aot} keys removed (manifest rows)
     */
    public static int deleteSupersededEngineAot(Path aotDir, String keepVersion) {
        if (aotDir == null || keepVersion == null || keepVersion.isBlank()) return 0;
        if (!Files.isDirectory(aotDir)) return 0;
        Set<String> removedKeys = new LinkedHashSet<>();
        try (var entries = Files.newDirectoryStream(aotDir, "engine-*")) {
            for (Path p : entries) {
                String name = p.getFileName().toString();
                Matcher m = ENGINE_AOT_NAME.matcher(name);
                if (!m.matches()) continue;
                String v = m.group(1);
                if (keepVersion.equals(v)) continue;
                String key = m.group(2);
                // Manifest keys the primary .aot name even when only a .noaot marker remains.
                removedKeys.add("engine-" + v + "-" + key + ".aot");
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // best-effort maintenance
        }
        // Worker caches: content-keyed, but classpath records first-party worker jars that move
        // on every product version. A surviving 0.10.1 java-compiler-*.aot is pure dead weight.
        for (cc.jumpkick.util.AotManifest.Entry e : cc.jumpkick.util.AotManifest.load(aotDir)) {
            if (e == null || e.file() == null) continue;
            if (e.file().startsWith("engine-") || "engine".equals(e.tool())) continue;
            if ("pending".equals(e.status())) continue; // train still running — leave alone
            if (!workerClasspathStale(e.classpath(), keepVersion)) continue;
            String primary = e.file().endsWith(".aot") ? e.file() : e.file() + ".aot";
            removedKeys.add(primary);
            deleteWorkerAotArtifacts(aotDir, primary);
        }
        if (!removedKeys.isEmpty()) {
            cc.jumpkick.util.AotManifest.remove(aotDir, List.copyOf(removedKeys));
            cc.jumpkick.util.AotManifest.reconcile(aotDir);
        }
        return removedKeys.size();
    }

    /**
     * True when a worker cache cannot map for the live product version: a classpath entry is
     * gone, or names a first-party {@code jk-*-&lt;v&gt;.jar} with {@code v ≠ keepVersion}.
     */
    static boolean workerClasspathStale(List<String> classpath, String keepVersion) {
        if (classpath == null || classpath.isEmpty()) return false;
        for (String cp : classpath) {
            if (cp == null || cp.isBlank()) continue;
            Path p;
            try {
                p = Path.of(cp);
            } catch (RuntimeException e) {
                return true; // unparseable path — treat as dead
            }
            if (!Files.isRegularFile(p)) return true;
            String name = p.getFileName().toString();
            // First-party workers/plugins: jk-java-compiler-0.10.1.jar, jk-kotlin-compiler-….
            // plugin-sdk-0.1.0.jar is not product-versioned; leave it.
            if (!name.startsWith("jk-") || !name.endsWith(".jar")) continue;
            String jarVer = cc.jumpkick.compile.WorkerLib.jarVersion(name);
            if (jarVer != null && !jarVer.equals(keepVersion)) return true;
        }
        return false;
    }

    /** Delete a worker primary {@code <tool>-<key>.aot} plus sticky markers / config sidecars. */
    private static void deleteWorkerAotArtifacts(Path aotDir, String primaryAot) {
        if (primaryAot == null || primaryAot.isBlank()) return;
        try {
            Files.deleteIfExists(aotDir.resolve(primaryAot));
            Files.deleteIfExists(aotDir.resolve(primaryAot + ".noaot")); // workers: foo.aot.noaot
            Files.deleteIfExists(aotDir.resolve(primaryAot + ".config"));
            if (primaryAot.endsWith(".aot")) {
                String stem = primaryAot.substring(0, primaryAot.length() - ".aot".length());
                Files.deleteIfExists(aotDir.resolve(stem + ".noaot"));
            }
        } catch (IOException ignored) {
            // best-effort
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
                        // engine uses engine-<ver>-<key>.noaot (no ".aot" in the stem)
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

    /**
     * Newest complete version on disk. Ordering is dotted-numeric; {@code -SNAPSHOT} (or any
     * qualifier) sorts below its release.
     */
    public Optional<Materialized> newest() {
        Path dir = versionsDir();
        if (!Files.isDirectory(dir)) return Optional.empty();
        List<Materialized> found = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                if (!Files.isDirectory(p)) continue;
                read(p, p.getFileName().toString()).ifPresent(found::add);
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return found.stream().max(Comparator.comparing(Materialized::version, VersionStore::compare));
    }

    /**
     * Materialize {@code version} from CAS blobs: copy the engine jar (and, when given, the
     * client binary) into a temp sibling, write the manifest, atomically rename into place.
     * Already-complete versions return immediately (immutable once materialized).
     *
     * @param clientBinSha CAS sha of this host's native client, or {@code null} (engine-only
     * the self-fetch path, where the running client IS this version's client)
     */
    public Materialized materialize(String version, Cas cas, String engineJarSha, String clientBinSha)
            throws IOException {
        Optional<Materialized> existing = resolve(version);
        if (existing.isPresent() && hasContent(existing.get(), engineJarSha, clientBinSha)) return existing.get();

        Path finalRoot = versionsDir().resolve(version);
        Files.createDirectories(versionsDir());
        // Per-version lock: two racing materializers could otherwise both see "aborted dir"
        // and delete the one the other just atomically moved into place (self-healing but
        // nondeterministic). The lock file lives beside the version dirs; content addressing
        // makes the serialized loser's resolve below hit the winner's identical tree.
        Path lockPath = versionsDir().resolve("." + version + ".lock");
        try (java.nio.channels.FileChannel lockCh = java.nio.channels.FileChannel.open(
                        lockPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                java.nio.channels.FileLock lock = lockCh.lock()) {
            Optional<Materialized> raced = resolve(version);
            if (raced.isPresent() && hasContent(raced.get(), engineJarSha, clientBinSha)) return raced.get();
            if (raced.isPresent()) {
                // Same version, DIFFERENT bytes: a dev -SNAPSHOT re-install. Replace the stale
                // tree — short-circuiting on engine-only match once left a new client binary
                // unused while versions/<v>/bin/jk stayed old.
                deleteRecursively(finalRoot);
            }
            return materializeLocked(version, cas, engineJarSha, clientBinSha, finalRoot);
        }
    }

    /**
     * The manifest's recorded {@code engine-sha256} for {@code version}, or empty — the client's
     * EXPECTED engine identity (a running engine reporting a different content identity for the
     * same -SNAPSHOT version is stale and gets taken over).
     */
    public java.util.Optional<String> engineSha(String version) {
        try {
            Path manifest = versionsDir().resolve(version).resolve(MANIFEST);
            for (String line : Files.readAllLines(manifest)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("engine-sha256 = \"")) {
                    String v = trimmed.substring("engine-sha256 = \"".length());
                    int q = v.indexOf('"');
                    if (q > 0) return java.util.Optional.of(v.substring(0, q));
                }
            }
        } catch (IOException ignored) {
            // no manifest — no expectation
        }
        return java.util.Optional.empty();
    }

    /**
     * True when the materialized tree's manifest records this engine jar, and — when a client
     * binary was supplied — the same client content. Engine-only checks left SNAPSHOT client
     * binaries stale when the engine jar was unchanged.
     */
    private static boolean hasContent(Materialized m, String engineJarSha, String clientBinSha) {
        try {
            String manifest = Files.readString(m.root().resolve(MANIFEST));
            if (!manifest.contains("engine-sha256 = \"" + engineJarSha + "\"")) return false;
            if (clientBinSha != null) {
                return manifest.contains("client-sha256 = \"" + clientBinSha + "\"");
            }
            return true;
        } catch (IOException e) {
            return false; // unreadable manifest — re-materialize
        }
    }

    private Materialized materializeLocked(
            String version, Cas cas, String engineJarSha, String clientBinSha, Path finalRoot) throws IOException {
        Path tmp = Files.createTempDirectory(versionsDir(), "." + version + "-");
        try {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path engineJar = lib.resolve("jk-engine.jar");
            Files.copy(cas.pathFor(engineJarSha), engineJar, StandardCopyOption.REPLACE_EXISTING);
            String clientLine = "";
            if (clientBinSha != null) {
                Path bin = Files.createDirectories(tmp.resolve("bin"));
                Path client = bin.resolve("jk");
                Files.copy(cas.pathFor(clientBinSha), client, StandardCopyOption.REPLACE_EXISTING);
                makeExecutable(client);
                clientLine = "client-sha256 = \"" + clientBinSha + "\"\n";
            }
            Files.writeString(
                    tmp.resolve(MANIFEST),
                    "version = \"" + version + "\"\n"
                            + "engine-sha256 = \"" + engineJarSha + "\"\n"
                            + clientLine
                            + "protocol = 1\n");
            // An aborted earlier materialization (dir without manifest) blocks the rename
            // clear it; a COMPLETE dir was returned above and never reaches this point.
            if (Files.isDirectory(finalRoot) && !Files.isRegularFile(finalRoot.resolve(MANIFEST))) {
                deleteRecursively(finalRoot);
            }
            try {
                AtomicWrites.publishDir(tmp, finalRoot);
            } catch (IOException e) {
                // Lost a race to a concurrent materializer — theirs is identical by content
                // addressing; use it.
                Optional<Materialized> won = resolve(version);
                if (won.isPresent()) return won.get();
                throw e;
            }
        } finally {
            deleteRecursively(tmp);
        }
        return resolve(version)
                .orElseThrow(() -> new IOException("materialization of " + version + " left no manifest"));
    }

    /**
     * Materialize from loose files (install.sh parity for programmatic callers, and the
     * self-fetch path handing over the just-downloaded jar + the running client): the bytes are
     * ingested into the CAS first — the CAS stays the single source of truth.
     */
    public Materialized materializeFromFiles(String version, Cas cas, Path engineJar, Path clientBin)
            throws IOException {
        String engineSha = Hashing.sha256Hex(engineJar);
        cas.putFile(engineJar, engineSha);
        String clientSha = null;
        if (clientBin != null && Files.isRegularFile(clientBin)) {
            clientSha = Hashing.sha256Hex(clientBin);
            cas.putFile(clientBin, clientSha);
        }
        return materialize(version, cas, engineSha, clientSha);
    }

    // ---- internals -------------------------------------------------------

    private static Optional<Materialized> read(Path root, String version) {
        if (!Files.isRegularFile(root.resolve(MANIFEST))) return Optional.empty();
        Path engineJar = root.resolve("lib").resolve("jk-engine.jar");
        if (!Files.isRegularFile(engineJar)) return Optional.empty();
        Path client = root.resolve("bin").resolve("jk");
        return Optional.of(new Materialized(
                version, root, Files.isRegularFile(client) ? Optional.of(client) : Optional.empty(), engineJar));
    }

    /**
     * Version ordering: dotted numeric segments compare numerically; a qualifier
     * ({@code -SNAPSHOT}, {@code -rc1}, …) sorts BELOW its release; qualifiers compare
     * lexicographically among themselves. Intentionally small — release trains are simple
     * (releases.md), and this must never depend on the resolver.
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
        if (aq != bq) return aq ? -1 : 1; // qualified < release
        if (!aq) return 0;
        return an[1].compareTo(bn[1]);
    }

    private static long parse(String seg) {
        try {
            return Long.parseLong(seg);
        } catch (NumberFormatException e) {
            return 0;
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
            // Windows / restricted FS: executability is not permission-borne there.
        }
    }

    private static void deleteRecursively(Path root) {
        cc.jumpkick.util.PathUtil.deleteRecursively(root);
    }
}

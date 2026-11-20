// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.AppInstallConfig;
import cc.jumpkick.version.Versions;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
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
 * beside the old file. {@link #gc} deletes the engine jars nothing needs any more (not the one the
 * pointer names, not one holding the engine its rollback record names, not one a running process
 * was started from), plus parked PATH binaries ({@code jk.old} / {@code jk.exe.old}). Every install
 * sweeps after it writes the pointer, and the engine sweeps on its maintenance tick, so a jar a
 * draining engine kept mapped goes once that engine exits.
 *
 * <p>{@code jk-shelf.toml} beside the pointer ({@link ShelfManifest}) names the first-party
 * shelf the last install pinned to its engine; it is the install's to write and never swept.
 */
public final class EngineInstall {

    public static final String BIN_NAME = "jk-engine";
    public static final String LOCK_NAME = ".jk-engine.lock";
    public static final String POINTER_NAME = "jk-engine.toml";
    public static final String SHELF_NAME = ShelfManifest.FILE_NAME;

    /**
     * {@code jk-engine-<version>.jar} or {@code jk-engine-<version>.<10-13 digit epoch>.jar}. The
     * epoch is {@link System#currentTimeMillis()} at publish, so it cannot collide with a version
     * segment.
     */
    private static final Pattern ENGINE_JAR_NAME = Pattern.compile("^jk-engine-(.+?)(?:\\.(\\d{10,13}))?\\.jar$");

    private final Path productLib;
    private final LongSupplier clock;
    private final Predicate<Path> running;

    public EngineInstall(Path productLibDir) {
        this(productLibDir, Clock.SYSTEM::millis);
    }

    EngineInstall(Path productLibDir, LongSupplier clock) {
        this(productLibDir, clock, EngineInstall::runByAProcess);
    }

    EngineInstall(Path productLibDir, LongSupplier clock, Predicate<Path> running) {
        this.productLib = productLibDir;
        this.clock = clock == null ? Clock.SYSTEM::millis : clock;
        this.running = running;
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

    /** The shelf manifest beside the pointer; may not exist. */
    public Path shelfFile() {
        return engineHome().resolve(SHELF_NAME);
    }

    /** The shelf of the install before the current one: kept for a rollback, never swept. */
    public Path previousShelfFile() {
        return engineHome().resolve(ShelfManifest.PREVIOUS_FILE_NAME);
    }

    /** The pointer key naming the engine jar the install before this one ran, kept for a rollback. */
    public static final String PREVIOUS_ENGINE_SHA = "previous-engine-sha256";

    /** The pointer key naming the version of the engine {@link #PREVIOUS_ENGINE_SHA} names. */
    public static final String PREVIOUS_VERSION = "previous-version";

    /** The pointer key naming the PATH client this install put in place, kept for a rollback. */
    public static final String CLIENT_SHA = "client-sha256";

    /** The pointer key naming the PATH client the install before this one put in place. */
    public static final String PREVIOUS_CLIENT_SHA = "previous-client-sha256";

    /**
     * The pointer key recording the commit the {@link #CLIENT_SHA} client was built from, for a
     * client that carries none itself (a snapshot's native image); absent when the install knew none.
     */
    public static final String CLIENT_COMMIT = "client-commit";

    /** The commit recorded for the {@link #PREVIOUS_CLIENT_SHA} client, which a rollback restores with it. */
    public static final String PREVIOUS_CLIENT_COMMIT = "previous-client-commit";

    /** The pointer key naming the kind of the {@link #CLIENT_SHA} client ({@code native} or {@code jvm}). */
    public static final String CLIENT_KIND = "client-kind";

    /** The kind of the {@link #PREVIOUS_CLIENT_SHA} client. */
    public static final String PREVIOUS_CLIENT_KIND = "previous-client-kind";

    /** What a PATH client is: a native binary in {@code bin/}, or the JVM client's jar under {@code lib/jk}. */
    public enum ClientKind {
        NATIVE,
        JVM;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static ClientKind of(@Nullable String key) {
            return "jvm".equals(key) ? JVM : NATIVE;
        }
    }

    /**
     * A PATH client an install put in place, as the pointer records it beside the engine it pairs
     * with: its bytes' digest (a blob the store keeps), its kind, and the commit it was built from
     * when the client does not carry that itself ({@code null}: not known).
     */
    public record InstalledClient(
            String sha256, ClientKind kind, @Nullable String commit) {

        private static @Nullable InstalledClient read(
                Map<String, String> keys, String sha, String kind, String commit) {
            String digest = keys.get(sha);
            if (digest == null || digest.isBlank()) return null;
            String c = keys.get(commit);
            return new InstalledClient(digest, ClientKind.of(keys.get(kind)), c == null || c.isBlank() ? null : c);
        }

        private void write(Map<String, String> keys, String sha, String kind, String commit) {
            keys.put(sha, sha256);
            keys.put(kind, this.kind.key());
            if (this.commit != null && !this.commit.isBlank()) keys.put(commit, this.commit);
        }
    }

    /** One install as the pointer records it: an engine and the client paired with it, when one is known. */
    public record Install(
            String version, String engineSha, @Nullable InstalledClient client) {}

    /**
     * Puts a recorded client's bytes ({@code blob}, from the store) in place as the PATH client of
     * {@code version}, returning the path it is run by. The CLI owns the JVM client's layout; {@link
     * #nativeClients} places native binaries only.
     */
    @FunctionalInterface
    public interface ClientPlacer {
        Path place(InstalledClient client, Path blob, String version) throws IOException;
    }

    /** A {@link ClientPlacer} for native clients under {@code binDir}; a JVM client is refused. */
    public static ClientPlacer nativeClients(Path binDir) {
        return (client, blob, version) -> {
            if (client.kind() != ClientKind.NATIVE) {
                throw new IOException("a JVM client is put back by the jk client, not here");
            }
            installBinaries(blob, binDir);
            return pathClient(binDir);
        };
    }

    /**
     * The engine jars and PATH clients the store must keep for this install: the live ones the
     * pointer names, and the ones the install before it named, which {@link #rollback} puts back.
     * Lower-case hex; empty when the pointer names none.
     */
    public Set<String> pinnedEngineShas() {
        Set<String> out = new HashSet<>();
        try {
            Path pointer = configFile();
            if (!Files.isRegularFile(pointer)) return out;
            Map<String, String> cfg = AppInstallConfig.parse(Files.readString(pointer));
            for (String key : List.of("engine-sha256", PREVIOUS_ENGINE_SHA, CLIENT_SHA, PREVIOUS_CLIENT_SHA)) {
                String sha = cfg.get(key);
                if (sha != null && !sha.isBlank()) out.add(sha.strip().toLowerCase(Locale.ROOT));
            }
        } catch (IOException | RuntimeException unreadable) {
            // an unreadable pointer pins nothing it could be compared with
        }
        return out;
    }

    /**
     * Live jar path from the pointer ({@code jar =}) or inferred under the engine home. May not
     * exist when the install is incomplete.
     */
    public Path engineJarPath() {
        return currentInstall().map(Materialized::engineJar).orElse(engineHome().resolve("jk-engine.jar"));
    }

    /**
     * The live engine: the jar the pointer names, when both are present. A jar the pointer does not
     * name is a leftover (a removed home's, a drained engine's), never the install: a home with no
     * pointer has none, so nothing in it refuses an install.
     */
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
     * Best-effort removal of retired engine jars, leftover temp/old names and parked PATH
     * binaries. Never throws.
     */
    public List<Path> gc() {
        List<Path> removed = gc(JkDirs.binDir());
        sweepAbandonedConfig(JkDirs.current().configDir(), removed);
        return removed;
    }

    /**
     * {@link #gc()} with the PATH install directory ({@code jk.old} / {@code jk.exe.old}) given;
     * {@code null} skips it. The engine jars are swept only while no install holds the install
     * dir's lock: an install sweeps them itself once its pointer is written.
     */
    public List<Path> gc(@Nullable Path binDir) {
        List<Path> removed = new ArrayList<>();
        Path home = engineHome();
        if (Files.isDirectory(home)) {
            try (FileChannel lockCh = FileChannel.open(
                            home.resolve(LOCK_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock lock = lockCh.tryLock()) {
                if (lock != null) sweepRetiredJars(removed);
            } catch (IOException | RuntimeException busy) {
                // an install in progress sweeps when it is done
            }
        }
        sweepParkedClients(binDir, removed);
        return removed;
    }

    /**
     * Install {@code version}'s engine jar from CAS as {@code jk-engine-<version>.jar} when that
     * path is free, else {@code jk-engine-<version>.<epoch>.jar}. Identical live bytes are a no-op.
     * Refuses to replace a <em>newer</em> live install with an older version, newer being
     * {@link Versions#compare} — the one version order in the product.
     */
    public Materialized materialize(String version, Cas cas, String engineJarSha) throws IOException {
        return materialize(version, cas, engineJarSha, false);
    }

    /**
     * As {@link #materialize(String, Cas, String)}; {@code downgrade} lifts the refusal of an older
     * version, for the explicit {@code jk self update <version>} a person runs to go back.
     */
    public Materialized materialize(String version, Cas cas, String engineJarSha, boolean downgrade)
            throws IOException {
        return materialize(version, cas, engineJarSha, downgrade, null);
    }

    /**
     * As {@link #materialize(String, Cas, String, boolean)}, recording {@code client} as the PATH
     * client paired with this engine. A new engine makes the install the pointer named until now,
     * engine and client together, the previous one ({@link #rollback} puts it back); {@code null}
     * keeps the client the pointer names, since this install did not change it. The same engine
     * again only records the client.
     */
    public Materialized materialize(
            String version, Cas cas, String engineJarSha, boolean downgrade, @Nullable InstalledClient client)
            throws IOException {
        Optional<Materialized> existing = currentInstall();
        if (sameLive(existing, version, engineJarSha)
                && pointerComplete(existing.get())
                && intact(existing.get(), cas, engineJarSha)) {
            if (client != null) recordClient(client);
            return existing.get();
        }

        Files.createDirectories(engineHome());
        Path lockPath = engineHome().resolve(LOCK_NAME);
        try (FileChannel lockCh = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = lockCh.lock()) {
            Optional<Materialized> raced = currentInstall();
            if (sameLive(raced, version, engineJarSha) && intact(raced.get(), cas, engineJarSha)) {
                Materialized live = confirmLive(raced.get(), version, engineJarSha);
                if (client != null) recordClient(client);
                return live;
            }
            refuseOlder(raced, version, downgrade);
            return materializeLocked(version, cas, engineJarSha, client);
        }
    }

    /**
     * Install {@code version}'s engine and {@code client} as one step, in the order that never
     * leaves a client older than the engine the pointer names (that client refuses to replace the
     * newer engine and hands every command back to itself): a move to an older version installs
     * the engine first and the client after, any other the client first, since a newer client
     * installs its own engine when the pointer still names an older one. A {@code client} whose
     * blob the store no longer holds is not placed, and the pointer keeps naming the client in
     * place. Refusals ({@link #requireAccepts}) come before anything moves.
     */
    public Installed install(
            String version,
            Cas cas,
            String engineJarSha,
            boolean downgrade,
            @Nullable InstalledClient client,
            ClientPlacer placer)
            throws IOException {
        requireAccepts(version, downgrade);
        if (client == null || !Files.isRegularFile(cas.pathFor(client.sha256()))) {
            return new Installed(materialize(version, cas, engineJarSha, downgrade, null), null);
        }
        Path blob = cas.pathFor(client.sha256());
        boolean older = currentInstall()
                .map(live -> Versions.compare(live.version(), version) > 0)
                .orElse(false);
        if (older) {
            Materialized engine = materialize(version, cas, engineJarSha, downgrade, null);
            Path placed = placer.place(client, blob, version);
            recordClient(client);
            return new Installed(engine, placed);
        }
        Path placed = placer.place(client, blob, version);
        return new Installed(materialize(version, cas, engineJarSha, downgrade, client), placed);
    }

    /** What {@link #install} put in place: the engine, and the client's path when it placed one. */
    public record Installed(Materialized engine, @Nullable Path client) {}

    /**
     * A new {@link Pass} over this install that places a client of {@code clientVersion} and
     * installs the engine of {@code engineVersion} ({@code null}: the pass places or installs none),
     * refused by {@link #requirePass} before anything moves.
     */
    public Pass pass(@Nullable String clientVersion, @Nullable String engineVersion) throws IOException {
        return pass(clientVersion, engineVersion, false);
    }

    /** As {@link #pass(String, String)}; {@code downgrade} ({@code -F}) lets the engine replace a newer one. */
    public Pass pass(@Nullable String clientVersion, @Nullable String engineVersion, boolean downgrade)
            throws IOException {
        requirePass(clientVersion, engineVersion, downgrade);
        return new Pass(downgrade);
    }

    /**
     * Throw unless a pass that places a client of {@code clientVersion} and installs the engine of
     * {@code engineVersion} ({@code null}: none) ends whole: the engine is not older than the live
     * one, and the client is of the version the pointer names once the pass is done, since a client
     * runs only the engine of its own version.
     */
    public void requirePass(@Nullable String clientVersion, @Nullable String engineVersion) throws IOException {
        requirePass(clientVersion, engineVersion, false);
    }

    /** As {@link #requirePass(String, String)}; {@code downgrade} lifts the refusal of an older engine. */
    public void requirePass(@Nullable String clientVersion, @Nullable String engineVersion, boolean downgrade)
            throws IOException {
        Optional<Materialized> live = currentInstall();
        if (engineVersion != null
                && !downgrade
                && live.isPresent()
                && Versions.compare(live.get().version(), engineVersion) > 0) {
            throw new IOException("jk " + live.get().version() + " is installed, newer than " + engineVersion
                    + "; nothing was installed (-F replaces it, or a private JK_HOME takes it)");
        }
        if (clientVersion == null) return;
        String after = engineVersion != null
                ? engineVersion
                : live.map(Materialized::version).orElse(null);
        if (!clientVersion.equals(after)) {
            throw new IOException("client " + clientVersion + " needs its engine, and this install has "
                    + (after == null ? "none" : "engine " + after) + "; nothing was installed");
        }
    }

    /**
     * One install that puts a native PATH client and the engine in place as separate steps, the
     * client first (a checkout install of jk's own modules). The client is recorded with the
     * engine step, so the install the pass replaces, engine and client, becomes the previous one
     * whole; recorded any earlier, the pointer would pair the old engine with the new client and a
     * rollback would leave the new client in place. A pass with no engine step records the client
     * alone at {@link #finish}.
     */
    public final class Pass {
        private final boolean downgrade;
        private @Nullable InstalledClient placed;

        private Pass(boolean downgrade) {
            this.downgrade = downgrade;
        }

        /**
         * Ingest {@code built} into {@code cas} and put a copy of that blob in place as the PATH
         * client under {@code binDir}, built from {@code commit} ({@code null}: not known).
         */
        public Path placeNative(Path built, Path binDir, Cas cas, @Nullable String commit) throws IOException {
            String sha = cas.putFile(built).sha256();
            installBinaries(cas.pathFor(sha), binDir);
            placed = new InstalledClient(sha, ClientKind.NATIVE, commit);
            return pathClient(binDir);
        }

        /** Ingest {@code engineJar} and materialize it as {@code version}, paired with the client placed so far. */
        public Materialized engine(String version, Cas cas, Path engineJar) throws IOException {
            Materialized m = materializeFromFiles(version, cas, engineJar, downgrade, placed);
            placed = null;
            return m;
        }

        /** Record a client placed after the engine step, or by a pass that had none. */
        public void finish() throws IOException {
            if (placed != null) recordClient(placed);
            placed = null;
        }
    }

    /**
     * Throw the refusal {@link #materialize(String, Cas, String, boolean)} would, without changing
     * anything: an install that must not end half done asks first.
     */
    public void requireAccepts(String version, boolean downgrade) throws IOException {
        refuseOlder(currentInstall(), version, downgrade);
    }

    private static void refuseOlder(Optional<Materialized> live, String version, boolean downgrade) throws IOException {
        if (!downgrade && live.isPresent() && Versions.compare(live.get().version(), version) > 0) {
            throw new IOException("refusing to replace jk-engine " + live.get().version() + " with older " + version);
        }
    }

    /**
     * Whether the live jar is as long as the blob it was copied from: a jar a power loss truncated
     * is installed again instead of kept because its pointer still names it.
     */
    private static boolean intact(Materialized live, Cas cas, String engineJarSha) {
        try {
            Path blob = cas.pathFor(engineJarSha);
            return !Files.isRegularFile(blob) || Files.size(blob) == Files.size(live.engineJar());
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * Ingest {@code engineJar} into the CAS, then materialize.
     */
    public Materialized materializeFromFiles(String version, Cas cas, Path engineJar) throws IOException {
        return materializeFromFiles(version, cas, engineJar, false);
    }

    /**
     * As {@link #materializeFromFiles(String, Cas, Path)}; {@code downgrade} lifts the refusal of an
     * older version, as {@link #materialize(String, Cas, String, boolean)} does.
     */
    public Materialized materializeFromFiles(String version, Cas cas, Path engineJar, boolean downgrade)
            throws IOException {
        return materializeFromFiles(version, cas, engineJar, downgrade, null);
    }

    /** As {@link #materializeFromFiles(String, Cas, Path, boolean)}, recording {@code client} as {@link #materialize(String, Cas, String, boolean, InstalledClient)} does. */
    public Materialized materializeFromFiles(
            String version, Cas cas, Path engineJar, boolean downgrade, @Nullable InstalledClient client)
            throws IOException {
        String engineSha = cas.putFile(engineJar).sha256();
        return materialize(version, cas, engineSha, downgrade, client);
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
            // The parked name is still mapped, or a scanner holds the live file it just saw written:
            // park beside it, retrying a Windows sharing denial the way every atomic replace does.
            Path alt = live.resolveSibling(live.getFileName() + ".old-" + System.nanoTime());
            AtomicWrites.moveInto(live, alt);
            return Optional.of(alt);
        }
    }

    /** The {@code jk} on PATH under {@code binDir}: {@code jk.exe}, else {@code jk.bat}, else {@code jk}. */
    public static Path pathClient(Path binDir) {
        Path exe = binDir.resolve("jk.exe");
        if (Files.isRegularFile(exe)) return exe;
        Path bat = binDir.resolve("jk.bat");
        if (Files.isRegularFile(bat)) return bat;
        return binDir.resolve("jk");
    }

    /** A client an install parked: {@code jk.old}, {@code jk.exe.old-<nanos>}, {@code jkx.bat.old} and so on. */
    private static final Pattern PARKED = Pattern.compile("jkx?(\\.exe|\\.bat|\\.cmd)?\\.old(-\\d+)?");

    /**
     * Delete the clients earlier installs parked under {@code binDir}. A parked client still running
     * keeps its file on Windows (the delete is refused while it is mapped) and is left for the next
     * sweep; nothing else is touched. Returns how many went.
     */
    public static int sweepParked(Path binDir) {
        int[] removed = {0};
        try {
            PathUtil.forEachChild(binDir, (p, attrs) -> {
                if (attrs.isRegularFile()
                        && PARKED.matcher(p.getFileName().toString()).matches()) {
                    try {
                        Files.delete(p);
                        removed[0]++;
                    } catch (IOException stillRunning) {
                        // Mapped by a process that has not exited yet: the next sweep takes it.
                    }
                }
                return true;
            });
        } catch (IOException e) {
            return removed[0];
        }
        return removed[0];
    }

    public static Path parkedName(Path live) {
        return live.resolveSibling(live.getFileName() + ".old");
    }

    public static void installBinaries(Path clientSource, Path binDir) throws IOException {
        installBinaries(clientSource, binDir, Os.isWindows());
    }

    /**
     * Replace the PATH client with a copy of {@code clientSource}. Both {@code jk} and {@code jkx}
     * are staged beside their destination and made executable first; only then is the staged pair
     * renamed over the live one. On Windows the live pair is parked as {@code .old} before the
     * rename, and a failure after the park puts it back, so the bin never ends up with no client.
     */
    static void installBinaries(Path clientSource, Path binDir, boolean windows) throws IOException {
        Files.createDirectories(binDir);
        // The clients an earlier install parked go first; the one this install parks stays until the next.
        sweepParked(binDir);
        String jkName = windows ? "jk.exe" : "jk";
        String jkxName = windows ? "jkx.exe" : "jkx";
        Path jk = binDir.resolve(jkName);
        Path jkx = binDir.resolve(jkxName);
        Path stagedJk = jk.resolveSibling("." + jkName + "-new");
        Path stagedJkx = jkx.resolveSibling("." + jkxName + "-new");
        Files.deleteIfExists(stagedJk);
        Files.deleteIfExists(stagedJkx);
        try {
            // A copy, never a link: the source is a CAS blob whose bytes its digest vouches for, and a
            // linked bin/jk would share its inode — the chmod below, or any later rewrite of the PATH
            // client, would then change the verified blob in place.
            Files.copy(clientSource, stagedJk, StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(stagedJk);
            try {
                Files.createLink(stagedJkx, stagedJk);
            } catch (IOException | UnsupportedOperationException noHardlink) {
                Files.copy(stagedJk, stagedJkx, StandardCopyOption.REPLACE_EXISTING);
            }
            makeExecutable(stagedJkx);
        } catch (IOException | RuntimeException staging) {
            discardStaged(stagedJk, staging);
            discardStaged(stagedJkx, staging);
            throw staging;
        }
        Optional<Path> parkedJk = Optional.empty();
        Optional<Path> parkedJkx = Optional.empty();
        try {
            // POSIX renames over a running binary, which keeps its inode, so the name is never
            // missing; Windows refuses to replace a mapped .exe, so the live one is parked first.
            if (windows) {
                parkedJk = displaceToOld(jk);
                parkedJkx = displaceToOld(jkx);
            }
            AtomicWrites.moveInto(stagedJk, jk);
            AtomicWrites.moveInto(stagedJkx, jkx);
        } catch (IOException | RuntimeException publish) {
            restoreParked(parkedJk, jk, publish);
            restoreParked(parkedJkx, jkx, publish);
            discardStaged(stagedJk, publish);
            discardStaged(stagedJkx, publish);
            throw publish;
        }
    }

    /**
     * Put a client {@link #displaceToOld} parked back as {@code live}, over whatever the failed
     * publish left there. A failure to do so is added to {@code cause} as suppressed.
     */
    public static void restoreParked(Optional<Path> parked, Path live, Exception cause) {
        if (parked.isEmpty()) return;
        try {
            Files.move(parked.get(), live, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failed) {
            cause.addSuppressed(failed);
        }
    }

    /** Delete a staged sibling a failed publish left behind; a failure is suppressed into {@code cause}. */
    public static void discardStaged(Path staged, Exception cause) {
        try {
            Files.deleteIfExists(staged);
        } catch (IOException | RuntimeException failed) {
            cause.addSuppressed(failed);
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

    private Materialized materializeLocked(
            String version, Cas cas, String engineJarSha, @Nullable InstalledClient client) throws IOException {
        Path dest = allocateJarPath(version);
        Path jarTmp = Files.createTempFile(engineHome(), ".jk-engine-", ".tmp");
        try {
            Files.copy(cas.pathFor(engineJarSha), jarTmp, StandardCopyOption.REPLACE_EXISTING);
            // The pointer below is written durably; the jar it names has to be on disk first, or a
            // power loss leaves a pointer to a torn jar.
            try (FileChannel ch = FileChannel.open(jarTmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            AtomicWrites.publish(jarTmp, dest);
        } finally {
            Files.deleteIfExists(jarTmp);
        }
        writePointer(version, engineJarSha, dest.getFileName().toString(), client);
        sweepRetiredJars(new ArrayList<>());
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

    private void writePointer(String version, String engineJarSha, String jarFileName, @Nullable InstalledClient client)
            throws IOException {
        // jk-config.* properties are the base; the COMPUTED identity keys are written last so they
        // always win. A stray -Djk-config.engine-sha256 could otherwise poison the digest and make
        // every command see a build-id mismatch and respawn the engine.
        Map<String, String> keys = new LinkedHashMap<>(AppInstallConfig.jkConfigProperties());
        Map<String, String> before = pointerKeys();
        keys.put("version", version);
        keys.put("engine-sha256", engineJarSha);
        keys.put("protocol", "1");
        keys.put("jar", jarFileName);
        String live = before.get("engine-sha256");
        InstalledClient liveClient = InstalledClient.read(before, CLIENT_SHA, CLIENT_KIND, CLIENT_COMMIT);
        if (live != null && !live.isBlank() && !live.equalsIgnoreCase(engineJarSha)) {
            // A new engine: the install the pointer named until now, engine and client together,
            // becomes the previous one, so a rollback puts back a pair that ran together.
            keys.put(PREVIOUS_ENGINE_SHA, live);
            String liveVersion = before.get("version");
            if (liveVersion != null && !liveVersion.isBlank()) keys.put(PREVIOUS_VERSION, liveVersion);
            if (liveClient != null)
                liveClient.write(keys, PREVIOUS_CLIENT_SHA, PREVIOUS_CLIENT_KIND, PREVIOUS_CLIENT_COMMIT);
        } else {
            for (String key : List.of(
                    PREVIOUS_ENGINE_SHA,
                    PREVIOUS_VERSION,
                    PREVIOUS_CLIENT_SHA,
                    PREVIOUS_CLIENT_KIND,
                    PREVIOUS_CLIENT_COMMIT)) {
                String value = before.get(key);
                if (value != null && !value.isBlank()) keys.put(key, value);
            }
        }
        InstalledClient next = client == null ? liveClient : withKnownCommit(client, liveClient);
        if (next != null) next.write(keys, CLIENT_SHA, CLIENT_KIND, CLIENT_COMMIT);
        // Durably: a pointer torn by a power loss names no jar, and every later command sees an
        // install it cannot start rather than one it can re-derive.
        AtomicWrites.replaceDurably(configFile(), AppInstallConfig.render(keys));
    }

    /** {@code client}, taking the commit recorded for {@code live} when it is the same binary and names none itself. */
    private static InstalledClient withKnownCommit(InstalledClient client, @Nullable InstalledClient live) {
        if (client.commit() != null || live == null || !live.sha256().equalsIgnoreCase(client.sha256())) return client;
        return new InstalledClient(client.sha256(), client.kind(), live.commit());
    }

    /** The pointer's keys as they stand, or none when it is missing or unreadable. */
    private Map<String, String> pointerKeys() {
        try {
            Path pointer = configFile();
            if (!Files.isRegularFile(pointer)) return Map.of();
            return AppInstallConfig.parse(Files.readString(pointer));
        } catch (IOException | RuntimeException unreadable) {
            return Map.of();
        }
    }

    /**
     * Name {@code client} as the PATH client paired with the engine the pointer names, replacing the
     * one named until now; the previous install is untouched (a new engine moves it, in {@link
     * #materialize(String, Cas, String, boolean, InstalledClient)}). The same binary again keeps
     * the commit recorded for it unless {@code client} names one. A no-op when the pointer is
     * missing (nothing to pair the client with).
     */
    public void recordClient(InstalledClient client) throws IOException {
        Map<String, String> keys = new LinkedHashMap<>(pointerKeys());
        if (keys.isEmpty()) return;
        Map<String, String> before = Map.copyOf(keys);
        InstalledClient next = withKnownCommit(client, liveClient(keys));
        keys.remove(CLIENT_SHA);
        keys.remove(CLIENT_KIND);
        keys.remove(CLIENT_COMMIT);
        next.write(keys, CLIENT_SHA, CLIENT_KIND, CLIENT_COMMIT);
        if (keys.equals(before)) return;
        AtomicWrites.replaceDurably(configFile(), AppInstallConfig.render(keys));
    }

    /**
     * Name the PATH client whose bytes are {@code client} beside the live engine, when the pointer
     * names the engine of that client's {@code version} and pairs it with other bytes: an installer
     * that put a client in place over an engine of its version installs no engine, so nothing else
     * records it. Returns whether the pointer changed.
     */
    public boolean recordPathClient(String version, Path client, ClientKind kind, Cas cas) throws IOException {
        Optional<Materialized> live = currentInstall();
        if (live.isEmpty() || !live.get().version().equals(version)) return false;
        String sha = Hashing.sha256Hex(client);
        if (liveClient().filter(c -> c.sha256().equalsIgnoreCase(sha)).isPresent()) return false;
        recordClient(new InstalledClient(cas.putFile(client).sha256(), kind, null));
        return true;
    }

    /** The PATH client the pointer pairs with the live engine, when it names one. */
    public Optional<InstalledClient> liveClient() {
        return Optional.ofNullable(liveClient(pointerKeys()));
    }

    private static @Nullable InstalledClient liveClient(Map<String, String> keys) {
        return InstalledClient.read(keys, CLIENT_SHA, CLIENT_KIND, CLIENT_COMMIT);
    }

    /** The install before the live one, as the pointer records it for {@link #rollback}. */
    public Optional<Install> previousInstall() {
        Map<String, String> keys = pointerKeys();
        String engineSha = keys.get(PREVIOUS_ENGINE_SHA);
        String version = keys.get(PREVIOUS_VERSION);
        if (engineSha == null || engineSha.isBlank() || version == null || version.isBlank()) return Optional.empty();
        return Optional.of(new Install(
                version,
                engineSha,
                InstalledClient.read(keys, PREVIOUS_CLIENT_SHA, PREVIOUS_CLIENT_KIND, PREVIOUS_CLIENT_COMMIT)));
    }

    /**
     * Drop the record of the install before the live one, and the shelf it was installed with, so
     * {@link #rollback} has nothing to put back: for a release taken back, whose worker jars are
     * gone. The live install is untouched.
     */
    public void forgetPrevious() throws IOException {
        Files.deleteIfExists(previousShelfFile());
        Map<String, String> keys = new LinkedHashMap<>(pointerKeys());
        boolean changed = false;
        for (String key : List.of(
                PREVIOUS_ENGINE_SHA,
                PREVIOUS_VERSION,
                PREVIOUS_CLIENT_SHA,
                PREVIOUS_CLIENT_KIND,
                PREVIOUS_CLIENT_COMMIT)) {
            changed |= keys.remove(key) != null;
        }
        if (changed) AtomicWrites.replaceDurably(configFile(), AppInstallConfig.render(keys));
    }

    /**
     * The commit the install recorded for the PATH client whose bytes hash to {@code clientSha}, or
     * empty when the pointer names another client or records no commit for it.
     */
    public Optional<String> recordedClientCommit(String clientSha) {
        Map<String, String> keys = pointerKeys();
        if (!clientSha.equalsIgnoreCase(keys.get(CLIENT_SHA))) return Optional.empty();
        return Optional.ofNullable(keys.get(CLIENT_COMMIT)).filter(c -> !c.isBlank());
    }

    /** What {@link #rollback} put back: the engine's version and jar, and the client, when one was. */
    public record RolledBack(
            String version, Path engineJar, @Nullable Path client) {}

    /**
     * Put back the install before this one, its engine and the client paired with it, from the store
     * that keeps them ({@link #pinnedEngineShas}); the install replaced becomes the previous one, so
     * a second rollback undoes the first. Both move in the order {@link #install} keeps, and the
     * shelf manifest that engine was installed with becomes the live one again. Needs no
     * engine: an install that left an engine and client that fail every command is rolled back from
     * the client alone. Throws when the pointer names no previous engine, or the store no longer
     * holds it.
     */
    public RolledBack rollback(Cas cas, ClientPlacer placer) throws IOException {
        Install previous = previousInstall()
                .orElseThrow(
                        () -> new IOException("no previous engine to roll back to: " + configFile() + " names none"));
        if (!Files.isRegularFile(cas.pathFor(previous.engineSha()))) {
            throw new IOException("the previous engine " + previous.engineSha() + " is no longer in the store");
        }
        Installed back = install(previous.version(), cas, previous.engineSha(), true, previous.client(), placer);
        // The engine put back launches its workers by the shelf it was installed with.
        ShelfManifest.restorePrevious(shelfFile(), previous.engineSha());
        return new RolledBack(back.engine().version(), back.engine().engineJar(), back.client());
    }

    private Optional<Materialized> readLive() {
        Path pointer = configFile();
        if (!Files.isRegularFile(pointer)) return Optional.empty();
        try {
            return fromPointer(AppInstallConfig.parse(Files.readString(pointer)));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
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

    private Materialized confirmLive(Materialized m, String version, String sha) throws IOException {
        if (pointerComplete(m) && sha.equalsIgnoreCase(m.engineSha())) return m;
        writePointer(version, sha, m.engineJar().getFileName().toString(), null);
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

    /**
     * Delete the engine jars and leftovers the install no longer needs. Kept: the jar the pointer
     * names, a jar holding the previous engine the pointer's rollback record names by digest (what
     * {@link #rollback} puts back), and any jar a running process was started from (a draining
     * engine, an engine of another store under this home). With no readable pointer nothing is
     * retired. The caller holds the install dir's lock.
     */
    private void sweepRetiredJars(List<Path> removed) {
        Path home = engineHome();
        if (!Files.isDirectory(home)) return;
        Map<String, String> pointer = pointerKeys();
        String liveName = pointer.get("jar");
        if (liveName == null || liveName.isBlank()) return;
        String previous = pointer.getOrDefault(PREVIOUS_ENGINE_SHA, "").strip().toLowerCase(Locale.ROOT);
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(home)) {
            for (Path p : entries) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (LOCK_NAME.equals(name) || POINTER_NAME.equals(name) || SHELF_NAME.equals(name)) continue;
                if (liveName.equals(name)) continue;
                if (isEngineJarName(name)) jars.add(p);
                else if (name.endsWith(".tmp") || name.endsWith(".old") || name.contains(".old-"))
                    tryDelete(p, removed);
            }
        } catch (IOException ignored) {
            // best-effort
        }
        for (Path jar : jars) {
            if (running.test(jar) || (!previous.isEmpty() && previous.equals(digest(jar)))) continue;
            tryDelete(jar, removed);
        }
    }

    private static String digest(Path jar) {
        try {
            return Hashing.sha256Hex(jar).toLowerCase(Locale.ROOT);
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    /**
     * Whether a live process names {@code jar} on its command line, as an engine names the jar it
     * runs from on its class path. Where the OS does not show other processes' command lines
     * (Windows), a jar a running engine has mapped refuses the delete instead.
     */
    static boolean runByAProcess(Path jar) {
        Set<String> spellings = new HashSet<>();
        spellings.add(jar.toAbsolutePath().normalize().toString());
        try {
            spellings.add(jar.toRealPath().toString());
        } catch (IOException | RuntimeException unresolved) {
            // the absolute spelling is enough
        }
        try {
            return ProcessHandle.allProcesses().anyMatch(p -> p.info()
                    .commandLine()
                    .map(cmd -> spellings.stream().anyMatch(cmd::contains))
                    .orElse(false));
        } catch (RuntimeException unlisted) {
            return true;
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

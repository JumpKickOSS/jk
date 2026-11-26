// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.ShelfManifest;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Classpath used to fork a thin plugin worker: the worker jar plus the Maven runtime closure from
 * its POM ({@code repos/jk-local} / {@code jumpkick} / {@code central} / {@code google}). A
 * first-party worker's jar holds its own module's classes only; the JumpKick libraries it runs on
 * (the plugin SDK, {@code jk-host}, …) are dependencies its POM names, resolved like any other. A
 * worker built in a workspace takes them from the workspace's {@code target/} trees
 * ({@link PomRuntimeClasspath}).
 *
 * <p>{@code repos/jk-local} jars reach a fork as artifact-CAS blobs, never as shelf paths: a
 * {@code cache-install} from another checkout replaces the shelf path in place, and on Windows
 * that rename is refused while a worker still has the shelf file mapped. The engine's shelf
 * manifest ({@link ShelfPins}) says which blob: a jar it pins launches at the pinned sha whatever
 * the shelf holds now, so every fork of one engine runs the workers and libraries that engine was
 * installed with. A jar the manifest does not name launches as the shelf has it. POMs are pinned
 * the same way: the runtime closure is walked from the store's copy of each pinned POM when the
 * shelf holds another install's, so a worker never forks on another checkout's dependency list.
 *
 * <p>A first-party worker from the official repository's store runs only on first-party libraries
 * whose bytes its release signed ({@link StoredWorkers}).
 */
public final class WorkerLaunchClasspath {

    private WorkerLaunchClasspath() {}

    public static List<Path> paths(Path workerJar) {
        return paths(workerJar, ShelfPins.current());
    }

    static List<Path> paths(Path workerJar, @Nullable ShelfManifest pins) {
        // A CAS blob can only reach a fork as a path-pinned plugin jar — coordinate pins and
        // first-party workers resolve to Maven-layout paths. A path pin carries no POM, so the
        // sha-verified jar is its whole classpath here; the SDK floor it compiled against joins at
        // the launch from the consumer's lock (PluginSdkFloor).
        if (Cas.isBlobPath(workerJar)) return List.of(workerJar);
        Path worker = workerJar.toAbsolutePath().normalize();
        List<Path> closure =
                PomRuntimeClasspath.resolve(worker, pinnedPom(worker, pins), coord -> pinnedLibrary(coord, pins));
        vouchFirstParty(worker, closure);
        return pinJkLocal(closure, pins);
    }

    /**
     * A shelf library whose POM the manifest pins to other bytes than the shelf now holds: its shelf
     * jar (launched at its pinned sha by {@link #pinJkLocal}) and the store's copy of the pinned POM.
     * {@code null} when the shelf's own POM is the one to walk.
     */
    private static PomRuntimeClasspath.@Nullable Local pinnedLibrary(Coordinate coord, @Nullable ShelfManifest pins) {
        if (pins == null || coord.classifier() != null) return null;
        Path jar = shelfRoot().resolve(MavenLayout.artifactPath(coord));
        Path pom = pinnedPom(jar, pins);
        return pom == null ? null : new PomRuntimeClasspath.Local(jar, pom);
    }

    /**
     * Refuse a first-party library on the classpath of a worker from the official repository's
     * store (or Central's) unless its release signed the jar and the POM: the worker itself was
     * held to those digests before it was located, and its libraries decide what it runs as surely.
     */
    private static void vouchFirstParty(Path worker, List<Path> closure) {
        Cas cas = JkStores.storeCas();
        if (signedStoreRelPath(cas, worker) == null) return;
        for (int i = 1; i < closure.size(); i++) {
            Path entry = closure.get(i);
            String rel = signedStoreRelPath(cas, entry);
            if (rel == null) continue;
            String refusal =
                    switch (StoredWorkers.check(cas, rel, entry, null)) {
                        case StoredWorkers.Verdict.Vouched v -> null;
                        case StoredWorkers.Verdict.Refused r -> r.reason();
                        case StoredWorkers.Verdict.Unknown u ->
                            "no signed release digests are kept for it; run jk storage clean -w to fetch it again";
                    };
            if (refusal != null) {
                throw new IllegalStateException(
                        entry.getFileName() + " on the classpath of " + worker.getFileName() + ": " + refusal);
            }
        }
    }

    /**
     * {@code jar}'s Maven path when it is a first-party artifact in the official repository's store
     * or Central's, the stores whose first-party jars run only once a signed release lists them;
     * otherwise {@code null}.
     */
    private static @Nullable String signedStoreRelPath(Cas cas, Path jar) {
        Path abs = jar.toAbsolutePath().normalize();
        for (RepoArtifactStore store : RepoArtifactStore.firstParty(cas.root())) {
            Path root = store.root();
            if (root == null || RepoArtifactResolver.JK_LOCAL.equals(String.valueOf(root.getFileName()))) continue;
            Path normalized = root.toAbsolutePath().normalize();
            if (!abs.startsWith(normalized)) continue;
            String rel = normalized.relativize(abs).toString().replace('\\', '/');
            Coordinate coord = PomRuntimeClasspath.coordinateOf(abs);
            return coord != null && PluginJar.isFirstPartyGroup(coord.group()) ? rel : null;
        }
        return null;
    }

    public static String resolve(Path workerJar) {
        return Classpaths.join(paths(workerJar));
    }

    /**
     * Copy each {@code repos/jk-local} jar into the artifact CAS (idempotent by sha), at the sha
     * the engine's shelf manifest pins when it names the jar. POM resolution still reads the
     * shelf; only the paths handed to the forked JVM change.
     */
    static List<Path> pinJkLocal(List<Path> resolved) {
        return pinJkLocal(resolved, ShelfPins.current());
    }

    static List<Path> pinJkLocal(List<Path> resolved, @Nullable ShelfManifest pins) {
        Cas cas = JkStores.storeCas();
        List<Path> out = new ArrayList<>(resolved.size());
        for (Path p : resolved) {
            out.add(isJkLocalJar(p) ? pinOne(cas, p, pins) : p);
        }
        return List.copyOf(out);
    }

    /** The live store's {@code repos/jk-local}, absolute and normalized. */
    private static Path shelfRoot() {
        return JkStores.store()
                .resolve("repos")
                .resolve(RepoArtifactResolver.JK_LOCAL)
                .toAbsolutePath()
                .normalize();
    }

    /**
     * True when {@code path} sits under the live artifact store's {@code repos/jk-local} — the
     * shelf {@code cache-install} overwrites. Test fixtures and alternate store roots are left
     * alone.
     */
    static boolean isJkLocalJar(Path path) {
        if (path == null) return false;
        return path.toAbsolutePath().normalize().startsWith(shelfRoot());
    }

    /** {@code group:artifact:version} of a shelf jar, from its Maven-layout path. */
    static String coordinateOf(Path shelfJar) {
        Path rel = shelfRoot().relativize(shelfJar.toAbsolutePath().normalize());
        return RepoArtifactStore.inferGav(rel.toString().replace('\\', '/'));
    }

    /** Shelf artifacts already reported as served from the store instead of the shelf, so the log says it once each. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * The POM to walk {@code worker}'s runtime closure from when it is not the shelf's own: the
     * store's copy at the sha the manifest pins, once another install has replaced the shelf POM.
     * Null when the worker is not a shelf jar, the manifest pins no POM for it, or the shelf POM
     * is the pinned one.
     */
    static @Nullable Path pinnedPom(Path worker, @Nullable ShelfManifest pins) {
        if (pins == null || !isJkLocalJar(worker)) return null;
        String coordinate = coordinateOf(worker);
        String pinned = pins.pomSha(coordinate).orElse(null);
        if (pinned == null) return null;
        Path shelfPom = PomRuntimeClasspath.pomOf(worker);
        if (shelfPom != null) {
            try {
                if (pinned.equalsIgnoreCase(shaOf(shelfPom))) return null;
            } catch (IOException unreadable) {
                // Not the shelf's to answer: the pinned copy below decides.
            }
        }
        Cas cas = JkStores.storeCas();
        String from = pins.source().isEmpty() ? "" : " from " + pins.source();
        if (cas.contains(pinned)) {
            if (REPORTED.add(coordinate + ".pom@" + pinned)) {
                Log.info("jk engine: the POM of " + coordinate + " on the shelf is another install's; walking the"
                        + " pinned " + pinned.substring(0, 12) + " from the store (installed" + from + ")");
            }
            return cas.pathFor(pinned);
        }
        throw new IllegalStateException("POM of " + coordinate + ": this engine needs " + pinned.substring(0, 12)
                + from + ", but another install replaced it; run jk install from that checkout, or rerun jk to"
                + " start the installed engine");
    }

    /**
     * The blob to launch {@code jar} from. Pinned by the manifest: the shelf's bytes when they
     * are the pinned ones, else the store's copy at the pinned sha — the install put it there
     * before it wrote the shelf — and a launch failure naming both shas when the store has none.
     * Unpinned: the shelf's bytes, whatever they are.
     */
    private static Path pinOne(Cas cas, Path jar, @Nullable ShelfManifest pins) {
        String coordinate = coordinateOf(jar);
        String pinned = pins == null ? null : pins.sha(coordinate).orElse(null);
        String from = pins == null || pins.source().isEmpty() ? "" : " from " + pins.source();
        String hex;
        try {
            hex = shaOf(jar);
        } catch (IOException unreadable) {
            if (pinned != null && cas.contains(pinned)) return cas.pathFor(pinned);
            // Launch with the shelf path: cache-install may still race, but a pin failure must not
            // refuse every worker on a full disk.
            return jar;
        }
        if (pinned == null || pinned.equalsIgnoreCase(hex)) {
            Optional<Path> held = cas.reuse(hex);
            if (held.isPresent()) return held.get();
            try {
                // Another install may replace the shelf jar since it was hashed: the copy is
                // stored and checked under the hash of what it took.
                Cas.Stored stored = cas.putFile(jar);
                hex = stored.sha256();
                if (pinned == null || pinned.equalsIgnoreCase(hex)) return stored.path();
            } catch (IOException e) {
                return jar;
            }
        }
        if (cas.contains(pinned)) {
            if (REPORTED.add(coordinate + "@" + pinned)) {
                Log.info("jk engine: " + coordinate + " on the shelf is " + hex.substring(0, 12)
                        + ", another install's; launching the pinned " + pinned.substring(0, 12)
                        + " from the store (installed" + from + ")");
            }
            return cas.pathFor(pinned);
        }
        throw new IllegalStateException(coordinate + ": this engine needs " + pinned.substring(0, 12) + from
                + ", but another install replaced it with " + hex.substring(0, 12)
                + "; run jk install from that checkout, or rerun jk to start the installed engine");
    }

    /**
     * The shelf memo's sha when the memo still describes this file — its recorded size and mtime
     * are the jar's, the pair the memo is keyed on — otherwise the bytes are hashed. A memo that
     * outlived a same-length replacement must not pin the previous jar's blob under a worker.
     */
    static String shaOf(Path jar) throws IOException {
        long size = Files.size(jar);
        long mtime = Files.getLastModifiedTime(jar).toMillis();
        Path memo = jar.resolveSibling(ArtifactMemo.jkFileName(jar.getFileName().toString()));
        var pinned = ArtifactMemo.read(memo)
                .filter(m -> m.size() == size && m.mtimeMillis() == mtime)
                .map(ArtifactMemo::sha256);
        if (pinned.isPresent()) return pinned.get();
        return Hashing.sha256Hex(jar);
    }
}

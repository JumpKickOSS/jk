// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.CentralMirror;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.LockClosureInputs;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.ToolEntry;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.repo.ArtifactFanOut;
import cc.jumpkick.repo.ArtifactLocator;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The closures plugins resolve for the lock ({@code [[contribute.lock-closure]]}, {@code
 * LockExtension}): {@code jk lock} asks the plugin's worker for the files, pins each by digest, and
 * {@code jk sync} stages them, so a build reads them from the store and an offline build has them.
 *
 * <p>Only a module whose active plugins declare one is asked, and only when no row of the lock
 * being replaced answers for the module's current {@linkplain LockClosureInputs inputs}: a plain
 * {@code jk lock} with nothing changed forks nothing. {@code jk lock -F} asks again.
 */
final class LockClosures {

    private LockClosures() {}

    /**
     * {@code lock} with a resolved row for every closure a module's active plugins declare, each
     * file pinned by digest in the row. {@code previous} is the lock being replaced when its pins
     * are kept: a row it has for the same inputs is kept as it stands, and a file it (or {@code
     * lock}) already pins keeps that digest without a download. {@code cache} is where worker jars
     * live.
     */
    static Lockfile pin(
            Lockfile lock,
            @Nullable Lockfile previous,
            Path lockDir,
            Map<Path, JkBuild> modules,
            @Nullable URI repoUrl,
            Path cache,
            LockPipeline.Progress progress) {
        Cas cas = JkStores.storeCas();
        Map<String, ToolEntry> answered = new LinkedHashMap<>();
        Map<Coordinate, String> known = null;
        // Inputs are read off the lock as it will read back from disk, so the build and the
        // freshness check, which read the file, compute the same digest this pin does.
        Lockfile written = null;
        for (Map.Entry<Path, JkBuild> module : modules.entrySet()) {
            Path dir = module.getKey();
            JkBuild build = module.getValue();
            List<PluginContributions.LockClosureDep> closures = PluginContributions.lockClosures(build, dir);
            if (closures.isEmpty()) continue;
            if (written == null) written = LockfileReader.parse(LockfileWriter.render(lock));
            Lockfile view = view(written, lockDir, dir);
            for (PluginContributions.LockClosureDep closure : closures) {
                String inputs = LockClosureInputs.of(closure, lockDir, dir, view);
                String key = closure.resolvedBy() + "|" + inputs;
                if (answered.containsKey(key)) continue;
                ToolEntry row =
                        previous == null ? null : ToolEntry.resolvedIn(previous.tools(), closure.resolvedBy(), inputs);
                if (row == null) {
                    progress.label("lock " + closure.resolvedBy());
                    if (known == null) known = knownDigests(lock, previous);
                    RepoGroup repos = RepoGroupBuilder.buildFor(build, repoUrl, cas, BuildEnv.forModule(dir));
                    row = ToolEntry.resolved(
                            closure.resolvedBy(),
                            inputs,
                            ask(new Asking(closure, build, dir, lock, view, known, repos), cas, cache, progress));
                }
                answered.put(key, row);
            }
        }
        if (answered.isEmpty()) return lock;
        List<ToolEntry> rows = new ArrayList<>(lock.tools());
        rows.addAll(answered.values());
        return lock.withTools(rows);
    }

    /**
     * Put every closure the module's plugins resolved for the lock on disk as the lock pins it —
     * the {@linkplain Repo repository} its plugin's steps receive, every file against its digest:
     * what {@code jk sync} stages. A repository already whole costs a listing check. A closure the
     * lock answers nothing for is a stale lock. Answers the closures' names.
     */
    static List<String> stage(JkBuild build, Path lockDir, Path moduleDir, Cas cas, Lockfile lock, Lockfile view)
            throws IOException, InterruptedException {
        List<String> names = new ArrayList<>();
        for (Repo repo : planned(build, lockDir, moduleDir, null, cas, lock, view)) {
            repo.materialize();
            names.add(repo.closure.resolvedBy());
        }
        return names;
    }

    /** The extra a plugin's steps find a closure's repository under: {@code lock-closure.<name>}. */
    static String extraName(String closure) {
        return "lock-closure." + closure;
    }

    /**
     * One closure a plugin resolved for the lock, as the repository a step receives: its extra name
     * and its directory, named by a hash of every pinned file and digest. Planning it reads the lock
     * alone, so a step whose result is cached keys on the name without building anything; {@link
     * #materialize} builds it, once, when a step runs.
     */
    static final class Repo {
        private final PluginContributions.LockClosureDep closure;
        private final Map<Coordinate, String> pinned;
        private final Path dir;
        private final Cas cas;
        private final Supplier<RepoGroup> repos;
        private final boolean m2;
        private boolean built;

        private Repo(
                PluginContributions.LockClosureDep closure,
                Map<Coordinate, String> pinned,
                Cas cas,
                Supplier<RepoGroup> repos,
                boolean m2) {
            this.closure = closure;
            this.m2 = m2;
            this.pinned = pinned;
            this.dir = ToolClosures.root(cas).resolve(key(closure, pinned));
            this.cas = cas;
            this.repos = repos;
        }

        /** The extra the step receives it under ({@link #extraName}). */
        String extra() {
            return extraName(closure.name());
        }

        /** True when the step or packager {@code consumer} reads it ({@code for-step}). */
        boolean reaches(String consumer) {
            return closure.closure().reaches(consumer);
        }

        /** The step's action-key token: the directory's name is a hash of every file and digest. */
        String token() {
            return extra() + ":" + dir.getFileName();
        }

        /**
         * The repository on disk: found whole, or built — the files jk does not hold yet fetched
         * all at once against their digests, every file linked, published atomically. Each call
         * records a use, which the tools collector counts down from.
         */
        synchronized Path materialize() throws IOException, InterruptedException {
            if (built || ToolClosures.complete(dir)) {
                built = true;
                return ToolClosures.used(cas, dir);
            }
            Path staging = Files.createTempDirectory(Files.createDirectories(dir.getParent()), ".repo-");
            try {
                link(bytes(pinned, cas, repos.get(), true, m2), staging);
                ToolClosures.publish(staging, dir);
            } catch (IOException | RuntimeException e) {
                PathUtil.deleteRecursively(staging);
                throw new IOException(
                        "the " + closure.resolvedBy() + " closure did not materialize under " + dir + ": "
                                + e.getMessage(),
                        e);
            }
            built = true;
            return ToolClosures.used(cas, dir);
        }
    }

    /**
     * The closures plugin {@code pluginId} (every plugin when null) resolved for the module, as the
     * lock pins them, planned and not built. A closure the lock answers nothing for is a stale lock.
     */
    static List<Repo> planned(
            JkBuild build,
            Path lockDir,
            Path moduleDir,
            @Nullable String pluginId,
            Cas cas,
            Lockfile lock,
            Lockfile view)
            throws IOException {
        List<Repo> out = new ArrayList<>();
        RepoGroup[] repos = {null};
        Supplier<RepoGroup> group = () -> {
            synchronized (repos) {
                if (repos[0] == null)
                    repos[0] = RepoGroupBuilder.buildFor(build, null, cas, BuildEnv.forModule(moduleDir));
                return repos[0];
            }
        };
        for (PluginContributions.LockClosureDep closure : PluginContributions.lockClosures(build, moduleDir)) {
            if (pluginId != null && !closure.manifest().id().equals(pluginId)) continue;
            out.add(new Repo(
                    closure, requireRow(closure, lockDir, moduleDir, lock, view).files(), cas, group, m2(lock)));
        }
        return out;
    }

    /** {@code extras} with each of {@code repos} built and added under its extra name. */
    static Map<String, Path> withRepositories(Map<String, Path> extras, List<Repo> repos)
            throws IOException, InterruptedException {
        if (repos.isEmpty()) return extras;
        Map<String, Path> out = new LinkedHashMap<>(extras);
        for (Repo repo : repos) out.put(repo.extra(), repo.materialize());
        return out;
    }

    /**
     * A closure repository's directory name: the plugin and closure, readable, and a hash of every
     * file with its digest, so two answers that differ in any byte are two directories.
     */
    private static String key(PluginContributions.LockClosureDep closure, Map<Coordinate, String> pinned) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Coordinate, String> file : pinned.entrySet()) lines.add(file.getKey() + "=" + file.getValue());
        Collections.sort(lines);
        String hash = Hashing.sha256Hex(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return "lock-" + closure.manifest().id() + "-" + closure.name() + "__" + hash.substring(0, 40);
    }

    /**
     * Where each of {@code pinned} is on disk: where jk already keeps it ({@link #local}), else —
     * when {@code fetch} — fetched against its digest from {@code repos}, the missing ones all at
     * once and declared on the run's board; without {@code fetch}, a file jk does not hold is left
     * out.
     */
    private static Map<Coordinate, Path> bytes(
            Map<Coordinate, String> pinned, Cas cas, RepoGroup repos, boolean fetch, boolean m2)
            throws IOException, InterruptedException {
        ArtifactLocator locator = new ArtifactLocator(cas.root(), m2);
        Map<Coordinate, Path> out = new LinkedHashMap<>();
        List<ArtifactFanOut.Fetch<Map.Entry<Coordinate, Path>>> missing = new ArrayList<>();
        for (Map.Entry<Coordinate, String> file : pinned.entrySet()) {
            Coordinate coord = file.getKey();
            Path found = local(coord, file.getValue(), cas, repos, locator);
            if (found != null) {
                out.put(coord, found);
            } else if (fetch) {
                missing.add(() -> Map.entry(
                        coord,
                        repos.tryFetchArtifact(coord, file.getValue())
                                .orElseThrow(() -> new IOException(
                                        "cannot fetch " + coord + " — no declared repository serves it"))
                                .fetched()
                                .cachePath()));
            }
        }
        if (!missing.isEmpty()) {
            var board = SessionContext.current().io().transfers();
            for (Map.Entry<Coordinate, Path> fetched :
                    ArtifactFanOut.all(missing, hit -> board.landed(hit.getKey().toString()))) {
                out.put(fetched.getKey(), fetched.getValue());
            }
        }
        return out;
    }

    /**
     * Link each of {@code files} into {@code staging} at its Maven-layout path (a copy where the
     * filesystem links nothing), then write the {@link ToolClosures#LISTING} of every file linked
     * last, so a directory without it is never taken for whole.
     */
    private static void link(Map<Coordinate, Path> files, Path staging) throws IOException {
        List<String> listed = new ArrayList<>();
        for (Map.Entry<Coordinate, Path> file : files.entrySet()) {
            String rel = MavenLayout.artifactPath(file.getKey());
            Path target = MavenLayout.safeResolve(staging, rel);
            Files.createDirectories(Objects.requireNonNull(target.getParent()));
            try {
                Files.createLink(target, file.getValue());
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(file.getValue(), target);
            }
            listed.add(rel);
        }
        Collections.sort(listed);
        Files.writeString(
                staging.resolve(ToolClosures.LISTING), String.join("\n", listed) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Where jk already keeps {@code coord}'s bytes with digest {@code sha256}, without a request:
     * under one of {@code repos} as the compile classpath finds a locked jar (the Maven local
     * repository or the store's tree, checked against jk's memo), else in the store's blobs. Null
     * when jk holds no such bytes.
     */
    private static @Nullable Path local(
            Coordinate coord, String sha256, Cas cas, RepoGroup repos, ArtifactLocator locator) {
        String rel = MavenLayout.artifactPath(coord);
        for (MavenRepo repo : repos.repos()) {
            Optional<Path> found = locator.locate(repo.name() + "+" + repo.baseUrl(), rel, sha256);
            if (found.isPresent()) return found.get();
        }
        Path blob = cas.pathFor(sha256);
        return Files.isRegularFile(blob) ? blob : null;
    }

    /**
     * Whether the Maven local repository holds {@code lock}'s repository artifacts, as the compile
     * classpath reads them: integration on, and no locked module opted out.
     */
    private static boolean m2(Lockfile lock) {
        return JkM2Config.resolve().integration()
                && lock.modules().stream().noneMatch(m -> Boolean.FALSE.equals(m.m2integration()));
    }

    /**
     * What jk already holds of a closure about to be resolved, as a Maven-layout repository in a
     * directory of its own under the store (the caller deletes it): every file {@code pinned} names
     * — the lock's and the previous lock's pins, every closure's files among them, and what an
     * earlier closure of this lock just took in — and each row of the module's view with its POM.
     * The plugin's resolver reads it as its tail, so it downloads only what jk does not hold.
     */
    private static Path seed(Lockfile view, Map<Coordinate, String> pinned, Cas cas, RepoGroup repos)
            throws IOException, InterruptedException {
        Map<Coordinate, String> known = new LinkedHashMap<>(pinned);
        List<Path> mirrors = new ArrayList<>();
        PathUtil.forEachChild(cas.root().resolve("repos"), (child, attrs) -> {
            if (attrs.isDirectory()) mirrors.add(child);
            return true;
        });
        Collections.sort(mirrors);
        for (Lockfile.Artifact a : view.artifacts()) {
            String checksum = a.checksum();
            if (checksum != null && !a.pomOnly()) {
                known.putIfAbsent(
                        a.coordinate(),
                        checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum);
            }
            Coordinate pom = new Coordinate(a.moduleGroup(), a.moduleArtifact(), a.version(), null, "pom");
            if (known.containsKey(pom)) continue;
            for (Path mirror : mirrors) {
                var memo = ArtifactMemo.read(ArtifactMemo.jkPath(mirror, MavenLayout.pomPath(pom)));
                if (memo.isPresent()) {
                    known.put(pom, memo.get().sha256());
                    break;
                }
            }
        }
        Path dir = Files.createTempDirectory(Files.createDirectories(ToolClosures.root(cas)), ".seed-");
        link(bytes(known, cas, repos, false, m2(view)), dir);
        return dir;
    }

    /** The row of {@code lock} that answers {@code closure} for the module's current inputs; none is a stale lock. */
    private static ToolEntry requireRow(
            PluginContributions.LockClosureDep closure, Path lockDir, Path moduleDir, Lockfile lock, Lockfile view)
            throws IOException {
        ToolEntry row = ToolEntry.resolvedIn(
                lock.tools(), closure.resolvedBy(), LockClosureInputs.of(closure, lockDir, moduleDir, view));
        if (row == null) {
            throw new IOException(
                    "jk-lock.toml answers no " + closure.resolvedBy() + " closure for this module — run `jk lock`");
        }
        return row;
    }

    /**
     * Every digest {@code lock} and {@code previous} already pin, by coordinate: the {@code
     * [[artifact]]} rows' checksums, the tools' single-artifact rows and the files of every resolved
     * row. A closure file one of them names keeps that digest and is not downloaded to learn it.
     */
    private static Map<Coordinate, String> knownDigests(Lockfile lock, @Nullable Lockfile previous) {
        Map<Coordinate, String> out = new LinkedHashMap<>();
        for (Lockfile source : previous == null ? List.of(lock) : List.of(lock, previous)) {
            for (Lockfile.Artifact a : source.artifacts()) {
                String checksum = a.checksum();
                if (checksum == null || a.pomOnly()) continue;
                out.putIfAbsent(
                        a.coordinate(),
                        checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum);
            }
            for (ToolEntry row : source.tools()) {
                if (row.isResolved()) {
                    row.files().forEach(out::putIfAbsent);
                } else if (row.checksum() != null && row.url() == null && !row.isClosure()) {
                    String hex = row.sha256Hex(row.classifier());
                    if (hex != null) out.putIfAbsent(row.root(), hex);
                }
            }
        }
        return out;
    }

    /**
     * Each answered file to its digest: the one {@code known} pins; else, when the plugin's
     * resolver holds the bytes under {@code held} (its private repository, or the seed), their
     * digest, the bytes taken into the store so neither the sync nor a build downloads them again;
     * else the bytes', fetched all at once from {@code repos} and declared on the run's board.
     */
    private static Map<Coordinate, String> digests(
            Map<Coordinate, @Nullable Path> files,
            Map<Coordinate, String> known,
            RepoGroup repos,
            Cas cas,
            List<Path> held)
            throws IOException, InterruptedException {
        Map<Coordinate, String> out = new LinkedHashMap<>();
        List<Coordinate> toFetch = new ArrayList<>();
        for (Map.Entry<Coordinate, @Nullable Path> file : files.entrySet()) {
            Coordinate coord = file.getKey();
            String hex = known.get(coord);
            Path bytes = file.getValue();
            if (hex == null && bytes != null && within(bytes, held) && Files.isRegularFile(bytes)) {
                hex = Hashing.sha256Hex(bytes);
                cas.putFile(bytes, hex);
                known.put(coord, hex);
            }
            if (hex != null) {
                out.put(coord, hex);
            } else {
                toFetch.add(coord);
            }
        }
        if (toFetch.isEmpty()) return out;
        List<Optional<RepoGroup.RepoFetched>> fetched = ArtifactFanOut.artifacts(repos, toFetch);
        for (int i = 0; i < toFetch.size(); i++) {
            Coordinate file = toFetch.get(i);
            RepoGroup.RepoFetched hit = fetched.get(i)
                    .orElseThrow(() -> new IOException("lock closure file " + file + " not found in any repo"));
            out.put(file, hit.fetched().sha256());
            known.put(file, hit.fetched().sha256());
        }
        return out;
    }

    /** True when {@code file} is under one of {@code roots}, after resolving {@code ..}. */
    private static boolean within(Path file, List<Path> roots) {
        Path real = file.toAbsolutePath().normalize();
        for (Path root : roots) {
            if (real.startsWith(root.toAbsolutePath().normalize())) return true;
        }
        return false;
    }

    /** One closure about to be asked for, with what asking reads. */
    private record Asking(
            PluginContributions.LockClosureDep closure,
            JkBuild build,
            Path dir,
            Lockfile lock,
            Lockfile view,
            Map<Coordinate, String> known,
            RepoGroup repos) {}

    /**
     * Ask the plugin's worker for {@code closure}'s files: a {@code lock-closure} spec carrying the
     * module as {@code lock} resolves it — its production and test entries, its step tools, its
     * routed repositories and its JDK — over an empty classes directory, since nothing is compiled
     * yet, with what the store already holds of the closure as its {@linkplain #seed repository}.
     * Each {@code pin} reply is one file; answers each with its {@linkplain #digests digest}.
     */
    private static Map<Coordinate, String> ask(Asking asking, Cas cas, Path cache, LockPipeline.Progress progress) {
        PluginContributions.LockClosureDep closure = asking.closure();
        JkBuild build = asking.build();
        Path dir = asking.dir();
        Lockfile view = asking.view();
        RepoGroup repos = asking.repos();
        String plugin = closure.manifest().id();
        ActivePlugin active = ActivePlugins.of(build, dir).stream()
                .filter(a -> a.manifest().id().equals(plugin))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "lock closure " + closure.resolvedBy() + " — plugin " + plugin + " is not active"));
        Path scratch = null;
        Path spec = null;
        Path seed = null;
        try {
            scratch = Files.createTempDirectory("jk-lock-closure-");
            seed = seed(view, asking.known(), cas, repos);
            Path classes = Files.createDirectories(scratch.resolve("classes"));
            SpecWriter writer = new SpecWriter()
                    .op(PluginProtocol.OP_LOCK_CLOSURE, closure.name(), plugin)
                    .configValues(active.config().values())
                    .project(PluginBuild.facts(build, build.mainClass()))
                    .layout(classes, dir, scratch)
                    .javaHome(JavaHomes.resolveJavaHome(dir));
            for (var e : PluginBuild.lockedEntries(cas, view, build, dir, false)) {
                writer.entry(e.fileName(), e.jar(), e.snapshot(), e.container(), e.group(), e.artifact(), e.version());
            }
            for (var e : PluginBuild.lockedEntries(cas, view, build, dir, true)) {
                writer.testEntry(
                        e.fileName(), e.jar(), e.snapshot(), e.container(), e.group(), e.artifact(), e.version());
            }
            for (var tool :
                    PluginBuild.stepTools(build, dir, cas, asking.lock(), view).entrySet()) {
                writer.extra(tool.getKey(), tool.getValue());
            }
            writer.extra(extraName(closure.name()), seed);
            for (var route : PluginRepositories.of(repos, CentralMirror.standard()::route)
                    .routes()) {
                writer.repository(route);
            }
            spec = writer.writeTempSpec();
            Map<Coordinate, @Nullable Path> files = new LinkedHashMap<>();
            for (String line : PluginBuild.runWorker(active, cache, spec, WorkerEnv.strict(), progress::label)) {
                if (PluginProtocol.PIN.equals(Jsonl.str(line, PluginProtocol.T))) {
                    String held = Jsonl.str(line, "path");
                    files.put(
                            Coordinate.parse(Objects.requireNonNull(Jsonl.str(line, "coordinate"), "pin.coordinate")),
                            held == null ? null : Path.of(held));
                }
            }
            // While the worker's scratch is still on disk: the bytes its resolver downloaded there.
            return digests(files, asking.known(), repos, cas, List.of(scratch, seed));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("lock closure " + closure.resolvedBy() + " — " + e.getMessage(), e);
        } finally {
            try {
                if (spec != null) Files.deleteIfExists(spec);
                if (scratch != null) PathUtil.deleteRecursively(scratch);
                if (seed != null) PathUtil.deleteRecursively(seed);
            } catch (IOException cleanup) {
                Log.debug("lock closure: scratch cleanup", cleanup);
            }
        }
    }

    /** {@code lock} as the module at {@code dir} reads it: a member's partition, the root's own. */
    private static Lockfile view(Lockfile lock, Path lockDir, Path dir) {
        return dir.equals(lockDir)
                ? lock
                : lock.forMember(lockDir.relativize(dir).toString().replace('\\', '/'));
    }
}

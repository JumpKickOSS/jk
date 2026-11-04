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
        for (Map.Entry<Path, JkBuild> module : modules.entrySet()) {
            Path dir = module.getKey();
            JkBuild build = module.getValue();
            List<PluginContributions.LockClosureDep> closures = PluginContributions.lockClosures(build, dir);
            if (closures.isEmpty()) continue;
            Lockfile view = view(lock, lockDir, dir);
            for (PluginContributions.LockClosureDep closure : closures) {
                String inputs = LockClosureInputs.of(closure, lockDir, dir, view);
                String key = closure.resolvedBy() + "|" + inputs;
                if (answered.containsKey(key)) continue;
                ToolEntry row =
                        previous == null ? null : ToolEntry.resolvedIn(previous.tools(), closure.resolvedBy(), inputs);
                if (row == null) {
                    progress.label("lock " + closure.resolvedBy());
                    List<Coordinate> files = ask(closure, build, dir, lock, view, previous, cas, cache, progress);
                    if (known == null) known = knownDigests(lock, previous);
                    RepoGroup repos = RepoGroupBuilder.buildFor(build, repoUrl, cas, BuildEnv.forModule(dir));
                    row = ToolEntry.resolved(closure.resolvedBy(), inputs, digests(files, known, repos));
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
     * the {@linkplain #repositories repository} its plugin's steps receive, every file against its
     * digest: what {@code jk sync} stages. A repository already whole costs a listing check. A
     * closure the lock answers nothing for is a stale lock. Answers the closures' names.
     */
    static List<String> stage(JkBuild build, Path lockDir, Path moduleDir, Cas cas, Lockfile lock, Lockfile view)
            throws IOException, InterruptedException {
        List<String> names = new ArrayList<>();
        RepoGroup[] repos = {null};
        for (PluginContributions.LockClosureDep closure : PluginContributions.lockClosures(build, moduleDir)) {
            repository(
                    closure, requireRow(closure, lockDir, moduleDir, lock, view).files(), cas, () -> {
                        if (repos[0] == null)
                            repos[0] = RepoGroupBuilder.buildFor(build, null, cas, BuildEnv.forModule(moduleDir));
                        return repos[0];
                    });
            names.add(closure.resolvedBy());
        }
        return names;
    }

    /** The extra a plugin's steps find a closure's repository under: {@code lock-closure.<name>}. */
    static String extraName(String closure) {
        return "lock-closure." + closure;
    }

    /**
     * Every closure plugin {@code pluginId} resolved for the module, each a Maven-layout repository
     * of its pinned files under the store, by {@linkplain #extraName extra name}: what the plugin's
     * steps hand their resolver as its local tail. Built once per closure and kept; a later step or
     * build finds it whole and touches no file of it. A closure the lock answers nothing for is a
     * stale lock.
     */
    static Map<String, Path> repositories(
            JkBuild build, Path lockDir, Path moduleDir, String pluginId, Cas cas, Lockfile lock, Lockfile view)
            throws IOException, InterruptedException {
        Map<String, Path> out = new LinkedHashMap<>();
        RepoGroup[] repos = {null};
        for (PluginContributions.LockClosureDep closure : PluginContributions.lockClosures(build, moduleDir)) {
            if (!closure.manifest().id().equals(pluginId)) continue;
            Path dir = repository(
                    closure, requireRow(closure, lockDir, moduleDir, lock, view).files(), cas, () -> {
                        if (repos[0] == null)
                            repos[0] = RepoGroupBuilder.buildFor(build, null, cas, BuildEnv.forModule(moduleDir));
                        return repos[0];
                    });
            out.put(extraName(closure.name()), dir);
        }
        return out;
    }

    /**
     * {@code pinned} as a Maven-layout repository under the store, keyed by every file and digest:
     * found whole, or built — the files jk does not hold yet fetched all at once against their
     * digests from {@code repos}, every file linked, published atomically.
     */
    private static Path repository(
            PluginContributions.LockClosureDep closure,
            Map<Coordinate, String> pinned,
            Cas cas,
            Supplier<RepoGroup> repos)
            throws IOException, InterruptedException {
        Path dir = cas.root().resolve(REPOSITORIES).resolve(key(pinned));
        if (ToolClosures.complete(dir)) return dir;
        Path staging = Files.createTempDirectory(Files.createDirectories(dir.getParent()), ".repo-");
        try {
            link(bytes(pinned, cas, repos.get(), true), staging);
            ToolClosures.publish(staging, dir);
        } catch (IOException | RuntimeException e) {
            PathUtil.deleteRecursively(staging);
            throw new IOException(
                    "the " + closure.resolvedBy() + " closure did not materialize under " + dir + ": " + e.getMessage(),
                    e);
        }
        return dir;
    }

    /** Where closure repositories live under the store, beside the tool closures. */
    private static final String REPOSITORIES = "plugin-tools/lock-closures";

    /** A closure repository's directory name: a hash of every file with its digest. */
    private static String key(Map<Coordinate, String> pinned) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Coordinate, String> file : pinned.entrySet()) lines.add(file.getKey() + "=" + file.getValue());
        Collections.sort(lines);
        return Hashing.sha256Hex(String.join("\n", lines).getBytes(StandardCharsets.UTF_8))
                .substring(0, 40);
    }

    /**
     * Where each of {@code pinned} is on disk: where jk already keeps it ({@link #local}), else —
     * when {@code fetch} — fetched against its digest from {@code repos}, the missing ones all at
     * once and declared on the run's board; without {@code fetch}, a file jk does not hold is left
     * out.
     */
    private static Map<Coordinate, Path> bytes(Map<Coordinate, String> pinned, Cas cas, RepoGroup repos, boolean fetch)
            throws IOException, InterruptedException {
        ArtifactLocator locator =
                new ArtifactLocator(cas.root(), JkM2Config.resolve().integration());
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
     * What jk already holds of a closure about to be resolved, as a Maven-layout repository in a
     * directory of its own under the store (the caller deletes it): the files of every row {@code
     * previous} has for it, and each row of the module's view with its POM. The plugin's
     * resolver reads it as its tail, so a relock downloads only what is new.
     */
    private static Path seed(String resolvedBy, Lockfile view, @Nullable Lockfile previous, Cas cas, RepoGroup repos)
            throws IOException, InterruptedException {
        Map<Coordinate, String> known = new LinkedHashMap<>();
        if (previous != null) {
            for (ToolEntry row : previous.tools()) {
                if (resolvedBy.equals(row.resolvedBy())) row.files().forEach(known::putIfAbsent);
            }
        }
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
        Path dir = Files.createTempDirectory(Files.createDirectories(cas.root().resolve(REPOSITORIES)), ".seed-");
        link(bytes(known, cas, repos, false), dir);
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
     * {@code files} each to its digest: the one {@code known} pins, else the bytes', fetched all at
     * once from {@code repos} and declared on the run's board.
     */
    private static Map<Coordinate, String> digests(
            List<Coordinate> files, Map<Coordinate, String> known, RepoGroup repos) {
        Map<Coordinate, String> out = new LinkedHashMap<>();
        List<Coordinate> toFetch = new ArrayList<>();
        for (Coordinate file : files) {
            String hex = known.get(file);
            if (hex != null) {
                out.put(file, hex);
            } else {
                toFetch.add(file);
            }
        }
        if (toFetch.isEmpty()) return out;
        List<Optional<RepoGroup.RepoFetched>> fetched;
        try {
            fetched = ArtifactFanOut.artifacts(repos, toFetch);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("lock closure — " + e.getMessage(), e);
        }
        for (int i = 0; i < toFetch.size(); i++) {
            Coordinate file = toFetch.get(i);
            RepoGroup.RepoFetched hit = fetched.get(i)
                    .orElseThrow(
                            () -> new IllegalStateException("lock closure file " + file + " not found in any repo"));
            out.put(file, hit.fetched().sha256());
            known.put(file, hit.fetched().sha256());
        }
        return out;
    }

    /**
     * Ask the plugin's worker for {@code closure}'s files: a {@code lock-closure} spec carrying the
     * module as {@code lock} resolves it — its production and test entries, its step tools, its
     * routed repositories and its JDK — over an empty classes directory, since nothing is compiled
     * yet, with what the store already holds of the closure as its {@linkplain #seed repository}.
     * Each {@code pin} reply is one file.
     */
    private static List<Coordinate> ask(
            PluginContributions.LockClosureDep closure,
            JkBuild build,
            Path dir,
            Lockfile lock,
            Lockfile view,
            @Nullable Lockfile previous,
            Cas cas,
            Path cache,
            LockPipeline.Progress progress) {
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
            RepoGroup repos = RepoGroupBuilder.buildFor(build, null, cas, BuildEnv.forModule(dir));
            seed = seed(closure.resolvedBy(), view, previous, cas, repos);
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
            for (var tool : PluginBuild.stepTools(build, dir, cas, lock, view).entrySet()) {
                writer.extra(tool.getKey(), tool.getValue());
            }
            writer.extra(extraName(closure.name()), seed);
            for (var route : PluginRepositories.of(repos, CentralMirror.standard()::route)
                    .routes()) {
                writer.repository(route);
            }
            spec = writer.writeTempSpec();
            List<Coordinate> files = new ArrayList<>();
            for (String line : PluginBuild.runWorker(active, cache, spec, WorkerEnv.strict(), progress::label)) {
                if (PluginProtocol.PIN.equals(Jsonl.str(line, PluginProtocol.T))) {
                    files.add(
                            Coordinate.parse(Objects.requireNonNull(Jsonl.str(line, "coordinate"), "pin.coordinate")));
                }
            }
            return files;
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

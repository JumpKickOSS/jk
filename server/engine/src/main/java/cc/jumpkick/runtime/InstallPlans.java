// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceResolve;
import cc.jumpkick.git.GitFetcher;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.manifest.PluginModule;
import cc.jumpkick.publish.PublishablePom;
import cc.jumpkick.repo.M2CompatWriter;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.resolver.LockGraph;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk install} heavy halves: {@link #projectInstallBuildPlan} (build + cache-install into
 * {@code repos/jk-local/}) and {@link #gitFetchBuildPlan}. User-home launcher shims stay client-side.
 */
public final class InstallPlans {

    private InstallPlans() {}

    // Cross-step keys.
    public static final BuildPlanKey<Coordinate> PRIMARY = BuildPlanKey.scalar("primary-coord", Coordinate.class);
    public static final BuildPlanKey<Path> CHECKOUT = BuildPlanKey.scalar("checkout-dir", Path.class);
    public static final BuildPlanKey<String> FETCHED_SHA = BuildPlanKey.scalar("fetched-sha", String.class);

    /**
     * Build the project-install plan for {@code projectDir}: core plan + declared tails +
     * (native application only) the native-image tail with {@code graalHome} + the {@code
     * cache-install} step. {@code m2Dir} is the local Maven repo root ({@code ~/.m2} or the
     * {@code --m2-dir} override).
     */
    public static BuildPlan projectInstallBuildPlan(
            Path projectDir, Path cache, Path m2Dir, boolean skipTests, boolean verbose, @Nullable Path graalHome)
            throws IOException {
        JkBuild proj = JkBuildParser.parse(projectDir.resolve(ManifestPaths.MANIFEST));

        Path lockFile = LockPaths.lockFile(projectDir);
        boolean compact = ModuleLayout.isCompact(projectDir);
        int estimatedTestCount = TestSupport.estimateAllSuiteTestCount(projectDir, compact);
        BuildPlanner.Inputs inputs = new BuildPlanner.Inputs(
                projectDir,
                cache,
                projectDir.resolve(ManifestPaths.MANIFEST),
                lockFile,
                projectDir,
                1,
                estimatedTestCount,
                null,
                null,
                skipTests,
                verbose,
                false,
                false,
                Set.of(),
                SessionContext.current());
        BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs);
        // ALWAYS modules get native from appendDeclaredTails (same as jk build); pass the
        // client-resolved GraalVM so install does not re-resolve.
        PlannerTails.appendDeclaredTails(builder, inputs, graalHome, true);
        appendCacheInstall(builder, proj, cache, m2Dir);
        return builder.build();
    }

    /**
     * ALWAYS mode ships the binary — native is part of the standard build. SUPPORTED deploys the
     * jar even when an explicit {@code jk native} (or a mode change) left a binary in
     * {@code target/}: that binary is not this install's output.
     */
    public static boolean installsNativeBinary(JkBuild project, BuildLayout layout) {
        return project.nativeMode() == JkBuild.NativeMode.ALWAYS && Files.isRegularFile(layout.nativeBinary());
    }

    /**
     * The fat-jar rung for an install: the minified jar when declared, else the assembly jar when
     * declared ({@code minified} implies the fat jar, so a declared-minified module whose R8 jar
     * is absent still gets its next-best declared rung), else empty. Declared-only: a
     * {@code target/} leftover from an undeclared artifact never outranks the thin jar.
     */
    public static Optional<Path> declaredFatJar(JkBuild project, BuildLayout layout) {
        Path minified = layout.minifiedJar();
        if (project.minified() && Files.isRegularFile(minified)) return Optional.of(minified);
        Path assembly = layout.assemblyJar();
        if ((project.assembly() || project.minified()) && Files.isRegularFile(assembly)) {
            return Optional.of(assembly);
        }
        return Optional.empty();
    }

    /**
     * Thin-jar {@code cache-install} tail. Fat and minified jars are not written to the local
     * repo — only the thin jar is.
     *
     * <p>Requiring the displaced terminal also keeps {@code run-tests} in the install plan when
     * the request does not skip tests — the deliver join carries the suite, exactly as
     * {@code jk build} runs it. Deliberate: install publishes, and a publish without the suite
     * would be the one build verb that skips it silently; {@code --skip-tests} stays the opt-out.
     */
    public static void appendCacheInstall(BuildPlan.Builder builder, JkBuild proj, Path cache, Path m2Dir) {
        builder.stateKeys(PRIMARY);
        // Require whatever the plan already ends on, not just package-jar. appendDeclaredTails
        // re-roots the terminal onto its own join so the assembly / minified / sources tails are
        // not pruned; taking the terminal for cache-install without requiring that join pruned
        // them right back. The visible symptom was `jk install` on a project declaring
        // `assembly = true` installing a THIN-jar launcher — the fat jar was never built, so the
        // install plan's artifact ladder (native > minified > fat > thin) found nothing better
        // than the thin jar and honestly picked it.
        String displaced = builder.currentTerminal();
        List<String> requires = new ArrayList<>(List.of(TaskNames.PACKAGE_JAR));
        if (displaced != null && !TaskNames.PACKAGE_JAR.equals(displaced)) requires.add(displaced);
        boolean isNative = proj.nativeMode() == JkBuild.NativeMode.ALWAYS;
        if (isNative && !requires.contains(TaskNames.NATIVE_IMAGE)) requires.add(TaskNames.NATIVE_IMAGE);
        Task cacheInstall = Task.builder(TaskNames.CACHE_INSTALL)
                .stage(BuildStage.PUBLISH)
                .requires(requires.toArray(new String[0]))
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(BuildPlanner.PROJECT);
                    BuildLayout layout = ctx.require(BuildPlanner.LAYOUT);
                    var p = project.project();
                    Coordinate coord = Coordinate.of(p.group(), p.name(), p.version());
                    if (alreadyInstalled(project, layout, cache, m2Dir)) {
                        ctx.label("already in local repo");
                        ctx.cached();
                        ctx.put(PRIMARY, coord);
                        ctx.progress(1);
                        return;
                    }
                    ctx.label(
                            "install " + coord.group() + ":" + coord.artifact() + ":" + coord.version() + " to cache");
                    try {
                        cacheInstallArtifact(project, layout, cache, m2Dir);
                    } catch (IOException e) {
                        ctx.error(TaskNames.CACHE_INSTALL, Errors.text(e));
                        throw new RuntimeException(e);
                    }
                    ctx.put(PRIMARY, coord);
                    ctx.progress(1);
                })
                .build();
        builder.addTask(cacheInstall).terminal(TaskNames.CACHE_INSTALL);
    }

    /**
     * Build the git-fetch plan for {@code jk install <git-url>}: materialize {@code ref} (tried as
     * a tag first, then a branch) of {@code url} under the cache's git store, requiring the
     * checkout to carry a {@code jk.toml}. {@code refresh} forces a re-fetch. Publishes {@link
     * #CHECKOUT} + {@link #FETCHED_SHA}.
     */
    public static BuildPlan gitFetchBuildPlan(
            String url, String canonicalUrl, String ref, Path cacheDir, boolean refresh) {
        return gitFetchBuildPlan(url, canonicalUrl, ref, cacheDir, refresh, /* requireJkToml */ true);
    }

    /** Like {@link #gitFetchBuildPlan(String, String, String, Path, boolean)} with optional jk.toml gate. */
    public static BuildPlan gitFetchBuildPlan(
            @Nullable String url,
            @Nullable String canonicalUrl,
            @Nullable String ref,
            Path cacheDir,
            boolean refresh,
            boolean requireJkToml) {
        Task fetch = Task.builder(TaskNames.FETCH_GIT)
                .kind(TaskKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("git fetch " + url + " @ " + ref);
                    GitFetcher fetcher = new GitFetcher(JkStores.resolve("git"));
                    GitFetcher.Fetched fetched;
                    try {
                        fetched = fetchTagOrBranch(fetcher, url, canonicalUrl, ref, refresh);
                    } catch (IOException e) {
                        ctx.error("fetch", Errors.text(e));
                        throw new RuntimeException(e);
                    }
                    Path checkout = fetched.checkoutPath();
                    if (requireJkToml && !Files.exists(checkout.resolve(ManifestPaths.MANIFEST))) {
                        ctx.error("no-jk-toml", url + " has no jk.toml at " + ref);
                        throw new RuntimeException("no jk.toml in checkout");
                    }
                    ctx.put(CHECKOUT, checkout);
                    ctx.put(FETCHED_SHA, fetched.sha());
                    ctx.progress(1);
                })
                .build();
        return BuildPlan.builder("install-git-fetch")
                .stateKeys(CHECKOUT, FETCHED_SHA)
                .addTask(fetch)
                .build();
    }

    /** Try the user's ref as a tag first, then a branch. */
    private static GitFetcher.Fetched fetchTagOrBranch(
            GitFetcher fetcher,
            @Nullable String expanded,
            @Nullable String canonical,
            @Nullable String refStr,
            boolean refresh)
            throws IOException {
        String url = Objects.requireNonNull(canonical, "canonical git url");
        String origin = Objects.requireNonNull(expanded, "git url");
        String wanted = Objects.requireNonNull(refStr, "git ref");
        IOException tagFailure;
        try {
            GitSource asTag = new GitSource(url, origin, new GitRefSpec.Tag(wanted), null, true, false);
            return fetcher.fetch(asTag, refresh);
        } catch (IOException e) {
            tagFailure = e;
        }
        try {
            GitSource asBranch = new GitSource(url, origin, new GitRefSpec.Branch(wanted), null, true, false);
            return fetcher.fetch(asBranch, refresh);
        } catch (IOException branchFailure) {
            IOException wrapped = new IOException("ref `" + refStr + "` not found as tag or branch in " + expanded
                    + " (tag: " + tagFailure.getMessage() + "; branch: " + branchFailure.getMessage() + ")");
            wrapped.addSuppressed(tagFailure);
            wrapped.addSuppressed(branchFailure);
            throw wrapped;
        }
    }

    /** Cache-install the thin jar of {@code moduleDir} after a workspace package. */
    public static void installThinJar(Path moduleDir, Path cache, Path m2Dir) throws IOException {
        JkBuild proj = JkBuildParser.parse(moduleDir.resolve(ManifestPaths.MANIFEST));
        proj = WorkspaceResolve.applyWorkspace(moduleDir, proj);
        cacheInstallArtifact(proj, BuildLayout.of(moduleDir, proj), cache, m2Dir);
    }

    /**
     * Install the built JAR and POM into {@code repos/jk-local/} — always, in full: that shelf is
     * where jk's own resolvers and the worker launcher read, and a memo pointing elsewhere is a
     * jar the launcher cannot find. When {@code [m2] install} (and the machine {@code
     * JK_M2_INSTALL} policy) is on, the same bytes also go to the Maven local repo with Maven's
     * checksum sidecars, for Maven and Gradle builds beside jk. Independent of {@code [m2]
     * integration}.
     */
    private static void cacheInstallArtifact(JkBuild project, BuildLayout layout, Path cacheDir, Path m2Dir)
            throws IOException {
        var p = project.project();
        Coordinate coord = Coordinate.of(p.group(), p.name(), p.version());
        Path jar = layout.mainJar();
        String jarRelPath = MavenLayout.artifactPath(coord);
        String pomRelPath = MavenLayout.pomPath(coord);
        byte[] pomBytes = renderedPom(project, layout);

        writeToLocalStore(cacheDir, jarRelPath, jar);
        writeBytesToLocalStore(cacheDir, pomRelPath, pomBytes);
        if (PluginModule.isWorker(layout.moduleRoot())) stageWorkerClosure(coord, cacheDir, jarRelPath);

        if (installToMavenLocal(p)) {
            // m2Dir is caller-resolved (--m2-dir redirects it).
            Path m2Root = m2Dir.resolve("repository");

            Path m2Jar = m2Root.resolve(jarRelPath);
            M2CompatWriter.MavenHashes jarH = M2CompatWriter.copyToM2AndHash(jar, m2Jar);
            M2CompatWriter.writeMavenSidecars(m2Jar, jarH.sha1(), jarH.md5());
            M2CompatWriter.writeRemoteRepositories(
                    Objects.requireNonNull(m2Jar.getParent()),
                    "local",
                    m2Jar.getFileName().toString());

            Path m2Pom = m2Root.resolve(pomRelPath);
            M2CompatWriter.MavenHashes pomH = M2CompatWriter.writeBytesToM2(pomBytes, m2Pom);
            M2CompatWriter.writeMavenSidecars(m2Pom, pomH.sha1(), pomH.md5());
        }
    }

    /** Project {@code [m2] install} and the machine {@code JK_M2_INSTALL} / {@code [m2] install} policy. */
    private static boolean installToMavenLocal(Project p) {
        return p.m2install() && JkM2Config.resolve().install();
    }

    /**
     * True when this module's thin jar and POM are on the shelf at the same SHA-256 — and, when
     * {@code [m2] install} is on, in the machine's Maven local repo too.
     */
    public static boolean alreadyInstalled(JkBuild project, BuildLayout layout, Path cacheDir) {
        return alreadyInstalled(project, layout, cacheDir, null);
    }

    /**
     * As above, checking the Maven local repo under {@code m2Dir} — the caller-resolved {@code ~/.m2}
     * root that {@code --m2-dir} redirects, the same one the install writes to. Null means the
     * machine's.
     */
    public static boolean alreadyInstalled(JkBuild project, BuildLayout layout, Path cacheDir, @Nullable Path m2Dir) {
        if (project == null || layout == null) return false;
        Path jar = layout.mainJar();
        if (!Files.isRegularFile(jar)) return false;
        var p = project.project();
        Coordinate coord = Coordinate.of(p.group(), p.name(), p.version());
        String jarRel = MavenLayout.artifactPath(coord);
        String pomRel = MavenLayout.pomPath(coord);
        try {
            String jarHex = Hashing.sha256Hex(jar);
            String pomHex = Hashing.sha256Hex(renderedPom(project, layout));
            RepoArtifactStore local = localStore(cacheDir);
            if (local.locate(jarRel, jarHex).isEmpty()
                    || local.locate(pomRel, pomHex).isEmpty()) return false;
            if (!installToMavenLocal(p)) return true;
            Path m2 = m2Dir == null ? M2Dirs.localRepository() : m2Dir.resolve("repository");
            return sameBytes(m2.resolve(jarRel), jarHex) && sameBytes(m2.resolve(pomRel), pomHex);
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    private static boolean sameBytes(Path file, String sha256Hex) throws IOException {
        return Files.isRegularFile(file) && Hashing.sha256Hex(file).equalsIgnoreCase(sha256Hex);
    }

    static byte[] renderedPomBytes(JkBuild project, BuildLayout layout) {
        return renderedPom(project, layout);
    }

    private static byte[] renderedPom(JkBuild project, BuildLayout layout) {
        Path moduleRoot = layout.moduleRoot();
        // Worker jars vendor workspace MAIN siblings (plugin-sdk / host). Those edges must not
        // appear on the sidecar / install POM — otherwise PomRuntimeClasspath looks for a
        // coordinate whose classes are already inside the jar.
        JkBuild forPom = omitVendoredWorkerSiblings(project, moduleRoot);
        Lockfile lock = lockOf(moduleRoot);
        String pomXml = PublishablePom.render(
                        forPom,
                        null,
                        WorkspaceResolve.siblingCoordinates(moduleRoot),
                        lockPins(lock),
                        lockClosure(forPom, lock))
                .xml();
        return pomXml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The runtime closure the lock resolved for {@code forPom}: every artifact reachable from its
     * MAIN / RUNTIME / EXPORT declarations along the lock's dependency edges, at the locked
     * version. The installed POM manages these versions so a classpath rebuilt from it — a worker
     * launch — is the closure the module was compiled and tested against, not whatever each
     * transitive POM asks for. Empty when the module is unlocked or declares nothing the lock
     * carries.
     */
    static List<Coordinate> lockClosure(JkBuild forPom, @Nullable Lockfile lock) {
        if (lock == null) return List.of();
        LockGraph graph = LockGraph.forLock(lock);
        Map<String, Coordinate> out = new LinkedHashMap<>();
        Deque<Lockfile.Artifact> queue = new ArrayDeque<>();
        for (Scope scope : List.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME)) {
            for (Dependency d : forPom.dependencies().of(scope)) {
                if (d.isWorkspace()) continue;
                Lockfile.Artifact root = graph.artifact(d.packageKey());
                if (root == null) root = graph.artifact(d.module());
                if (root != null && !out.containsKey(root.name())) {
                    out.put(root.name(), root.coordinate());
                    queue.add(root);
                }
            }
        }
        while (!queue.isEmpty()) {
            Lockfile.Artifact current = queue.poll();
            for (String child : graph.forward(current.name())) {
                Lockfile.Artifact next = graph.artifact(child);
                if (next == null || out.containsKey(next.name())) continue;
                out.put(next.name(), next.coordinate());
                queue.add(next);
            }
        }
        return List.copyOf(out.values());
    }

    /** The module's lock, or null when it has none or it does not read. */
    static @Nullable Lockfile lockOf(Path moduleDir) {
        try {
            Path lockFile = LockPaths.lockFile(moduleDir);
            if (lockFile == null || !Files.isRegularFile(lockFile)) return null;
            return LockfileReader.read(lockFile);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Replace MAIN / RUNTIME / EXPORT edges to workspace siblings, in a worker's POM view, with
     * what those siblings need from <em>outside</em> the workspace. Libraries are untouched — they
     * keep sibling deps so consumers can resolve them.
     *
     * <p>A worker jar vendors its workspace siblings' classes, so naming the sibling in the POM
     * would send a consumer looking for a coordinate that is already inside the jar. Dropping the
     * edge outright is not the answer either: the vendored
     * classes still have third-party dependencies of their own, and nothing else declares them.
     * That is how {@code jk-auditor} came to ship {@code LockfileReader} — vendored from
     * {@code jk-core} — with no mention of tomlj anywhere, and die on the first lockfile it read.
     *
     * <p>So each sibling edge is replaced by that sibling's own non-sibling edges, transitively:
     * what the jar carries is elided, what it still needs is declared.
     */
    static JkBuild omitVendoredWorkerSiblings(JkBuild project, Path moduleRoot) {
        if (project == null || moduleRoot == null || !PluginModule.isWorker(moduleRoot)) {
            return project;
        }
        Set<String> siblings = WorkspaceResolve.siblingCoordinates(moduleRoot);
        if (siblings.isEmpty()) return project;
        Map<String, JkBuild> manifests = WorkspaceResolve.siblingManifests(moduleRoot);
        Map<Scope, List<Dependency>> by = new LinkedHashMap<>();
        boolean changed = false;
        for (Scope scope : Scope.values()) {
            List<Dependency> deps = project.dependencies().of(scope);
            if (deps.isEmpty()) continue;
            boolean strip = scope == Scope.MAIN || scope == Scope.RUNTIME || scope == Scope.EXPORT;
            if (!strip) {
                by.put(scope, deps);
                continue;
            }
            Map<String, Dependency> kept = new LinkedHashMap<>();
            for (Dependency d : deps) {
                JkBuild vendored = siblingOf(d, siblings, manifests);
                if (vendored != null || isSibling(d, siblings)) {
                    changed = true;
                    hoistVendored(vendored, siblings, manifests, new LinkedHashSet<>(), kept);
                    continue;
                }
                kept.putIfAbsent(d.module(), d);
            }
            if (!kept.isEmpty()) by.put(scope, List.copyOf(kept.values()));
            else changed = true;
        }
        return changed ? project.withDependencies(new JkBuild.Dependencies(by)) : project;
    }

    /**
     * Collect {@code coord}'s non-sibling MAIN / RUNTIME / EXPORT edges into {@code out}, following
     * sibling edges through. {@code seen} makes a workspace cycle terminate rather than recurse;
     * a sibling whose manifest could not be loaded contributes nothing, which is the same
     * best-effort posture {@link WorkspaceResolve#siblingCoordinates} takes.
     */
    private static void hoistVendored(
            @Nullable JkBuild sibling,
            Set<String> siblings,
            Map<String, JkBuild> manifests,
            Set<String> seen,
            Map<String, Dependency> out) {
        if (sibling == null) return;
        if (!seen.add(sibling.project().group() + ":" + sibling.project().name())) return;
        for (Scope scope : List.of(Scope.MAIN, Scope.RUNTIME, Scope.EXPORT)) {
            for (Dependency d : sibling.dependencies().of(scope)) {
                JkBuild next = siblingOf(d, siblings, manifests);
                if (next != null) hoistVendored(next, siblings, manifests, seen, out);
                // A sibling edge whose manifest would not load contributes nothing — never the
                // raw `workspace:<name>` placeholder, which no consumer could resolve.
                else if (!isSibling(d, siblings)) out.putIfAbsent(d.module(), d);
            }
        }
    }

    /** True when {@code d} names a workspace member, in either spelling. */
    private static boolean isSibling(Dependency d, Set<String> siblings) {
        return d.isWorkspace() || siblings.contains(d.module());
    }

    /** The member {@code d} names, resolved through either spelling; {@code null} when it names none. */
    private static @Nullable JkBuild siblingOf(Dependency d, Set<String> siblings, Map<String, JkBuild> manifests) {
        if (!isSibling(d, siblings)) return null;
        JkBuild byCoord = manifests.get(d.module());
        return byCoord != null ? byCoord : manifests.get(d.library());
    }

    /** Exact versions from the module's lock, keyed by {@code group:artifact}. Empty when unlocked. */
    static Map<String, String> lockPins(Path moduleDir) {
        return lockPins(lockOf(moduleDir));
    }

    static Map<String, String> lockPins(@Nullable Lockfile lock) {
        if (lock == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Lockfile.Artifact a : lock.artifacts()) {
            if (a.name() != null
                    && !a.name().isBlank()
                    && a.version() != null
                    && !a.version().isBlank()) {
                out.put(a.name(), a.version());
            }
        }
        for (Lockfile.ModuleEntry m : lock.modules()) {
            out.put(m.group() + ":" + m.name(), m.version());
        }
        return out;
    }

    /**
     * Walk the shelved worker's POM graph now, while the remotes are reachable, so every POM and
     * jar its launch needs is in the store before the first fork — a fork resolves from disk and
     * never over the network. A closure that does not resolve fails the install here, naming the
     * gap, rather than the worker later.
     */
    static void stageWorkerClosure(Coordinate coord, Path cacheDir, String jarRelPath) throws IOException {
        Path shelved = localStore(cacheDir)
                .locate(jarRelPath)
                .orElseThrow(() -> new IOException("worker " + coord + " was not shelved under repos/jk-local"));
        try {
            PomRuntimeClasspath.stage(shelved);
        } catch (IllegalStateException e) {
            throw new IOException(
                    "worker " + coord + " installed, but its launch classpath does not resolve: " + e.getMessage(), e);
        }
    }

    /**
     * See {@link RepoArtifactStore#writeToLocalStore} — the one shared local-install write, routed
     * to the store root.
     */
    public static void writeToLocalStore(Path cacheDir, String relativePath, Path source) throws IOException {
        RepoArtifactStore.writeToLocalStore(JkStores.store(), relativePath, source);
    }

    private static RepoArtifactStore localStore(Path cacheDir) {
        return RepoArtifactStore.forStoreId(JkStores.store(), RepoArtifactResolver.JK_LOCAL);
    }

    /** Write byte content into {@code repos/jk-local/} as a full-store entry with a {@code .jk} memo. */
    private static void writeBytesToLocalStore(Path cacheDir, String relativePath, byte[] content) throws IOException {
        Path tmp = Files.createTempFile("jk-install-", ".bin");
        try {
            Files.write(tmp, content);
            writeToLocalStore(cacheDir, relativePath, tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}

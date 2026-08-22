// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.git.GitFetcher;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code jk install} heavy halves: {@link #projectInstallBuildPlan} (build + cache-install into
 * {@code repos/local/}) and {@link #gitFetchBuildPlan}. User-home launcher shims stay client-side.
 */
public final class InstallPlans {

    private InstallPlans() {}

    // Cross-step keys.
    public static final BuildPlanKey<Coordinate> PRIMARY = BuildPlanKey.of("primary-coord", Coordinate.class);
    public static final BuildPlanKey<Path> CHECKOUT = BuildPlanKey.of("checkout-dir", Path.class);
    public static final BuildPlanKey<String> FETCHED_SHA = BuildPlanKey.of("fetched-sha", String.class);

    /**
     * Build the project-install plan for {@code projectDir}: core plan + declared tails +
     * (native application only) the native-image tail with {@code graalHome} + the {@code
     * cache-install} step. {@code m2Dir} is the local Maven repo root ({@code ~/.m2} or the
     * {@code --m2-dir} override).
     */
    public static BuildPlan projectInstallBuildPlan(
            Path projectDir, Path cache, Path m2Dir, boolean skipTests, boolean verbose, Path graalHome)
            throws IOException {
        JkBuild proj = JkBuildParser.parse(projectDir.resolve("jk.toml"));

        Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(projectDir);
        boolean compact = cc.jumpkick.layout.ModuleLayout.isCompact(projectDir);
        int estimatedTestCount = TestSupport.estimateAllSuiteTestCount(projectDir, compact);
        BuildPlanner.Inputs inputs = new BuildPlanner.Inputs(
                projectDir,
                cache,
                projectDir.resolve("jk.toml"),
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
                cc.jumpkick.config.SessionContext.current());
        BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs);
        // ALWAYS modules get native from appendDeclaredTails (same as jk build); pass the
        // client-resolved GraalVM so install does not re-resolve.
        BuildPlanner.appendDeclaredTails(builder, inputs, graalHome, true);
        appendCacheInstall(builder, proj, cache, m2Dir);
        return builder.build();
    }

    /**
     * Thin-jar {@code cache-install} tail. Fat and minified jars are not written to the local
     * repo — only the thin jar is.
     */
    public static void appendCacheInstall(BuildPlan.Builder builder, JkBuild proj, Path cache, Path m2Dir) {
        boolean isNative = proj.nativeMode() == JkBuild.NativeMode.ALWAYS;
        List<String> requires = new ArrayList<>(List.of(TaskNames.PACKAGE_JAR));
        if (isNative) requires.add(TaskNames.NATIVE_IMAGE);
        Task cacheInstall = Task.builder(TaskNames.CACHE_INSTALL)
                .stage(cc.jumpkick.run.BuildStage.PUBLISH)
                .requires(requires.toArray(new String[0]))
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(BuildPlanner.PROJECT);
                    BuildLayout layout = ctx.require(BuildPlanner.LAYOUT);
                    var p = project.project();
                    Coordinate coord = Coordinate.of(p.group(), p.name(), p.version());
                    if (alreadyInstalled(project, layout, cache)) {
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
                        ctx.error(TaskNames.CACHE_INSTALL, e.getMessage());
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
            String url, String canonicalUrl, String ref, Path cacheDir, boolean refresh, boolean requireJkToml) {
        Task fetch = Task.builder(TaskNames.FETCH_GIT)
                .kind(TaskKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("git fetch " + url + " @ " + ref);
                    GitFetcher fetcher = new GitFetcher(JkStores.resolve(cacheDir, "git"));
                    GitFetcher.Fetched fetched;
                    try {
                        fetched = fetchTagOrBranch(fetcher, url, canonicalUrl, ref, refresh);
                    } catch (IOException e) {
                        ctx.error("fetch", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    Path checkout = fetched.checkoutPath();
                    if (requireJkToml && !Files.exists(checkout.resolve("jk.toml"))) {
                        ctx.error("no-jk-toml", url + " has no jk.toml at " + ref);
                        throw new RuntimeException("no jk.toml in checkout");
                    }
                    ctx.put(CHECKOUT, checkout);
                    ctx.put(FETCHED_SHA, fetched.sha());
                    ctx.progress(1);
                })
                .build();
        return BuildPlan.builder("install-git-fetch").addTask(fetch).build();
    }

    /** Try the user's ref as a tag first, then a branch. */
    private static GitFetcher.Fetched fetchTagOrBranch(
            GitFetcher fetcher, String expanded, String canonical, String refStr, boolean refresh) throws IOException {
        IOException tagFailure;
        try {
            GitSource asTag = new GitSource(canonical, expanded, new GitRefSpec.Tag(refStr), null, true, false);
            return fetcher.fetch(asTag, refresh);
        } catch (IOException e) {
            tagFailure = e;
        }
        try {
            GitSource asBranch = new GitSource(canonical, expanded, new GitRefSpec.Branch(refStr), null, true, false);
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
        JkBuild proj = JkBuildParser.parse(moduleDir.resolve("jk.toml"));
        proj = cc.jumpkick.config.WorkspaceResolve.applyWorkspace(moduleDir, proj);
        cacheInstallArtifact(proj, BuildLayout.of(moduleDir, proj), cache, m2Dir);
    }

    /**
     * Install the built JAR and POM into {@code repos/local/}; when {@code [m2] install} (and the
     * machine {@code JK_M2_INSTALL} policy) is on, also write the Maven local repo with checksum
     * sidecars. Independent of {@code [m2] integration}.
     */
    private static void cacheInstallArtifact(JkBuild project, BuildLayout layout, Path cacheDir, Path m2Dir)
            throws IOException {
        var p = project.project();
        Coordinate coord = Coordinate.of(p.group(), p.name(), p.version());
        Path jar = layout.mainJar();
        String jarRelPath = cc.jumpkick.repo.MavenLayout.artifactPath(coord);
        String pomRelPath = cc.jumpkick.repo.MavenLayout.pomPath(coord);
        byte[] pomBytes = renderedPom(project, layout);

        if (installToMavenLocal(p)) {
            // The local Maven repo is primary. m2Dir is caller-resolved (--m2-dir redirects it).
            Path m2Root = m2Dir.resolve("repository");

            Path m2Jar = m2Root.resolve(jarRelPath);
            cc.jumpkick.repo.M2CompatWriter.MavenHashes jarH =
                    cc.jumpkick.repo.M2CompatWriter.copyToM2AndHash(jar, m2Jar);
            cc.jumpkick.repo.M2CompatWriter.writeMavenSidecars(m2Jar, jarH.sha1(), jarH.md5());
            cc.jumpkick.repo.M2CompatWriter.writeRemoteRepositories(
                    m2Jar.getParent(), "local", m2Jar.getFileName().toString());

            Path m2Pom = m2Root.resolve(pomRelPath);
            cc.jumpkick.repo.M2CompatWriter.MavenHashes pomH =
                    cc.jumpkick.repo.M2CompatWriter.writeBytesToM2(pomBytes, m2Pom);
            cc.jumpkick.repo.M2CompatWriter.writeMavenSidecars(m2Pom, pomH.sha1(), pomH.md5());

            RepoArtifactStore local = localStore(cacheDir);
            local.writeMemo(jarRelPath, m2Jar, Hashing.sha256Hex(jar));
            local.writeMemo(pomRelPath, m2Pom, Hashing.sha256Hex(pomBytes));
        } else {
            writeToLocalStore(cacheDir, jarRelPath, jar);
            writeBytesToLocalStore(cacheDir, pomRelPath, pomBytes);
        }
    }

    /** Project {@code [m2] install} and the machine {@code JK_M2_INSTALL} / {@code [m2] install} policy. */
    private static boolean installToMavenLocal(JkBuild.Project p) {
        return p.m2install() && JkM2Config.resolve().install();
    }

    /**
     * True when this module's thin jar and POM are already installed at the same SHA-256
     * ({@code repos/local}, or the Maven local repo when {@code [m2] install} is on).
     */
    public static boolean alreadyInstalled(JkBuild project, BuildLayout layout, Path cacheDir) {
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
            if (installToMavenLocal(p)) {
                Path storeLocal =
                        JkStores.storeRootFor(cacheDir).resolve("repos").resolve("local");
                Path m2 = M2Dirs.localRepository();
                return ArtifactMemo.verify(
                                m2.resolve(jarRel), ArtifactMemo.jkPath(storeLocal, jarRel), coord.toGav(), jarHex)
                        && ArtifactMemo.verify(
                                m2.resolve(pomRel), ArtifactMemo.jkPath(storeLocal, pomRel), coord.toGav(), pomHex);
            }
            RepoArtifactStore local = localStore(cacheDir);
            return local.locate(jarRel, jarHex).isPresent()
                    && local.locate(pomRel, pomHex).isPresent();
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    static byte[] renderedPomBytes(JkBuild project, BuildLayout layout) {
        return renderedPom(project, layout);
    }

    private static byte[] renderedPom(JkBuild project, BuildLayout layout) {
        String pomXml = cc.jumpkick.publish.PublishablePom.render(
                        project,
                        null,
                        cc.jumpkick.config.WorkspaceResolve.siblingCoordinates(layout.moduleRoot()),
                        lockPins(layout.moduleRoot()))
                .xml();
        return pomXml.getBytes(StandardCharsets.UTF_8);
    }

    /** Exact versions from the module's lock, keyed by {@code group:artifact}. Empty when unlocked. */
    static Map<String, String> lockPins(Path moduleDir) {
        try {
            Path lockFile = LockPaths.lockFile(moduleDir);
            if (lockFile == null || !Files.isRegularFile(lockFile)) return Map.of();
            Lockfile lock = LockfileReader.read(lockFile);
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
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * See {@link RepoArtifactStore#writeToLocalStore} — the one shared local-install write, routed
     * to the store root.
     */
    public static void writeToLocalStore(Path cacheDir, String relativePath, Path source) throws IOException {
        RepoArtifactStore.writeToLocalStore(JkStores.storeRootFor(cacheDir), relativePath, source);
    }

    private static RepoArtifactStore localStore(Path cacheDir) {
        return RepoArtifactStore.forRepoName(JkStores.storeRootFor(cacheDir), "local");
    }

    /** Write byte content into {@code repos/local/} as a full-store entry with a {@code .jk} memo. */
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

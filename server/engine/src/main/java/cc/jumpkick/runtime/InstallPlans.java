// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.git.GitFetcher;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
     * Install the built JAR and POM into {@code repos/local/}; when {@code m2install}, also mirror
     * to the local Maven repo with checksum sidecars.
     */
    private static void cacheInstallArtifact(JkBuild project, BuildLayout layout, Path cacheDir, Path m2Dir)
            throws IOException {
        var p = project.project();
        Coordinate coord = Coordinate.of(p.group(), p.name(), p.version());
        Path jar = layout.mainJar();
        String jarRelPath = cc.jumpkick.repo.MavenLayout.artifactPath(coord);
        String pomRelPath = cc.jumpkick.repo.MavenLayout.pomPath(coord);
        String pomXml = cc.jumpkick.publish.PublishablePom.render(
                        project, null, cc.jumpkick.config.WorkspaceResolve.siblingCoordinates(layout.moduleRoot()))
                .xml();
        byte[] pomBytes = pomXml.getBytes(StandardCharsets.UTF_8);

        if (p.m2install()) {
            // The local Maven repo is primary. m2Dir is caller-resolved (--m2-dir redirects it).
            Path m2Root = m2Dir.resolve("repository");

            // JAR → ~/.m2 with .sha1, .md5, _remote.repositories
            Path m2Jar = m2Root.resolve(jarRelPath);
            cc.jumpkick.repo.M2CompatWriter.MavenHashes jarH =
                    cc.jumpkick.repo.M2CompatWriter.copyToM2AndHash(jar, m2Jar);
            cc.jumpkick.repo.M2CompatWriter.writeMavenSidecars(m2Jar, jarH.sha1(), jarH.md5());
            cc.jumpkick.repo.M2CompatWriter.writeRemoteRepositories(
                    m2Jar.getParent(), "local", m2Jar.getFileName().toString());

            // POM → ~/.m2 with .sha1, .md5
            Path m2Pom = m2Root.resolve(pomRelPath);
            cc.jumpkick.repo.M2CompatWriter.MavenHashes pomH =
                    cc.jumpkick.repo.M2CompatWriter.writeBytesToM2(pomBytes, m2Pom);
            cc.jumpkick.repo.M2CompatWriter.writeMavenSidecars(m2Pom, pomH.sha1(), pomH.md5());

            // Index sidecars in repos/local/ (jk's O(1) lookup, pointing to ~/.m2)
            writeLocalIndexSidecar(cacheDir, jarRelPath, Hashing.sha256Hex(jar));
            writeLocalIndexSidecar(cacheDir, pomRelPath, Hashing.sha256Hex(pomBytes));
        } else {
            // repos/local/ is primary (plugin JARs, jk-internal use).
            writeToLocalStore(cacheDir, jarRelPath, jar);
            writeContentToLocalStore(cacheDir, pomRelPath, pomBytes);
        }
    }

    /** Write a sidecar-only entry in {@code repos/local/} pointing to an artifact in {@code ~/.m2}. */
    private static void writeLocalIndexSidecar(Path cacheDir, String relativePath, String sha256) {
        try {
            Path sidecar = JkStores.resolve(cacheDir, "repos").resolve("local").resolve(relativePath + ".sha256");
            Files.createDirectories(sidecar.getParent());
            if (!Files.exists(sidecar)) Files.writeString(sidecar, sha256);
        } catch (IOException ignored) {
        }
    }

    /**
     * See {@link cc.jumpkick.repo.RepoArtifactStore#writeToLocalStore} — the one shared
     * local-install write, routed to the store root: the resolver reads
     * {@code repos/local/} from the store since the cache/store split, so writing to the raw
     * cache root strands the artifact.
     */
    public static void writeToLocalStore(Path cacheDir, String relativePath, Path source) throws IOException {
        cc.jumpkick.repo.RepoArtifactStore.writeToLocalStore(JkStores.storeRootFor(cacheDir), relativePath, source);
    }

    /** Write byte content directly into {@code repos/local/} as a full-store entry. */
    private static void writeContentToLocalStore(Path cacheDir, String relativePath, byte[] content)
            throws IOException {
        Path target = JkStores.resolve(cacheDir, "repos").resolve("local").resolve(relativePath);
        AtomicWrites.replace(target, content);
        Files.writeString(Path.of(target + ".sha256"), Hashing.sha256Hex(content));
    }
}

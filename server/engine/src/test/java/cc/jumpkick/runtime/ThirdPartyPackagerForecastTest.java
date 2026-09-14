// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.PluginDescriptorOps;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.tool.TrustedPlugins;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forecast arm for a module whose main artifact a plugin packs, driven by a real third-party
 * packager rather than a stub: the plugin is published to a {@code file://} repo, resolved,
 * SHA-pinned, trust-gated, and declares its packager over the describe protocol.
 *
 * <p>That arm is fail-closed — a missing tool, an untrusted worker, or any exception all forecast
 * RUN, which is also what a correct cache miss looks like. RUN alone therefore proves nothing, and
 * the load-bearing assertion is the cached one: given the action record the live packaging step
 * would store, the forecast has to reproduce the packager's own key rather than the plain jar's.
 */
@Tag("slow")
class ThirdPartyPackagerForecastTest {

    private static final String GROUP = "com.example";
    private static final String ARTIFACT = "hello-packager-plugin";

    /**
     * Unique per run: the shared store feeds artifacts by coordinate across runs and the fixture
     * jar's bytes differ every publish, so a fixed version would fail the SHA pin later.
     */
    private static final String VERSION = "1.0." + System.currentTimeMillis();

    private static final String PACKAGER = "hello-pack";

    private static final String MANIFEST = """
            [plugin]
            id      = "hellopack"
            table   = "hellopack"
            version = "1.0.0"

            [schema]
            greeting = { type = "string", default = "hi" }

            [packaging]
            packager           = "%s"
            artifact-extension = "hlo"

            [code]
            protocol-prefix = "##HELLOPACK:"
            """.formatted(PACKAGER);

    /**
     * Answers {@code describe} with one packager declaration. Packaging itself is never forked: a
     * forecast is read-only, and the cached side is seeded through the same action-cache call the
     * live packaging step makes.
     */
    private static final String MAIN = """
            import java.nio.file.*;
            public final class HelloPackagerMain {
                public static void main(String[] args) throws Exception {
                    String spec = Files.readString(Path.of(args[0]));
                    if (spec.contains("\\"op\\":\\"describe\\"")) {
                        System.out.println("##HELLOPACK:{\\"t\\":\\"packager\\",\\"name\\":\\"%s\\","
                                + "\\"inputs\\":[\\"classes\\",\\"config\\"]}");
                    }
                }
            }
            """.formatted(PACKAGER);

    @AfterEach
    void clearSeam() {
        System.clearProperty("jk.trust.state.dir");
    }

    @Test
    void plugin_owned_packaging_forecasts_run_then_cached(@TempDir Path tmp) throws Exception {
        Path repo = ThirdPartyPluginFixture.publish(
                tmp.resolve("repo"), GROUP, ARTIFACT, VERSION, "HelloPackagerMain", MAIN, MANIFEST);
        Path cache = tmp.resolve("cache");
        Path proj = Files.createDirectories(tmp.resolve("proj"));
        Path stateDir = Files.createDirectories(tmp.resolve("state"));
        System.setProperty("jk.trust.state.dir", stateDir.toString());

        Path jar = repo.resolve(GROUP.replace('.', '/'))
                .resolve(ARTIFACT)
                .resolve(VERSION)
                .resolve(ARTIFACT + "-" + VERSION + ".jar");
        String hex = Hashing.sha256Hex(jar);

        Files.writeString(proj.resolve("jk.toml"), """
                name = "demo"
                group = "com.demo"
                version = "0.1.0"
                java = 25
                groovy = "5.0.4"

                # Keep the fixture jar+pom under repos/<name>/ (not ~/.m2) so worker launch
                # finds a sibling POM next to the jar.
                [m2]
                integration = false
                install = false

                [repositories]
                local = "%s"

                [plugins]
                hellopack = { group = "%s", name = "%s", version = "%s", sha256 = "%s" }

                [hellopack]
                greeting = "yo"
                """.formatted(repo.toUri(), GROUP, ARTIFACT, VERSION, hex));

        // One source, plus the stamp compile-groovy leaves behind: the packaging arm sits behind a
        // clean compile, and an hour-old source keeps the stamp comparison off the wall clock.
        Path src = Files.createDirectories(proj.resolve("src"));
        Path hello = Files.writeString(src.resolve("Hello.groovy"), "class Hello {}");
        Files.setLastModifiedTime(hello, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));

        // Resolve the plugin the way lock-plugins does: fetch through the shared store, pin the
        // SHA, pool the jar in the CAS, materialize the descriptor, then trust the coordinate.
        JkBuild parsed = JkBuildParser.parse(proj.resolve("jk.toml"));
        Cas cas = JkStores.storeCas();
        RepoGroup repos = RepoGroupBuilder.buildFor(parsed, null, cas);
        var fetched =
                repos.tryFetchArtifact(Coordinate.of(GROUP, ARTIFACT, VERSION)).orElseThrow();
        assertThat(fetched.fetched().sha256()).isEqualTo(hex);
        // Sibling POM is required for worker classpath reconstruction.
        repos.tryFetchArtifact(new Coordinate(GROUP, ARTIFACT, VERSION, null, "pom"))
                .orElseThrow();
        cas.putFile(fetched.fetched().cachePath(), hex);
        Path lockFile = proj.resolve("jk-lock.toml");
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        null,
                        null,
                        List.of(),
                        List.of(new Lockfile.PluginEntry(
                                GROUP + ":" + ARTIFACT,
                                VERSION,
                                "sha256:" + fetched.fetched().sha256())),
                        List.of(),
                        List.of(),
                        null,
                        // Without the manifest digest the lock reads as stale and the forecast
                        // short-circuits to a single "lock update needed" step.
                        LockManifestDigest.compute(proj),
                        null),
                lockFile);
        assertThat(PluginDescriptorOps.ensureMaterialized(proj, cache)).isTrue();
        TrustedPlugins.load(stateDir).add(GROUP + ":" + ARTIFACT);

        JkBuild project = JkBuildParser.reparse(proj.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(proj, project);
        GroovyForecastStamps.writeBuildStamp(proj, proj, List.of(hello), cas);

        // The plugin declares a packager and keeps the main artifact, so the build packs with it.
        var owner = requireNonNull(PackagingKeys.pluginFor(project, layout, cache));
        assertThat(PackagingKeys.ownsPackaging(owner)).isTrue();
        assertThat(requireNonNull(owner.decls().packager()).name()).isEqualTo(PACKAGER);

        BuildGraph.Result graph = BuildGraph.resolve(proj, project);
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache = new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache));

        // Cold: no packaging record, so the step names the packager as the work that will run.
        TaskForecast.Task cold = packageStep(TaskForecaster.of(graph, cas, actionCache, cache, true));
        assertThat(cold.cached()).isFalse();
        assertThat(cold.text()).isEqualTo("repackage · " + PACKAGER);

        // The record a live package-jar leaves behind, keyed by the packager's own token bag.
        Path artifact = PluginBuild.mainArtifactPath(layout, owner.active());
        assertThat(artifact.getFileName().toString()).endsWith(".hlo");
        var keyed = PackagingKeys.pluginPackager(new PackagingKeys.Packager(
                        project,
                        proj,
                        cache,
                        lockFile,
                        cas,
                        new PluginBuild.StepTools(),
                        layout,
                        layout.classesDir(),
                        artifact,
                        TaskForecaster.forecastJavaHome(proj, project, LockfileReader.read(lockFile)),
                        owner.active(),
                        owner.decls(),
                        Map.of()))
                .keyed();
        Files.createDirectories(requireNonNull(artifact.getParent()));
        Files.writeString(artifact, "packed by " + PACKAGER);
        PlannerSupport.storePackagedForTest(
                cache,
                keyed.taskId(),
                keyed.key(),
                keyed.tokens(),
                requireNonNull(artifact.getParent()),
                List.of(artifact),
                true);

        // Warm: the forecast reproduces that key and prices the step against it.
        TaskForecast.Task warm = packageStep(TaskForecaster.of(graph, cas, actionCache, cache, true));
        assertThat(warm.cached()).isTrue();
        assertThat(warm.key()).isEqualTo(TaskForecaster.key8(keyed.key()));
    }

    private static TaskForecast.Task packageStep(List<TaskForecast.Module> plan) {
        // TempDir paths may be symlink-normalized by the graph — match by basename.
        return plan.stream().filter(m -> m.dir().endsWith("proj")).findFirst().orElseThrow().steps().stream()
                .filter(s -> s.name().equals(TaskNames.PACKAGE_JAR))
                .findFirst()
                .orElseThrow();
    }
}

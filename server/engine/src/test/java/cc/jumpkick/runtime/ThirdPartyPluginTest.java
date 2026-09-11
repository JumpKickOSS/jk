// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.base.PluginDescriptorOps;
import cc.jumpkick.tool.TrustedPlugins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P5 acceptance (build-plugins plan §4): a third-party hello-world table plugin, published to a
 * (file://) repo, declared under {@code [plugins]} — resolved, SHA-pinned, manifest-extracted,
 * schema-validated, contribution-applied, and its worker code trust-gated end to end.
 */
@Tag("slow")
class ThirdPartyPluginTest {

    private static final String GROUP = "com.example";
    private static final String ARTIFACT = "hello-jk-plugin";

    /**
     * Unique per run: the shared store feeds artifacts by coordinate across test runs, and the
     * fixture jar's bytes differ every publish (jar entry timestamps) — a fixed version would
     * make a later run fetch the previous run's jar and fail the SHA pin.
     */
    private static final String VERSION = "1.0." + System.currentTimeMillis();

    private static final String MANIFEST = """
            [plugin]
            id      = "hello"
            table   = "hello"
            version = "1.0.0"

            [schema]
            greeting = { type = "string", default = "hi" }

            [[contribute.compiler-args]]
            javac = ["-Averbose.greeting=${config.greeting}"]

            [code]
            protocol-prefix = "##HELLO:"
            """;

    private static final String MAIN = """
            import java.nio.file.*;
            public final class HelloPluginMain {
                public static void main(String[] args) throws Exception {
                    String spec = Files.readString(Path.of(args[0]));
                    if (spec.contains("\\"op\\":\\"describe\\"")) {
                        System.out.println("##HELLO:{\\"t\\":\\"command\\",\\"name\\":\\"hello\\",\\"description\\":\\"Say hello\\"}");
                    } else if (spec.contains("\\"op\\":\\"command\\"")) {
                        System.out.println("##HELLO:{\\"t\\":\\"command-out\\",\\"line\\":\\"hello from the plugin worker\\"}");
                    }
                }
            }
            """;

    @AfterEach
    void clearSeam() {
        System.clearProperty("jk.trust.state.dir");
    }

    @Test
    void hello_world_plugin_runs_from_a_published_coordinate(@TempDir Path tmp) throws Exception {
        Path repo = publishFixture(tmp.resolve("repo"));
        Path cache = tmp.resolve("cache");
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Path stateDir = Files.createDirectories(tmp.resolve("state"));
        System.setProperty("jk.trust.state.dir", stateDir.toString());

        Path jar = repo.resolve(GROUP.replace('.', '/'))
                .resolve(ARTIFACT)
                .resolve(VERSION)
                .resolve(ARTIFACT + "-" + VERSION + ".jar");
        String hex = Hashing.sha256Hex(jar);

        Files.writeString(project.resolve("jk.toml"), """
                name = "demo"
                group = "com.demo"
                version = "0.1.0"

                # Keep the fixture jar+pom under repos/<name>/ (not ~/.m2) so worker launch
                # finds a sibling POM next to the jar.
                [m2]
                integration = false
                install = false

                [repositories]
                local = "%s"

                [plugins]
                hello = { group = "%s", name = "%s", version = "%s", sha256 = "%s" }

                [hello]
                greeting = "yo"
                """.formatted(repo.toUri(), GROUP, ARTIFACT, VERSION, hex));

        // 1. Pre-lock: the declaration is unresolved — the parse stays soft, no config yet.
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        assertThat(build.pluginConfig("hello")).isEmpty();
        assertThat(build.plugins().getFirst().sha256()).isEqualTo(hex);

        // 2. Lock: resolve the coordinate exactly as lock-plugins does — fetch, SHA-pin, extract.
        // JkStores.cas, not new Cas(cache): the engine reads plugin jars through the shared
        // store root, so the fetch must land there too or ensureMaterialized sees no jar.
        // JkStores.cas ignores the temp cache path and uses the ambient product store (same
        // root PluginDescriptorOps.ensureMaterialized reads). Fetch into that store, then put the
        // jar into the CAS blob pool — matching SyncPlans.syncPlugins.
        Cas cas = JkStores.storeCas();
        RepoGroup repos = RepoGroupBuilder.buildFor(build, null, cas);
        Coordinate jarCoord = Coordinate.of(GROUP, ARTIFACT, VERSION);
        var fetched = repos.tryFetchArtifact(jarCoord).orElseThrow();
        assertThat(fetched.fetched().sha256()).isEqualTo(hex);
        // Sibling POM is required for worker classpath reconstruction.
        repos.tryFetchArtifact(new Coordinate(GROUP, ARTIFACT, VERSION, null, "pom"))
                .orElseThrow();
        cas.putFile(fetched.fetched().cachePath(), hex);
        var entry = new Lockfile.PluginEntry(
                GROUP + ":" + ARTIFACT, VERSION, "sha256:" + fetched.fetched().sha256());
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        null,
                        null,
                        List.of(),
                        List.of(entry)),
                project.resolve("jk-lock.toml"));
        assertThat(PluginDescriptorOps.ensureMaterialized(project, cache)).isTrue();

        // 3. Re-parse: the table validates against the extracted schema; the contribution applies.
        build = JkBuildParser.reparse(project.resolve("jk.toml"));
        assertThat(build.pluginConfig("hello")).isPresent();
        assertThat(build.pluginConfig("hello").orElseThrow().string("greeting")).isEqualTo("yo");
        assertThat(PluginContributions.javacArgs(build, project, Set.of())).contains("-Averbose.greeting=yo");

        // 3b. With the plugin resolved, a genuinely unowned table is the plan's hard error.
        Files.writeString(
                project.resolve("jk.toml"), Files.readString(project.resolve("jk.toml")) + "\n[bogus]\nx = 1\n");
        assertThatThrownBy(() -> JkBuildParser.reparse(project.resolve("jk.toml")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[bogus] is not owned by any installed plugin");
        Files.writeString(
                project.resolve("jk.toml"),
                Files.readString(project.resolve("jk.toml")).replace("\n[bogus]\nx = 1\n", ""));
        build = JkBuildParser.reparse(project.resolve("jk.toml"));

        // 4. Untrusted: the engine refuses to fork the worker, naming the remedy.
        var active = PluginBuild.activeCodePlugin(build, project).orElseThrow();
        Path spec = Files.writeString(tmp.resolve("noop.spec"), "{\"t\":\"op\",\"op\":\"describe\"}\n");
        assertThatThrownBy(() -> PluginBuild.runWorker(active, cache, spec, WorkerEnv.strict(), null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not trusted")
                .hasMessageContaining("jk trust plugin com.example:hello-jk-plugin");

        // 5. Trusted: describe answers, and the command runs through the real command machinery.
        TrustedPlugins.load(stateDir).add(GROUP + ":" + ARTIFACT);
        var report = PluginCommands.run(project, cache, "hello", List.of());
        assertThat(report.error()).isNull();
        assertThat(report.exit()).isZero();
        assertThat(report.output()).containsExactly("hello from the plugin worker");
    }

    /**
     * Pin-is-law for the plugin sync path: the pinned fetch overload never serves a
     * warm hit that disagrees with the pin, and when the remote itself serves different bytes the
     * returned digest exposes the mismatch — the caller-side compare in SyncPlans.syncPlugins is
     * what keeps those bytes out of the CAS.
     */
    @Test
    void pinned_fetch_exposes_bytes_that_disagree_with_the_lock_pin(@TempDir Path tmp) throws Exception {
        // Distinct version: JkStores.cas ignores its argument and serves the ambient shared store,
        // so sharing VERSION with the end-to-end test would cross-feed its coordinate a jar this
        // test fetched without the sibling POM.
        String version = VERSION + "1";
        Path repo = publishFixture(tmp.resolve("repo"), version);
        Path jar = repo.resolve(GROUP.replace('.', '/'))
                .resolve(ARTIFACT)
                .resolve(version)
                .resolve(ARTIFACT + "-" + version + ".jar");
        String hex = Hashing.sha256Hex(jar);

        Files.writeString(tmp.resolve("jk.toml"), """
                name = "demo"
                group = "com.demo"
                version = "0.1.0"

                [m2]
                integration = false
                install = false

                [repositories]
                fixture = "%s"
                """.formatted(repo.toUri()));
        JkBuild build = JkBuildParser.parse(tmp.resolve("jk.toml"));
        Cas cas = JkStores.storeCas();
        RepoGroup repos = RepoGroupBuilder.buildFor(build, null, cas);
        Coordinate coord = Coordinate.of(GROUP, ARTIFACT, version);

        // Matching pin: cold then warm, both return bytes hashing to the pin.
        assertThat(repos.tryFetchArtifact(coord, hex).orElseThrow().fetched().sha256())
                .isEqualToIgnoringCase(hex);
        assertThat(repos.tryFetchArtifact(coord, hex).orElseThrow().fetched().sha256())
                .isEqualToIgnoringCase(hex);

        // A pin the remote cannot satisfy: the warm mirror hit must not be blessed into the
        // answer; the re-fetched bytes carry their true digest, which disagrees with the pin —
        // exactly the signal syncPlugins refuses to putFile.
        String wrongPin = "0".repeat(64);
        var refetched = repos.tryFetchArtifact(coord, wrongPin);
        assertThat(refetched).isPresent();
        assertThat(refetched.orElseThrow().fetched().sha256()).isEqualToIgnoringCase(hex);
        assertThat(refetched.orElseThrow().fetched().sha256()).isNotEqualToIgnoringCase(wrongPin);
    }

    /** Compile the fixture main, jar it with the manifest, publish to a Maven-layout dir. */
    private static Path publishFixture(Path repo) throws Exception {
        return publishFixture(repo, VERSION);
    }

    private static Path publishFixture(Path repo, String version) throws Exception {
        return ThirdPartyPluginFixture.publish(repo, GROUP, ARTIFACT, version, "HelloPluginMain", MAIN, MANIFEST);
    }
}

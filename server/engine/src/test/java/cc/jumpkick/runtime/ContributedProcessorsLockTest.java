// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.ToolEntry;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin's contributed annotation processors as the lock pins them: {@code jk lock} resolves each
 * one's closure from its POMs and pins every jar, the build and the forecast read the same jars
 * from those rows, and a lock without the closure is stale and refused.
 */
class ContributedProcessorsLockTest {

    private static final String GROUP = "com.example.apt";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @TempDir
    Path isolatedStore;

    private final String version = MavenStub.freshVersion();

    private final PluginDescriptor fixture = PluginDescriptors.parse("""
            [plugin]
            id = "apt-fixture"
            table = "apt-fixture"

            [schema]
            version = { type = "string", required = true }

            [[contribute.processor-dependency]]
            coordinate = "com.example.apt:codegen-apt:${config.version}"

            [[contribute.processor-dependency]]
            coordinate = "com.example.apt:registry-apt:${config.version}"
            when = { classpath-has = "com.example:registry" }
            """, "apt-fixture.toml");

    @BeforeEach
    void publish() {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        PluginTableRegistry.putBuiltIn(fixture, null);
        MavenStub repo = new MavenStub(http);
        repo.pomOnly(GROUP, "codegen-apt", version, """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>%1$s</groupId><artifactId>codegen-apt</artifactId><version>%2$s</version>
                <dependencies><dependency>
                  <groupId>%1$s</groupId><artifactId>codegen-core</artifactId><version>%2$s</version>
                </dependency></dependencies></project>
                """.formatted(GROUP, version));
        repo.pomOnly(GROUP, "codegen-core", version, MavenStub.emptyPom(GROUP, "codegen-core", version));
        for (String artifact : List.of("codegen-apt", "codegen-core")) {
            repo.metadata(GROUP, artifact, version);
            repo.bytes(MavenStub.path(GROUP, artifact, version, ".jar"), artifact.getBytes(StandardCharsets.UTF_8));
        }
    }

    @AfterEach
    void uninstall() {
        PluginTableRegistry.unregister(fixture);
    }

    @Test
    void the_lock_pins_the_closure_and_build_and_forecast_read_the_same_jars(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Lockfile lock = pipeline(tmp, build).pinTools(Lockfile.empty("test"), LockPipeline.Progress.SILENT);

        ToolEntry closure = Objects.requireNonNull(
                ToolEntry.closureIn(lock.tools(), GROUP + ":codegen-apt:" + version, List.of(), null),
                "the codegen processor's closure row");
        assertThat(closure.closure())
                .containsExactlyInAnyOrder(GROUP + ":codegen-apt:" + version, GROUP + ":codegen-core:" + version);
        assertThat(lock.tools())
                .as("the registry processor is gated on a row the lock does not hold")
                .noneMatch(row -> (GROUP + ":registry-apt").equals(row.coordinate()));

        Path lockFile = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(lock, lockFile);
        Lockfile read = LockfileReader.read(lockFile);
        assertThat(LockFreshness.isStale(tmp, lockFile)).isFalse();

        Cas cas = new Cas(tmp.resolve("cas"));
        ContributedProcessors.Paths paths = ContributedProcessors.resolve(build, tmp, read, cas);
        assertThat(paths.main()).hasSize(2).isEqualTo(paths.test());
        assertThat(paths.main().stream().map(ContributedProcessorsLockTest::sha256))
                .containsExactlyInAnyOrder(sha256Of("codegen-apt"), sha256Of("codegen-core"));
        assertThat(ContributedProcessors.forecast(build, tmp, read, cas))
                .as("the forecast derives the build's jars")
                .isEqualTo(paths);
    }

    @Test
    void a_lock_without_the_closure_is_stale_and_the_build_refuses_it(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Path lockFile = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(Lockfile.empty("test"), lockFile);

        assertThat(LockFreshness.isStale(tmp, lockFile)).isTrue();
        Cas cas = new Cas(tmp.resolve("cas"));
        Lockfile empty = LockfileReader.read(lockFile);
        assertThatThrownBy(() -> ContributedProcessors.resolve(build, tmp, empty, cas))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("codegen-apt:" + version)
                .hasMessageContaining("run `jk lock`");
        assertThat(ContributedProcessors.forecast(build, tmp, empty, cas))
                .as("a forecast without the closure predicts a miss")
                .isEqualTo(ContributedProcessors.Paths.NONE);
    }

    private static String sha256(Path jar) {
        try {
            return Hashing.sha256Hex(jar);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Of(String body) {
        return Hashing.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
    }

    private static LockPipeline pipeline(Path tmp, JkBuild build) {
        return new LockPipeline(tmp, build, tmp.resolve("cache"), null, List.of(), false, new LockMode.Freshen());
    }

    private JkBuild project(Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
                name    = "svc"
                group   = "com.example"
                version = "0.1.0"
                java    = 25

                [apt-fixture]
                version = "%s"

                [repositories.mirror]
                url = "%s"
                groups = ["com.example.apt"]
                """.formatted(version, http.baseUrl()));
        return JkBuildParser.parse(tmp.resolve("jk.toml"));
    }
}

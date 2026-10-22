// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.Interpolation;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
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
 * A tool whose classifier is {@code ${host.os}} (aapt2's shape: {@code linux}, {@code osx},
 * {@code windows}) is pinned once per OS, and the build fetches this host's OS artifact against
 * that digest.
 */
class HostOsToolLockTest {

    private static final String GROUP = "com.example.tools";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void publishPerOsTool() {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        RepoGroup.clearProcessFetchCache();
        PluginTableRegistry.putBuiltIn(PluginDescriptors.parse("""
                        [plugin]
                        id      = "perostool"
                        table   = "perostool"
                        version = "1.0.0"

                        [schema]
                        enabled = { type = "bool", default = true }

                        [[contribute.step-dependency]]
                        artifact   = "binary"
                        coordinate = "com.example.tools:binary:2.0:${host.os}"
                        """, "perostool"), null);
        MavenStub upstream = new MavenStub(http);
        for (String os : List.of("linux", "osx", "windows")) {
            upstream.bytes(MavenStub.path(GROUP, "binary", "2.0", "-" + os + ".jar"), body(os));
        }
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    @Test
    void a_per_os_classifier_is_pinned_per_os_and_fetched_for_the_host(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                name    = "app"
                group   = "com.example"
                version = "0.1.0"
                java    = 25

                [perostool]

                [repositories.tools]
                url = "%s"
                groups = ["com.example.tools"]
                """.formatted(http.baseUrl()));
        JkBuild build = JkBuildParser.parse(tmp.resolve("jk.toml"));

        Lockfile lock = ToolLockFixtures.writeWithTools(tmp, tmp.resolve("cache"), Lockfile.empty("test"));

        assertThat(lock.tools()).singleElement().satisfies(row -> {
            assertThat(row.coordinate()).isEqualTo(GROUP + ":binary");
            assertThat(row.sha256()).containsOnlyKeys("linux", "osx", "windows");
            for (String os : List.of("linux", "osx", "windows")) {
                assertThat(row.sha256()).containsEntry(os, Hashing.sha256Hex(body(os)));
            }
        });
        Path lockFile = tmp.resolve("jk-lock.toml");
        assertThat(LockfileReader.read(lockFile).tools()).isEqualTo(lock.tools());
        assertThat(LockFreshness.isStale(tmp, lockFile)).isFalse();

        Path fetched = Objects.requireNonNull(
                PluginBuild.fetchStepDependencies(build, tmp, new Cas(tmp.resolve("cas")), lockFile, false)
                        .get("binary"));
        assertThat(Files.readAllBytes(fetched))
                .isEqualTo(body(Interpolation.Host.current().os()));
    }

    private static byte[] body(String os) {
        return ("binary for " + os).getBytes(StandardCharsets.UTF_8);
    }
}

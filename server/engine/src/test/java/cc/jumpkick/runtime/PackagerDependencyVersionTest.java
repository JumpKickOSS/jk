// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin table's {@code version} is a selector ({@code =4.1.1}, {@code ^4.1}); the packager
 * dependencies the engine fetches beside the project's own jars are fetched at the version the
 * lock pinned that platform to. The selector string never reaches a repository.
 */
class PackagerDependencyVersionTest {

    @BeforeEach
    void forgetProcessCaches() {
        // The repository group's hit and version caches are process-wide; a fetch another test made
        // for the same coordinate would answer from memory and never reach the stub this test watches.
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
    }

    private static final String BOOT = "org.springframework.boot";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @Test
    void an_exact_pin_fetches_the_loader_at_the_locked_boot_version(@TempDir Path tmp) throws Exception {
        Map<String, Path> fetched = fetchPackagerDependencies(tmp, "=4.1.1");

        assertThat(fetched).containsOnlyKeys("spring-boot-loader", "spring-boot-jarmode-tools");
        assertThat(http.requested())
                .anySatisfy(path -> assertThat(path).contains("/spring-boot-loader/4.1.1/"))
                .noneSatisfy(path -> assertThat(path).contains("=4.1.1"))
                .noneSatisfy(path -> assertThat(path).endsWith("/maven-metadata.xml"));
    }

    @Test
    void a_caret_floor_fetches_the_loader_at_the_locked_boot_version(@TempDir Path tmp) throws Exception {
        Map<String, Path> fetched = fetchPackagerDependencies(tmp, "^4.1");

        assertThat(fetched).containsOnlyKeys("spring-boot-loader", "spring-boot-jarmode-tools");
        assertThat(http.requested())
                .anySatisfy(path -> assertThat(path).contains("/spring-boot-jarmode-tools/4.1.1/"))
                .noneSatisfy(path -> assertThat(path).contains("4.2.0"))
                .noneSatisfy(path -> assertThat(path).endsWith("/maven-metadata.xml"));
    }

    /** Boot 4.1.1 locked, 4.2.0 also published — the lock, not the newest release, chooses. */
    private Map<String, Path> fetchPackagerDependencies(Path tmp, String selector) throws Exception {
        for (String tool : List.of("spring-boot-loader", "spring-boot-jarmode-tools")) {
            upstream.metadata(BOOT, tool, "4.1.1", "4.2.0")
                    .jar(BOOT, tool, "4.1.1")
                    .jar(BOOT, tool, "4.2.0");
        }
        JkBuild build = JkBuildParser.parse("""
                name = "demo"
                group = "com.example"
                version = "1.0.0"
                java = 25

                [repositories.boot]
                url = "%s"
                groups = ["org.springframework.boot"]
                allow-insecure = true

                [spring-boot]
                version = "%s"
                """.formatted(http.base(), selector));
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(new Lockfile.Artifact(
                        BOOT + ":spring-boot",
                        "4.1.1",
                        "central+" + http.base(),
                        "sha256:abcd",
                        null,
                        List.of(Scope.MAIN),
                        List.of(),
                        BOOT + ":spring-boot-dependencies:4.1.1")));
        Path lockFile = tmp.resolve("jk-lock.toml");
        Files.writeString(lockFile, LockfileWriter.render(lock));

        return PluginBuild.fetchPackagerDependencies(build, tmp, new Cas(tmp.resolve("cas")), lockFile);
    }
}

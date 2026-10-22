// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.ToolEntry;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin table's {@code version} is a selector ({@code =4.1.1}, {@code ^4.1}); the packager
 * dependencies the engine fetches beside the project's own jars are pinned by {@code [[tool]]}
 * rows at the version the lock pinned that platform to, and fetched against those rows. The
 * selector string never reaches a repository.
 */
class PackagerDependencyVersionTest {

    private static final String BOOT = "org.springframework.boot";
    private static final List<String> TOOLS = List.of("spring-boot-loader", "spring-boot-jarmode-tools");

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void publishBoot() {
        // The repository group's hit and version caches are process-wide; a fetch another test made
        // for the same coordinate would answer from memory and never reach the stub this test watches.
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        for (String tool : TOOLS) {
            upstream.metadata(BOOT, tool, "4.1.1", "4.2.0")
                    .jar(BOOT, tool, "4.1.1")
                    .jar(BOOT, tool, "4.2.0");
        }
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    @Test
    void an_exact_pin_fetches_the_loader_at_the_locked_boot_version(@TempDir Path tmp) throws Exception {
        Map<String, Path> fetched = fetchPackagerDependencies(tmp, "=4.1.1");

        assertThat(fetched).containsOnlyKeys(TOOLS);
        assertThat(http.requested())
                .anySatisfy(path -> assertThat(path).contains("/spring-boot-loader/4.1.1/"))
                .noneSatisfy(path -> assertThat(path).contains("=4.1.1"))
                .noneSatisfy(path -> assertThat(path).endsWith("/maven-metadata.xml"));
    }

    @Test
    void a_caret_floor_fetches_the_loader_at_the_locked_boot_version(@TempDir Path tmp) throws Exception {
        Map<String, Path> fetched = fetchPackagerDependencies(tmp, "^4.1");

        assertThat(fetched).containsOnlyKeys(TOOLS);
        assertThat(http.requested())
                .anySatisfy(path -> assertThat(path).contains("/spring-boot-jarmode-tools/4.1.1/"))
                .noneSatisfy(path -> assertThat(path).contains("4.2.0"))
                .noneSatisfy(path -> assertThat(path).endsWith("/maven-metadata.xml"));
    }

    @Test
    void the_lock_pins_each_packager_tool_by_digest(@TempDir Path tmp) throws Exception {
        Lockfile lock = pinned(tmp, "^4.1");

        assertThat(lock.tools())
                .extracting(ToolEntry::coordinate)
                .containsExactlyInAnyOrder(BOOT + ":spring-boot-loader", BOOT + ":spring-boot-jarmode-tools");
        assertThat(lock.tools()).allSatisfy(row -> {
            assertThat(row.version()).isEqualTo("4.1.1");
            assertThat(row.checksum()).startsWith("sha256:");
        });
        assertThat(LockFreshness.isStale(tmp, tmp.resolve("jk-lock.toml"))).isFalse();
    }

    @Test
    void a_lock_without_the_rows_is_stale_and_the_fetch_refuses(@TempDir Path tmp) throws Exception {
        Path lockFile = tmp.resolve("jk-lock.toml");
        JkBuild build = project(tmp, "^4.1");
        LockfileWriter.write(bootLock(), lockFile);

        assertThat(LockFreshness.isStale(tmp, lockFile)).isTrue();
        assertThatThrownBy(
                        () -> PluginBuild.fetchPackagerDependencies(build, tmp, new Cas(tmp.resolve("cas")), lockFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("pins no [[tool]] row for " + BOOT + ":spring-boot-loader:4.1.1");
    }

    @Test
    void bytes_other_than_the_pinned_ones_are_refused(@TempDir Path tmp) throws Exception {
        Lockfile lock = pinned(tmp, "^4.1");
        List<ToolEntry> tampered = new ArrayList<>();
        for (ToolEntry row : lock.tools()) {
            tampered.add(new ToolEntry(
                    row.coordinate(), row.version(), row.type(), null, "sha256:" + "0".repeat(64), Map.of(), null));
        }
        Path lockFile = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(lock.withTools(tampered), lockFile);
        RepoGroup.clearProcessFetchCache();

        assertThatThrownBy(() -> PluginBuild.fetchPackagerDependencies(
                        project(tmp, "^4.1"), tmp, new Cas(tmp.resolve("cas")), lockFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-lock.toml pins sha256 " + "0".repeat(64));
    }

    /** Boot 4.1.1 locked, 4.2.0 also published — the lock, not the newest release, chooses. */
    private Map<String, Path> fetchPackagerDependencies(Path tmp, String selector) throws Exception {
        pinned(tmp, selector);
        return PluginBuild.fetchPackagerDependencies(
                project(tmp, selector), tmp, new Cas(tmp.resolve("cas")), tmp.resolve("jk-lock.toml"));
    }

    /** The Boot lock with its {@code [[tool]]} rows pinned, written beside the manifest. */
    private Lockfile pinned(Path tmp, String selector) throws Exception {
        JkBuild build = project(tmp, selector);
        Lockfile lock = new LockPipeline(
                        tmp, build, tmp.resolve("cache"), null, List.of(), false, new LockMode.Freshen())
                .pinTools(bootLock(), LockPipeline.Progress.SILENT);
        LockfileWriter.write(lock, tmp.resolve("jk-lock.toml"));
        return lock;
    }

    private Lockfile bootLock() {
        return new Lockfile(
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
    }

    private JkBuild project(Path tmp, String selector) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
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
        return JkBuildParser.parse(tmp.resolve("jk.toml"));
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.ToolEntry;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.workspace.SyncPlans;
import cc.jumpkick.testing.FakeJdk;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk sync} fetches every tool the lock pins for this platform — a plugin's Maven artifact,
 * its runtime closure and its file at a URL — into the store, verified against the rows, so the
 * next build downloads nothing. A row the repository's bytes disagree with fails the sync.
 */
class SyncToolsTest {

    private static final String GROUP = "com.example.kit";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void publish() {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        PluginTableRegistry.putBuiltIn(PluginDescriptors.parse("""
                        [plugin]
                        id      = "kit"
                        table   = "kit"
                        version = "1.0.0"

                        [schema]
                        rules = { type = "string" }

                        [[contribute.step-dependency]]
                        artifact   = "kit-cli"
                        coordinate = "com.example.kit:kit-cli:1.0"
                        transitive = true

                        [[contribute.step-dependency]]
                        artifact   = "kit-data"
                        coordinate = "com.example.kit:kit-data:1.0"

                        [[contribute.step-dependency]]
                        artifact = "kit-rules"
                        url      = "${config.rules}"
                        """, "kit"), null);
        new MavenStub(http)
                .metadata(GROUP, "kit-cli", "1.0")
                .pom(GROUP, "kit-cli", "1.0", """
                        <project><modelVersion>4.0.0</modelVersion>
                          <groupId>com.example.kit</groupId><artifactId>kit-cli</artifactId><version>1.0</version>
                          <dependencies>
                            <dependency><groupId>com.example.kit</groupId><artifactId>kit-core</artifactId>
                              <version>1.0</version></dependency>
                          </dependencies>
                        </project>""")
                .bytes(MavenStub.path(GROUP, "kit-cli", "1.0", ".jar"), bytes("cli"))
                .leaf(GROUP, "kit-core", "1.0")
                .bytes(MavenStub.path(GROUP, "kit-core", "1.0", ".jar"), bytes("core"))
                .leaf(GROUP, "kit-data", "1.0")
                .bytes(MavenStub.path(GROUP, "kit-data", "1.0", ".jar"), bytes("data"));
        http.serve("/rules.xml", "<rules/>");
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    @Test
    void sync_fetches_every_pinned_tool_into_the_store(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        ToolLockFixtures.writeWithTools(project, tmp.resolve("cache"), Lockfile.empty("test"));
        // The lock pinned from a store of its own: the sync starts from an empty one.
        System.setProperty("jk.env.JK_STORE_DIR", tmp.resolve("synced-store").toString());
        RepoGroup.clearProcessFetchCache();

        BuildPlanResult result = sync(project, tmp);

        assertThat(result.success()).as("sync errors: %s", result.errors()).isTrue();
        Path store = JkStores.storeCas().root();
        try (Stream<Path> closures = Files.list(store.resolve("plugin-tools"))) {
            Path closure = closures.filter(dir -> dir.getFileName().toString().startsWith("kit-cli-1.0__"))
                    .findFirst()
                    .orElseThrow();
            assertThat(closure.resolve("kit-core-1.0.jar")).hasBinaryContent(bytes("core"));
        }
        try (Stream<Path> files = Files.walk(store.resolve("tools/url"))) {
            assertThat(files.filter(file -> file.getFileName().toString().equals("rules.xml")))
                    .singleElement()
                    .satisfies(file -> assertThat(file).hasContent("<rules/>"));
        }
    }

    @Test
    void a_row_the_repository_disagrees_with_fails_the_sync(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        Lockfile lock = ToolLockFixtures.writeWithTools(project, tmp.resolve("cache"), Lockfile.empty("test"));
        List<ToolEntry> tampered = new ArrayList<>();
        for (ToolEntry row : lock.tools()) {
            tampered.add(
                    (GROUP + ":kit-data").equals(row.coordinate())
                            ? ToolEntry.single(Coordinate.parse(GROUP + ":kit-data:1.0"), "0".repeat(64))
                            : row);
        }
        LockfileWriter.write(lock.withTools(tampered), project.resolve("jk-lock.toml"));
        System.setProperty("jk.env.JK_STORE_DIR", tmp.resolve("synced-store").toString());
        RepoGroup.clearProcessFetchCache();

        BuildPlanResult result = sync(project, tmp);

        assertThat(result.success()).isFalse();
        assertThat(result.errors().toString()).contains("jk-lock.toml pins sha256 " + "0".repeat(64));
    }

    private static BuildPlanResult sync(Path project, Path tmp) throws IOException {
        // The sync resolves java = 25 first; a JDK of its own keeps the host's inventory out of it.
        FakeJdk.create(tmp.resolve("jdks").resolve("temurin-25.0.4"), "25.0.4");
        return SyncPlans.syncBuildPlan(
                        project,
                        tmp.resolve("cache"),
                        tmp.resolve("jdks"),
                        null,
                        false,
                        new AtomicInteger(),
                        new AtomicInteger(),
                        null,
                        false,
                        true)
                .run();
    }

    private static byte[] bytes(String what) {
        return ("jar " + what).getBytes(StandardCharsets.UTF_8);
    }

    private Path project(Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("app"));
        String base = http.baseUrl().replaceAll("/$", "");
        Files.writeString(project.resolve("jk.toml"), """
                name    = "app"
                group   = "com.example"
                version = "0.1.0"
                java    = 25

                [kit]
                rules = "%s/rules.xml"

                [repositories.kit]
                url = "%s"
                groups = ["com.example.kit"]
                """.formatted(base, base));
        return project;
    }
}

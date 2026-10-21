// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.RepoStoreDirs;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.testing.SysProps;
import cc.jumpkick.util.JkDirs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every sandbox store's public-origin trees are links into one pool, so what one sandbox fetched
 * another finds, and nothing that deletes a sandbox reaches the pool.
 */
@SysProps.TempRoots("jk.env.JK_HOME")
class TestStorePoolTest {

    private static final String JAR = "org/example/lib/1.0/lib-1.0.jar";

    @Test
    void what_one_sandbox_stores_under_central_another_reads(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        Path a = tmp.resolve("a/home/store");
        Path b = tmp.resolve("b/home/store");

        assertThat(TestStorePool.link(pool, a)).isEqualTo(TestStorePool.SHARED.size());
        assertThat(TestStorePool.link(pool, b)).isEqualTo(TestStorePool.SHARED.size());
        materialize(a, "lib");

        assertThat(b.resolve("repos/central").resolve(JAR)).hasContent("lib");
        assertThat(pool.resolve("repos/central").resolve(JAR)).hasContent("lib");
        assertThat(TestStorePool.link(pool, b))
                .as("a sandbox already linked is left as it is")
                .isZero();
    }

    @Test
    void only_the_public_origin_trees_and_tool_distributions_are_shared(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        Path store = tmp.resolve("home/store");
        TestStorePool.link(pool, store);

        for (String rel : List.of(
                "repos/central", "repos/google", "repos/jumpkick", "tools/kotlin", "tools/gradle", "tools/maven")) {
            assertThat(store.resolve(rel)).as(rel).isSymbolicLink();
            assertThat(Files.readSymbolicLink(store.resolve(rel)))
                    .isEqualTo(pool.resolve(rel).toAbsolutePath().normalize());
        }
        assertThat(store.resolve("repos").resolve(RepositorySpec.JK_LOCAL)).doesNotExist();
        try (var names = Files.list(store.resolve("repos"))) {
            assertThat(names.map(p -> p.getFileName().toString()))
                    .as("no staged link is left behind")
                    .containsExactlyInAnyOrder("central", "google", "jumpkick");
        }
        try (var names = Files.list(store.resolve("tools"))) {
            assertThat(names.map(p -> p.getFileName().toString()))
                    .as("closure indexes into the sandbox CAS, node and the rest stay per sandbox")
                    .containsExactlyInAnyOrder("kotlin", "gradle", "maven");
        }
    }

    @Test
    void a_sweep_removes_staged_leftovers_older_than_an_hour_and_nothing_else(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        TestStorePool.link(pool, tmp.resolve("home/store"));
        long now = Clock.SYSTEM.millis();
        Path oldPut = stale(pool.resolve("repos/central/.put-123.tmp"), now);
        Path youngPut = Files.writeString(pool.resolve("repos/central/.put-456.tmp"), "in flight");
        Path oldStage = pool.resolve("tools/kotlin/jk-tool-stage-1");
        stale(oldStage.resolve("bin/kotlinc"), now);
        Files.setLastModifiedTime(oldStage, FileTime.fromMillis(now - 2 * TestStorePool.LEAK_MILLIS));
        Path oldZip = stale(pool.resolve("android-sdk/.downloads/jk-sdk-1.zip"), now);
        Path artifact = stale(pool.resolve("repos/central").resolve(JAR), now);
        Path install = stale(pool.resolve("tools/kotlin/2.4.10/bin/kotlinc"), now);

        assertThat(TestStorePool.sweep(pool, now)).isEqualTo(3);

        assertThat(oldPut).doesNotExist();
        assertThat(oldStage).doesNotExist();
        assertThat(oldZip).doesNotExist();
        assertThat(youngPut).as("may belong to a live download").exists();
        assertThat(artifact).exists();
        assertThat(install).exists();
    }

    @Test
    void a_private_tree_from_before_is_replaced_by_the_link(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        Path store = tmp.resolve("home/store");
        Path stale = store.resolve("repos/central").resolve(JAR);
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "private");

        TestStorePool.link(pool, store);

        assertThat(store.resolve("repos/central")).isSymbolicLink();
        assertThat(pool.resolve("repos/central").resolve(JAR))
                .as("a private tree is never merged into the pool: it may hold a test's fakes")
                .doesNotExist();
    }

    @Test
    void deleting_a_sandbox_home_leaves_the_pool(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        Path home = tmp.resolve("slot/home");
        Path store = home.resolve("store");
        TestStorePool.link(pool, store);
        materialize(store, "kept");

        PathUtil.deleteRecursivelyOrThrow(home);

        assertThat(home).doesNotExist();
        assertThat(pool.resolve("repos/central").resolve(JAR)).hasContent("kept");
    }

    @Test
    void store_listings_see_a_linked_tree(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        Path store = tmp.resolve("home/store");
        TestStorePool.link(pool, store);
        materialize(store, "lib");

        assertThat(RepoArtifactStore.storeIds(store)).contains(RepositorySpec.CENTRAL);
        assertThat(RepoStoreDirs.known(store)).contains(store.resolve("repos/central"));
        assertThat(RepoArtifactStore.forStoreId(store, RepositorySpec.CENTRAL).modules())
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.moduleKey()).isEqualTo("org.example:lib");
                    assertThat(m.versions()).containsExactly("1.0");
                });
    }

    @Test
    void a_launched_sandbox_links_the_pool_beside_its_test_homes(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        Files.writeString(module.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """);
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));

        var env = TestEnv.forModule(project, module, BuildLayout.of(module, project))
                .extras();

        Path central = Path.of(env.get("JK_STORE_DIR")).resolve("repos/central");
        assertThat(central).isSymbolicLink();
        assertThat(Files.readSymbolicLink(central))
                .isEqualTo(JkDirs.home().resolve(TestStorePool.DIR).resolve("repos/central"));
        assertThat(TestStorePool.root().startsWith(Path.of(env.get("JK_HOME"))))
                .as("the pool is outside the sandbox home a test may delete")
                .isFalse();
    }

    /** A file at {@code path}, two leak windows old. */
    private static Path stale(Path path, long now) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "x");
        Files.setLastModifiedTime(path, FileTime.fromMillis(now - 2 * TestStorePool.LEAK_MILLIS));
        return path;
    }

    private static void materialize(Path store, String body) throws Exception {
        Path source = Files.createTempFile(store.getParent(), "lib", ".jar");
        Files.writeString(source, body);
        RepoArtifactStore.forStoreId(store, RepositorySpec.CENTRAL)
                .materialize(JAR, source, Hashing.sha256Hex(body.getBytes(StandardCharsets.UTF_8)));
    }
}

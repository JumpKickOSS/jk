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
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.JkDirs;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every sandbox store's public-origin trees are links into one pool, so what one sandbox fetched
 * another finds, and nothing that deletes a sandbox reaches the pool. The pool stays under its cap
 * by evicting whole units nobody used for a day, and loses what killed processes left staged.
 */
@SysProps.TempRoots("jk.env.JK_HOME")
class TestStorePoolTest {

    private static final String JAR = "org/example/lib/1.0/lib-1.0.jar";

    @Test
    void the_cap_doubles_on_ci_and_an_explicit_size_wins_everywhere() {
        Map<String, String> ci = Map.of("CI", "true");
        Map<String, String> ciSized = Map.of("CI", "1", "JK_TEST_POOL_MAX_MB", "512");
        assertThat(TestStorePool.maxBytes(null, Map.<String, String>of()::get))
                .isEqualTo(TestStorePool.DEFAULT_MAX_BYTES);
        assertThat(TestStorePool.maxBytes(null, ci::get)).isEqualTo(TestStorePool.CI_MAX_BYTES);
        assertThat(TestStorePool.maxBytes(null, ciSized::get)).isEqualTo(512L << 20);
        assertThat(TestStorePool.maxBytes("256", ciSized::get)).isEqualTo(256L << 20);
        assertThat(TestStorePool.maxBytes("lots", ci::get)).isEqualTo(TestStorePool.CI_MAX_BYTES);
    }

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
            assertThat(TestStorePool.linksTo(store.resolve(rel), pool.resolve(rel)))
                    .as(rel)
                    .isTrue();
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
    void a_reap_removes_staged_leftovers_older_than_an_hour_at_any_depth_and_nothing_else(@TempDir Path tmp)
            throws Exception {
        Path pool = tmp.resolve("test-store");
        TestStorePool.link(pool, tmp.resolve("home/store"));
        long now = Clock.SYSTEM.millis();
        Path versionDir = pool.resolve("repos/central/org/example/lib/1.0");
        Path oldPart = stale(versionDir.resolve(".lib-1.0.jar.123.part"), now);
        Path youngPart = Files.writeString(versionDir.resolve(".lib-1.0.pom.456.part"), "in flight");
        Path oldPut = stale(pool.resolve("repos/central/.put-123.tmp"), now);
        Path oldStage = staleDir(pool.resolve("tools/kotlin/jk-tool-stage-1"), "bin/kotlinc", now);
        Path oldUnpack = staleDir(pool.resolve("android-sdk/platforms/.extract-77"), "android.jar", now);
        Path oldZip = stale(pool.resolve("android-sdk/.downloads/jk-sdk-1.zip"), now);
        Path artifact = stale(pool.resolve("repos/central").resolve(JAR), now);
        Path install = stale(pool.resolve("tools/kotlin/2.4.10/bin/kotlinc"), now);
        Path platform = stale(pool.resolve("android-sdk/platforms/android-34/android.jar"), now);

        TestStorePool.Pass pass = TestStorePool.reap(pool, now, Long.MAX_VALUE);

        assertThat(pass.leaks()).isEqualTo(5);
        assertThat(pass.evicted()).isZero();
        assertThat(oldPart).as("deep in a version directory").doesNotExist();
        assertThat(oldPut).doesNotExist();
        assertThat(oldStage).doesNotExist();
        assertThat(oldUnpack).as("an SDK unpack beside its component").doesNotExist();
        assertThat(oldZip).doesNotExist();
        assertThat(youngPart).as("may belong to a live download").exists();
        assertThat(artifact).exists();
        assertThat(install).exists();
        assertThat(platform).exists();
    }

    @Test
    void over_the_cap_the_least_recently_used_units_go_whole(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        TestStorePool.link(pool, tmp.resolve("home/store"));
        long now = Clock.SYSTEM.millis();
        Path android = unit(pool.resolve("android-sdk/platforms/android-28"), "android.jar", 10, now);
        Path oldLib = unit(pool.resolve("repos/central/org/example/lib/1.0"), "lib-1.0.jar", 5, now);
        Path tool = unit(pool.resolve("tools/gradle/9.8.0"), "lib/gradle.jar", 4, now);
        Path lib = unit(pool.resolve("repos/central/org/example/lib/2.0"), "lib-2.0.jar", 3, now);
        Path google = unit(pool.resolve("repos/google/com/example/aar/1.0"), "aar-1.0.aar", 2, now);
        unit(pool.resolve("android-sdk/licenses"), "android-sdk-license", 30, now);

        TestStorePool.Pass pass = TestStorePool.reap(pool, now, 3500);

        assertThat(pass.evicted()).isEqualTo(3);
        assertThat(pass.bytes())
                .as("two units and the license, which counts but never goes")
                .isEqualTo(3000);
        assertThat(android).doesNotExist();
        assertThat(oldLib).doesNotExist();
        assertThat(tool).as("a tool version goes as one unit, not file by file").doesNotExist();
        assertThat(lib).exists();
        assertThat(google).exists();
        assertThat(pool.resolve("tools/gradle")).exists();
        assertThat(pool.resolve(TestStorePool.TRASH)).isEmptyDirectory();
        assertThat(pool.resolve("android-sdk/licenses/android-sdk-license"))
                .as("an accepted license is never a unit")
                .exists();
    }

    @Test
    void a_unit_unused_for_a_week_goes_even_under_the_cap(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        TestStorePool.link(pool, tmp.resolve("home/store"));
        long now = Clock.SYSTEM.millis();
        Path stale = unit(pool.resolve("repos/central/org/example/lib/1.0"), "lib-1.0.jar", 8, now);
        Path recent = unit(pool.resolve("repos/central/org/example/lib/2.0"), "lib-2.0.jar", 3, now);

        TestStorePool.Pass pass = TestStorePool.reap(pool, now, Long.MAX_VALUE);

        assertThat(pass.evicted()).isEqualTo(1);
        assertThat(stale).doesNotExist();
        assertThat(recent).exists();
    }

    @Test
    void a_unit_used_within_a_day_stays_even_over_the_cap(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        TestStorePool.link(pool, tmp.resolve("home/store"));
        long now = Clock.SYSTEM.millis();
        Path idle = unit(pool.resolve("repos/central/org/example/lib/1.0"), "lib-1.0.jar", 2, now);
        Path fresh = pool.resolve("repos/central/org/example/lib/2.0");
        Files.createDirectories(fresh);
        Files.write(fresh.resolve("lib-2.0.jar"), new byte[1000]);

        TestStorePool.Pass pass = TestStorePool.reap(pool, now, 0);

        assertThat(idle).doesNotExist();
        assertThat(fresh).as("a running suite may be reading it").exists();
        assertThat(pass.bytes()).isGreaterThan(0);
    }

    @Test
    void a_unit_the_store_reads_is_stamped_so_the_reap_keeps_it(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        Path store = tmp.resolve("home/store");
        TestStorePool.link(pool, store);
        materialize(store, "lib");
        long now = Clock.SYSTEM.millis();
        Path used = pool.resolve("repos/central").resolve(JAR).getParent();
        Path unused = unit(pool.resolve("repos/central/org/example/lib/0.9"), "lib-0.9.jar", 3, now);
        Files.setLastModifiedTime(used, FileTime.fromMillis(now - 3 * TestStorePool.IDLE_MILLIS));

        assertThat(central(store).locate(JAR)).isPresent();
        TestStorePool.reap(pool, now, 0);

        assertThat(used).exists();
        assertThat(unused).doesNotExist();
    }

    @Test
    void an_evicted_artifact_is_a_clean_miss_and_fetches_again(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        Path store = tmp.resolve("home/store");
        TestStorePool.link(pool, store);
        materialize(store, "lib");
        long now = Clock.SYSTEM.millis();
        Path versionDir = pool.resolve("repos/central").resolve(JAR).getParent();
        Files.setLastModifiedTime(versionDir, FileTime.fromMillis(now - 3 * TestStorePool.IDLE_MILLIS));

        assertThat(TestStorePool.reap(pool, now, 0).evicted()).isEqualTo(1);

        assertThat(versionDir).as("the jar and its memo go together").doesNotExist();
        assertThat(central(store).contains(JAR)).isFalse();
        assertThat(central(store).locate(JAR)).isEmpty();
        materialize(store, "again");
        assertThat(central(store).locate(JAR))
                .hasValueSatisfying(p -> assertThat(p).hasContent("again"));
    }

    @Test
    void a_reader_holding_a_file_open_reads_it_whole_across_an_eviction(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        TestStorePool.link(pool, tmp.resolve("home/store"));
        long now = Clock.SYSTEM.millis();
        Path versionDir = unit(pool.resolve("repos/central/org/example/lib/1.0"), "lib-1.0.jar", 3, now);

        try (InputStream in = Files.newInputStream(versionDir.resolve("lib-1.0.jar"))) {
            TestStorePool.reap(pool, now, 0);
            assertThat(in.readAllBytes()).hasSize(1000);
        }
    }

    @Test
    void a_reap_runs_once_per_interval_per_host_and_never_beside_another(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        TestStorePool.link(pool, tmp.resolve("home/store"));
        long now = Clock.SYSTEM.millis();
        Files.deleteIfExists(pool.resolve(TestStorePool.REAPED_AT));

        long every = TestStorePool.REAP_EVERY_MILLIS;

        try (var held = (FileLocks.Hold) FileLocks.tryHold(pool.resolve(TestStorePool.REAP_LOCK))) {
            assertThat(TestStorePool.reapIfDue(pool, now + every, 0))
                    .as("another reap holds the lock")
                    .isNull();
        }
        assertThat(TestStorePool.reapIfDue(pool, now + 2 * every, 0)).isNotNull();
        assertThat(TestStorePool.reapIfDue(pool, now + 2 * every + 1, 0))
                .as("this process reaped within the interval")
                .isNull();

        Path other = Files.createDirectories(tmp.resolve("other"));
        Files.writeString(other.resolve(TestStorePool.REAPED_AT), "");
        Files.setLastModifiedTime(other.resolve(TestStorePool.REAPED_AT), FileTime.fromMillis(now));
        assertThat(TestStorePool.reapIfDue(other, now + 1, 0))
                .as("a reap another process started within the interval counts")
                .isNull();
    }

    @Test
    void leftovers_of_a_killed_reap_are_emptied_from_the_trash(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        TestStorePool.link(pool, tmp.resolve("home/store"));
        Path left = pool.resolve(TestStorePool.TRASH).resolve("1-1-1.0/lib-1.0.jar");
        Files.createDirectories(left.getParent());
        Files.writeString(left, "half deleted");

        TestStorePool.reap(pool, Clock.SYSTEM.millis(), Long.MAX_VALUE);

        assertThat(pool.resolve(TestStorePool.TRASH)).isEmptyDirectory();
    }

    @Test
    void a_private_tree_from_before_is_replaced_by_the_link(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("test-store");
        Path store = tmp.resolve("home/store");
        Path stale = store.resolve("repos/central").resolve(JAR);
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "private");

        TestStorePool.link(pool, store);

        assertThat(TestStorePool.isLink(store.resolve("repos/central"))).isTrue();
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
        assertThat(TestStorePool.linksTo(
                        central, JkDirs.home().resolve(TestStorePool.DIR).resolve("repos/central")))
                .isTrue();
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

    /** A directory of a stale leak holding {@code file}, two leak windows old. */
    private static Path staleDir(Path dir, String file, long now) throws Exception {
        stale(dir.resolve(file), now);
        Files.setLastModifiedTime(dir, FileTime.fromMillis(now - 2 * TestStorePool.LEAK_MILLIS));
        return dir;
    }

    /** A unit at {@code dir} holding 1000 bytes at {@code file}, last used {@code daysAgo} days before now. */
    private static Path unit(Path dir, String file, int daysAgo, long now) throws Exception {
        Files.createDirectories(dir.resolve(file).getParent());
        Files.write(dir.resolve(file), new byte[1000]);
        Files.setLastModifiedTime(dir, FileTime.fromMillis(now - daysAgo * TestStorePool.IDLE_MILLIS));
        return dir;
    }

    private static RepoArtifactStore central(Path store) {
        return RepoArtifactStore.forStoreId(store, RepositorySpec.CENTRAL);
    }

    private static void materialize(Path store, String body) throws Exception {
        Path source = Files.createTempFile(store.getParent(), "lib", ".jar");
        Files.writeString(source, body);
        RepoArtifactStore.forStoreId(store, RepositorySpec.CENTRAL)
                .materialize(JAR, source, Hashing.sha256Hex(body.getBytes(StandardCharsets.UTF_8)));
    }
}

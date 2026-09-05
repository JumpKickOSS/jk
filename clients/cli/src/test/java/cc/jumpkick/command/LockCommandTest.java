// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static cc.jumpkick.cli.testing.MockMavenServer.pom;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.testing.SysProps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class LockCommandTest {

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    @BeforeEach
    void seedRepo() {
        DefaultTestDepsFixture.seed(maven.served());
    }

    @AfterEach
    void reset() {
        LockfileReader.clearCache();
    }

    @Test
    void init_add_lock_full_pipeline(@TempDir Path tempDir) throws Exception {
        // Set up a tiny graph: root -> leaf.
        maven.registerMetadata("com.foo", "leaf", "1.0");
        maven.registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        byte[] leafJar = "leaf".getBytes(StandardCharsets.UTF_8);
        maven.registerJar("com.foo", "leaf", "1.0", leafJar);

        maven.registerMetadata("com.foo", "root", "1.0");
        maven.registerPom("com.foo", "root", "1.0", pom("com.foo", "root", "1.0", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>leaf</artifactId>
                  <version>1.0</version>
                </dependency>
                """));
        byte[] rootJar = "root".getBytes(StandardCharsets.UTF_8);
        maven.registerJar("com.foo", "root", "1.0", rootJar);

        // Run the commands against the test repo.
        int exit;
        exit = run("new", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        exit = run("add", "com.foo:root:1.0", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        exit = run(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        // Inspect the lockfile.
        Lockfile lock = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(DefaultTestDepsFixture.projectCoords(lock)).hasSize(2);
        assertThat(DefaultTestDepsFixture.projectCoords(lock))
                .containsExactly("com.foo:leaf", "com.foo:root"); // writer sorts by name

        Lockfile.Artifact leaf = DefaultTestDepsFixture.pkg(lock, "com.foo:leaf");
        Lockfile.Artifact root = DefaultTestDepsFixture.pkg(lock, "com.foo:root");

        assertThat(leaf.version()).isEqualTo("1.0");
        assertThat(leaf.checksum()).isEqualTo("sha256:" + Hashing.sha256Hex(leafJar));
        assertThat(leaf.source()).startsWith("central+").endsWith("/");
        assertThat(leaf.deps()).isEmpty();

        // dependsOn uses package keys (g:a:type:classifier@version).
        assertThat(root.deps()).containsExactly("com.foo:leaf:jar:@1.0");
        assertThat(root.checksum()).isEqualTo("sha256:" + Hashing.sha256Hex(rootJar));
    }

    @Test
    void lock_without_build_jk_fails(@TempDir Path tempDir) {
        int exit = run(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void lock_with_no_dependencies_defaults_to_junit(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        int exit = run(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        // No project deps — but jk defaults the test framework to JUnit.
        assertThat(DefaultTestDepsFixture.projectCoords(lock)).isEmpty();
        assertThat(lock.artifacts())
                .extracting(Lockfile.Artifact::name)
                .containsExactlyInAnyOrder(DefaultTestDepsFixture.JUPITER, DefaultTestDepsFixture.LAUNCHER);
    }

    @Test
    void lock_from_module_dir_locks_module_only(@TempDir Path tempDir) throws Exception {
        // External graph served by the test repo: root -> leaf.
        maven.registerMetadata("com.foo", "leaf", "1.0");
        maven.registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        maven.registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));
        maven.registerMetadata("com.foo", "root", "1.0");
        maven.registerPom("com.foo", "root", "1.0", pom("com.foo", "root", "1.0", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>leaf</artifactId>
                  <version>1.0</version>
                </dependency>
                """));
        maven.registerJar("com.foo", "root", "1.0", "root".getBytes(StandardCharsets.UTF_8));

        // Workspace root + two modules. `app` depends on its sibling `libb`
        // (must be filtered out, never fetched) and the external com.foo:root.
        Files.writeString(tempDir.resolve("jk.toml"), """
                group = "com.acme"
                name     = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["app", "libb"]
                """);
        Path app = Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group = "com.acme"
                name     = "app"
                version = "0.1.0"

                [dependencies]
                libb = { group = "com.acme", name = "libb", version = "0.1.0" }
                root = { group = "com.foo",  name = "root", version = "1.0" }
                """);
        Path libb = Files.createDirectories(tempDir.resolve("libb"));
        Files.writeString(libb.resolve("jk.toml"), """
                group = "com.acme"
                name     = "libb"
                version = "0.1.0"
                """);

        // Invoke from INSIDE the module directory — still writes the workspace root lock.
        int exit = run(
                "lock",
                "-C",
                app.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        // Single workspace lock at the root (never a module-local lock).
        assertThat(Files.exists(tempDir.resolve("jk-lock.toml"))).isTrue();
        assertThat(Files.exists(app.resolve("jk-lock.toml"))).isFalse();
        Lockfile lock = LockfileReader.read(tempDir.resolve("jk-lock.toml"));

        // External coords are resolved; the workspace sibling is not locked.
        assertThat(DefaultTestDepsFixture.projectCoords(lock))
                .containsExactlyInAnyOrder("com.foo:root", "com.foo:leaf");
        assertThat(DefaultTestDepsFixture.projectCoords(lock)).doesNotContain("com.acme:libb");
    }

    @Test
    void lock_from_workspace_root_writes_single_root_lock(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo", "leaf", "1.0");
        maven.registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        maven.registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));
        maven.registerMetadata("com.foo", "root", "1.0");
        maven.registerPom("com.foo", "root", "1.0", pom("com.foo", "root", "1.0", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>leaf</artifactId>
                  <version>1.0</version>
                </dependency>
                """));
        maven.registerJar("com.foo", "root", "1.0", "root".getBytes(StandardCharsets.UTF_8));

        Files.writeString(tempDir.resolve("jk.toml"), """
                group = "com.acme"
                name     = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["app", "libb"]
                """);
        Path app = Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group = "com.acme"
                name     = "app"
                version = "0.1.0"

                [dependencies]
                libb = { group = "com.acme", name = "libb", version = "0.1.0" }
                root = { group = "com.foo",  name = "root", version = "1.0" }
                """);
        Path libb = Files.createDirectories(tempDir.resolve("libb"));
        Files.writeString(libb.resolve("jk.toml"), """
                group = "com.acme"
                name     = "libb"
                version = "0.1.0"
                """);

        // Invoke from the workspace root — one lock for the whole monorepo.
        int exit = run(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        assertThat(Files.exists(tempDir.resolve("jk-lock.toml"))).isTrue();
        assertThat(Files.exists(app.resolve("jk-lock.toml"))).isFalse();
        assertThat(Files.exists(libb.resolve("jk-lock.toml"))).isFalse();

        Lockfile rootLock = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(DefaultTestDepsFixture.projectCoords(rootLock))
                .containsExactlyInAnyOrder("com.foo:root", "com.foo:leaf");
        assertThat(DefaultTestDepsFixture.projectCoords(rootLock)).doesNotContain("com.acme:libb");
    }

    @Test
    void offline_reuses_a_satisfiable_lock(@TempDir Path tempDir) throws Exception {
        registerRootLeafGraph();
        Path cache = tempDir.resolve("cache");

        run("new", tempDir.toString());
        run("add", "com.foo:root:1.0", "-C", tempDir.toString());
        assertThat(run(
                        "lock",
                        "-C",
                        tempDir.toString(),
                        "--repo-url",
                        maven.base().toString(),
                        "--cache-dir",
                        cache.toString()))
                .isEqualTo(0);

        // Stop the server so any network attempt would fail; offline must not need it.
        maven.stop();
        int exit = run("lock", "--offline", "-C", tempDir.toString(), "--cache-dir", cache.toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(DefaultTestDepsFixture.projectCoords(lock)).containsExactly("com.foo:leaf", "com.foo:root");
    }

    @Test
    void offline_hard_fails_when_declared_dep_missing_from_lock(@TempDir Path tempDir) throws Exception {
        // jk.toml declares com.foo:root, but the lockfile has no such package.
        writeProjectWithRootDep(tempDir);
        LockfileWriter.write(
                new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of()),
                tempDir.resolve("jk-lock.toml"));

        int exit = run(
                "lock",
                "--offline",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(6);
    }

    @Test
    void offline_hard_fails_when_locked_blob_not_cached(@TempDir Path tempDir) throws Exception {
        writeProjectWithRootDep(tempDir);
        // Lock references a checksum whose blob is absent from the (empty) cache.
        Lockfile.Artifact root = new Lockfile.Artifact(
                "com.foo:root",
                "1.0",
                "central+" + maven.base(),
                "sha256:" + "00".repeat(32),
                null,
                List.of(),
                List.of(),
                null);
        LockfileWriter.write(
                new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(root)),
                tempDir.resolve("jk-lock.toml"));

        int exit = run(
                "lock",
                "--offline",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(6);
    }

    @Test
    void offline_solves_from_journal_when_no_lock_exists(@TempDir Path tempDir) throws Exception {
        registerRootLeafGraph();
        Path cache = tempDir.resolve("cache");

        // Warm the shared cache + journal with an online lock in one project.
        Path online = Files.createDirectories(tempDir.resolve("online"));
        run("new", online.toString());
        run("add", "com.foo:root:1.0", "-C", online.toString());
        assertThat(run(
                        "lock",
                        "-C",
                        online.toString(),
                        "--repo-url",
                        maven.base().toString(),
                        "--cache-dir",
                        cache.toString()))
                .isEqualTo(0);

        // Fresh project, no lockfile, offline — must resolve from the journal.
        Path fresh = Files.createDirectories(tempDir.resolve("fresh"));
        writeProjectWithRootDep(fresh);
        maven.stop();
        int exit = run("lock", "--offline", "-C", fresh.toString(), "--cache-dir", cache.toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(fresh.resolve("jk-lock.toml"));
        assertThat(DefaultTestDepsFixture.projectCoords(lock))
                .containsExactlyInAnyOrder("com.foo:root", "com.foo:leaf");
    }

    @Test
    void kotlin_project_lock_pins_floating_compiler_version(@TempDir Path tempDir) throws Exception {
        // 2.4.0-RC2 is higher than 2.3.21 and also in range, but a floating
        // selector must skip the pre-release and pin the highest stable.
        maven.registerMetadata(
                "org.jetbrains.kotlin",
                "kotlin-compiler-embeddable",
                "2.0.21",
                "2.3.0",
                "2.3.21",
                "2.4.0-RC2",
                "3.0.0");
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("jk.toml"), """
                group = "com.acme"
                name     = "app"
                version = "0.1.0"
                kotlin = "^2.3.0"
                """);

        int exit = run(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        // ^2.3.0 → >=2.3.0, <3.0.0; highest *stable* match is 2.3.21 (not 2.4.0-RC2).
        Lockfile lock = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(lock.kotlin()).isEqualTo("2.3.21");
    }

    @Test
    void kotlin_exact_pin_locks_without_metadata(@TempDir Path tempDir) throws Exception {
        // No kotlin-compiler-embeddable metadata registered — an exact pin
        // must short-circuit and lock without hitting the repo.
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("jk.toml"), """
                group = "com.acme"
                name     = "app"
                version = "0.1.0"
                kotlin = "=2.1.0"
                """);

        int exit = run(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(lock.kotlin()).isEqualTo("2.1.0");
    }

    @Test
    void java_project_lock_has_no_kotlin_version(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        int exit = run(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(LockfileReader.read(tempDir.resolve("jk-lock.toml")).kotlin())
                .isNull();
    }

    // --- helpers -----------------------------------------------------------

    /** Register a root -> leaf graph (metadata + pom + jar for each) on the test repo. */
    /**
     * A manifest edit that changes no dependency still stales the stamp. Bare {@code jk lock}
     * re-stamps and keeps every pin — the whole lockfile diff is the stamp line — while {@code -F}
     * is what floats a pin to the newer root that has since been published.
     */
    @Test
    void bare_relock_restamps_without_moving_a_pin_that_a_forced_lock_floats(@TempDir Path tempDir) throws Exception {
        registerRootLeafGraph();
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("jk.toml"), """
                group = "com.acme"
                name     = "app"
                version = "0.1.0"

                [dependencies]
                root = { group = "com.foo", name = "root", version = "^1.0" }
                """);
        assertThat(run(
                        "lock",
                        "-C",
                        tempDir.toString(),
                        "--repo-url",
                        maven.base().toString(),
                        "--cache-dir",
                        cache.toString()))
                .isEqualTo(0);
        assertThat(rootVersion(tempDir)).isEqualTo("1.0");

        maven.registerMetadata("com.foo", "root", "1.0", "1.1");
        maven.registerPom("com.foo", "root", "1.1", pom("com.foo", "root", "1.1", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>leaf</artifactId>
                  <version>1.0</version>
                </dependency>
                """));
        maven.registerJar("com.foo", "root", "1.1", "root".getBytes(StandardCharsets.UTF_8));
        Files.writeString(
                tempDir.resolve("jk.toml"), "\n# a note that changes no dependency\n", StandardOpenOption.APPEND);
        Path lockFile = tempDir.resolve("jk-lock.toml");
        LockfileReader.clearCache();
        assertThat(LockfileReader.read(lockFile).manifestsSha256())
                .as("the edit stales the stamp")
                .isNotEqualTo(LockManifestDigest.compute(tempDir));
        String beforeRelock = Files.readString(lockFile);

        assertThat(run(
                        "lock",
                        "-C",
                        tempDir.toString(),
                        "--repo-url",
                        maven.base().toString(),
                        "--cache-dir",
                        cache.toString()))
                .isEqualTo(0);
        LockfileReader.clearCache();
        assertThat(LockfileReader.read(lockFile).manifestsSha256())
                .as("re-stamped")
                .isEqualTo(LockManifestDigest.compute(tempDir));
        assertThat(rootVersion(tempDir)).as("the pin stays where it was").isEqualTo("1.0");
        assertThat(withoutStamp(Files.readString(lockFile)))
                .as("a dependency-neutral edit moves the stamp and nothing else")
                .isEqualTo(withoutStamp(beforeRelock));

        assertThat(run(
                        "lock",
                        "-F",
                        "-C",
                        tempDir.toString(),
                        "--repo-url",
                        maven.base().toString(),
                        "--cache-dir",
                        cache.toString()))
                .isEqualTo(0);
        assertThat(rootVersion(tempDir))
                .as("-F floats to the newest compatible")
                .isEqualTo("1.1");
    }

    /** The lockfile text minus its manifest stamp, so a re-stamp compares equal. */
    private static String withoutStamp(String lock) {
        return lock.lines().filter(l -> !l.contains("manifests-sha256")).collect(Collectors.joining("\n"));
    }

    private static String rootVersion(Path dir) throws IOException {
        LockfileReader.clearCache();
        return LockfileReader.read(dir.resolve("jk-lock.toml")).artifacts().stream()
                .filter(a -> a.name().startsWith("com.foo:root:"))
                .findFirst()
                .orElseThrow()
                .version();
    }

    private void registerRootLeafGraph() {
        maven.registerMetadata("com.foo", "leaf", "1.0");
        maven.registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        maven.registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));
        maven.registerMetadata("com.foo", "root", "1.0");
        maven.registerPom("com.foo", "root", "1.0", pom("com.foo", "root", "1.0", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>leaf</artifactId>
                  <version>1.0</version>
                </dependency>
                """));
        maven.registerJar("com.foo", "root", "1.0", "root".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeProjectWithRootDep(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.acme"
                name     = "app"
                version = "0.1.0"

                [dependencies]
                root = { group = "com.foo", name = "root", version = "1.0" }
                """);
    }
}

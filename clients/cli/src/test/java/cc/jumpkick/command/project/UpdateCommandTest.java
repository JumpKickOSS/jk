// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.cli.testing.JkRun.run;
import static cc.jumpkick.cli.testing.MockMavenServer.pom;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.command.DefaultTestDepsFixture;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.testing.SysProps;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class UpdateCommandTest {

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
    void update_offline_resolves_from_journal(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.update", "leaf", "1.0");
        maven.registerPom("com.foo.update", "leaf", "1.0", pom("com.foo.update", "leaf", "1.0", ""));
        maven.registerJar("com.foo.update", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");

        // Warm cache + journal online.
        run("new", tempDir.toString());
        run("add", "com.foo.update:leaf:1.0", "-C", tempDir.toString());
        assertThat(run(
                        "update",
                        "-C",
                        tempDir.toString(),
                        "--repo-url",
                        maven.base().toString(),
                        "--cache-dir",
                        cache.toString()))
                .isEqualTo(0);
        Files.delete(tempDir.resolve("jk-lock.toml"));

        // Offline re-solve must come entirely from the journal. The store is keyed by origin, so
        // the offline solve names the same repository the warm-up fetched from.
        maven.stop();
        int exit = run(
                "update",
                "--offline",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                cache.toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(DefaultTestDepsFixture.projectCoords(lock)).containsExactly("com.foo.update:leaf");
    }

    @Test
    void update_rewrites_lockfile_after_dep_added(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.update", "leaf", "1.0");
        maven.registerPom("com.foo.update", "leaf", "1.0", pom("com.foo.update", "leaf", "1.0", ""));
        maven.registerJar("com.foo.update", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        // Initial state: no deps.
        run("new", tempDir.toString());
        run(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        Lockfile initial = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(DefaultTestDepsFixture.projectCoords(initial)).isEmpty();

        // Add a dep, then update.
        run("add", "com.foo.update:leaf:1.0", "-C", tempDir.toString());
        int exit = run(
                "update",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        Lockfile updated = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(DefaultTestDepsFixture.projectCoords(updated)).containsExactly("com.foo.update:leaf");
    }

    @Test
    void update_without_build_jk_fails(@TempDir Path tempDir) {
        int exit = run(
                "update",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void update_from_module_dir_locks_module_only(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.update", "leaf", "1.0");
        maven.registerPom("com.foo.update", "leaf", "1.0", pom("com.foo.update", "leaf", "1.0", ""));
        maven.registerJar("com.foo.update", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

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
                leaf = { group = "com.foo.update",  name = "leaf", version = "1.0" }
                """);
        Path libb = Files.createDirectories(tempDir.resolve("libb"));
        Files.writeString(libb.resolve("jk.toml"), """
                group = "com.acme"
                name     = "libb"
                version = "0.1.0"
                """);

        // Invoke from module — still writes the workspace root lock (full union).
        int exit = run(
                "update",
                "-C",
                app.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        assertThat(Files.exists(tempDir.resolve("jk-lock.toml"))).isTrue();
        assertThat(Files.exists(app.resolve("jk-lock.toml"))).isFalse();
        Lockfile lock = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(DefaultTestDepsFixture.projectCoords(lock)).containsExactly("com.foo.update:leaf");
    }
}

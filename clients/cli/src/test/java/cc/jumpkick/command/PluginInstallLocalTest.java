// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.Jk;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class PluginInstallLocalTest {

    @Test
    void workspace_root_install_publishes_plugin_jar_and_pom(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Path worker = writePluginWorkspace(dir);

        int exit = Jk.execute(
                "install",
                "-C",
                dir.toString(),
                "--skip-tests",
                "--cache-dir",
                cache.toString(),
                "--state-dir",
                dir.resolve("state").toString(),
                "--bin-dir",
                dir.resolve("bin").toString(),
                "--m2-dir",
                dir.resolve("m2").toString());
        assertThat(exit).isZero();
        assertInstalled(dir, worker);
        assertThat(dir.resolve("bin/jk-test-runner")).doesNotExist();
    }

    @Test
    void workspace_root_install_after_build_still_publishes_the_worker(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Path worker = writePluginWorkspace(dir);

        assertThat(Jk.execute("build", "-C", dir.toString(), "--skip-tests", "--cache-dir", cache.toString()))
                .isZero();

        int exit = Jk.execute(
                "install",
                "-C",
                dir.toString(),
                "--skip-tests",
                "--cache-dir",
                cache.toString(),
                "--state-dir",
                dir.resolve("state").toString(),
                "--bin-dir",
                dir.resolve("bin").toString(),
                "--m2-dir",
                dir.resolve("m2").toString());
        assertThat(exit).isZero();
        assertInstalled(dir, worker);
    }

    private static Path writePluginWorkspace(Path dir) throws Exception {
        Path mod = dir.resolve("plugins/worker");
        Files.createDirectories(mod.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                java = 25
                [workspace]
                modules = ["plugins/worker"]
                """);
        // `[m2] install` defaults ON machine-wide, and that branch copies the jar and POM into the
        // Maven local repo beside the shelf. This test is about the jk-local store alone, so the
        // module opts out — and the --m2-dir redirect above means even a policy regression cannot
        // reach the real ~/.m2.
        Files.writeString(mod.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "jk-test-runner"
                version = "0.12.0"
                java = 25

                [m2]
                install = false
                """);
        Files.writeString(mod.resolve("jk-plugin.toml"), """
                [plugin]
                id = "test-runner"
                table = "test-runner"
                """);
        Files.writeString(mod.resolve("src/main/java/x/X.java"), "package x; public class X {}\n");
        return mod;
    }

    /**
     * The local-install write is store-rooted whatever {@code --cache-dir} says, so the store is
     * the only place to look: resolvers read {@code repos/jk-local} from there.
     */
    private static void assertInstalled(Path dir, Path worker) {
        Path jar = JkStores.resolve("repos")
                .resolve("jk-local/cc/jumpkick/jk-test-runner/0.12.0/jk-test-runner-0.12.0.jar");
        assertThat(jar).isRegularFile();
        assertThat(jar.resolveSibling("jk-test-runner-0.12.0.pom")).isRegularFile();
        assertThat(dir.resolve("m2"))
                .as("[m2] install = false must leave the Maven local repo alone")
                .doesNotExist();
        assertThat(worker.resolve("src/main/java/x/X.java")).isRegularFile();
    }
}

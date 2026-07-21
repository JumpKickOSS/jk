// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginInstallLocalTest {

    @Test
    void install_local_side_loads_assembly_jar(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Path mod = dir.resolve("plugins/worker");
        Files.createDirectories(mod.resolve("target"));
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25
                [workspace]
                modules = ["plugins/worker"]
                """);
        Files.writeString(
                mod.resolve("jk.toml"),
                """
                [project]
                group = "cc.jumpkick"
                name = "jk-test-runner"
                version = "0.10.0-SNAPSHOT"
                jdk = 25
                java = 25
                [application]
                main = "cc.jumpkick.plugin.process.PluginMain"
                assembly = true
                """);
        Path jar = mod.resolve("target/jk-test-runner-0.10.0-SNAPSHOT-assembly.jar");
        Files.writeString(jar, "fake-worker-jar");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute(
                    "plugin",
                    "install-local",
                    "-C",
                    dir.toString(),
                    "--cache-dir",
                    cache.toString());
        } finally {
            System.setOut(orig);
        }
        assertThat(exit).isZero();
        Path dest = cache.resolve(
                "repos/local/cc/jumpkick/jk-test-runner/0.10.0-SNAPSHOT/jk-test-runner-0.10.0-SNAPSHOT.jar");
        assertThat(dest).exists();
        assertThat(Files.readString(dest)).isEqualTo("fake-worker-jar");
        assertThat(Path.of(dest + ".sha256")).exists();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Installed");
    }
}

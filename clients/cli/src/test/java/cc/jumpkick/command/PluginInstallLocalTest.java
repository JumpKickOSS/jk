// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class PluginInstallLocalTest {

    private static final String WORKER_TOML = """
            [project]
            group = "cc.jumpkick"
            name = "jk-test-runner"
            version = "0.10.1"
            jdk = 25
            java = 25
            [application]
            main = "cc.jumpkick.plugin.process.PluginMain"
            assembly = true
            """;

    @Test
    void install_local_side_loads_assembly_jar(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Path mod = dir.resolve("plugins/worker");
        Files.createDirectories(mod.resolve("target"));
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25
                [workspace]
                modules = ["plugins/worker"]
                """);
        Files.writeString(mod.resolve("jk.toml"), WORKER_TOML);
        Path jar = mod.resolve("target/jk-test-runner-0.10.1-all.jar");
        Files.writeString(jar, "fake-worker-jar");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("plugin", "install-local", "-C", dir.toString(), "--cache-dir", cache.toString());
        } finally {
            System.setOut(orig);
        }
        assertThat(exit).isZero();
        Path dest = cache.resolve("repos/local/cc/jumpkick/jk-test-runner/0.10.1/jk-test-runner-0.10.1.jar");
        assertThat(dest).exists();
        assertThat(Files.readString(dest)).isEqualTo("fake-worker-jar");
        assertThat(Path.of(dest + ".sha256")).exists();
        assertThat(Files.readString(Path.of(dest + ".sha256"))).matches("[0-9a-f]{64}");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Installed");
    }

    @Test
    void dry_run_writes_nothing(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Path mod = dir.resolve("plugins/worker");
        Files.createDirectories(mod.resolve("target"));
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25
                [workspace]
                modules = ["plugins/worker"]
                """);
        Files.writeString(mod.resolve("jk.toml"), WORKER_TOML);
        Files.writeString(mod.resolve("target/jk-test-runner-0.10.1-all.jar"), "x");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute(
                    "plugin", "install-local", "-C", dir.toString(), "--cache-dir", cache.toString(), "--dry-run");
        } finally {
            System.setOut(orig);
        }
        assertThat(exit).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("would install");
        assertThat(Files.exists(cache.resolve("repos/local"))).isFalse();
    }

    @Test
    void modules_filter_selects_by_path_fragment(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Path a = dir.resolve("plugins/alpha");
        Path b = dir.resolve("plugins/beta");
        Files.createDirectories(a.resolve("target"));
        Files.createDirectories(b.resolve("target"));
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25
                [workspace]
                modules = ["plugins/alpha", "plugins/beta"]
                """);
        Files.writeString(a.resolve("jk.toml"), """
                [project]
                group = "cc.jumpkick"
                name = "jk-alpha"
                version = "0.10.1"
                jdk = 25
                java = 25
                [application]
                main = "cc.jumpkick.plugin.process.PluginMain"
                assembly = true
                """);
        Files.writeString(b.resolve("jk.toml"), """
                [project]
                group = "cc.jumpkick"
                name = "jk-beta"
                version = "0.10.1"
                jdk = 25
                java = 25
                [application]
                main = "cc.jumpkick.plugin.process.PluginMain"
                assembly = true
                """);
        Files.writeString(a.resolve("target/jk-alpha-0.10.1-all.jar"), "A");
        Files.writeString(b.resolve("target/jk-beta-0.10.1-all.jar"), "B");

        assertThat(Jk.execute(
                        "plugin",
                        "install-local",
                        "-C",
                        dir.toString(),
                        "--cache-dir",
                        cache.toString(),
                        "--modules",
                        "alpha"))
                .isZero();
        assertThat(cache.resolve("repos/local/cc/jumpkick/jk-alpha/0.10.1/jk-alpha-0.10.1.jar"))
                .exists();
        assertThat(cache.resolve("repos/local/cc/jumpkick/jk-beta/0.10.1/jk-beta-0.10.1.jar"))
                .doesNotExist();
    }

    @Test
    void missing_jar_fails_when_no_workers_installed(@TempDir Path dir) throws Exception {
        Path mod = dir.resolve("plugins/worker");
        Files.createDirectories(mod);
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25
                [workspace]
                modules = ["plugins/worker"]
                """);
        Files.writeString(mod.resolve("jk.toml"), WORKER_TOML);
        // no target jar
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream orig = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute(
                    "plugin",
                    "install-local",
                    "-C",
                    dir.toString(),
                    "--cache-dir",
                    dir.resolve("cache").toString());
        } finally {
            System.setErr(orig);
        }
        assertThat(exit).isEqualTo(1); // FAILURE
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("missing jar");
    }

    @Test
    void skips_non_plugin_main_modules(@TempDir Path dir) throws Exception {
        Path lib = dir.resolve("lib");
        Files.createDirectories(lib);
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25
                [workspace]
                modules = ["lib"]
                """);
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "lib"
                version = "0.0.1"
                jdk = 25
                """);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream orig = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute(
                    "plugin",
                    "install-local",
                    "-C",
                    dir.toString(),
                    "--cache-dir",
                    dir.resolve("cache").toString());
        } finally {
            System.setErr(orig);
        }
        assertThat(exit).isEqualTo(2); // CONFIG — no PluginMain modules
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("PluginMain");
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class PluginInstallLocalTest {

    /** Thin PluginMain worker — no assembly (JK-1347). */
    private static final String WORKER_TOML = """
            [project]
            group = "cc.jumpkick"
            name = "jk-test-runner"
            version = "0.11.0"
            jdk = 25
            java = 25
            [application]
            main = "cc.jumpkick.plugin.process.PluginMain"
            """;

    @Test
    void install_local_side_loads_thin_jar_and_classpath(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
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
        Path jar = dir.resolve("target/plugins/worker/jk-test-runner-0.11.0.jar");
        Files.createDirectories(jar.getParent());
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
        Path dest = cache.resolve("repos/local/cc/jumpkick/jk-test-runner/0.11.0/jk-test-runner-0.11.0.jar");
        assertThat(dest).exists();
        assertThat(Files.readString(dest)).isEqualTo("fake-worker-jar");
        assertThat(Path.of(dest + ".sha256")).exists();
        assertThat(Files.readString(Path.of(dest + ".sha256"))).matches("[0-9a-f]{64}");
        assertThat(Path.of(dest + ".classpath")).exists();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Installed");
    }

    @Test
    void reinstall_drops_stale_sidecar_entries(@TempDir Path dir) throws Exception {
        // JK-1352: the lock closure is authoritative — a removed dep left in the previous
        // sidecar must not survive regeneration.
        Path cache = dir.resolve("cache");
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
        Files.writeString(mod.resolve("jk.toml"), """
                [project]
                group = "cc.jumpkick"
                name = "jk-test-runner"
                version = "0.11.0"
                jdk = 25
                java = 25
                [application]
                main = "cc.jumpkick.plugin.process.PluginMain"
                [dependencies]
                gson = { group = "com.google.code.gson", name = "gson", version = "2.11.0" }
                """);
        Path jar = dir.resolve("target/plugins/worker/jk-test-runner-0.11.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "fake-worker-jar");

        // Deterministic non-empty closure: seed the CAS with a fake gson blob and hand-write a
        // fresh (digest-stamped) lock pinning it, so no engine or network is needed.
        byte[] gsonBytes = "fake-gson-jar".getBytes(StandardCharsets.UTF_8);
        String gsonSha = cc.jumpkick.util.Hashing.sha256Hex(gsonBytes);
        cc.jumpkick.cache.JkStores.cas(cache).put(gsonBytes, gsonSha);
        cc.jumpkick.lock.Lockfile lock = new cc.jumpkick.lock.Lockfile(
                cc.jumpkick.lock.Lockfile.CURRENT_VERSION,
                "jk test",
                cc.jumpkick.lock.Lockfile.RESOLUTION_ALGORITHM,
                List.of(new cc.jumpkick.lock.Lockfile.Artifact(
                        "com.google.code.gson:gson",
                        "2.11.0",
                        "https://repo.example/gson",
                        "sha256:" + gsonSha,
                        null,
                        List.of(cc.jumpkick.model.Scope.MAIN),
                        List.of(),
                        null,
                        null)));
        cc.jumpkick.lock.LockfileWriter.write(lock, dir.resolve("jk-lock.toml"));

        assertThat(Jk.execute("plugin", "install-local", "-C", dir.toString(), "--cache-dir", cache.toString()))
                .isZero();
        Path dest = cache.resolve("repos/local/cc/jumpkick/jk-test-runner/0.11.0/jk-test-runner-0.11.0.jar");
        Path destSidecar = Path.of(dest + ".classpath");
        assertThat(destSidecar).exists();
        long depLines = Files.readAllLines(destSidecar).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .count();
        assertThat(depLines).isGreaterThanOrEqualTo(1); // the pinned gson resolved from the CAS

        // A dep that has since been removed from the manifest, still on disk and in both sidecars.
        Path stale = dir.resolve("stale.jar");
        Files.writeString(stale, "stale-bytes");
        Files.writeString(destSidecar, Files.readString(destSidecar) + stale.toAbsolutePath() + "\n");
        Path sourceSidecar = Path.of(jar + ".classpath");
        Files.writeString(sourceSidecar, stale.toAbsolutePath() + "\n");

        assertThat(Jk.execute("plugin", "install-local", "-C", dir.toString(), "--cache-dir", cache.toString()))
                .isZero();
        String regenerated = Files.readString(destSidecar);
        assertThat(regenerated).doesNotContain("stale.jar");
        assertThat(Files.readAllLines(destSidecar).stream()
                        .filter(l -> !l.isBlank() && !l.startsWith("#"))
                        .count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void dry_run_writes_nothing(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
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
        Files.createDirectories(dir.resolve("target/plugins/worker"));
        Files.writeString(dir.resolve("target/plugins/worker/jk-test-runner-0.11.0.jar"), "x");

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
        Files.createDirectories(a);
        Files.createDirectories(b);
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
                version = "0.11.0"
                jdk = 25
                java = 25
                [application]
                main = "cc.jumpkick.plugin.process.PluginMain"
                """);
        Files.writeString(b.resolve("jk.toml"), """
                [project]
                group = "cc.jumpkick"
                name = "jk-beta"
                version = "0.11.0"
                jdk = 25
                java = 25
                [application]
                main = "cc.jumpkick.plugin.process.PluginMain"
                """);
        Files.createDirectories(dir.resolve("target/plugins/alpha"));
        Files.createDirectories(dir.resolve("target/plugins/beta"));
        Files.writeString(dir.resolve("target/plugins/alpha/jk-alpha-0.11.0.jar"), "A");
        Files.writeString(dir.resolve("target/plugins/beta/jk-beta-0.11.0.jar"), "B");

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
        assertThat(cache.resolve("repos/local/cc/jumpkick/jk-alpha/0.11.0/jk-alpha-0.11.0.jar"))
                .exists();
        assertThat(cache.resolve("repos/local/cc/jumpkick/jk-beta/0.11.0/jk-beta-0.11.0.jar"))
                .doesNotExist();
    }

    @Test
    void cache_dir_install_leaves_the_global_lib_untouched(@TempDir Path dir) throws Exception {
        // JK-1354: an isolated --cache-dir install must not create or overwrite the shared
        // store/lib/<id>/ dir every ambient launch prefers.
        Path cache = dir.resolve("cache");
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
        Files.writeString(mod.resolve("jk.toml"), """
                [project]
                group = "cc.jumpkick"
                name = "jk-iso-worker"
                version = "0.11.0"
                jdk = 25
                java = 25
                [application]
                main = "cc.jumpkick.plugin.process.PluginMain"
                """);
        Path jar = dir.resolve("target/plugins/worker/jk-iso-worker-0.11.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "fake-worker-jar");

        assertThat(Jk.execute("plugin", "install-local", "-C", dir.toString(), "--cache-dir", cache.toString()))
                .isZero();
        assertThat(cache.resolve("repos/local/cc/jumpkick/jk-iso-worker/0.11.0/jk-iso-worker-0.11.0.jar"))
                .exists();
        assertThat(Files.exists(cc.jumpkick.compile.WorkerLib.dir("jk-iso-worker")))
                .isFalse();
    }

    @Test
    void uninstall_removes_local_repo_entries(@TempDir Path dir) throws Exception {
        // JK-1353: `jk plugin uninstall` drops what install-local side-loaded.
        Path cache = dir.resolve("cache");
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
        Path jar = dir.resolve("target/plugins/worker/jk-test-runner-0.11.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "fake-worker-jar");

        assertThat(Jk.execute("plugin", "install-local", "-C", dir.toString(), "--cache-dir", cache.toString()))
                .isZero();
        Path repoDir = cache.resolve("repos/local/cc/jumpkick/jk-test-runner");
        assertThat(repoDir).exists();

        assertThat(Jk.execute("plugin", "uninstall", "jk-test-runner", "--cache-dir", cache.toString()))
                .isZero();
        assertThat(Files.exists(repoDir)).isFalse();

        // A second uninstall has nothing to remove.
        assertThat(Jk.execute("plugin", "uninstall", "jk-test-runner", "--cache-dir", cache.toString()))
                .isEqualTo(2);
    }

    @Test
    void no_plugin_main_modules_is_config_error(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "solo"
                version = "0.0.1"
                jdk = 25
                """);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream orig = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("plugin", "install-local", "-C", dir.toString(), "--cache-dir", cache.toString());
        } finally {
            System.setErr(orig);
        }
        assertThat(exit).isEqualTo(2); // CONFIG — no PluginMain modules
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("PluginMain");
    }
}

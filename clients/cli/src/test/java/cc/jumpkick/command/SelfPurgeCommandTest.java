// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.command.SelfPurgeCommand.Target;
import cc.jumpkick.util.JkDirs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Uses the suite's isolated {@code JK_HOME} so purge only touches throwaway trees under the test
 * harness, never the developer's real product dirs.
 */
class SelfPurgeCommandTest {

    @Test
    void wipeRoots_never_includes_bin_jdks_active_version_or_store_lib() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path bin = dirs.binDirectory().toAbsolutePath().normalize();
        Path jdks = dirs.jdksDir().toAbsolutePath().normalize();
        Path active = dirs.versionsDir().resolve(Jk.VERSION).toAbsolutePath().normalize();
        Path lib = dirs.libDir().toAbsolutePath().normalize();
        Files.createDirectories(active);
        Files.createDirectories(lib.resolve("jk-java-compiler"));

        List<Path> roots = SelfPurgeCommand.wipeRoots(dirs);
        for (Path r : roots) {
            Path abs = r.toAbsolutePath().normalize();
            assertThat(abs).isNotEqualTo(bin);
            assertThat(abs).isNotEqualTo(jdks);
            assertThat(abs).isNotEqualTo(active);
            assertThat(abs).isNotEqualTo(lib);
            assertThat(abs.startsWith(bin)).isFalse();
            assertThat(abs.startsWith(jdks)).isFalse();
            assertThat(abs.startsWith(active)).isFalse();
            assertThat(abs.startsWith(lib)).isFalse();
        }
    }

    @Test
    void store_deletes_old_versions_and_cas_but_keeps_active_and_lib() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path versions = dirs.versionsDir();
        Path active = versions.resolve(Jk.VERSION);
        Path old = versions.resolve("0.9.0");
        Path cas = dirs.storeDir().resolve("sha256");
        Path lib = dirs.libDir().resolve("jk-java-compiler");
        Files.createDirectories(active.resolve("lib"));
        Files.writeString(active.resolve("manifest.toml"), "version = \"" + Jk.VERSION + "\"\n");
        Files.createDirectories(old);
        Files.writeString(old.resolve("manifest.toml"), "version = \"0.9.0\"\n");
        Files.createDirectories(cas.resolve("ab"));
        Files.writeString(cas.resolve("ab/blob"), "cas");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("plugin.jar"), "plugin");

        List<Path> roots = SelfPurgeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        assertThat(roots).anyMatch(p -> p.endsWith("0.9.0") || p.toString().endsWith("0.9.0"));
        assertThat(roots).anyMatch(p -> p.endsWith("sha256") || p.toString().contains("sha256"));
        assertThat(roots).noneMatch(p -> p.equals(active.toAbsolutePath().normalize()));
        assertThat(roots)
                .noneMatch(p -> p.equals(lib.toAbsolutePath().normalize())
                        || p.startsWith(dirs.libDir().toAbsolutePath().normalize()));

        int exit = capture(() -> Jk.execute("self", "purge", "--store", "-y"));
        assertThat(exit).isZero();
        assertThat(old).doesNotExist();
        assertThat(cas.resolve("ab/blob")).doesNotExist();
        assertThat(active.resolve("manifest.toml")).exists();
        assertThat(lib.resolve("plugin.jar")).exists();
    }

    @Test
    void cache_only_selects_cache_dir() {
        JkDirs dirs = JkDirs.current();
        List<Path> roots = SelfPurgeCommand.wipeRoots(dirs, EnumSet.of(Target.CACHE));
        assertThat(roots).containsExactly(dirs.cacheDir().toAbsolutePath().normalize());
    }

    @Test
    void purge_yes_removes_cache_state_and_leaves_bin_alone() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Path state = dirs.stateDir();
        Path bin = dirs.binDirectory();
        Files.createDirectories(cache.resolve("actions"));
        Files.writeString(cache.resolve("actions/marker"), "x");
        Files.createDirectories(state.resolve("aot"));
        Files.writeString(state.resolve("aot/marker"), "y");
        Files.createDirectories(bin);
        Path jkBin = bin.resolve("jk");
        if (!Files.exists(jkBin)) Files.writeString(jkBin, "#!/bin/sh\n");
        Path foreign = bin.resolve("uv");
        Files.writeString(foreign, "foreign-tool");

        int exit = capture(() -> Jk.execute("self", "purge", "--cache", "--state", "-y"));
        assertThat(exit).isZero();
        assertThat(Files.exists(cache.resolve("actions/marker"))).isFalse();
        assertThat(Files.exists(state.resolve("aot/marker"))).isFalse();
        assertThat(Files.exists(jkBin)).isTrue();
        assertThat(Files.exists(foreign)).isTrue();
    }

    @Test
    void dry_run_does_not_delete() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache);
        Path marker = cache.resolve("dry-run-keep");
        Files.writeString(marker, "keep");

        String out = captureStdout(() -> assertThat(Jk.execute("self", "purge", "--cache", "--dry-run", "-y"))
                .isZero());
        assertThat(TestAnsi.strip(out)).containsIgnoringCase("dry run");
        assertThat(TestAnsi.strip(out)).contains("Path to Delete").contains("What");
        assertThat(marker).exists();
        Files.deleteIfExists(marker);
    }

    @Test
    void config_under_jk_home_targets_only_config_file_even_with_outside_bin() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-cfg");
        Path home = root.resolve("home");
        Path outsideBin = root.resolve("outside-bin");
        Files.createDirectories(home.resolve("versions"));
        Files.createDirectories(outsideBin);
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_BIN_DIR", outsideBin.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfPurgeCommand.wipeRoots(dirs, EnumSet.of(Target.CONFIG));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).containsExactly(homeAbs.resolve("config.toml"));
        assertThat(roots).noneMatch(p -> p.equals(homeAbs));
    }

    @Test
    void guard_refuses_rows_that_contain_active_version_or_lib() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-anc");
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve("versions").resolve(Jk.VERSION));
        Files.createDirectories(home.resolve("store").resolve("lib"));
        // JK_STATE_DIR mis-pointed at the umbrella root: state purge must not take the whole tree.
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_STATE_DIR", home.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfPurgeCommand.wipeRoots(dirs, EnumSet.of(Target.STATE));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> p.equals(homeAbs));
        assertThat(roots).noneMatch(p -> homeAbs.resolve("versions").startsWith(p));
    }

    @Test
    void guards_resolve_from_injected_dirs_not_process_environment() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-seam");
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve("store").resolve("lib"));
        Files.createDirectories(home.resolve("store").resolve("sha256"));
        Files.createDirectories(home.resolve("versions").resolve(Jk.VERSION));
        Files.createDirectories(home.resolve("versions").resolve("0.0.1"));
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString()), root.resolve("userhome").toString());

        List<Path> roots = SelfPurgeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).contains(homeAbs.resolve("versions").resolve("0.0.1"));
        assertThat(roots).contains(homeAbs.resolve("store").resolve("sha256"));
        assertThat(roots).noneMatch(p -> p.equals(homeAbs.resolve("store").resolve("lib")));
        assertThat(roots).noneMatch(p -> p.equals(homeAbs.resolve("versions").resolve(Jk.VERSION)));
    }

    @Test
    void symlinked_bin_dir_is_protected_via_realpath() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-link");
        Path home = root.resolve("home");
        Path realBin = home.resolve("data").resolve("bin");
        Path linkBin = root.resolve("linked-bin");
        Files.createDirectories(realBin);
        Files.writeString(realBin.resolve("jk"), "#!/bin/sh\n");
        try {
            Files.createSymbolicLink(linkBin, realBin);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support — nothing to verify here
        }
        // PATH bin is the symlink; its target lives inside the purged data tree.
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_BIN_DIR", linkBin.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfPurgeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path realBinAbs = realBin.toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> p.equals(realBinAbs) || realBinAbs.startsWith(p));
    }

    @Test
    void store_sweep_keeps_credentials_and_sweeps_completions_in_default_layout() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-cred");
        Path userHome = root.resolve("userhome");
        // Default (no JK_HOME) layout: home() == dataDir() == ~/.local/share/jk, where
        // credentials/, repo-credentials/, and completions/ live as data-dir siblings.
        JkDirs dirs = JkDirs.of(env(), userHome.toString());
        Path data = dirs.dataDir();
        Files.createDirectories(data.resolve("credentials"));
        Files.createDirectories(data.resolve("repo-credentials"));
        Files.createDirectories(data.resolve("completions"));
        Files.createDirectories(data.resolve("store").resolve("sha256"));

        List<Path> roots = SelfPurgeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path dataAbs = data.toAbsolutePath().normalize();
        assertThat(roots).doesNotContain(dataAbs.resolve("credentials"));
        assertThat(roots).doesNotContain(dataAbs.resolve("repo-credentials"));
        assertThat(roots).contains(dataAbs.resolve("completions"));
        assertThat(roots).contains(dataAbs.resolve("store").resolve("sha256"));
    }

    @Test
    void store_sweep_keeps_active_version_lock_file() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-lock");
        Path home = root.resolve("home");
        Path versions = home.resolve("versions");
        Files.createDirectories(versions);
        Files.writeString(versions.resolve("." + Jk.VERSION + ".lock"), "");
        Files.writeString(versions.resolve(".0.9.0.lock"), "");
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString()), root.resolve("userhome").toString());

        List<Path> roots = SelfPurgeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path versionsAbs = versions.toAbsolutePath().normalize();
        assertThat(roots).contains(versionsAbs.resolve(".0.9.0.lock"));
        assertThat(roots).doesNotContain(versionsAbs.resolve("." + Jk.VERSION + ".lock"));
    }

    private static java.util.function.Function<String, String> env(String... kv) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        return map::get;
    }

    @Test
    void global_yes_works_before_the_command_and_between_group_and_sub() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache.resolve("actions"));
        Files.writeString(cache.resolve("actions/pre-yes"), "x");

        // Without -y reaching Confirm, non-TTY stdin would abort with exit 1.
        int exit = capture(() -> Jk.execute("-y", "self", "purge", "--cache"));
        assertThat(exit).isZero();
        assertThat(cache.resolve("actions/pre-yes")).doesNotExist();

        Files.createDirectories(cache.resolve("actions"));
        Files.writeString(cache.resolve("actions/mid-yes"), "x");
        exit = capture(() -> Jk.execute("self", "--yes", "purge", "--cache"));
        assertThat(exit).isZero();
        assertThat(cache.resolve("actions/mid-yes")).doesNotExist();
    }

    @Test
    void declining_the_prompt_deletes_nothing_and_exits_one() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache);
        Path marker = cache.resolve("decline-keep");
        Files.writeString(marker, "keep");

        // No -y: cooked Confirm treats empty/EOF stdin as decline. Explicit System.in is required —
        // under `jk test` the worker's System.in is (or was) a protocol pipe that never EOFs, so
        // relying on ambient stdin hung the suite.
        java.io.InputStream prevIn = System.in;
        try {
            System.setIn(new java.io.ByteArrayInputStream(new byte[0]));
            int exit = capture(() -> Jk.execute("self", "purge", "--cache"));
            assertThat(exit).isEqualTo(1);
        } finally {
            System.setIn(prevIn);
        }
        assertThat(marker).exists();
        Files.deleteIfExists(marker);
    }

    @Test
    void dry_run_without_yes_does_not_prompt_and_exits_zero() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache);
        Path marker = cache.resolve("dry-run-no-yes-keep");
        Files.writeString(marker, "keep");

        // No -y and no TTY: a prompt would hit EOF and abort with exit 1.
        String out = captureStdout(() ->
                assertThat(Jk.execute("self", "purge", "--cache", "--dry-run")).isZero());
        assertThat(TestAnsi.strip(out)).containsIgnoringCase("dry run");
        assertThat(TestAnsi.strip(out)).doesNotContain("Purge aborted");
        assertThat(marker).exists();
        Files.deleteIfExists(marker);
    }

    @Test
    void displayPath_uses_tilde_under_home() {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path under = home.resolve("cache").resolve("jk");
        assertThat(SelfPurgeCommand.displayPath(under)).isEqualTo("~/cache/jk");
    }

    private static int capture(java.util.function.IntSupplier body) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream cap = new PrintStream(buf, true, StandardCharsets.UTF_8);
        System.setOut(cap);
        System.setErr(cap);
        try {
            return body.getAsInt();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static String captureStdout(Runnable body) {
        PrintStream out = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(out);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.command.SelfPurgeCommand.Target;
import cc.jumpkick.util.JkDirs;
import java.io.ByteArrayOutputStream;
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
        assertThat(roots).noneMatch(p -> p.equals(lib.toAbsolutePath().normalize())
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

        String out = captureStdout(() -> assertThat(Jk.execute("self", "purge", "--cache", "--dry-run", "-y")).isZero());
        assertThat(TestAnsi.strip(out)).containsIgnoringCase("dry run");
        assertThat(TestAnsi.strip(out)).contains("Path to Delete").contains("What");
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

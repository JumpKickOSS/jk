// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.util.JkDirs;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Uses the suite's isolated {@code JK_HOME} so purge only touches throwaway trees under the test
 * harness, never the developer's real product dirs.
 */
class SelfPurgeCommandTest {

    @Test
    void wipeRoots_never_includes_bin_or_jdks() {
        JkDirs dirs = JkDirs.current();
        List<Path> roots = SelfPurgeCommand.wipeRoots(dirs);
        Path bin = dirs.binDirectory().toAbsolutePath().normalize();
        Path jdks = dirs.jdksDir().toAbsolutePath().normalize();
        for (Path r : roots) {
            assertThat(r).isNotEqualTo(bin);
            assertThat(r).isNotEqualTo(jdks);
            assertThat(r.startsWith(bin) || bin.startsWith(r)).isFalse();
            assertThat(r.startsWith(jdks) || jdks.startsWith(r)).isFalse();
        }
    }

    @Test
    void purge_yes_removes_cache_state_and_keeps_jk_binary() throws Exception {
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
        Path tool = bin.resolve("some-tool");
        Files.writeString(tool, "tool");

        int exit = capture(() -> Jk.execute("self", "purge", "-y"));
        assertThat(exit).isZero();
        assertThat(Files.exists(cache.resolve("actions/marker"))).isFalse();
        assertThat(Files.exists(state.resolve("aot/marker"))).isFalse();
        assertThat(Files.exists(jkBin)).isTrue();
        assertThat(Files.exists(tool)).isFalse();
    }

    @Test
    void dry_run_does_not_delete() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache);
        Path marker = cache.resolve("dry-run-keep");
        Files.writeString(marker, "keep");

        String out = captureStdout(() -> assertThat(Jk.execute("self", "purge", "--dry-run", "-y")).isZero());
        assertThat(TestAnsi.strip(out)).containsIgnoringCase("dry run");
        assertThat(marker).exists();
        Files.deleteIfExists(marker);
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

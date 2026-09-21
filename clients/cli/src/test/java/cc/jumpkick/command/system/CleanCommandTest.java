// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.builds.CheckoutSlot;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.FileLocks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class CleanCommandTest {

    // ---- module containment ------------------------------------------------

    @Test
    void escaping_module_entries_are_skipped_with_a_warning(@TempDir Path tempDir) throws IOException {
        Path ws = Files.createDirectories(tempDir.resolve("ws"));
        Path victim = Files.createDirectories(tempDir.resolve("victim"));

        List<String> warnings = new ArrayList<>();
        List<Path> dirs = CleanCommand.resolveModuleDirs(ws, List.of("lib", "../victim", "/etc"), warnings);

        assertThat(dirs).containsExactly(ws.toAbsolutePath().normalize().resolve("lib"));
        assertThat(dirs)
                .allSatisfy(d -> assertThat(d.startsWith(ws.toAbsolutePath().normalize()))
                        .isTrue());
        assertThat(warnings)
                .containsExactly(
                        "skipping module outside workspace: ../victim", "skipping module outside workspace: /etc");
        assertThat(victim).exists(); // never a candidate for deletion
    }

    @Test
    void dotted_but_contained_module_entries_survive_normalization(@TempDir Path tempDir) throws IOException {
        Path ws = Files.createDirectories(tempDir.resolve("ws"));
        List<String> warnings = new ArrayList<>();
        List<Path> dirs = CleanCommand.resolveModuleDirs(ws, List.of("lib/../app"), warnings);
        assertThat(dirs).containsExactly(ws.toAbsolutePath().normalize().resolve("app"));
        assertThat(warnings).isEmpty();
    }

    // ---- held checkout ------------------------------------------------------

    @Test
    void a_checkout_a_build_holds_is_refused_with_its_number(@TempDir Path ws) throws IOException {
        Path app = Files.createDirectories(ws.resolve("app"));
        FileLocks.Hold hold = (FileLocks.Hold) FileLocks.tryHold(CheckoutSlot.lockFile(app));
        try {
            hold.write("pid=1\nbuild=7\nkind=build\nstarted=1\n");
            assertThat(CleanCommand.heldMessage(ws, List.of(ws, app)))
                    .hasValueSatisfying(m -> assertThat(m).startsWith("Build #7 is running in app"));
        } finally {
            hold.close();
        }
        assertThat(CleanCommand.heldMessage(ws, List.of(ws, app))).isEmpty();
    }

    // ---- delete tally -------------------------------------------

    @Test
    void delete_counts_files_and_bytes_once(@TempDir Path tempDir) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(root.resolve("a.txt"), "abc");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/b.txt"), "defgh");

        var stats = new PathUtil.Removed();
        PathUtil.deleteTrees(List.of(root), stats);

        assertThat(root).doesNotExist();
        assertThat(stats.files()).isEqualTo(2);
        assertThat(stats.bytes()).isEqualTo(8);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC}) // PosixFilePermissions — undeletable-dir lock is POSIX-only
    void delete_stats_count_only_what_was_actually_removed(@TempDir Path tempDir) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("target"));
        // Deletable files on both sides of {@code locked} in sort order. Both removals count,
        // whichever order the walk visits them.
        Files.writeString(root.resolve("aa.txt"), "abc");
        Files.writeString(root.resolve("zz.txt"), "abc");
        Path locked = Files.createDirectories(root.resolve("locked"));
        Files.writeString(locked.resolve("file.txt"), "defghi");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));

        var stats = new PathUtil.Removed();
        try {
            assertThatThrownBy(() -> PathUtil.deleteTrees(List.of(root), stats)).isInstanceOf(IOException.class);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }

        // Both deletable files; the one that survived contributes nothing.
        assertThat(stats.files()).as("files removed").isEqualTo(2);
        assertThat(stats.bytes()).as("bytes removed").isEqualTo(6);
        assertThat(locked.resolve("file.txt")).exists();
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
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

    // ---- deleteRecursively stats -------------------------------------------

    @Test
    void delete_counts_files_and_bytes_once(@TempDir Path tempDir) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(root.resolve("a.txt"), "abc");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/b.txt"), "defgh");

        long[] stats = {0, 0};
        CleanCommand.deleteRecursively(root, stats);

        assertThat(root).doesNotExist();
        assertThat(stats[0]).isEqualTo(2);
        assertThat(stats[1]).isEqualTo(8);
    }

    @Test
    void delete_stats_do_not_double_count_across_retry_walks(@TempDir Path tempDir) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("target"));
        // "zz.txt" sorts after "locked/…" in the reverse-order walk, so it is deleted (and must be
        // counted exactly once) before the undeletable file fails each of the four attempts.
        Files.writeString(root.resolve("zz.txt"), "abc");
        Path locked = Files.createDirectories(root.resolve("locked"));
        Files.writeString(locked.resolve("file.txt"), "defghi");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));

        long[] stats = {0, 0};
        try {
            assertThatThrownBy(() -> CleanCommand.deleteRecursively(root, stats))
                    .isInstanceOf(IOException.class);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }

        // zz.txt once; the never-deleted file contributes nothing despite four walk attempts.
        assertThat(stats[0]).isEqualTo(1);
        assertThat(stats[1]).isEqualTo(3);
        assertThat(locked.resolve("file.txt")).exists();
    }
}

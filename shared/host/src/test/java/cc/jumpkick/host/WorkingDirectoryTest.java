// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class WorkingDirectoryTest {

    @Test
    void a_pin_moves_where_relative_paths_resolve_and_restoring_it_brings_back_the_previous(@TempDir Path dir) {
        Path before = WorkingDirectory.current();
        Path previous = WorkingDirectory.pin(dir);
        try {
            assertThat(WorkingDirectory.current())
                    .isEqualTo(dir.toAbsolutePath().normalize());
            assertThat(WorkingDirectory.resolve("demo/../app")).isEqualTo(dir.resolve("app"));
            assertThat(WorkingDirectory.resolve(dir.resolve("x/./y"))).isEqualTo(dir.resolve("x/y"));
        } finally {
            WorkingDirectory.pin(previous);
        }
        assertThat(WorkingDirectory.current()).isEqualTo(before);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void a_rooted_path_without_a_drive_uses_the_pinned_drive() {
        Path previous = WorkingDirectory.pin(Path.of("Z:/checkout"));
        try {
            assertThat(WorkingDirectory.resolve("/tmp/a/../b")).isEqualTo(Path.of("Z:/tmp/b"));
        } finally {
            WorkingDirectory.pin(previous);
        }
    }

    @Test
    void unpinned_it_is_the_process_working_directory() {
        Path previous = WorkingDirectory.pin(null);
        try {
            assertThat(WorkingDirectory.current())
                    .isEqualTo(Path.of("").toAbsolutePath().normalize());
        } finally {
            WorkingDirectory.pin(previous);
        }
    }
}

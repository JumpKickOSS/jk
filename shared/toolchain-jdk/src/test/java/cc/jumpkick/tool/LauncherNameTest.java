// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherNameTest {

    @Test
    void accepts_portable_launcher_leaf_names() {
        assertThat(List.of("checkstyle", "kotlin-2.2", "my_tool", "org.example.tool"))
                .allMatch(name -> LauncherName.validationError(name).isEmpty());
    }

    @Test
    void rejects_paths_controls_and_windows_devices() {
        assertThat(List.of(
                        "",
                        ".",
                        "..",
                        "../outside",
                        "nested/tool",
                        "nested\\tool",
                        "/tmp/tool",
                        "C:\\tool",
                        "bad\nname",
                        "CON",
                        "con.txt",
                        "NUL",
                        "COM1",
                        "lpt9.exe"))
                .allMatch(name -> LauncherName.validationError(name).isPresent());
    }

    @Test
    void resolve_child_never_returns_a_parent_or_sibling(@TempDir Path tmp) {
        Path root = tmp.resolve("bin");
        assertThat(LauncherName.resolveChild(root, "tool")).isEqualTo(root.resolve("tool"));
        assertThatThrownBy(() -> LauncherName.resolveChild(root, "../sentinel"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        LauncherName.resolveChild(root, tmp.resolve("sentinel").toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persistent_writers_reject_before_creating_roots(@TempDir Path tmp) {
        Path bin = tmp.resolve("bin");
        assertThatThrownBy(() -> AppLauncher.install(bin, tmp.resolve("jdk"), "../outside", "Main", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(bin).doesNotExist();
        assertThat(tmp.resolve("outside")).doesNotExist();
    }
}

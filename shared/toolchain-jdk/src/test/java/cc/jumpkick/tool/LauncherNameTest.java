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
    void rejects_the_names_jk_itself_keeps_in_bin() {
        assertThat(List.of(
                        "jk",
                        "JK",
                        "jkx",
                        "jk.exe",
                        "jkx.exe",
                        "jk.bat",
                        "jk.cmd",
                        "jk.old",
                        "jkx.old.exe",
                        "jk.exe.old",
                        "VERSION",
                        "version"))
                .allMatch(name -> LauncherName.validationError(name)
                        .filter(message -> message.contains("jk's own"))
                        .isPresent());
        // Near misses stay valid: only the exact stems are jk's.
        assertThat(List.of("jk2", "jkx-foo", "jkbuild", "my-jk", "versions"))
                .allMatch(name -> LauncherName.validationError(name).isEmpty());
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

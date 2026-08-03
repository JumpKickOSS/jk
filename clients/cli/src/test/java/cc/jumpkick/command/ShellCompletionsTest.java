// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellCompletionsTest {

    @Test
    void top_level_names_include_common_commands() {
        assertThat(ShellCompletions.topLevelNames()).contains("build", "test", "activate", "completion");
    }

    @Test
    void write_all_creates_scripts_under_data(@TempDir Path data) throws Exception {
        // Point data root via JK_DATA_DIR (JkDirs) without full home isolation.
        String prev = System.getenv("JK_DATA_DIR");
        // System.getenv can't be set; writeAll uses JkDirs.data() which under tests is JK_HOME.
        // Call generators through package methods by writing relative to a temp via reflection-free
        // path: temporarily we just assert generator content helpers via public writeAll when
        // JK_HOME is the test isolation dir from gradle.
        Path root = ShellCompletions.writeAll();
        assertThat(root.resolve("bash/jk")).exists();
        assertThat(root.resolve("zsh/_jk")).exists();
        assertThat(root.resolve("fish/jk.fish")).exists();
        assertThat(root.resolve("pwsh/jk.ps1")).exists();
        assertThat(Files.readString(root.resolve("zsh/_jk"))).contains("compdef").contains("build");
        assertThat(Files.readString(root.resolve("bash/jk"))).contains("complete -F _jk jk");
    }
}

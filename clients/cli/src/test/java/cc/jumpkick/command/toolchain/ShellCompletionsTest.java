// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CommandDispatch;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellCompletionsTest {

    @Test
    void top_level_names_include_common_commands() {
        assertThat(ShellCompletions.topLevelNames(CommandDispatch.commands()))
                .contains("build", "test", "activate", "completion", "results", "skill");
    }

    @Test
    void write_all_creates_scripts_under_data(@TempDir Path data) throws Exception {
        // jk.env.JK_STORE_DIR overlay (same seam as IsolatedRootsExtension): keep the write out
        // of the suite-shared JK_HOME so parallel workers never race on data/.
        String prev = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_STORE_DIR", data.toString());
        try {
            Path root = ShellCompletions.writeAll(CommandDispatch.commands());
            assertThat(root).startsWith(data);
            assertThat(root.resolve("bash/jk")).exists();
            assertThat(root.resolve("zsh/_jk")).exists();
            assertThat(root.resolve("fish/jk.fish")).exists();
            assertThat(root.resolve("pwsh/jk.ps1")).exists();
            assertThat(Files.readString(root.resolve("zsh/_jk")))
                    .contains("compdef")
                    .contains("build");
            assertThat(Files.readString(root.resolve("bash/jk"))).contains("complete -F _jk jk");
        } finally {
            if (prev == null) System.clearProperty("jk.env.JK_STORE_DIR");
            else System.setProperty("jk.env.JK_STORE_DIR", prev);
        }
    }
}

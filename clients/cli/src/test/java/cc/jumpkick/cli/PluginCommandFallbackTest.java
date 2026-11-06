// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.model.command.Exit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A command jk does not know, in a project, is offered to the plugins. When no plugin can be asked
 * — here no engine runs for the home — the normal help follows, except beside a config.toml that
 * does not parse: a pin there may declare the command, so the file is named with its position.
 */
class PluginCommandFallbackTest {

    @TempDir
    Path home;

    @TempDir
    Path project;

    @BeforeEach
    void isolateHome() throws Exception {
        System.setProperty("jk.env.JK_HOME", home.toString());
        Files.writeString(project.resolve("jk.toml"), """
                group = "g"
                name = "p"
                version = "1.0"
                java = 25
                """);
    }

    @Test
    void an_unanswered_command_beside_a_broken_config_toml_names_the_file_and_position() throws Exception {
        Path config = home.resolve("config.toml");
        Files.writeString(config, "[network\n");
        @Nullable Integer[] exit = new Integer[1];

        String err = Capture.stderr(() -> {
            exit[0] = CommandDispatch.tryPluginCommand(project, "frobnicate", List.of());
        });

        assertThat(exit[0]).isEqualTo(Exit.CONFIG);
        assertThat(err).contains("frobnicate").contains(config + ":1:");
    }

    @Test
    void an_unanswered_command_beside_a_sound_config_toml_falls_back_to_the_help() throws Exception {
        Files.writeString(home.resolve("config.toml"), "[network]\n");
        @Nullable Integer[] exit = new Integer[] {-1};

        String err = Capture.stderr(() -> {
            exit[0] = CommandDispatch.tryPluginCommand(project, "frobnicate", List.of());
        });

        assertThat(exit[0]).isNull();
        assertThat(err).isEmpty();
    }
}

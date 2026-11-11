// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.IsolatedHome;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.model.command.Exit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A command jk does not know, beside a {@code ~/.jk/config.toml} that does not parse, gets one
 * answer: the file's position and the configuration exit code. Run in the project (the lookup in the
 * working directory) and with {@code -C <dir>} from elsewhere, with an engine running, as it is
 * between commands in a shell.
 */
@Tag("integration")
@IsolatedHome
class PluginCommandBrokenConfigTest {

    private static final String ANSWER = "not a jk command, and the plugins that may declare it cannot be read: ";

    @ParameterizedTest
    @ValueSource(strings = {"deps", "info"})
    void an_unknown_command_names_the_broken_file_alike_in_the_project_and_with_dir(String command, @TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "p"
                version = "1.0"
                java = 25
                """);
        assertThat(run("engine", "start")).as("an engine is up").isZero();
        Path config = JkDirs.userConfigFile();
        Files.writeString(config, "\n[network\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        @Nullable Integer[] inProject = new Integer[1];
        String inProjectErr = Capture.stderr(() -> {
            inProject[0] = CommandDispatch.tryPluginCommand(dir, command, List.of());
        });
        int[] withDir = new int[1];
        Capture.Streams withDirConsole = Capture.both(() -> {
            withDir[0] = run(command, "-C", dir.toString());
        });

        assertThat(inProject[0]).as("in the project").isEqualTo(Exit.CONFIG);
        assertThat(inProjectErr).contains(ANSWER + config + ":");
        assertThat(withDir[0]).as("with -C").isEqualTo(Exit.CONFIG);
        assertThat(withDirConsole.out() + withDirConsole.err()).contains(ANSWER + config + ":");
    }
}

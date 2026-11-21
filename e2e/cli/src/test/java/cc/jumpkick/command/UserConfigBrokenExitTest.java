// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A {@code ~/.jk/config.toml} that does not parse fails every engine-hosted verb that reads a
 * {@code jk.toml}, whichever shape the engine answers in — a build's request-failed line, a read
 * verb's ack or report error — with the file's position and the configuration exit code.
 */
@Tag("integration")
@IsolatedHome
class UserConfigBrokenExitTest {

    @ParameterizedTest
    @ValueSource(strings = {"build", "deps", "info", "graph"})
    void a_hosted_verb_beside_a_broken_config_toml_exits_config_naming_the_position(String verb, @TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "p"
                version = "1.0"
                java = 25
                """);
        Path config = JkDirs.userConfigFile();
        Files.writeString(config, "\n[network\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        int[] exit = new int[1];
        Capture.Streams console = Capture.both(() -> {
            exit[0] = run(verb, "-C", dir.toString());
        });

        assertThat(exit[0]).as(verb + " exit").isEqualTo(Exit.CONFIG);
        assertThat(console.out() + console.err()).as(verb + " output").contains(config + ":");
    }
}

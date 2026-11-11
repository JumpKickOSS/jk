// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.process.CommandChannel;
import java.io.BufferedReader;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerCommandsTest {

    /**
     * Every user of a host can read a process's command line, so the token a worker proves itself
     * with travels in its environment; the command line names only the address.
     */
    @Test
    void theTokenTravelsInTheWorkersEnvironmentNotOnItsCommandLine() throws Exception {
        try (WorkerCommands commands = WorkerCommands.open()) {
            ProcessBuilder pb = new ProcessBuilder("java", "-jar", "worker.jar");
            pb.environment().clear();
            commands.attach(pb);

            String token = pb.environment().get(CommandChannel.TOKEN_ENV);
            assertThat(token).isNotBlank();
            assertThat(pb.command()).hasSize(4).noneMatch(arg -> arg.contains(token));
            assertThat(pb.command().getLast()).startsWith(CommandChannel.COMMANDS);

            try (BufferedReader worker =
                    CommandChannel.connect(List.of(pb.command().getLast()), token)) {
                assertThat(commands.connect(Duration.ofSeconds(10)))
                        .as("the environment's token is the one the channel takes")
                        .isTrue();
            }
        }
    }
}

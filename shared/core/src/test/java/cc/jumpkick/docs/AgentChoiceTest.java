// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The agents setting a create carries: read from an environment, and across the wire by name. */
class AgentChoiceTest {

    @Test
    void jk_agents_names_the_mode_and_the_calling_shell_names_its_agent() {
        AgentChoice choice = AgentChoice.from(Map.of("JK_AGENTS", "none", "CLAUDECODE", "1")::get);

        assertThat(choice.mode()).isEqualTo(AgentSetup.Mode.NONE);
        assertThat(choice.hosts()).containsExactly(AgentSetup.Host.CLAUDE);
        assertThat(AgentChoice.from(Map.<String, String>of()::get))
                .isEqualTo(new AgentChoice(AgentSetup.Mode.DETECT, Set.of()));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void an_agent_cli_on_path_is_detected(@TempDir Path bin) throws IOException {
        Path codex = Files.writeString(bin.resolve("codex"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(codex, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThat(AgentChoice.from(Map.of("PATH", bin.toString())::get).hosts())
                .containsExactly(AgentSetup.Host.CODEX);
    }

    @Test
    void the_names_parse_back_to_the_choice() {
        AgentChoice choice =
                new AgentChoice(AgentSetup.Mode.ALL, Set.of(AgentSetup.Host.GEMINI, AgentSetup.Host.CLAUDE));

        assertThat(choice.modeName()).isEqualTo("all");
        assertThat(choice.hostNames()).containsExactly("claude", "gemini");
        assertThat(AgentChoice.parse(choice.modeName(), choice.hostNames())).isEqualTo(choice);
        assertThat(AgentChoice.parse("detect", List.of("nobody")).hosts()).isEmpty();
    }
}

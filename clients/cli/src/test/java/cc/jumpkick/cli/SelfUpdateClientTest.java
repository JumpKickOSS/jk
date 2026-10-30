// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The client's half of self-update: who hands off to a newer install, and when a prompt may follow. */
class SelfUpdateClientTest {

    @Test
    void a_released_client_hands_off_only_to_a_newer_install() {
        assertThat(SupersededClient.newerInstall("0.16.2", true, () -> Optional.of("0.17.0")))
                .contains("0.17.0");
        assertThat(SupersededClient.newerInstall("0.17.0", true, () -> Optional.of("0.17.0")))
                .isEmpty();
        assertThat(SupersededClient.newerInstall("0.17.0", true, () -> Optional.of("0.16.2")))
                .isEmpty();
        assertThat(SupersededClient.newerInstall("0.16.2", true, () -> Optional.empty()))
                .isEmpty();
    }

    @Test
    void a_development_client_never_hands_off() {
        assertThat(SupersededClient.newerInstall("0.16.2-SNAPSHOT", true, () -> Optional.of("0.17.0")))
                .isEmpty();
        assertThat(SupersededClient.newerInstall("0.16.2", false, () -> Optional.of("0.17.0")))
                .isEmpty();
    }

    @Test
    void only_a_successful_human_command_is_followed_by_the_prompt() {
        assertThat(SelfUpdatePrompt.eligible("build", List.of("build"), 0)).isTrue();
        assertThat(SelfUpdatePrompt.eligible("build", List.of("build"), 1)).isFalse();
        assertThat(SelfUpdatePrompt.eligible(null, List.of("--version"), 0)).isFalse();
        for (String quiet : SelfUpdatePrompt.QUIET_COMMANDS) {
            assertThat(SelfUpdatePrompt.eligible(quiet, List.of(quiet), 0))
                    .as(quiet)
                    .isFalse();
        }
        for (String machine : List.of("--agent", "-q", "--json", "-Ojsonl", "--output=jsonl")) {
            assertThat(SelfUpdatePrompt.eligible("test", List.of("test", machine), 0))
                    .as(machine)
                    .isFalse();
        }
    }

    @Test
    void the_command_name_is_the_first_positional() {
        assertThat(Jk.commandName(new String[] {"-C", "dir", "build"})).isEqualTo("build");
        assertThat(Jk.commandName(new String[] {"--version"})).isNull();
    }
}

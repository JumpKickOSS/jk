// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.ClientUpdate;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.command.interop.McpCommand;
import cc.jumpkick.config.NerdFontCaps;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The client's half of self-update: who hands off to a newer install, and when a prompt may follow. */
class SelfUpdateClientTest {

    @Test
    void a_released_client_hands_off_only_to_a_newer_install() {
        assertThat(ClientUpdate.newerInstall("0.16.2", true, () -> Optional.of("0.17.0")))
                .contains("0.17.0");
        assertThat(ClientUpdate.newerInstall("0.17.0", true, () -> Optional.of("0.17.0")))
                .isEmpty();
        assertThat(ClientUpdate.newerInstall("0.17.0", true, () -> Optional.of("0.16.2")))
                .isEmpty();
        assertThat(ClientUpdate.newerInstall("0.16.2", true, () -> Optional.empty()))
                .isEmpty();
    }

    @Test
    void a_development_client_never_hands_off() {
        assertThat(ClientUpdate.newerInstall("0.16.2-SNAPSHOT", true, () -> Optional.of("0.17.0")))
                .isEmpty();
        assertThat(ClientUpdate.newerInstall("0.16.2", false, () -> Optional.of("0.17.0")))
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
    void the_updated_line_names_both_versions_within_78_columns() {
        var ctx = new RenderContext(Theme.active(), false, NerdFontCaps.NONE, 200, 0);
        assertThat(SelfUpdatePrompt.announcement(ctx, "0.50.3", "0.51.0"))
                .isEqualTo("jk: ^ Updated > JumpKick was updated to 0.51.0 (from 0.50.3)")
                .hasSizeLessThanOrEqualTo(78);
    }

    @Test
    void the_shell_hooks_are_never_followed_by_the_notice() {
        for (String hook : List.of("hook-env", "activate", "deactivate")) {
            assertThat(SelfUpdatePrompt.eligible(hook, List.of(hook, "-s", "zsh"), 0))
                    .as(hook)
                    .isFalse();
        }
    }

    @Test
    void jk_mcp_keeps_serving_through_the_newer_engine_an_update_installed() {
        Optional<String> installed = Optional.of("0.52.0");
        assertThat(McpCommand.servesNewerInstall("0.52.0", false, "0.51.0", installed))
                .isTrue();
        // Not when it drains, is not newer, or is not what the install names.
        assertThat(McpCommand.servesNewerInstall("0.52.0", true, "0.51.0", installed))
                .isFalse();
        assertThat(McpCommand.servesNewerInstall("0.51.0", false, "0.51.0", installed))
                .isFalse();
        assertThat(McpCommand.servesNewerInstall("0.53.0", false, "0.51.0", installed))
                .isFalse();
    }

    @Test
    void the_command_name_is_the_first_positional() {
        assertThat(Jk.commandName(new String[] {"-C", "dir", "build"})).isEqualTo("build");
        assertThat(Jk.commandName(new String[] {"--version"})).isNull();
    }
}

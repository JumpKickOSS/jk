// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.command.interop.GradleCommand;
import cc.jumpkick.command.interop.MvnCommand;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code --accept-unverified-tool} is one flag with one meaning on every verb that provisions a
 * build-tool distribution: {@code jk mvn}, {@code jk gradle} and {@code jk tool install}.
 */
class ToolInstallOptionsTest {

    @Test
    void tool_install_declares_the_flag_mvn_and_gradle_carry() {
        for (CliCommand verb : List.of(new ToolInstallCommand(), new MvnCommand(), new GradleCommand())) {
            assertThat(verb.options().stream().map(Opt::canonicalName))
                    .as(verb.name())
                    .contains(ToolRegistry.ACCEPT_FLAG.substring(2));
        }
    }

    @Test
    void the_flag_parses_on_a_build_tool_target() throws Exception {
        Invocation in = ArgParser.parse(new ToolInstallCommand(), List.of("maven:3.6.3", ToolRegistry.ACCEPT_FLAG));
        assertThat(in.positionals()).containsExactly("maven:3.6.3");
        assertThat(CommonOpts.acceptUnverifiedTool(in)).isTrue();
        assertThat(CommonOpts.acceptUnverifiedTool(ArgParser.parse(new ToolInstallCommand(), List.of("maven:3.6.3"))))
                .isFalse();
    }
}

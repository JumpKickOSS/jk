// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CommandModels;
import cc.jumpkick.cli.HelpRenderer;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.model.command.Invocation;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code jk dev} runs the app alone on request, and says so on its help screen. */
class DevCommandTest {

    @Test
    void no_sidecars_is_a_flag_on_dev_and_on_watch() throws Exception {
        Invocation dev = ArgParser.parse(new DevCommand(), List.of("--no-sidecars", "--", "--port=8080"));
        assertThat(dev.flag("no-sidecars")).contains(true);
        assertThat(dev.positionals()).containsExactly("--port=8080");
        Invocation watch = ArgParser.parse(new WatchCommand(), List.of("run", "--no-sidecars"));
        assertThat(watch.flag("no-sidecars")).contains(true);
    }

    @Test
    void the_help_screen_names_no_sidecars() {
        String help = HelpRenderer.renderHelp(CommandModels.from(new DevCommand(), "jk dev", List.of()), false);
        assertThat(help).contains("--no-sidecars").contains("[dev.sidecars]");
    }
}

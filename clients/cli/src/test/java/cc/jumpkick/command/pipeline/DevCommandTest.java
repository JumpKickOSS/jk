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

    /** Global options such as {@code --output} are parsed by dispatch, so the invocation is built as dispatch would. */
    @Test
    void every_option_on_dev_reaches_watch_run_unchanged() {
        Invocation dev = Invocation.builder()
                .putValue("output", "json")
                .flag("no-sidecars", true)
                .putValue("cache-dir", "/tmp/c")
                .addPositional("8080")
                .addPositional("--flag-for-app")
                .build();
        Invocation watch = DevCommand.asWatchRun(dev);
        assertThat(watch.positionals()).containsExactly("run", "8080", "--flag-for-app");
        assertThat(watch.value("output")).contains("json");
        assertThat(watch.value("cache-dir")).contains("/tmp/c");
        assertThat(watch.flag("no-sidecars")).contains(true);
    }

    @Test
    void the_help_screen_names_no_sidecars() {
        String help = HelpRenderer.renderHelp(CommandModels.from(new DevCommand(), "jk dev", List.of()), false);
        assertThat(help).contains("--no-sidecars").contains("[dev.sidecars]");
    }
}

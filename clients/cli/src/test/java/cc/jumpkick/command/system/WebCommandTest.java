// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CommandDispatch;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebCommandTest {

    @Test
    void the_printed_url_is_signed_only_when_it_may_be(@TempDir Path tmp) throws Exception {
        EnginePaths.Paths paths = paths(tmp);
        Files.createDirectories(paths.httpToken().getParent());
        Files.writeString(paths.httpToken(), "sekret\n");
        assertThat(WebCommand.printedUrl("http://127.0.0.1:8910/", true, paths))
                .isEqualTo("http://127.0.0.1:8910/#t=sekret");
        assertThat(WebCommand.printedUrl("http://127.0.0.1:8910/", false, paths))
                .as("off an interactive terminal the token is left out")
                .isEqualTo("http://127.0.0.1:8910/");
    }

    @Test
    void the_printed_url_falls_back_to_the_plain_url_without_a_token_file(@TempDir Path tmp) throws Exception {
        EnginePaths.Paths paths = paths(tmp);
        assertThat(WebCommand.printedUrl("http://127.0.0.1:8910/", true, paths)).isEqualTo("http://127.0.0.1:8910/");
    }

    @Test
    void the_connect_line_names_url_and_engine_and_the_token_only_when_asked() {
        String bare = WebCommand.connectJson("http://127.0.0.1:8911/", null, "204af7ec9480");
        assertThat(Jsonl.str(bare, "url")).isEqualTo("http://127.0.0.1:8911/");
        assertThat(Jsonl.str(bare, "engineId")).isEqualTo("204af7ec9480");
        assertThat(bare).doesNotContain("token");
        String asked = WebCommand.connectJson("http://127.0.0.1:8911/", "sekret", "204af7ec9480");
        assertThat(Jsonl.str(asked, "token")).isEqualTo("sekret");
        assertThat(Jsonl.str(WebCommand.connectJson("http://127.0.0.1:8910/", "", null), "engineId"))
                .isEmpty();
    }

    @Test
    void with_token_is_an_option_of_jk_web() {
        assertThat(new WebCommand().options().stream().flatMap(o -> o.names().stream()))
                .contains("--with-token");
    }

    private static EnginePaths.Paths paths(Path tmp) throws Exception {
        Path state = tmp.resolve("state");
        Path store = tmp.resolve("store");
        Files.createDirectories(state);
        Files.createDirectories(store);
        return EnginePaths.resolve(state, store);
    }

    @Test
    void command_is_registered() {
        assertThat(CommandDispatch.commands().stream().map(c -> c.name())).contains("web");
    }
}

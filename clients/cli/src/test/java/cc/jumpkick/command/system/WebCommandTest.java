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
    void tokenizedUrl_appends_fragment_when_token_file_is_readable(@TempDir Path tmp) throws Exception {
        Path state = tmp.resolve("state");
        Path store = tmp.resolve("store");
        Files.createDirectories(state);
        Files.createDirectories(store);
        EnginePaths.Paths paths = EnginePaths.resolve(state, store);
        Files.createDirectories(paths.httpToken().getParent());
        Files.writeString(paths.httpToken(), "sekret\n");
        assertThat(WebCommand.tokenizedUrl("http://127.0.0.1:8910/", paths))
                .isEqualTo("http://127.0.0.1:8910/#t=sekret");
    }

    @Test
    void tokenizedUrl_falls_back_to_plain_url_without_token_file(@TempDir Path tmp) throws Exception {
        Path state = tmp.resolve("state");
        Path store = tmp.resolve("store");
        Files.createDirectories(state);
        Files.createDirectories(store);
        EnginePaths.Paths paths = EnginePaths.resolve(state, store);
        assertThat(WebCommand.tokenizedUrl("http://127.0.0.1:8910/", paths)).isEqualTo("http://127.0.0.1:8910/");
    }

    @Test
    void connectJson_names_url_token_and_engine() {
        String line = WebCommand.connectJson("http://127.0.0.1:8911/", "sekret", "204af7ec9480");
        assertThat(Jsonl.str(line, "url")).isEqualTo("http://127.0.0.1:8911/");
        assertThat(Jsonl.str(line, "token")).isEqualTo("sekret");
        assertThat(Jsonl.str(line, "engineId")).isEqualTo("204af7ec9480");
        assertThat(Jsonl.str(WebCommand.connectJson("http://127.0.0.1:8910/", "", null), "engineId"))
                .isEmpty();
    }

    @Test
    void command_is_registered() {
        assertThat(CommandDispatch.commands().stream().map(c -> c.name())).contains("web");
    }
}

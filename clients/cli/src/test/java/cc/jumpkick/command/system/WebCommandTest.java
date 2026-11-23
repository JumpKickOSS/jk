// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CommandDispatch;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.engine.ScriptedEngine;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class WebCommandTest {

    private static final EngineProbe.Patience QUICK = new EngineProbe.Patience(200, 2, Duration.ofMillis(20));

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkw-");

    @Test
    void an_engine_that_answers_after_one_silent_probe_still_gives_its_dashboard() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        try (ScriptedEngine ignored = new ScriptedEngine(socket, (type, connection) -> {
            if (connection == 1) return null;
            return EngineProtocol.HELLO.equals(type)
                    ? ScriptedEngine.helloAck("9.9.9", 4242)
                    : ScriptedEngine.statusAck("9.9.9", 4242, 0, false);
        })) {
            assertThat(WebCommand.answer(socket, QUICK))
                    .as("a loaded engine's late answer is waited for")
                    .extracting(EngineProbe.Status::pid)
                    .isEqualTo(4242L);
        }
    }

    @Test
    void nothing_listening_has_no_answer() throws Exception {
        assertThat(WebCommand.answer(tempDirs.create().resolve("gen1.sock"), QUICK))
                .isNull();
    }

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

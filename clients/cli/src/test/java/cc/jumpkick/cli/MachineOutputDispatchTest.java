// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.command.pipeline.GuardCommand;
import cc.jumpkick.command.toolchain.TrustCommand;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code -O} is validated per command: a command with no machine output refuses {@code -O jsonl}
 * naming what it prints, an unknown format is refused everywhere, and a command with machine output
 * writes only JSON lines to stdout, its human lines on stderr.
 */
class MachineOutputDispatchTest {

    @TempDir
    Path tmp;

    private record Run(int exit, Capture.Streams streams) {}

    private static Run run(String... args) throws Exception {
        int[] exit = new int[1];
        Capture.Streams streams = NoAnsi.forced(() -> Capture.both(() -> exit[0] = Jk.execute(args)));
        return new Run(exit[0], streams);
    }

    @Test
    void a_command_with_no_machine_output_refuses_jsonl_and_says_what_it_prints() throws Exception {
        Run refused = run("trust", "list", "-O", "jsonl");
        assertThat(refused.exit()).isEqualTo(Exit.USAGE);
        assertThat(refused.streams().out()).isEmpty();
        assertThat(refused.streams().err()).contains("jk trust list has no -O jsonl output; it prints text only");
    }

    @Test
    void a_format_no_command_prints_is_refused() throws Exception {
        Run xml = run("tool", "list", "-O", "xml");
        assertThat(xml.exit()).isEqualTo(Exit.USAGE);
        assertThat(xml.streams().err()).contains("has no -O xml output; it prints text, json or jsonl");
        Invocation sarif = Invocation.builder().putValue("output", "sarif").build();
        assertThat(CommandDispatch.refusedOutputFormat(new GuardCommand(), sarif))
                .as("a document format the command declares is accepted")
                .isNull();
        assertThat(CommandDispatch.refusedOutputFormat(new TrustCommand(), sarif))
                .isEqualTo("sarif");
    }

    @Test
    void tool_list_under_jsonl_prints_only_envelope_rows() throws Exception {
        Path state = tmp.resolve("state");
        Path env = Files.createDirectories(JkDirs.toolEnvsDir(state).resolve("hello"));
        Files.writeString(
                env.resolve("env.json"), "{\"primary\":\"g:hello:1.0\",\"kind\":\"maven\",\"spec\":\"g:hello\"}");
        Run json = run(
                "tool",
                "list",
                "--state-dir",
                state.toString(),
                "--bin-dir",
                tmp.resolve("bin").toString(),
                "-O",
                "jsonl");
        assertThat(json.exit()).isZero();
        boolean sawTool = false;
        for (String line : json.streams().out().split("\n")) {
            if (line.isBlank()) continue;
            if (!(MiniJson.parse(line) instanceof Map<?, ?> row)) {
                throw new AssertionError("stdout line is not a JSON object: " + line);
            }
            assertThat(row.containsKey("schema") && row.containsKey("ts") && row.containsKey("type"))
                    .as("envelope of %s", line)
                    .isTrue();
            if ("tool".equals(row.get("type"))) {
                sawTool = true;
                assertThat(row.get("tool")).isEqualTo("hello");
                assertThat(row.get("coordinates")).isEqualTo("g:hello:1.0");
            }
        }
        assertThat(sawTool).as("the installed tool's row").isTrue();
    }

    @Test
    void under_json_a_human_line_goes_to_stderr() {
        Capture.Streams streams = Capture.both(() -> {
            CliOutput.beginCommand(true, true);
            try {
                CliOutput.out("Installed g:a:1 to the local store");
                CliOutput.machine("{\"schema\":1,\"ts\":1,\"type\":\"x\"}");
            } finally {
                CliOutput.beginCommand(false);
            }
        });
        assertThat(streams.out()).isEqualTo("{\"schema\":1,\"ts\":1,\"type\":\"x\"}\n");
        assertThat(streams.err()).contains("Installed g:a:1 to the local store");
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CommandDispatch.Split;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.command.toolchain.NodeExecCommand;
import cc.jumpkick.command.toolchain.NodePassthroughCommand;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code jk npx <args…>} and {@code jk npm <args…>} are {@code jk node exec -- <program> <args…>},
 * every token after the name the program's.
 */
class NodePassthroughArgsTest {

    @TempDir
    Path dir;

    private static NodePassthroughCommand command(String program) {
        return program.equals("npm") ? NodePassthroughCommand.npm() : NodePassthroughCommand.npx();
    }

    private static NodeExecCommand.Launch passthrough(String program, List<String> leading, String... rest)
            throws Exception {
        NodePassthroughCommand cmd = command(program);
        return cmd.launch(CommandDispatch.parsePassthrough(cmd, new Split(leading, List.of(rest))));
    }

    private static NodeExecCommand.Launch nodeExec(List<String> tokens) throws Exception {
        return NodeExecCommand.launch(ArgParser.parse(CommandDispatch.withGlobals(new NodeExecCommand()), tokens));
    }

    @ParameterizedTest
    @ValueSource(strings = {"npx", "npm"})
    void is_node_exec_of_the_program(String program) throws Exception {
        List<String> args = List.of("-y", "create-foo@latest", "my-app");
        List<String> exec = new ArrayList<>(List.of("-C", dir.toString(), "--", program));
        exec.addAll(args);
        List<String> argv = new ArrayList<>(List.of(program));
        argv.addAll(args);

        assertThat(passthrough(program, List.of("-C", dir.toString()), args.toArray(String[]::new)))
                .isEqualTo(nodeExec(exec))
                .isEqualTo(new NodeExecCommand.Launch(dir.toAbsolutePath().normalize(), null, argv));
    }

    @ParameterizedTest
    @ValueSource(strings = {"npx", "npm"})
    void every_token_after_the_name_is_the_programs(String program) throws Exception {
        NodeExecCommand.Launch l =
                passthrough(program, List.of(), "-h", "--help", "-q", "-C", "elsewhere", "-n", "20", "--node", "x");

        assertThat(l.argv())
                .containsExactly(program, "-h", "--help", "-q", "-C", "elsewhere", "-n", "20", "--node", "x");
        assertThat(l.node()).isNull();
        assertThat(l.dir()).isEqualTo(Path.of("").toAbsolutePath().normalize());
    }

    @Test
    void both_are_listed_passthroughs() {
        assertThat(CommandDispatch.commands())
                .filteredOn(c -> c.name().equals("npx") || c.name().equals("npm"))
                .hasSize(2)
                .allSatisfy(c -> assertThat(c.passthrough()).isTrue());
    }
}

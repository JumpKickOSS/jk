// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CommandDispatch.Split;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.command.toolchain.NodeExecCommand;
import cc.jumpkick.command.toolchain.NpxCommand;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk npx <args…>} is {@code jk node exec -- npx <args…>}, every token after the name npx's. */
class NpxArgsTest {

    @TempDir
    Path dir;

    private static NodeExecCommand.Launch npx(List<String> leading, String... rest) throws Exception {
        return NpxCommand.launch(CommandDispatch.parsePassthrough(new NpxCommand(), new Split(leading, List.of(rest))));
    }

    private static NodeExecCommand.Launch nodeExec(String... tokens) throws Exception {
        return NodeExecCommand.launch(
                ArgParser.parse(CommandDispatch.withGlobals(new NodeExecCommand()), List.of(tokens)));
    }

    @Test
    void is_node_exec_of_npx() throws Exception {
        List<String> exec = new ArrayList<>(List.of("-C", dir.toString(), "--", "npx"));
        exec.addAll(List.of("-y", "create-foo@latest", "my-app"));

        assertThat(npx(List.of("-C", dir.toString()), "-y", "create-foo@latest", "my-app"))
                .isEqualTo(nodeExec(exec.toArray(String[]::new)))
                .isEqualTo(new NodeExecCommand.Launch(
                        dir.toAbsolutePath().normalize(), null, List.of("npx", "-y", "create-foo@latest", "my-app")));
    }

    @Test
    void every_token_after_the_name_is_npxs() throws Exception {
        NodeExecCommand.Launch l = npx(List.of(), "-h", "--help", "-q", "-C", "elsewhere", "-n", "20", "--node", "x");

        assertThat(l.argv()).containsExactly("npx", "-h", "--help", "-q", "-C", "elsewhere", "-n", "20", "--node", "x");
        assertThat(l.node()).isNull();
        assertThat(l.dir()).isEqualTo(Path.of("").toAbsolutePath().normalize());
    }

    @Test
    void is_a_listed_passthrough() {
        assertThat(CommandDispatch.commands())
                .filteredOn(c -> c.name().equals("npx"))
                .singleElement()
                .satisfies(c -> assertThat(c.passthrough()).isTrue());
    }
}

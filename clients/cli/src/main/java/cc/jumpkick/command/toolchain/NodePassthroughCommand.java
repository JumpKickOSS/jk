// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk npx <args…>} and {@code jk npm <args…>}: {@code jk node exec -- <program> <args…>}, the
 * program under the project's Node.js on jk's npm cache. Every token after the name is the program's.
 */
public final class NodePassthroughCommand implements CliCommand {

    private final String program;

    private NodePassthroughCommand(String program) {
        this.program = program;
    }

    /** {@code jk npx}. */
    public static NodePassthroughCommand npx() {
        return new NodePassthroughCommand("npx");
    }

    /** {@code jk npm}: npm whatever package manager the project uses. */
    public static NodePassthroughCommand npm() {
        return new NodePassthroughCommand("npm");
    }

    @Override
    public String name() {
        return program;
    }

    @Override
    public String description() {
        return "Run " + program + " under the project's Node.js (jk --help " + program + ")";
    }

    @Override
    public boolean passthrough() {
        return true;
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to " + program));
    }

    @Override
    public int run(Invocation in) throws Exception {
        return NodeExecCommand.run(launch(in));
    }

    /** The {@code jk node exec} launch this invocation is. */
    public NodeExecCommand.Launch launch(Invocation in) {
        List<String> argv = new ArrayList<>();
        argv.add(program);
        argv.addAll(in.positionals());
        return new NodeExecCommand.Launch(GlobalOptions.from(in).workingDir(), null, argv);
    }
}

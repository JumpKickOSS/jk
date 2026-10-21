// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk npx <args…>} — {@code jk node exec -- npx <args…>}: npx under the project's Node.js, on
 * jk's npm cache. Every token after the name is npx's.
 */
public final class NpxCommand implements CliCommand {

    @Override
    public String name() {
        return "npx";
    }

    @Override
    public String description() {
        return "Run npx under the project's Node.js (jk --help npx)";
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
        return List.of(Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to npx"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        return NodeExecCommand.run(launch(in));
    }

    /** The {@code jk node exec} launch this invocation is. */
    public static NodeExecCommand.Launch launch(Invocation in) {
        Path dir = GlobalOptions.from(in).workingDir();
        List<String> argv = new ArrayList<>();
        argv.add("npx");
        argv.addAll(in.positionals());
        return new NodeExecCommand.Launch(dir, null, argv);
    }
}

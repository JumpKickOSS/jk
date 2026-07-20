// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk inspect &lt;step&gt;} — describe a pipeline step (JK-1047). Equivalent to {@code jk
 * tasks inspect &lt;step&gt;}.
 */
public final class InspectCommand implements CliCommand {

    @Override
    public String name() {
        return "inspect";
    }

    @Override
    public String description() {
        return "Describe a pipeline step (phase, output path, on-disk status)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<sel>", "Module selector.", "--modules"),
                Opt.value("<git-ref>", "Intersect with modules changed since ref.", "--affected-since"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("step", Arity.ONE, "Step name (compile-java, package-jar, …)"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path startDir = global.workingDir();
        var proj = ProjectContext.require(startDir, "inspect").orElse(null);
        if (proj == null) return Exit.CONFIG;
        if (in.positionals().isEmpty()) {
            CliOutput.err("jk inspect: expected a step name (try `jk tasks`)");
            return Exit.USAGE;
        }
        try {
            return TasksCommand.showOrInspect(
                    "inspect", in.positionals().getFirst(), in, startDir, proj.buildFile());
        } catch (IllegalStateException e) {
            CliOutput.err("jk inspect: " + e.getMessage());
            return Exit.CONFIG;
        }
    }
}
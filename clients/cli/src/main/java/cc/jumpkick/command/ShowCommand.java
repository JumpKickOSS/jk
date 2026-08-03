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
 * {@code jk show &lt;step&gt;} — print primary output path(s) for a pipeline step.
 * Equivalent to {@code jk tasks show &lt;step&gt;}.
 */
public final class ShowCommand implements CliCommand {

    @Override
    public String name() {
        return "show";
    }

    @Override
    public String description() {
        return "Print primary output path for a pipeline step";
    }

    @Override
    public List<Opt> options() {
        var opts = new java.util.ArrayList<Opt>();
        opts.addAll(cc.jumpkick.cli.CommonOpts.moduleSelection());
        return opts;
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("step", Arity.ONE, "Step name (package-jar, compile-java, …)"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path startDir = global.workingDir();
        var proj = ProjectContext.require(startDir, "show").orElse(null);
        if (proj == null) return Exit.CONFIG;
        if (in.positionals().isEmpty()) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Show", "expected a step name (try `jk tasks`)"));
            return Exit.USAGE;
        }
        try {
            return TasksCommand.showOrInspect("show", in.positionals().getFirst(), in, startDir, proj.buildFile());
        } catch (IllegalStateException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Show", e.getMessage()));
            return Exit.CONFIG;
        }
    }
}

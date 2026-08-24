// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk inspect &lt;task&gt;} — describe a plan task. Equivalent to {@code jk
 * tasks inspect &lt;task&gt;}.
 */
public final class InspectCommand implements CliCommand {

    @Override
    public String name() {
        return "inspect";
    }

    @Override
    public String description() {
        return "Describe a plan task (path and on-disk status)";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<Opt>();
        opts.addAll(CommonOpts.moduleSelection());
        opts.add(CommonOpts.cacheDirHidden());
        return opts;
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("task", Arity.ONE, "Task name (compile-java, package-jar, …)"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path startDir = global.workingDir();
        var proj = ProjectContext.require(startDir, "inspect").orElse(null);
        if (proj == null) return Exit.CONFIG;
        if (in.positionals().isEmpty()) {
            CommandWedge.printFail("Inspect", "expected a task name (try `jk tasks`)");
            return Exit.USAGE;
        }
        try {
            return TasksCommand.showOrInspect("inspect", in.positionals().getFirst(), in, startDir, proj.buildFile());
        } catch (IllegalStateException e) {
            CommandWedge.printFail("Inspect", e.getMessage());
            return Exit.CONFIG;
        }
    }
}

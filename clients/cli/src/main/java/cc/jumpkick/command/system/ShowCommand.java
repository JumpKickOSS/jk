// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.ProjectContext;
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
 * {@code jk show &lt;task&gt;} — print primary output path(s) for a plan task.
 * Equivalent to {@code jk tasks show &lt;task&gt;}.
 */
public final class ShowCommand implements CliCommand {

    @Override
    public String name() {
        return "show";
    }

    @Override
    public String description() {
        return "Print primary output path for a build-plan task";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<Opt>();
        opts.addAll(CommonOpts.moduleSelection());
        return opts;
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("task", Arity.ONE, "Task name (package-jar, compile-java, …)"));
    }

    /** Always the path-printing form of {@code jk tasks show}: one path per line for command substitution. */
    @Override
    public boolean scriptMode(Invocation in) {
        return true;
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path startDir = global.workingDir();
        var proj = ProjectContext.require(startDir, "show").orElse(null);
        if (proj == null) return Exit.CONFIG;
        if (in.positionals().isEmpty()) {
            CommandWedge.printFail("Show", "expected a task name (try `jk tasks`)");
            return Exit.USAGE;
        }
        try {
            return TasksCommand.showOrInspect("show", in.positionals().getFirst(), in, startDir, proj.buildFile());
        } catch (IllegalStateException e) {
            CommandWedge.printFail("Show", e.getMessage());
            return Exit.CONFIG;
        }
    }
}

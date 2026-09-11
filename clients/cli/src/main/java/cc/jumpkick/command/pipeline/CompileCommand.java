// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.ProjectContext;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.command.ModuleSelectors;
import cc.jumpkick.command.VariantSelection;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk compile} — lock, sync, then compile this project's sources to {@code target/classes}
 * (no resources, tests, or packaging). It runs the shared engine plan in compile-only
 * mode, so it auto-locks and syncs on first run, re-locks when {@code jk.toml} changed, and reuses
 * the same incremental compile cache as {@code jk build}/{@code jk test}. A workspace root or
 * member compiles through the workspace orchestrator; see {@link PlanRun}.
 */
public final class CompileCommand implements CliCommand {

    @Override
    public String name() {
        return "compile";
    }

    @Override
    public String description() {
        return "Compile this project's source code";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<Opt>();
        opts.add(Opt.value("<name>", "Build profile (default auto)", "--profile"));
        opts.add(CommonOpts.cacheDir());
        opts.addAll(CommonOpts.moduleSelection());
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        String profileName = in.value("profile").orElse(null);
        Path cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        VariantSelection.install(in, dir);
        var proj = ProjectContext.require(dir, "compile").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        String modulesSpec = in.value("modules").orElse(null);
        String affectedSince = in.value("affected-since").orElse(null);
        boolean affectedWip = in.isSet("affected");
        if (ModuleSelectors.bothSelectors(affectedWip, affectedSince)) {
            CommandWedge.printFail("Compile", ModuleSelectors.BOTH_MESSAGE);
            return Exit.CONFIG;
        }
        return PlanRun.resolve(dir, modulesSpec, affectedSince, affectedWip, PlanRun.compile(profileName))
                .run(PlanRun.Labels.COMPILE, global, cache);
    }
}

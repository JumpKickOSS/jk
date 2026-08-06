// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk compile} — lock, sync, then compile this project's sources to {@code target/classes}
 * (no resources, tests, or packaging). It runs the shared engine plan in compile-only
 * mode, so it auto-locks and syncs on first run, re-locks when {@code jk.toml} changed, and reuses
 * the same incremental compile cache as {@code jk build}/{@code jk test}.
 *
 * <p>Was {@code jk check} pre-v1.0; {@code check} remains a hidden alias (see {@code
 * docs/aliases.md}).
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
        var opts = new java.util.ArrayList<Opt>();
        opts.add(Opt.value("<name>", "Build profile (default auto)", "--profile"));
        opts.add(cc.jumpkick.cli.CommonOpts.cacheDir());
        opts.addAll(cc.jumpkick.cli.CommonOpts.moduleSelection());
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        String profileName = in.value("profile").orElse(null);
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        VariantSelection.install(in, dir);
        var proj = ProjectContext.require(dir, "compile").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path buildFile = proj.buildFile();
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        // -m/--modules / --affected-since: compile each selected module (validated — JK-1360).
        List<Path> dirs = List.of(dir);
        String modulesSpec = in.value("modules").orElse(null);
        String affectedSince = in.value("affected-since").orElse(null);
        if ((modulesSpec != null && !modulesSpec.isBlank()) || (affectedSince != null && !affectedSince.isBlank())) {
            cc.jumpkick.model.JkBuild entry = cc.jumpkick.config.JkBuildParser.parse(buildFile);
            var selected = cc.jumpkick.config.ModuleSelection.resolveOptional(dir, entry, modulesSpec, affectedSince);
            if (selected != null && !selected.ok()) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Compile", selected.errorMessage()));
                return Exit.CONFIG;
            }
            if (selected != null) {
                if (selected.moduleDirs().isEmpty()) {
                    cc.jumpkick.cli.tui.CommandWedge.printOk("Compile", "nothing selected to compile");
                    return 0;
                }
                dirs = List.copyOf(selected.moduleDirs());
            }
        }

        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        // Engine-hosted: same plan as CompilePlans; listener chosen when the step list
        // arrives over the socket.
        var session = cc.jumpkick.config.SessionContext.current();
        for (Path moduleDir : dirs) {
            ConsoleSpec spec = new ConsoleSpec(
                    "Compile", r -> Theme.colorize("Compiled", Theme.active().focused()), r -> "Compilation failed");
            String target = BuildCommand.buildTarget(moduleDir.resolve("jk.toml"), moduleDir);
            BuildPlanResult result;
            try {
                result = cc.jumpkick.cli.engine.EngineClient.runCompile(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineClient.CompileRequest(
                                moduleDir, cache, profileName, session.offline(), session.force(), global.verbose),
                        steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, target));
            } catch (IOException e) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Compile", e.getMessage()));
                return Exit.SOFTWARE;
            }
            if (!result.success()) return 1;
        }
        return 0;
    }
}

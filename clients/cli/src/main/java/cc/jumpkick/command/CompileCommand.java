// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk compile} — lock, sync, then compile this project's sources to {@code target/classes}
 * (no resources, tests, or packaging). It runs the shared engine pipeline in compile-only
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
        var opts = new java.util.ArrayList<Opt>(List.of(
                Opt.value("<name>", "Apply a build profile. Default: auto (ci on CI).", "--profile"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide()));
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

        ConsoleSpec spec = new ConsoleSpec(
                "Compile", r -> Theme.colorize("Compiled", Theme.active().focused()), r -> "Compilation failed");
        String target = BuildCommand.buildTarget(buildFile, dir);
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
        PipelineResult result;
                // Engine-hosted: same pipeline as CompilePipelines; listener chosen when the step list
        // arrives over the socket.
        var session = cc.jumpkick.config.SessionContext.current();
        try {
            result = cc.jumpkick.cli.engine.EngineClient.runCompile(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineClient.CompileRequest(
                            dir, cache, profileName, session.offline(), session.force(), global.verbose),
                    steps -> PipelineConsole.chooseConsoleListener(steps, mode, spec, target));
        } catch (IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Compile", e.getMessage()));
            return Exit.SOFTWARE;
        }

        return result.success() ? 0 : 1;
    }
}

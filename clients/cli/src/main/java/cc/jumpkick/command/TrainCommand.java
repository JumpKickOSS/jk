// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk train} — observe a full-app run under the Graal tracing agent, write
 * {@code target/train/} (dynamic surface + reachability metadata, optional AOT cache). Never part
 * of default {@code jk build}.
 */
public final class TrainCommand implements CliCommand {

    @Override
    public String name() {
        return "train";
    }

    @Override
    public String description() {
        return "Train reachability metadata and an optional AOT cache";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<Opt>();
        opts.add(Opt.value("<name>", "Train profile name (default: all profiles)", "--profile"));
        opts.add(cc.jumpkick.cli.CommonOpts.cacheDir());
        opts.add(Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                .hide());
        opts.add(cc.jumpkick.cli.CommonOpts.skipTests());
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        String profile = in.value("profile").orElse(null);
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        VariantSelection.install(in, dir);
        var proj = ProjectContext.require(dir, "train").orElse(null);
        if (proj == null) return Exit.CONFIG;

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        var session = cc.jumpkick.config.SessionContext.current();
        boolean force = session.force() || global.force;

        // Prefer a Graal home so the tracing agent is available; fall back to null and let the
        // engine error with a clear message.
        Path graalHome = null;
        try {
            var graal = new cc.jumpkick.cli.GraalResolver(jdksDir, global.yes);
            graalHome = graal.resolve(dir, null).orElse(null);
        } catch (Exception ignored) {
            // train will fail clearly if the agent is missing
        }

        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        ConsoleSpec spec = new ConsoleSpec(
                "Train", r -> Theme.colorize("Trained", Theme.active().focused()), r -> "Train failed");
        String target = BuildCommand.buildTarget(proj.buildFile(), dir);
        BuildPlanResult result;
        try {
            result = cc.jumpkick.cli.engine.EngineClient.runTrain(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineRequests.TrainRequest(
                            dir,
                            cache,
                            jdksDir,
                            graalHome,
                            profile,
                            force,
                            in.isSet("skip-tests"),
                            session.offline(),
                            global.verbose),
                    steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, target));
        } catch (IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Train", e.getMessage()));
            return Exit.SOFTWARE;
        }
        return result.success() ? 0 : 1;
    }
}

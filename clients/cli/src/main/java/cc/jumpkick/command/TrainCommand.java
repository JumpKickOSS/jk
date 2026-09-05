// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.GraalResolver;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
        opts.add(CommonOpts.cacheDir());
        opts.add(CommonOpts.jdksDir());
        opts.add(CommonOpts.skipTests());
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Override
    public @Nullable int run(Invocation in) throws IOException, InterruptedException {
        String profile = in.value("profile").orElse(null);
        Path cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        Path jdksDir = CommonOpts.jdksDirValue(in);
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        VariantSelection.install(in, dir);
        var proj = ProjectContext.require(dir, "train").orElse(null);
        if (proj == null) return Exit.CONFIG;

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        var session = SessionContext.current();
        boolean force = session.force() || global.force;

        // Prefer a Graal home so the tracing agent is available; fall back to null and let the
        // engine error with a clear message.
        Path graalHome = null;
        try {
            var graal = new GraalResolver(jdksDir, global.yes);
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
            result = EngineClient.runTrain(
                    EnginePaths.current(),
                    new EngineRequests.TrainRequest(
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
            CommandWedge.printFail("Train", e.getMessage());
            return Exit.SOFTWARE;
        }
        return result.success() ? 0 : 1;
    }
}

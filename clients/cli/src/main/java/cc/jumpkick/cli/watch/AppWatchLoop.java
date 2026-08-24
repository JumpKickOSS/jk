// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.command.BuildCommand;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.ExecPlan;
import cc.jumpkick.engine.protocol.PluginCommandReport;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.terminal.Terminals;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;

/**
 * {@code jk watch run} / {@code jk dev}: build, start the app from classes, recompile on change.
 * Boot DevTools hot-restart when present; otherwise process restart. Android device deploy path
 * when the engine returns a deploy command.
 */
@RequiredArgsConstructor
public final class AppWatchLoop {

    private final GlobalOptions global;
    private final Path jdksDir;
    private final String logPrefix;

    public int run(Path projectDir, Path cache, List<String> appArgs) throws IOException, InterruptedException {
        if (!build(projectDir, cache)) return 1;

        ExecPlan plan = devPlan(projectDir, cache);
        if (plan.error() != null) {
            CliOutput.err(logPrefix + ": " + plan.error());
            return Exit.SOFTWARE;
        }
        if (!plan.deployCommand().isEmpty()) {
            return deviceLoop(projectDir, cache, plan, appArgs);
        }

        boolean devtools = plan.hotReload();
        if (plan.devtoolsInjected()) {
            CliOutput.err(logPrefix + ": spring-boot-devtools auto-injected for this session"
                    + " — add it to [dev-dependencies] to make it permanent.");
        }
        List<Path> watchRoots = rootsFromPlan(plan);
        logWatching(projectDir, watchRoots, devtools ? "DevTools hot-restart" : "process restart on change");

        Process app = startApp(plan, appArgs);
        try (SourceWatch watch = SourceWatch.open(projectDir, watchRoots)) {
            while (true) {
                Optional<SourceWatch.Changes> maybe = watch.pollChange(500, TimeUnit.MILLISECONDS);
                if (maybe.isEmpty()) {
                    if (!app.isAlive()) {
                        int exit = app.exitValue();
                        CliOutput.err(logPrefix + ": app exited with code " + exit + " — stopping.");
                        return exit;
                    }
                    continue;
                }
                SourceWatch.Changes changes = maybe.get();
                if (changes.manifest()) {
                    CliOutput.err(logPrefix + ": jk.toml changed — full rebuild + restart");
                    if (build(projectDir, cache)) {
                        plan = devPlan(projectDir, cache);
                        if (plan.error() != null) {
                            CliOutput.err(logPrefix + ": " + plan.error());
                            return Exit.SOFTWARE;
                        }
                        devtools = plan.hotReload();
                        app = restartApp(app, plan, appArgs);
                    }
                    continue;
                }
                boolean ok = changes.resources() ? build(projectDir, cache) : compile(projectDir, cache);
                if (!ok) {
                    CliOutput.err(logPrefix + ": build failed — app keeps running the last good code.");
                    continue;
                }
                if (devtools) {
                    CliOutput.err(logPrefix + ": recompiled — DevTools restarts the context.");
                } else {
                    app = restartApp(app, plan, appArgs);
                }
            }
        } finally {
            if (app.isAlive()) {
                app.destroy();
                if (!app.waitFor(5, TimeUnit.SECONDS)) app.destroyForcibly();
            }
        }
    }

    private int deviceLoop(Path projectDir, Path cache, ExecPlan plan, List<String> appArgs)
            throws IOException, InterruptedException {
        List<Path> watchRoots = rootsFromPlan(plan);
        logWatching(projectDir, watchRoots, "redeploy to device on change");
        int deployed = deploy(projectDir, cache, plan.deployCommand(), appArgs);
        if (deployed != 0) return deployed;

        try (SourceWatch watch = SourceWatch.open(projectDir, watchRoots)) {
            while (true) {
                SourceWatch.Changes changes = watch.awaitChange();
                if (changes.manifest()) {
                    CliOutput.err(logPrefix + ": jk.toml changed — full rebuild + redeploy");
                }
                if (!build(projectDir, cache)) {
                    CliOutput.err(logPrefix + ": build failed — the device keeps the last good install.");
                    continue;
                }
                int exit = deploy(projectDir, cache, plan.deployCommand(), appArgs);
                if (exit != 0) {
                    CliOutput.err(logPrefix + ": deploy failed (exit " + exit + ") — will retry on change.");
                }
            }
        }
    }

    private void logWatching(Path projectDir, List<Path> watchRoots, String mode) {
        CliOutput.err(logPrefix + ": watching "
                + watchRoots.stream()
                        .map(r -> {
                            try {
                                return projectDir.relativize(r).toString();
                            } catch (IllegalArgumentException e) {
                                return r.toString();
                            }
                        })
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("src")
                + " — " + mode + ". Ctrl-C stops.");
    }

    private static List<Path> rootsFromPlan(ExecPlan plan) {
        List<Path> watchRoots = new ArrayList<>();
        for (String root : plan.watchRoots()) watchRoots.add(Path.of(root));
        return watchRoots;
    }

    private int deploy(Path projectDir, Path cache, String command, List<String> appArgs) {
        PluginCommandReport report;
        try {
            report = EngineClient.pluginCommand(EnginePaths.current(), projectDir, cache, command, appArgs);
        } catch (Exception e) {
            CliOutput.err(logPrefix + ": " + e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!report.found()) {
            CliOutput.err(logPrefix + ": the packaging plugin declares deploy command `" + command
                    + "` but does not register it");
            return Exit.SOFTWARE;
        }
        if (report.error() != null) {
            CliOutput.err(logPrefix + ": " + report.error());
            return 1;
        }
        for (String line : report.output()) CliOutput.out(line);
        return report.exit();
    }

    private boolean build(Path projectDir, Path cache) throws IOException, InterruptedException {
        String target = BuildCommand.buildTarget(projectDir.resolve(ManifestPaths.MANIFEST), projectDir);
        ConsoleSpec spec = new ConsoleSpec(
                "Watch", r -> Theme.colorize("Built", Theme.active().focused()), r -> "Build failed");
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        var session = SessionContext.current();
        BuildPlanResult result = EngineClient.runSingleBuild(
                EnginePaths.current(),
                new EngineRequests.SingleBuildRequest(
                        projectDir,
                        cache,
                        jdksDir,
                        1,
                        null,
                        true,
                        global.verbose,
                        session.offline(),
                        session.force(),
                        session.variant(),
                        session.clientEnv()),
                steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, target),
                new TestSummary[1],
                new String[1]);
        return result.success();
    }

    private boolean compile(Path projectDir, Path cache) throws IOException, InterruptedException {
        String target = BuildCommand.buildTarget(projectDir.resolve(ManifestPaths.MANIFEST), projectDir);
        ConsoleSpec spec = new ConsoleSpec(
                "Watch", r -> Theme.colorize("Recompiled", Theme.active().focused()), r -> "Compile failed");
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        var session = SessionContext.current();
        BuildPlanResult result = EngineClient.runCompile(
                EnginePaths.current(),
                new EngineRequests.CompileRequest(
                        projectDir, cache, null, session.offline(), session.force(), global.verbose),
                steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, target));
        return result.success();
    }

    private ExecPlan devPlan(Path projectDir, Path cache) throws IOException {
        return EngineClient.execPlan(EnginePaths.current(), projectDir, cache, "dev", null, null);
    }

    private Process startApp(ExecPlan plan, List<String> appArgs) throws IOException {
        List<String> command = new ArrayList<>(plan.argv());
        command.addAll(appArgs);
        Terminals.restoreForChild();
        return new ProcessBuilder(command)
                .directory(Path.of(plan.workingDir()).toFile())
                .inheritIO()
                .start();
    }

    private Process restartApp(Process app, ExecPlan plan, List<String> appArgs)
            throws IOException, InterruptedException {
        if (app.isAlive()) {
            app.destroy();
            if (!app.waitFor(5, TimeUnit.SECONDS)) app.destroyForcibly();
        }
        CliOutput.err(logPrefix + ": restarting app");
        return startApp(plan, appArgs);
    }

    /** Resolve cache dir from override or defaults. */
    public static Path cache(Path override) {
        return override != null ? override : JkDirs.cache();
    }
}

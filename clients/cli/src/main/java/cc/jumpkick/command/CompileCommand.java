// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

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
        var opts = new ArrayList<Opt>();
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
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        String modulesSpec = in.value("modules").orElse(null);
        String affectedSince = in.value("affected-since").orElse(null);
        List<String> selectors = new ArrayList<>();
        if (modulesSpec != null && !modulesSpec.isBlank()) {
            for (String t : modulesSpec.split(",")) {
                if (!t.isBlank()) selectors.add(t.trim());
            }
        }
        if (affectedSince != null && !affectedSince.isBlank()) {
            selectors.add("affected:" + affectedSince);
        }
        var info = BuildCommand.projectInfoOrError(dir, modulesSpec, affectedSince);
        if (info.error() != null) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Compile", info.error());
            return Exit.CONFIG;
        }
        if (info.workspaceRoot() || !info.workspaceRootDir().isBlank()) {
            return runWorkspaceCompile(cache, profileName, global, dir, selectors);
        }

        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        // Engine-hosted: same plan as CompilePlans; listener chosen when the step list
        // arrives over the socket.
        var session = cc.jumpkick.config.SessionContext.current();
        for (Path moduleDir : List.of(dir)) {
            ConsoleSpec spec = new ConsoleSpec(
                    "Compile", r -> Theme.colorize("Compiled", Theme.active().focused()), r -> "Compilation failed");
            String target = BuildCommand.buildTarget(moduleDir.resolve("jk.toml"), moduleDir);
            BuildPlanResult result;
            try {
                result = cc.jumpkick.cli.engine.EngineClient.runCompile(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineRequests.CompileRequest(
                                moduleDir,
                                cache,
                                profileName,
                                session.offline(),
                                session.force(),
                                global.verbose,
                                selectors),
                        steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, target));
            } catch (cc.jumpkick.engine.protocol.EngineWireException e) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Compile", e.getMessage());
                return Exit.CONFIG;
            } catch (IOException e) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Compile", e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!result.success()) return 1;
        }
        return 0;
    }

    /** Workspace compile via {@code buildWorkspace}: aggregate TUI matches build/native/image. */
    private int runWorkspaceCompile(
            Path cache, String profileName, GlobalOptions global, Path entryDir, List<String> modules)
            throws IOException {
        var session = cc.jumpkick.config.SessionContext.current();
        var req = new cc.jumpkick.cli.engine.EngineRequests.CompileRequest(
                entryDir, cache, profileName, session.offline(), session.force(), global.verbose, modules);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        cc.jumpkick.cli.tui.JkManager view =
                cc.jumpkick.cli.tui.JkManager.plan(cc.jumpkick.cli.CliOutput.stdout(), "Compile", animate);
        cc.jumpkick.cli.run.AggregateContext agg = new cc.jumpkick.cli.run.AggregateContext(view);
        int[] finished = {0};
        long start = System.nanoTime();
        cc.jumpkick.runtime.WorkspaceResult result;
        try {
            result = cc.jumpkick.cli.engine.EngineClient.runCompileWorkspace(
                    cc.jumpkick.engine.EnginePaths.current(), req, new cc.jumpkick.runtime.WorkspaceBuildListener() {
                        @Override
                        public void onWorkspaceProgress(cc.jumpkick.runtime.WorkspaceProgressTracker.Snapshot snap) {
                            agg.applySnapshot(snap);
                        }

                        @Override
                        public cc.jumpkick.run.BuildPlanListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                            return new cc.jumpkick.cli.run.AggregateModuleListener(
                                    agg, m.coord(), m.plan().steps(), m.weight());
                        }

                        @Override
                        public void onModuleFinish(cc.jumpkick.runtime.ModuleOutcome o) {
                            int n = ++finished[0];
                            String completion =
                                    BuildCommand.completionLine(o.success(), n, Math.max(n, 1), o.coord(), o.millis());
                            if (view.animating()) {
                                view.addCompletion(completion);
                            }
                        }
                    });
        } catch (cc.jumpkick.engine.protocol.EngineWireException e) {
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()));
            return Exit.CONFIG;
        } catch (IOException e) {
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        if (!result.success()) {
            String detail = result.errors().isEmpty()
                    ? "compilation failed " + BuildCommand.elapsedSince(start)
                    : result.errors().getFirst();
            view.finishBuildPlanFailure(detail);
            return result.exitCode() == 0 ? 1 : result.exitCode();
        }
        view.finishBuildPlanSuccess(
                Theme.colorize("Compiled", Theme.active().focused()) + " " + BuildCommand.elapsedSince(start));
        return 0;
    }
}

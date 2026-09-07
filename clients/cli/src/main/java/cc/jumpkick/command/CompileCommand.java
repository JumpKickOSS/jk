// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.ProjectContext;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.AggregateContext;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.cli.tui.ModuleScopeHint;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineWireException;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
        var peek = ProjectInfos.orNull(dir);
        CwdModuleScope.Resolved cwdScope = CwdModuleScope.resolve(dir, modulesSpec, peek);
        if (cwdScope.inferredFromCwd()) modulesSpec = cwdScope.modulesSpec();
        List<String> selectors = ModuleSelectors.tokens(modulesSpec, affectedSince, affectedWip);
        Path infoDir = cwdScope.workspaceMember() ? cwdScope.workspaceRoot() : dir;
        var info = ProjectInfos.orError(infoDir, modulesSpec, affectedSince, affectedWip);
        if (info.error() != null) {
            CommandWedge.printFail("Compile", info.error());
            return Exit.CONFIG;
        }
        // Explicit selectors that match nothing are a no-op, not the whole graph — the wire
        // treats empty selectedModules as "everything" (WorkspaceSpec), so short-circuit here.
        if (!selectors.isEmpty() && info.moduleDirs().isEmpty()) {
            CommandWedge.printOk("Compile", "nothing selected to compile");
            return 0;
        }
        if (info.workspaceRoot()
                || cwdScope.workspaceMember()
                || !info.workspaceRootDir().isBlank()) {
            List<String> scopeNames = selectors.isEmpty() ? List.of() : ModuleScopeHint.namesFrom(info);
            return runWorkspaceCompile(cache, profileName, global, infoDir, selectors, scopeNames);
        }

        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        // Engine-hosted: same plan as CompilePlans; listener chosen when the step list
        // arrives over the socket.
        var session = SessionContext.current();
        for (Path moduleDir : List.of(dir)) {
            ConsoleSpec spec = new ConsoleSpec(
                    "Compile", r -> Theme.paint("Compiled", Theme.active().focused()), r -> "Compilation failed");
            String target = ProjectInfos.buildTarget(moduleDir.resolve(ManifestPaths.MANIFEST), moduleDir);
            BuildPlanResult result;
            try {
                result = EngineClient.runCompile(
                        EnginePaths.current(),
                        new EngineRequests.CompileRequest(
                                moduleDir,
                                cache,
                                profileName,
                                session.offline(),
                                session.force(),
                                global.verbose,
                                selectors),
                        steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, target));
            } catch (EngineWireException e) {
                CommandWedge.printFail("Compile", e.getMessage());
                return Exit.CONFIG;
            } catch (IOException e) {
                CommandWedge.printFail("Compile", e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!result.success()) return 1;
        }
        return 0;
    }

    /** Workspace compile via {@code buildWorkspace}: aggregate TUI matches build/native/image. */
    private int runWorkspaceCompile(
            Path cache,
            @Nullable String profileName,
            GlobalOptions global,
            Path entryDir,
            List<String> modules,
            List<String> scopeNames)
            throws IOException {
        var session = SessionContext.current();
        var req = new EngineRequests.CompileRequest(
                entryDir, cache, profileName, session.offline(), session.force(), global.verbose, modules);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        boolean live = mode == BuildPlanConsole.Mode.AUTO || mode == BuildPlanConsole.Mode.QUIET;
        if (!live) return runWorkspaceHeadless(req, global, entryDir, scopeNames);

        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        JkManager view = JkManager.plan(CliOutput.stdout(), "Compile", animate);
        view.setPlanCoord(BuildCommand.projectGaLabel(entryDir));
        ModuleScopeHint.show("compiling", scopeNames, false, view);
        AggregateContext agg = new AggregateContext(view);
        long start = System.nanoTime();
        // Not buffered: the live region owns every line, so nothing is written above it.
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Compile", false), entryDir, null, false);
        WorkspaceResult result;
        try {
            result = EngineClient.runCompileWorkspace(EnginePaths.current(), req, run.live(view, agg));
        } catch (EngineWireException e) {
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()));
            return Exit.CONFIG;
        } catch (IOException e) {
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        if (result.cancelled()) {
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            view.finishBuildPlanCancelled(List.of());
            return 1;
        }
        if (!result.success()) {
            String detail = result.errors().isEmpty()
                    ? "compilation failed " + BuildTails.elapsedSince(start)
                    : result.errors().getFirst();
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            view.finishBuildPlanFailure(detail);
            return result.exitCode() == 0 ? 1 : result.exitCode();
        }
        run.finishEvent(true, BuildTails.elapsedMsSince(start));
        view.finishBuildPlanSuccess(
                Theme.colorize("Compiled", Theme.active().focused()) + " " + BuildTails.elapsedSince(start));
        return 0;
    }

    /**
     * Non-animated workspace compile ({@code --output json} / {@code --verbose}), rendering through
     * {@link WorkspaceRunView#headless} exactly as {@code jk build} does — same events, same
     * per-module block, no {@link JkManager} region.
     */
    private int runWorkspaceHeadless(
            EngineRequests.CompileRequest req, GlobalOptions global, Path entryDir, List<String> scopeNames) {
        boolean json = global.outputIsJson();
        ModuleScopeHint.print("compiling", scopeNames, json);
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Compile", false), entryDir, null, json);
        long start = System.nanoTime();
        WorkspaceResult result;
        try {
            result = EngineClient.runCompileWorkspace(EnginePaths.current(), req, run.headless());
        } catch (EngineWireException e) {
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            if (!json) CommandWedge.printFail("Compile", e.getMessage());
            return Exit.CONFIG;
        } catch (IOException e) {
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            if (!json) CommandWedge.printFail("Compile", e.getMessage());
            return Exit.SOFTWARE;
        }
        long elapsed = BuildTails.elapsedMsSince(start);
        if (result.cancelled()) {
            run.finishEvent(false, elapsed);
            if (!json) CommandWedge.printFail("Compile", "Compile job was cancelled");
            return 1;
        }
        if (!result.success()) {
            run.finishEvent(false, elapsed);
            if (!json) {
                for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
                CommandWedge.printFail("Compile", "compilation failed");
            }
            return result.exitCode() == 0 ? 1 : result.exitCode();
        }
        run.finishEvent(true, elapsed);
        if (!json) CommandWedge.printOk("Compile", "Compiled " + BuildTails.elapsedSince(start));
        return 0;
    }
}

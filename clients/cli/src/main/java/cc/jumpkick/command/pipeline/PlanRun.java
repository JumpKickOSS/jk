// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
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
import cc.jumpkick.command.CwdModuleScope;
import cc.jumpkick.command.ModuleSelectors;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineWireException;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One compile, read in the vocabulary the engine will answer it in. A workspace root or member is
 * forked to the workspace orchestrator and settles {@code workspace-finish}; a standalone project
 * runs one plan and settles {@code plan-finish}. {@code jk compile} and the dev loop both compile
 * through here, so the two never choose differently.
 */
final class PlanRun {

    /** The words on the chrome: wedge/region name, the success word, the failure phrase. */
    record Labels(String name, String done, String failed) {
        static final Labels COMPILE = new Labels("Compile", "Compiled", "Compilation failed");
    }

    /**
     * Where a compile enters and how the engine answers it. {@code workspaceRoot} is the root that
     * owns {@code dir} — {@code dir} itself for a root — or null for a standalone project. The same
     * question the engine asks before it forks, so the client reads the stream the engine writes.
     */
    record Entry(Path dir, @Nullable Path workspaceRoot) {

        static Entry of(Path dir) throws IOException {
            Path normalized = dir.toAbsolutePath().normalize();
            return new Entry(normalized, WorkspaceLocator.owningRoot(normalized).orElse(null));
        }

        boolean workspace() {
            return workspaceRoot != null;
        }

        /** True when {@code dir} is a listed module of {@link #workspaceRoot}, not the root itself. */
        boolean member() {
            return workspaceRoot != null && !workspaceRoot.equals(dir);
        }

        /** The directory the engine request names: the root for a workspace, {@code dir} otherwise. */
        Path requestDir() {
            return workspaceRoot != null ? workspaceRoot : dir;
        }
    }

    /**
     * The engine verb a run drives: one plan for a standalone project, the workspace orchestrator
     * for a member or a root. {@code jk compile} and the dev loop's recompile are one verb; the dev
     * loop's full rebuild is another with the same two shapes.
     */
    interface Verb {
        /** The word the module-scope hint uses: "compiling", "building". */
        String gerund();

        /** What a no-op run says it found nothing for: "compile", "build". */
        String infinitive();

        BuildPlanResult single(
                Path dir, Path cache, GlobalOptions global, Function<List<Task>, BuildPlanListener> listeners)
                throws IOException;

        WorkspaceResult workspace(
                Path root, Path cache, List<String> selectors, GlobalOptions global, WorkspaceBuildListener listener)
                throws IOException;
    }

    /** {@code jk compile}: compile main and test sources, package nothing, run nothing. */
    static Verb compile(@Nullable String profile) {
        return new Verb() {
            @Override
            public String gerund() {
                return "compiling";
            }

            @Override
            public String infinitive() {
                return "compile";
            }

            @Override
            public BuildPlanResult single(
                    Path dir, Path cache, GlobalOptions global, Function<List<Task>, BuildPlanListener> listeners)
                    throws IOException {
                return EngineClient.runCompile(
                        EnginePaths.current(), request(dir, cache, global, List.of()), listeners);
            }

            @Override
            public WorkspaceResult workspace(
                    Path root,
                    Path cache,
                    List<String> selectors,
                    GlobalOptions global,
                    WorkspaceBuildListener listener)
                    throws IOException {
                return EngineClient.runCompileWorkspace(
                        EnginePaths.current(), request(root, cache, global, selectors), listener);
            }

            private EngineRequests.CompileRequest request(
                    Path dir, Path cache, GlobalOptions global, List<String> selectors) {
                var session = SessionContext.current();
                return new EngineRequests.CompileRequest(
                        dir, cache, profile, session.offline(), session.force(), global.verbose, selectors);
            }
        };
    }

    /**
     * The dev loop's full rebuild: package the app so its exec plan is current, tests skipped — the
     * loop restarts a process, it does not gate on a suite.
     */
    static Verb devBuild(@Nullable Path jdksDir) {
        return new Verb() {
            @Override
            public String gerund() {
                return "building";
            }

            @Override
            public String infinitive() {
                return "build";
            }

            @Override
            public BuildPlanResult single(
                    Path dir, Path cache, GlobalOptions global, Function<List<Task>, BuildPlanListener> listeners)
                    throws IOException {
                var session = SessionContext.current();
                return EngineClient.runSingleBuild(
                        EnginePaths.current(),
                        new EngineRequests.SingleBuildRequest(
                                dir,
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
                        listeners,
                        new TestSummary[1],
                        new String[1]);
            }

            @Override
            public WorkspaceResult workspace(
                    Path root,
                    Path cache,
                    List<String> selectors,
                    GlobalOptions global,
                    WorkspaceBuildListener listener)
                    throws IOException {
                var session = SessionContext.current();
                WorkspaceRequest req = new WorkspaceRequest(
                                root, cache, jdksDir, 1, null, true, global.verbose, 0, null, true, true)
                        .withVariant(session.variant(), session.clientEnv())
                        .withModules(selectors);
                return EngineClient.buildWorkspace(EnginePaths.current(), req, listener);
            }
        };
    }

    private final Entry entry;
    private final Verb verb;
    private final List<String> selectors;
    private final List<String> scopeNames;
    private final @Nullable String error;
    private final boolean nothingSelected;

    private PlanRun(
            Entry entry,
            Verb verb,
            List<String> selectors,
            List<String> scopeNames,
            @Nullable String error,
            boolean nothingSelected) {
        this.entry = entry;
        this.verb = verb;
        this.selectors = selectors;
        this.scopeNames = scopeNames;
        this.error = error;
        this.nothingSelected = nothingSelected;
    }

    /**
     * Resolve the compile for {@code dir}. A member directory without {@code -m} compiles that
     * member; selectors that match nothing make a no-op run rather than the whole graph, because
     * the wire reads an empty selection as "everything".
     */
    static PlanRun resolve(
            Path dir, @Nullable String modulesSpec, @Nullable String affectedSince, boolean affectedWip, Verb verb)
            throws IOException {
        Entry entry = Entry.of(dir);
        String memberRoot = entry.member() ? String.valueOf(entry.workspaceRoot()) : "";
        CwdModuleScope.Resolved scope = CwdModuleScope.resolve(entry.dir(), modulesSpec, false, memberRoot, null);
        if (scope.inferredFromCwd()) modulesSpec = scope.modulesSpec();
        List<String> selectors = ModuleSelectors.tokens(modulesSpec, affectedSince, affectedWip);
        var info = ProjectInfos.orError(entry.requestDir(), modulesSpec, affectedSince, affectedWip);
        if (info.error() != null) {
            return new PlanRun(entry, verb, selectors, List.of(), info.error(), false);
        }
        boolean nothingSelected = !selectors.isEmpty() && info.moduleDirs().isEmpty();
        List<String> scopeNames =
                entry.workspace() && !selectors.isEmpty() ? ModuleScopeHint.namesFrom(info) : List.of();
        return new PlanRun(entry, verb, selectors, scopeNames, null, nothingSelected);
    }

    Entry entry() {
        return entry;
    }

    /** Run the compile against the engine and render it under {@code labels}; returns the exit code. */
    int run(Labels labels, GlobalOptions global, Path cache) throws IOException {
        if (error != null) {
            CommandWedge.printFail(labels.name(), error);
            return Exit.CONFIG;
        }
        if (nothingSelected) {
            CommandWedge.printOk(labels.name(), "nothing selected to " + verb.infinitive());
            return 0;
        }
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        if (!entry.workspace()) return runSingle(cache, labels, global, mode);
        boolean live = mode == BuildPlanConsole.Mode.AUTO || mode == BuildPlanConsole.Mode.QUIET;
        return live ? runWorkspaceLive(cache, labels, global, mode) : runWorkspaceHeadless(cache, labels, global);
    }

    /** One plan; the console listener is chosen when the step list arrives over the socket. */
    private int runSingle(Path cache, Labels labels, GlobalOptions global, BuildPlanConsole.Mode mode) {
        ConsoleSpec spec = new ConsoleSpec(
                labels.name(), r -> Theme.paint(labels.done(), Theme.active().focused()), r -> labels.failed());
        String target = ProjectInfos.buildTarget(ManifestPaths.manifestIn(entry.dir()), entry.dir());
        BuildPlanResult result;
        try {
            result = verb.single(
                    entry.dir(),
                    cache,
                    global,
                    steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, target));
        } catch (EngineWireException e) {
            CommandWedge.printFail(labels.name(), e.getMessage());
            return Exit.CONFIG;
        } catch (IOException e) {
            CommandWedge.printFail(labels.name(), e.getMessage());
            return Exit.SOFTWARE;
        }
        return result.success() ? 0 : 1;
    }

    /** Workspace compile in a live region: the aggregate view build/native/image use. */
    private int runWorkspaceLive(Path cache, Labels labels, GlobalOptions global, BuildPlanConsole.Mode mode) {
        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        Path entryDir = entry.requestDir();
        ModuleScopeHint.print(verb.gerund(), scopeNames, false);
        JkManager view = JkManager.plan(CliOutput.stdout(), labels.name(), animate);
        view.setPlanCoord(BuildCommand.projectGaLabel(entryDir));
        AggregateContext agg = new AggregateContext(view);
        long start = Clock.SYSTEM.nanos();
        // Not buffered: the live region owns every line, so nothing is written above it.
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome(labels.name(), false), entryDir, null, false);
        WorkspaceResult result;
        try {
            result = verb.workspace(entryDir, cache, selectors, global, run.live(view, agg));
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
                    ? labels.failed() + " " + BuildTails.elapsedSince(start)
                    : result.errors().getFirst();
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            view.finishBuildPlanFailure(detail);
            return result.exitCode() == 0 ? 1 : result.exitCode();
        }
        run.finishEvent(true, BuildTails.elapsedMsSince(start));
        view.finishBuildPlanSuccess(
                Theme.colorize(labels.done(), Theme.active().focused()) + " " + BuildTails.elapsedSince(start));
        return 0;
    }

    /**
     * Workspace compile without a region ({@code --output json} / {@code --verbose}): the same
     * events and per-module block {@code jk build} renders through {@link WorkspaceRunView#headless}.
     */
    private int runWorkspaceHeadless(Path cache, Labels labels, GlobalOptions global) {
        boolean json = global.outputIsJson();
        Path entryDir = entry.requestDir();
        ModuleScopeHint.print(verb.gerund(), scopeNames, json);
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome(labels.name(), false), entryDir, null, json);
        long start = Clock.SYSTEM.nanos();
        WorkspaceResult result;
        try {
            result = verb.workspace(entryDir, cache, selectors, global, run.headless());
        } catch (EngineWireException e) {
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            if (!json) CommandWedge.printFail(labels.name(), e.getMessage());
            return Exit.CONFIG;
        } catch (IOException e) {
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            if (!json) CommandWedge.printFail(labels.name(), e.getMessage());
            return Exit.SOFTWARE;
        }
        long elapsed = BuildTails.elapsedMsSince(start);
        if (result.cancelled()) {
            run.finishEvent(false, elapsed);
            if (!json) CommandWedge.printFail(labels.name(), labels.name() + " job was cancelled");
            return 1;
        }
        if (!result.success()) {
            run.finishEvent(false, elapsed);
            if (!json) {
                for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
                CommandWedge.printFail(labels.name(), labels.failed());
            }
            return result.exitCode() == 0 ? 1 : result.exitCode();
        }
        run.finishEvent(true, elapsed);
        if (!json) CommandWedge.printOk(labels.name(), labels.done() + " " + BuildTails.elapsedSince(start));
        return 0;
    }
}

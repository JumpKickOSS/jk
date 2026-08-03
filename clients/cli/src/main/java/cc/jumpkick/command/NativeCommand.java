// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.CompositePipelineListener;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.EventLogListener;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.cli.tui.PipelineWedge;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code jk native} — GraalVM native-image of modules with {@code native = true} (others still
 * compile/package as deps). Graal is resolved client-side; the build runs engine-hosted.
 */
public final class NativeCommand implements CliCommand {

    @Override
    public String name() {
        return "native";
    }

    @Override
    public String description() {
        return "Build a native binary with GraalVM";
    }

    @Override
    public List<Opt> options() {
        var opts = new java.util.ArrayList<Opt>();
        opts.add(Opt.value("<class>", "Main class. Default: jk.toml image.main or project.main.", "--main"));
        opts.add(cc.jumpkick.cli.CommonOpts.cacheDirHidden());
        opts.add(Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                .hide());
        // Global -y/--yes: install Oracle GraalVM without prompting when native-image is missing.
        opts.add(cc.jumpkick.cli.CommonOpts.skipTests());
        opts.addAll(cc.jumpkick.cli.CommonOpts.moduleSelection());
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Override
    public List<cc.jumpkick.model.command.Param> parameters() {
        return List.of(cc.jumpkick.model.command.Param.of(
                "native-image-args",
                cc.jumpkick.model.command.Arity.ZERO_OR_MORE,
                "Extra arguments forwarded to\nnative-image (after --)."));
    }

    String mainClass;
    Path cacheDirOverride;
    Path jdksDir;
    boolean assumeYes;
    List<String> extra = new ArrayList<>();
    cc.jumpkick.cli.BuildOptions buildOpts;
    GlobalOptions global;
    cc.jumpkick.cli.GraalResolver graal;
    /** Optional {@code -m}/{@code --affected-since} filter; null = whole workspace. */
    String modulesSpec;

    String affectedSince;

    @Override
    public int run(Invocation in) throws Exception {
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.assumeYes = in.isSet("yes");
        this.extra = in.positionals();
        this.buildOpts = new cc.jumpkick.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);
        this.graal = new cc.jumpkick.cli.GraalResolver(jdksDir, assumeYes);
        this.modulesSpec = in.value("modules").orElse(null);
        this.affectedSince = in.value("affected-since").orElse(null);

        Path startDir = global.workingDir();
        VariantSelection.install(in, startDir);
        Path buildFile = startDir.resolve("jk.toml");
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();

        if (!Files.exists(buildFile)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Native", cc.jumpkick.cli.PathDisplay.styledRaw(buildFile) + " not found."));
            return Exit.NO_INPUT;
        }

        cc.jumpkick.engine.protocol.ProjectInfo peek = BuildCommand.projectInfoOrNull(startDir);

        // Workspace root: cascade to all eligible modules.
        if (peek != null && peek.workspaceRoot()) {
            return runWorkspaceNative(startDir, cache);
        }

        // Module redirect: if we're inside a workspace, build from the root.
        if (peek != null
                && !peek.workspaceRootDir().isEmpty()
                && !peek.workspaceRootDir().equals(startDir.toString())) {
            Path wsRoot = Path.of(peek.workspaceRootDir());
            {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                        "Native",
                        "building from workspace root " + wsRoot.getFileName()
                                + " (module: "
                                + startDir.getFileName()
                                + ")"));
                return runWorkspaceNative(wsRoot, cache);
            }
        }

        // Single project: -m/--affected-since still validate (JK-1366).
        if ((modulesSpec != null && !modulesSpec.isBlank()) || (affectedSince != null && !affectedSince.isBlank())) {
            var entry = cc.jumpkick.config.JkBuildParser.parse(buildFile);
            var sel = cc.jumpkick.config.ModuleSelection.resolveOptional(startDir, entry, modulesSpec, affectedSince);
            if (sel != null && !sel.ok()) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Native", sel.errorMessage()));
                return Exit.CONFIG;
            }
            if (sel != null && sel.moduleDirs().isEmpty()) {
                CliOutput.out("(no modules matched selection)");
                return 0;
            }
        }
        return runSingleProject(startDir, buildFile, cache);
    }

    /**
     * Native builds are opt-in ({@code native = true} → ALWAYS) — the same rule as the engine's
     * {@code NativePipelines.isNativeEligible} (a one-line enum check on the shared model).
     */
    static boolean nativeEligible(JkBuild build) {
        return build.nativeMode() == JkBuild.NativeMode.ALWAYS;
    }

    /** The engine request for {@code entryDir}, with the client-resolved GraalVM homes attached. */
    private EngineClient.NativeRequest hostedRequest(
            Path entryDir, Path cache, Map<Path, Path> graalHomes, List<Path> selectedModuleDirs) {
        var session = cc.jumpkick.config.SessionContext.current();
        return new EngineClient.NativeRequest(
                entryDir,
                cache,
                jdksDir,
                mainClass,
                buildOpts.skipTests,
                session.offline(),
                session.force(),
                global.verbose,
                extra,
                graalHomes,
                selectedModuleDirs);
    }

    // --- workspace cascade ---------------------------------------------------

    private int runWorkspaceNative(Path wsRoot, Path cache) throws Exception {
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
        long buildStart = System.nanoTime();

        // Thin client: per-module native-mode + graal spec ride ProjectInfo summaries; the
        // engine owns ordering/scheduling. The GraalVM pre-resolve stays HERE — a prompt or
        // install owns this terminal and must never run inside the engine.
        var rootInfo = BuildCommand.projectInfoOrNull(wsRoot);
        if (rootInfo == null) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Native", "could not read the workspace summary at " + wsRoot));
            return Exit.CONFIG;
        }
        if (rootInfo.moduleDirs().isEmpty()) {
            CliOutput.out("(workspace declares no modules)");
            return 0;
        }

        // -m / --affected-since: same ModuleSelection as jk build/test (paths, names, :gradle).
        List<Path> selectedDirs = null;
        if ((modulesSpec != null && !modulesSpec.isBlank()) || (affectedSince != null && !affectedSince.isBlank())) {
            cc.jumpkick.model.JkBuild rootBuild;
            try {
                rootBuild = cc.jumpkick.config.JkBuildParser.parse(wsRoot.resolve("jk.toml"));
            } catch (Exception e) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Native", String.valueOf(e.getMessage())));
                return Exit.CONFIG;
            }
            var sel = cc.jumpkick.config.ModuleSelection.resolveOptional(wsRoot, rootBuild, modulesSpec, affectedSince);
            if (sel == null) {
                // neither set — whole workspace
            } else if (!sel.ok()) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Native", sel.errorMessage()));
                return Exit.CONFIG;
            } else if (sel.moduleDirs().isEmpty()) {
                CliOutput.out("(no modules matched selection)");
                return 0;
            } else {
                selectedDirs = List.copyOf(sel.moduleDirs());
            }
        }

        Map<Path, Path> graalHomes = new java.util.HashMap<>();
        long nativeCount = 0;
        int considered = 0;
        for (String rel : rootInfo.moduleDirs()) {
            Path moduleDir = wsRoot.resolve(rel).toAbsolutePath().normalize();
            if (selectedDirs != null && !selectedDirs.contains(moduleDir)) continue;
            considered++;
            var info = BuildCommand.projectInfoOrNull(moduleDir);
            if (info == null || !"ALWAYS".equals(info.nativeMode())) continue;
            nativeCount++;
            Optional<Path> home = graal.resolve(moduleDir, info.graal().isEmpty() ? null : info.graal());
            if (home.isEmpty()) return Exit.CONFIG; // GraalResolver already printed why
            graalHomes.put(moduleDir, home.get());
        }
        if (selectedDirs != null && considered == 0) {
            CliOutput.out("(no modules matched selection)");
            return 0;
        }
        return runWorkspaceHosted(
                wsRoot,
                cache,
                graalHomes,
                selectedDirs,
                mode,
                buildStart,
                selectedDirs != null ? considered : rootInfo.moduleDirs().size(),
                nativeCount);
    }

    /**
     * Engine-hosted workspace cascade: the engine assembles and runs each module's pipeline (the
     * {@code native-image} child forks engine-side) and streams the workspace event vocabulary
     * back; this method only renders. Exit codes arrive engine-computed.
     */
    private int runWorkspaceHosted(
            Path wsRoot,
            Path cache,
            Map<Path, Path> graalHomes,
            List<Path> selectedModuleDirs,
            PipelineConsole.Mode mode,
            long buildStart,
            int totalModules,
            long nativeCount) {
        var req = hostedRequest(wsRoot, cache, graalHomes, selectedModuleDirs);
        var paths = EnginePaths.current();

        // JSON / verbose: append-only per-module listeners. JSON must not print human banners and
        // must not let module-local num/den clobber the engine aggregate rider.
        if (mode != PipelineConsole.Mode.AUTO && mode != PipelineConsole.Mode.QUIET) {
            int[] idx = {0};
            // Engine-corrected denominator: with -m the engine adds transitive prereqs the client
            // never counted, so the plan's modulesTotal wins over the client-side guess (JK-1361).
            int[] total = {totalModules};
            boolean json = mode == PipelineConsole.Mode.JSON;
            var listener = new cc.jumpkick.runtime.WorkspaceBuildListener() {
                @Override
                public void onWorkspaceProgress(cc.jumpkick.runtime.WorkspaceProgressTracker.Snapshot snap) {
                    if (snap.modulesTotal() > 0) total[0] = snap.modulesTotal();
                    if (!json) return;
                    cc.jumpkick.cli.run.LiveProgress.get().apply(snap);
                    cc.jumpkick.cli.run.JsonlShape.emitJsonl(
                            cc.jumpkick.cli.run.JsonlShape.workspaceProgress(
                                    wsRoot.toString(),
                                    snap.numerator(),
                                    snap.denominator(),
                                    snap.phase(),
                                    snap.modulesComplete(),
                                    snap.modulesTotal()),
                            true);
                }

                @Override
                public cc.jumpkick.run.PipelineListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                    if (!json) {
                        CliOutput.out();
                        CliOutput.out("══ " + wsRoot.relativize(m.dir()) + " (" + (++idx[0]) + "/"
                                + Math.max(total[0], idx[0]) + ") ══");
                    }
                    var log = EventLogListener.open(m.cache(), m.pipeline().name());
                    // JSON: workspace member listener (no aggregate-rider writes). Verbose: full console.
                    var console = json
                            ? new cc.jumpkick.cli.run.JsonlListener(System.out, false)
                            : PipelineConsole.chooseConsoleListener(
                                    m.pipeline().name(), m.pipeline().steps(), mode);
                    return CompositePipelineListener.of(console, log);
                }

                @Override
                public void onModuleFinish(cc.jumpkick.runtime.ModuleOutcome o) {
                    if (!o.success() && !json) {
                        CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                                "Native", wsRoot.relativize(o.dir()) + " failed (exit " + o.exitCode() + ")"));
                    }
                }
            };
            cc.jumpkick.runtime.WorkspaceResult result;
            try {
                result = EngineClient.runNative(paths, req, listener);
            } catch (IOException e) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Native", e.getMessage()));
                return Exit.SOFTWARE;
            }
            for (String err : result.errors()) CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Native", err));
            return result.exitCode();
        }

        // AUTO / QUIET: one shared aggregate view, calibrated to the whole cascade up front
        // (the plan burst carries every module pipeline's estimated weight).
        boolean animate = mode == PipelineConsole.Mode.AUTO && PipelineConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.pipeline(CliOutput.stdout(), "Build", animate);
        cc.jumpkick.cli.run.AggregateContext agg = new cc.jumpkick.cli.run.AggregateContext(view);
        int[] built = {0};
        var listener = new cc.jumpkick.runtime.WorkspaceBuildListener() {
            @Override
            public void onWorkspaceProgress(cc.jumpkick.runtime.WorkspaceProgressTracker.Snapshot snap) {
                agg.applySnapshot(snap);
            }

            @Override
            public cc.jumpkick.run.PipelineListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                var log = EventLogListener.open(m.cache(), m.pipeline().name());
                return CompositePipelineListener.of(
                        new cc.jumpkick.cli.run.AggregateModuleListener(
                                agg, m.coord(), m.pipeline().steps(), m.weight()),
                        log);
            }

            @Override
            public void onModuleFinish(cc.jumpkick.runtime.ModuleOutcome o) {
                if (o.success()) built[0]++;
            }
        };
        cc.jumpkick.runtime.WorkspaceResult result;
        try {
            result = EngineClient.runNative(paths, req, listener);
        } catch (IOException e) {
            view.finishPipelineFailure(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        if (!result.errors().isEmpty()) {
            view.finishPipelineFailure("dependency resolution failed");
            for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            return result.exitCode();
        }
        if (!result.success()) {
            String failedCoord = result.modules().stream()
                    .filter(m -> !m.success())
                    .map(cc.jumpkick.runtime.ModuleOutcome::coord)
                    .findFirst()
                    .orElse("build");
            view.finishPipelineFailure(PipelineWedge.coord(failedCoord) + " " + BuildCommand.elapsedSince(buildStart));
            for (PipelineResult.Diagnostic d : agg.lastErrors()) {
                CliOutput.err(ConsoleSpec.renderError(d));
            }
            return result.exitCode();
        }
        view.finishPipelineSuccess(
                Theme.colorize("Native build successful", Theme.active().success())
                        + ", "
                        + workspaceSummary(built[0], nativeCount)
                        + " "
                        + BuildCommand.elapsedSince(buildStart));
        return 0;
    }

    /** Success-summary tail shared by the hosted and in-process workspace paths. */
    static String workspaceSummary(int built, long nativeCount) {
        return built
                + " module"
                + (built == 1 ? "" : "s")
                + " built"
                + (nativeCount > 0 ? ", " + nativeCount + " native artifact" + (nativeCount == 1 ? "" : "s") : "");
    }

    // --- single-project (unchanged behaviour) --------------------------------

    private int runSingleProject(Path projectDir, Path buildFile, Path cache) throws IOException, InterruptedException {
        // Native builds are opt-in: require [native] always = true. Absent, or
        // [native] declared without always, → not eligible, even for an explicit `jk native`.
        // Thin client: the gate reads the engine's summary, never a client-side parse.
        cc.jumpkick.engine.protocol.ProjectInfo build = BuildCommand.projectInfoOrNull(projectDir);
        if (build == null || !"ALWAYS".equals(build.nativeMode())) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Native",
                    projectDir.getFileName()
                            + " is not native-eligible — set `always = true` under [native] to enable."));
            return Exit.CONFIG;
        }

        // Resolve GraalVM before the pipeline/progress UI starts (a prompt/install
        // can't run inside the captured-output region, and must never run inside
        // the engine — see docs/architecture.md).
        Optional<Path> graalHome = graal.resolve(projectDir, build.graal());
        if (graalHome.isEmpty()) return Exit.CONFIG; // GraalResolver already printed why

        String coord = BuildCommand.buildTarget(buildFile, projectDir);
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);

        // Engine-hosted (a cascade of one): the success tail names the built artifact from
        // the engine summary's candidate paths (thin client — no local layout derivation).
        ConsoleSpec spec = new ConsoleSpec(
                "Build",
                r -> Theme.colorize("Native build successful", Theme.active().success())
                        + BuildCommand.builtArtifact(projectDir, build),
                r -> PipelineWedge.coord(coord),
                true);
        var listener = new cc.jumpkick.runtime.WorkspaceBuildListener() {
            @Override
            public cc.jumpkick.run.PipelineListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                var log = EventLogListener.open(m.cache(), m.pipeline().name());
                return CompositePipelineListener.of(
                        PipelineConsole.chooseConsoleListener(m.pipeline().steps(), mode, spec, coord), log);
            }
        };
        cc.jumpkick.runtime.WorkspaceResult result;
        try {
            result = EngineClient.runNative(
                    EnginePaths.current(),
                    hostedRequest(projectDir, cache, Map.of(projectDir, graalHome.get()), null),
                    listener);
        } catch (IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Native", e.getMessage()));
            return Exit.SOFTWARE;
        }
        for (String err : result.errors()) CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Native", err));
        return result.exitCode();
    }
}

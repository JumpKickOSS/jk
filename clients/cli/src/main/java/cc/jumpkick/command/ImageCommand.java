// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.BuildOptions;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.AggregateContext;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Coords;
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
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk image} — build an OCI image (full build plan + Jib worker, engine-hosted). This
 * command renders streamed plan events.
 */
public final class ImageCommand implements CliCommand {

    @Override
    public String name() {
        return "image";
    }

    @Override
    public String description() {
        return "Bundle this project into an OCI image";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<Opt>(List.of(
                Opt.value("<class>", "Main class to set as the image entrypoint.", "--main"),
                Opt.value("<registry>", "Override image.registry from jk.toml.", "--registry"),
                Opt.value("<tag>", "Override image.tag from jk.toml.", "--tag"),
                Opt.value("<path>", "Write an OCI tarball instead of pushing.", "--tarball")
                        .withFallback(""),
                Opt.value("<exe>", "Docker/Podman binary (default: auto)", "--docker-executable"),
                Opt.value(
                                "<dir>",
                                "Override cache-tier directory (action outputs; not the artifact store). Default: $JK_CACHE_DIR or ~/.jk/cache.",
                                "--cache-dir")
                        .hide(),
                CommonOpts.jdksDir(),
                CommonOpts.skipTests()));
        opts.addAll(CommonOpts.moduleSelection());
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Nullable
    String mainClass;

    @Nullable
    String registry;

    @Nullable
    String tag;

    @Nullable
    String tarballArg;

    @Nullable
    String dockerExecutableArg;

    @Nullable
    Path cacheDirOverride;

    @Nullable
    Path jdksDir;

    BuildOptions buildOpts;
    GlobalOptions global;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.mainClass = in.value("main").orElse(null);
        this.registry = in.value("registry").orElse(null);
        this.tag = in.value("tag").orElse(null);
        this.tarballArg = in.value("tarball").orElse(null);
        this.dockerExecutableArg = in.value("docker-executable").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        this.jdksDir = CommonOpts.jdksDirValue(in);
        this.buildOpts = new BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);
        Path projectDir = global.workingDir();
        VariantSelection.install(in, projectDir);
        Path jkBuildPath = projectDir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(jkBuildPath)) {
            CommandWedge.printFail("Image", jkBuildPath + " not found.");
            return Exit.NO_INPUT;
        }
        // -m/--modules: an image is built for exactly one module — redirect to it.
        String modulesSpec = in.value("modules").orElse(null);
        String affectedSince = in.value("affected-since").orElse(null);
        boolean affectedWip = in.isSet("affected");
        if (ModuleSelectors.bothSelectors(affectedWip, affectedSince)) {
            CommandWedge.printFail("Image", ModuleSelectors.BOTH_MESSAGE);
            return Exit.CONFIG;
        }
        var peekEarly = ProjectInfos.orNull(projectDir);
        CwdModuleScope.Resolved cwdScope = CwdModuleScope.resolve(projectDir, modulesSpec, peekEarly);
        if (cwdScope.inferredFromCwd()) modulesSpec = cwdScope.modulesSpec();
        if (ModuleSelectors.anySelector(modulesSpec, affectedSince, affectedWip)) {
            Path selectRoot = cwdScope.workspaceMember() ? cwdScope.workspaceRoot() : projectDir;
            var selected = ProjectInfos.orError(selectRoot, modulesSpec, affectedSince, affectedWip);
            if (selected.error() != null && !selected.error().isBlank()) {
                CommandWedge.printFail("Image", selected.error());
                return Exit.CONFIG;
            }
            if (selected.moduleDirs().size() != 1) {
                CommandWedge.printFail(
                        "Image",
                        "an image is built for exactly one module — the selector matched "
                                + selected.moduleDirs().size());
                return Exit.USAGE;
            }
            projectDir = Path.of(selected.moduleDirs().getFirst());
            jkBuildPath = projectDir.resolve(ManifestPaths.MANIFEST);
        }
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        var peek = ProjectInfos.orNull(projectDir);
        if (peek != null
                && !peek.workspaceRootDir().isBlank()
                && !Path.of(peek.workspaceRootDir())
                        .toAbsolutePath()
                        .normalize()
                        .equals(projectDir.toAbsolutePath().normalize())) {
            return runWorkspaceImage(Path.of(peek.workspaceRootDir()), projectDir, cache);
        }
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        String module = BuildCommand.buildTarget(jkBuildPath, projectDir);

        BuildPlanResult result;
        TestSummary testResult;
        // The wire has no real BuildPlan, so the success tail renders from the structured fields the
        // terminal plan-finish carries — the summary holder is populated before the console
        // listener's own planFinish fires, same holder pattern as TestCommand's hosted path.
        var session = SessionContext.current();
        EngineRequests.ImageSummary[] summary = new EngineRequests.ImageSummary[1];
        ConsoleSpec spec = new ConsoleSpec(
                "Image",
                r -> summary[0] != null
                        ? imageSuccessTail(
                                summary[0].tarball(),
                                summary[0].name(),
                                summary[0].version(),
                                summary[0].daemonExe(),
                                summary[0].ref())
                        : "",
                r -> "Image build failed",
                true);
        try {
            result = EngineClient.runImage(
                    EnginePaths.current(),
                    new EngineRequests.ImageRequest(
                            projectDir,
                            cache,
                            jdksDir,
                            mainClass,
                            registry,
                            tag,
                            tarballArg,
                            dockerExecutableArg,
                            buildOpts.skipTests,
                            session.offline(),
                            session.force(),
                            session.config().rebuildOr(false),
                            global.verbose),
                    steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, module),
                    summary);
        } catch (IOException e) {
            CommandWedge.printFail("Image", e.getMessage());
            return Exit.SOFTWARE;
        }
        testResult = summary[0] != null ? summary[0].testResult() : null;

        if (!result.success()) {
            for (BuildPlanResult.Diagnostic d : result.errors()) {
                if ("no-main".equals(d.code())) return Exit.USAGE;
            }
            if (testResult != null && !testResult.allPassed()) return 4;
            return 1;
        }
        return 0;
    }

    /**
     * Success tail for the Image chip line, from the structured mode fields:
     *
     * <ul>
     *   <li>Tarball: {@code Wrote OCI tarball <path>}
     *   <li>Daemon load: {@code Loaded OCI image <name>:<version> into <docker|podman>}
     *   <li>Registry push: {@code Pushed <ref>}
     * </ul>
     *
     * The framework appends {@code took Xs} automatically. Theming happens here, client-side — the
     * engine only ever supplies the plain field values.
     */
    static String imageSuccessTail(
            @Nullable String tarball,
            @Nullable String name,
            @Nullable String version,
            @Nullable String daemonExe,
            @Nullable String ref) {
        if (tarball != null) {
            return "Wrote OCI tarball " + Theme.colorize(tarball, Theme.active().path());
        }
        if (daemonExe != null) {
            return "Loaded OCI image "
                    + Theme.colorize(name != null ? name : "", Coords.artifactStyle())
                    + ":"
                    + Theme.colorize(version != null ? version : "", Coords.versionStyle())
                    + " into "
                    + daemonExe;
        }
        return "Pushed " + Theme.colorize(ref != null ? ref : "", Theme.active().path());
    }

    /**
     * Workspace member: same {@code buildWorkspace} path as {@code jk build}, image terminal on
     * this module, prereqs package. Aggregate TUI matches native/build.
     */
    private int runWorkspaceImage(Path wsRoot, Path moduleDir, Path cache) throws IOException {
        var session = SessionContext.current();
        var imageReq = new EngineRequests.ImageRequest(
                moduleDir,
                cache,
                jdksDir,
                mainClass,
                registry,
                tag,
                tarballArg,
                dockerExecutableArg,
                buildOpts.skipTests,
                session.offline(),
                session.force(),
                session.config().rebuildOr(false),
                global.verbose);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        boolean live = mode == BuildPlanConsole.Mode.AUTO || mode == BuildPlanConsole.Mode.QUIET;
        var moduleInfo = ProjectInfos.orNull(moduleDir);
        if (!live) return runWorkspaceHeadless(imageReq, moduleDir, ModuleScopeHint.namesFrom(moduleInfo));

        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        JkManager view = JkManager.plan(CliOutput.stdout(), "Image", animate);
        view.setPlanCoord(BuildCommand.projectGaLabel(moduleDir));
        ModuleScopeHint.show("building", ModuleScopeHint.namesFrom(moduleInfo), false, view);
        AggregateContext agg = new AggregateContext(view);
        ModuleOutcome.Image[] imageOut = {null};
        long start = System.nanoTime();
        // Not buffered: the live region owns every line, so nothing is written above it.
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Image", false), moduleDir, null, false);
        WorkspaceResult result;
        try {
            result = EngineClient.runImageWorkspace(EnginePaths.current(), imageReq, run.live(view, agg, o -> {
                if (o.success() && o.image() != null) imageOut[0] = o.image();
            }));
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
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            view.finishBuildPlanFailure("image failed " + BuildTails.elapsedSince(start));
            return result.exitCode() == 0 ? 1 : result.exitCode();
        }
        run.finishEvent(true, BuildTails.elapsedMsSince(start));
        // Same Pushed/Wrote/Loaded tail as the single-project chip.
        var img = imageOut[0];
        String tail = img != null
                ? imageSuccessTail(img.tarball(), img.name(), img.version(), img.daemonExe(), img.ref())
                : "image built";
        view.finishBuildPlanSuccess(tail + " " + BuildTails.elapsedSince(start));
        return 0;
    }

    /**
     * Non-animated workspace image ({@code --output json} / {@code --verbose}), rendering through
     * {@link WorkspaceRunView#headless} exactly as {@code jk build} does — same events, same
     * per-module block, no {@link JkManager} region.
     */
    private int runWorkspaceHeadless(EngineRequests.ImageRequest imageReq, Path moduleDir, List<String> scopeNames) {
        boolean json = global.outputIsJson();
        ModuleScopeHint.print("building", scopeNames, json);
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Image", false), moduleDir, null, json);
        ModuleOutcome.Image[] imageOut = {null};
        long start = System.nanoTime();
        WorkspaceResult result;
        try {
            result = EngineClient.runImageWorkspace(EnginePaths.current(), imageReq, run.headless());
        } catch (IOException e) {
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            if (!json) CommandWedge.printFail("Image", e.getMessage());
            return Exit.SOFTWARE;
        }
        long elapsed = BuildTails.elapsedMsSince(start);
        for (var o : result.modules()) {
            if (o.success() && o.image() != null) imageOut[0] = o.image();
        }
        if (result.cancelled()) {
            run.finishEvent(false, elapsed);
            if (!json) CommandWedge.printFail("Image", "Image job was cancelled");
            return 1;
        }
        if (!result.success()) {
            run.finishEvent(false, elapsed);
            if (!json) {
                for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
                CommandWedge.printFail("Image", "image failed");
            }
            return result.exitCode() == 0 ? 1 : result.exitCode();
        }
        run.finishEvent(true, elapsed);
        if (!json) {
            var img = imageOut[0];
            String tail = img != null
                    ? imageSuccessTail(img.tarball(), img.name(), img.version(), img.daemonExe(), img.ref())
                    : "image built";
            CommandWedge.printOk("Image", tail + " " + BuildTails.elapsedSince(start));
        }
        return 0;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
                                "Override cache-tier directory (action outputs; not the artifact store). Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
                cc.jumpkick.cli.CommonOpts.skipTests()));
        opts.addAll(cc.jumpkick.cli.CommonOpts.moduleSelection());
        opts.addAll(VariantSelection.options());
        return opts;
    }

    String mainClass;
    String registry;
    String tag;
    String tarballArg;
    String dockerExecutableArg;
    Path cacheDirOverride;
    Path jdksDir;
    cc.jumpkick.cli.BuildOptions buildOpts;
    GlobalOptions global;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.mainClass = in.value("main").orElse(null);
        this.registry = in.value("registry").orElse(null);
        this.tag = in.value("tag").orElse(null);
        this.tarballArg = in.value("tarball").orElse(null);
        this.dockerExecutableArg = in.value("docker-executable").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.buildOpts = new cc.jumpkick.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);
        Path projectDir = global.workingDir();
        VariantSelection.install(in, projectDir);
        Path jkBuildPath = projectDir.resolve("jk.toml");
        if (!Files.exists(jkBuildPath)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Image", jkBuildPath + " not found.");
            return Exit.NO_INPUT;
        }
        // -m/--modules: an image is built for exactly one module — redirect to it.
        String modulesSpec = in.value("modules").orElse(null);
        String affectedSince = in.value("affected-since").orElse(null);
        if ((modulesSpec != null && !modulesSpec.isBlank()) || (affectedSince != null && !affectedSince.isBlank())) {
            cc.jumpkick.model.JkBuild entry = cc.jumpkick.config.JkBuildParser.parse(jkBuildPath);
            var selected =
                    cc.jumpkick.config.ModuleSelection.resolveOptional(projectDir, entry, modulesSpec, affectedSince);
            if (selected != null && !selected.ok()) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Image", selected.errorMessage());
                return Exit.CONFIG;
            }
            if (selected != null) {
                if (selected.moduleDirs().size() != 1) {
                    cc.jumpkick.cli.tui.CommandWedge.printFail(
                            "Image",
                            "an image is built for exactly one module — the selector matched "
                                    + selected.moduleDirs().size());
                    return Exit.USAGE;
                }
                projectDir = selected.moduleDirs().iterator().next();
                jkBuildPath = projectDir.resolve("jk.toml");
            }
        }
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        var wsRoot = cc.jumpkick.config.WorkspaceLocator.findRoot(projectDir);
        if (wsRoot.isPresent()) {
            cc.jumpkick.model.JkBuild rootBuild =
                    cc.jumpkick.config.JkBuildParser.parse(wsRoot.get().resolve("jk.toml"));
            if (rootBuild.isWorkspaceRoot()
                    && !wsRoot.get()
                            .toAbsolutePath()
                            .normalize()
                            .equals(projectDir.toAbsolutePath().normalize())) {
                return runWorkspaceImage(wsRoot.get(), rootBuild, projectDir, cache);
            }
        }
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        String module = BuildCommand.buildTarget(jkBuildPath, projectDir);

        BuildPlanResult result;
        cc.jumpkick.run.TestSummary testResult;
        // The wire has no real BuildPlan, so the success tail renders from the structured fields the
        // terminal plan-finish carries — the summary holder is populated before the console
        // listener's own planFinish fires, same holder pattern as TestCommand's hosted path.
        var session = cc.jumpkick.config.SessionContext.current();
        cc.jumpkick.cli.engine.EngineRequests.ImageSummary[] summary =
                new cc.jumpkick.cli.engine.EngineRequests.ImageSummary[1];
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
            result = cc.jumpkick.cli.engine.EngineClient.runImage(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineRequests.ImageRequest(
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
            cc.jumpkick.cli.tui.CommandWedge.printFail("Image", e.getMessage());
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
    static String imageSuccessTail(String tarball, String name, String version, String daemonExe, String ref) {
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
    private int runWorkspaceImage(Path wsRoot, cc.jumpkick.model.JkBuild rootBuild, Path moduleDir, Path cache)
            throws IOException {
        var session = cc.jumpkick.config.SessionContext.current();
        var imageReq = new cc.jumpkick.cli.engine.EngineRequests.ImageRequest(
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
        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        cc.jumpkick.cli.tui.JkManager view =
                cc.jumpkick.cli.tui.JkManager.plan(cc.jumpkick.cli.CliOutput.stdout(), "Image", animate);
        cc.jumpkick.cli.run.AggregateContext agg = new cc.jumpkick.cli.run.AggregateContext(view);
        int[] finished = {0};
        cc.jumpkick.runtime.ModuleOutcome.Image[] imageOut = {null};
        long start = System.nanoTime();
        cc.jumpkick.runtime.WorkspaceResult result;
        try {
            result = cc.jumpkick.cli.engine.EngineClient.runImageWorkspace(
                    cc.jumpkick.engine.EnginePaths.current(),
                    imageReq,
                    new cc.jumpkick.runtime.WorkspaceBuildListener() {
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
                            if (o.success() && o.image() != null) imageOut[0] = o.image();
                            String completion =
                                    BuildCommand.completionLine(o.success(), n, Math.max(n, 1), o.coord(), o.millis());
                            if (view.animating()) {
                                view.addCompletion(completion);
                            }
                        }
                    });
        } catch (IOException e) {
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        if (!result.success()) {
            view.finishBuildPlanFailure("image failed " + BuildCommand.elapsedSince(start));
            return result.exitCode() == 0 ? 1 : result.exitCode();
        }
        // Same Pushed/Wrote/Loaded tail as the single-project chip (JK-2100).
        var img = imageOut[0];
        String tail = img != null
                ? imageSuccessTail(img.tarball(), img.name(), img.version(), img.daemonExe(), img.ref())
                : "image built";
        view.finishBuildPlanSuccess(tail + " " + BuildCommand.elapsedSince(start));
        return 0;
    }
}

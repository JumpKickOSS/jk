// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.api.BuildOptions;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.GraalResolver;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EnginePrewarm;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.JobCancelledException;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.AggregateContext;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Coord;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.command.CwdModuleScope;
import cc.jumpkick.command.ToolTargets;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.manifest.PluginModule;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.tool.AppLauncher;
import cc.jumpkick.tool.JarManifest;
import cc.jumpkick.tool.LauncherName;
import cc.jumpkick.tool.ToolEnv;
import cc.jumpkick.tool.ToolLauncher;
import cc.jumpkick.tool.ToolProvenance;
import cc.jumpkick.util.AppInstallConfig;
import cc.jumpkick.util.GitUrl;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ExecPlan;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * App-install plan used by {@code jk tool install} / {@code jk install}: current project, Maven
 * coordinate, or git URL (optional {@code @}/{@code #} ref; {@code gh:owner/repo} shorthands).
 * Cache-installs the thin jar and POM into {@code repos/jk-local}; applications also get a launcher
 * under {@code ~/.jk/bin}. Plugin workers are those same repo jars — launch reconstructs the
 * classpath from the POM. jk's own modules declare {@code [install] product-lib} (the engine jar
 * into jk's product library) and {@code [install] product-bin} (the native client over the PATH
 * entry), so installing the product tree with jk is one {@code jk install}.
 */
public final class InstallCommand {

    @Nullable
    String source;

    @Nullable
    public String groupFlag;

    @Nullable
    public String nameFlag;

    @Nullable
    public String verFlag;

    @Nullable
    public String binName;

    @Nullable
    public String mainClass;

    @Nullable
    public Path cacheDirOverride;

    @Nullable
    public Path stateDirOverride;

    @Nullable
    public Path binDirOverride;
    /** From {@code jk install --lib-dir} (hidden, via ToolInstallCommand); engine default is the product lib. */
    @Nullable
    public Path libDirOverride;

    @Nullable
    public Path m2DirOverride;

    @Nullable
    public URI repoUrl;

    final BuildOptions buildOpts;
    final GlobalOptions global;

    /**
     * The delegate the tool commands drive: {@code jk install <coord>} hands its options here.
     * The options are the two things every mode reads, so they arrive by constructor rather than
     * by field assignment that a caller could forget.
     */
    public InstallCommand(GlobalOptions global, BuildOptions buildOpts) {
        this.global = global;
        this.buildOpts = buildOpts;
    }

    // --- mode 1: current project -----------------------------------------

    private int installCurrentProject() throws IOException {
        Path projectDir = global.workingDir();
        Path manifest = projectDir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(manifest)) {
            CommandWedge.printFail("Install", "no jk.toml in " + PathDisplay.styledRaw(projectDir));
            return Exit.CONFIG;
        }
        return runProjectInstallBuildPlan(projectDir);
    }

    // --- mode 2: local file ----------------------------------------------

    /**
     * Store a local file in the CAS and mirror it into the m2 local repo under its Maven coordinate
     * (the {@code mvn install} equivalent for pre-built artifacts). The coordinate is auto-detected
     * from {@code META-INF/maven/.../pom.properties} for {@code .jar} files; {@code --group}, {@code
     * --name}, and {@code --ver} override or supply missing fields.
     */
    /** Package-private: `jk tool install <file> --group/--name/--ver` delegates here. */
    public int installFromFile(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            CommandWedge.printFail("Install", PathDisplay.styled(filePath) + ": no such file");
            return Exit.CONFIG;
        }

        boolean isJar =
                filePath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
        Optional<Coordinate> detected = Optional.empty();
        if (isJar) {
            try {
                detected = JarManifest.coordinateFrom(filePath);
            } catch (IOException ignored) {
            }
        }

        String group = coalesce(groupFlag, detected.map(Coordinate::group).orElse(null));
        String artifact = coalesce(nameFlag, detected.map(Coordinate::artifact).orElse(null));
        String version = coalesce(verFlag, detected.map(Coordinate::version).orElse(null));

        if (group == null || artifact == null || version == null) {
            if (!isJar) {
                CommandWedge.printFail("Install", "--group, --name, and --ver are required for non-JAR files");
            } else {
                StringBuilder msg = new StringBuilder("jk install: could not detect");
                if (group == null) msg.append(" group");
                if (artifact == null) msg.append(" name");
                if (version == null) msg.append(" version");
                msg.append(" from JAR metadata");
                if (group == null) msg.append("; supply --group");
                if (artifact == null) msg.append("; supply --name");
                if (version == null) msg.append("; supply --ver");
                CliOutput.err(msg.toString());
            }
            return Exit.USAGE;
        }

        Path cache = cacheDir();
        Files.createDirectories(cache);
        Coordinate coord = Coordinate.of(group, artifact, version);
        // File-install writes directly to repos/jk-local/ (the JAR is already on disk, no project
        // metadata for a POM, so ~/.m2 write is not appropriate here). Route to the store root:
        // resolvers read repos/jk-local and the classpath CAS from the store.
        RepoArtifactStore.writeToLocalStore(JkStores.store(), MavenLayout.artifactPath(coord), filePath);

        if (!global.outputIsJson()) {
            CliOutput.out("Installed " + Coords.gav(coord) + " to the local store");
        }
        return 0;
    }

    private static @Nullable String coalesce(@Nullable String flag, @Nullable String detected) {
        return (flag != null && !flag.isBlank()) ? flag : detected;
    }

    // --- mode 3: Maven coord ---------------------------------------------

    /**
     * Resolve+fetch a published tool (engine-hosted {@code tool-resolve-request}), then write the
     * launcher client-side.
     */
    private int installFromMaven(String coord) throws IOException, InterruptedException {
        ToolTargets.Resolved resolved;
        try {
            resolved = ToolTargets.resolve(coord);
        } catch (ToolTargets.TargetException e) {
            CliOutput.err(e.getMessage());
            return Exit.USAGE;
        }
        String bin = binName != null && !binName.isBlank() ? binName : resolved.defaultBin();
        Integer invalidBin = rejectInvalidLauncherName(bin);
        if (invalidBin != null) return invalidBin;
        Path cacheDir = cacheDir();
        Path envsRoot = JkDirs.toolEnvsDir(stateDir());
        Path binDir = binDir();
        Files.createDirectories(cacheDir);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);

        ToolEnv env;
        EngineRequests.ToolResolveOutcome outcome;
        try {
            outcome = EngineClient.runToolResolve(
                    EnginePaths.current(),
                    new EngineRequests.ToolResolveRequest(
                            resolved.coordSpec(), List.of(), bin, mainClass, repoUrl, cacheDir),
                    steps -> BuildPlanConsole.chooseConsoleListener("install-maven", steps, mode));
        } catch (IOException e) {
            CommandWedge.printFail("Install", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!outcome.result().success() || outcome.mainClass() == null || outcome.coord() == null) {
            return failureExit(outcome.result(), "jk install", cacheDir);
        }
        env = new ToolEnv(bin, Coordinate.parse(outcome.coord()), outcome.mainClass(), outcome.classpath());

        Path launcher = ToolLauncher.install(
                envsRoot,
                binDir,
                JavaHomes.runningJavaHome(),
                env,
                new ToolProvenance("gav", coord, env.primary().toGav()),
                List.of());
        announceInstall(Coords.gav(env.primary()), launcher, binDir);
        return 0;
    }

    // --- mode 4: git URL -------------------------------------------------

    /** Package-private: {@code jk tool install <git-url>} delegates here. */
    public int installFromGit(String input) throws IOException, InterruptedException {
        UrlAndRef split = splitUrlRef(input);
        String expanded = GitUrl.expand(split.url());
        String canonical = GitUrl.canonicalize(split.url());
        String refStr = split.ref() != null ? split.ref() : "main";
        Path cacheDir = cacheDir();
        Files.createDirectories(cacheDir);
        boolean refresh = SessionContext.current().config().forceOr(false);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);

        BuildPlanResult fetchResult;
        Path checkout;
        String sha;
        // Engine-hosted clone: checkout path + sha ride the terminal plan-finish.
        EngineRequests.GitFetchOutcome outcome;
        try {
            outcome = EngineClient.runGitFetch(
                    EnginePaths.current(),
                    new EngineRequests.GitFetchRequest(expanded, canonical, refStr, cacheDir, refresh),
                    steps -> BuildPlanConsole.chooseConsoleListener("install-git-fetch", steps, mode));
        } catch (IOException e) {
            CommandWedge.printFail("Install", e.getMessage());
            return Exit.SOFTWARE;
        }
        fetchResult = outcome.result();
        checkout = outcome.checkout();
        sha = outcome.sha();

        if (!fetchResult.success() || checkout == null || sha == null) {
            for (BuildPlanResult.Diagnostic d : fetchResult.errors()) {
                if ("no-jk-toml".equals(d.code())) return Exit.SOFTWARE;
            }
            return failureExit(fetchResult, "jk install", cacheDir);
        }
        if (!global.outputIsJson()) {
            CliOutput.out(
                    "Fetched " + expanded + " @ " + refStr + " (" + sha.substring(0, Math.min(7, sha.length())) + ")");
        }

        // After fetch, hand off to the same project-install plan used by
        // mode 1, but with the checkout dir instead of the user's CWD.
        return runProjectInstallBuildPlan(checkout);
    }

    // --- shared project-install plan ---------------------------------

    /** Package-private: {@code jk tool install <project-dir>} delegates here. */
    public int runProjectInstallBuildPlan(Path projectDir) throws IOException {
        if (binName != null && !binName.isBlank()) {
            Integer invalidBin = rejectInvalidLauncherName(binName);
            if (invalidBin != null) return invalidBin;
        }
        Path cacheDir = cacheDir();
        Path binDir = binDir();

        // Validate up front: a non-native application needs a main class for its
        // launcher. (Done here, not in a step, so we fail before building.) Spring Boot
        // projects are exempt — the boot jar carries Start-Class in its manifest (resolved
        // by scan at package time) and the launcher runs it with -jar. Thin client: the
        // parsed summary comes from the engine, never a client-side parse.
        ProjectInfo proj = projectInfo(projectDir);
        if (proj.error() != null) {
            CommandWedge.printFail("Install", proj.error());
            return Exit.CONFIG;
        }
        CwdModuleScope.Resolved cwdScope = CwdModuleScope.resolve(projectDir, null, proj);
        // A pinned JDK that is not installed downloads here, with the `jk jdk install` bar, before
        // the build console opens — never as a silent step inside the engine.
        if (!JdkPreflight.ensure(projectDir, proj, null, BuildPlanConsole.modeFor(global))) return Exit.FAILURE;
        if (proj.workspaceRoot() || cwdScope.workspaceMember()) {
            return runWorkspaceInstall(cwdScope.workspaceRoot(), cwdScope);
        }
        if (proj.application()
                && "DISABLED".equals(proj.nativeMode())
                && proj.mainClass().isEmpty()
                && !proj.springBoot()) {
            CommandWedge.printFail(
                    "Install",
                    "application project at " + PathDisplay.styledRaw(projectDir)
                            + " has no `main` class set in [application]");
            return Exit.USAGE;
        }
        // ALWAYS: native is part of the standard build and install produces a native binary.
        // SUPPORTED: user runs `jk native` explicitly; install deploys the jar.
        boolean isNative = proj.application() && "ALWAYS".equals(proj.nativeMode());

        // `jk build` no longer auto-builds native (that's `jk native`), so an installed native
        // application builds its binary here. Resolve the GraalVM up front — before any progress
        // UI opens, and before the request ships (a prompt/install owns this terminal and must
        // never run inside the engine).
        Path graalHome = null;
        if (isNative) {
            Optional<Path> resolved =
                    new GraalResolver(null, false, BuildPlanConsole.modeFor(global)).resolve(projectDir, proj.graal());
            if (resolved.isEmpty()) return 1; // GraalResolver already printed why
            graalHome = resolved.get();
        }

        // Build + cache-install through the shared InstallPlans plan (jar always; assembly/native
        // per jk.toml; jar + generated pom into ~/.m2 / repos/jk-local) — engine-hosted for a real
        // invocation, in-process for the test-only bypass. The make-install half runs below,
        // client-side either way: it writes the user-home launcher/binary this process owns.
        // The console `jk build`'s single-project path opens: the live region with the bar and
        // countdown, the diagnostics above the settle line, the chip. The copy step under it is this
        // process's own and reports after the chip — it writes the user-home launcher and the
        // product layout, which the engine never touches.
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        String target = ProjectInfos.buildTarget(projectDir.resolve(ManifestPaths.MANIFEST), projectDir);
        ConsoleSpec spec = new ConsoleSpec(
                "Install",
                r -> BuildTails.buildOk() + BuildTails.builtArtifact(projectDir, proj),
                r -> Coord.module(target).renderLine(),
                true);
        BuildPlanResult result;
        TestSummary testResult;
        var session = SessionContext.current();
        TestSummary[] testResultHolder = new TestSummary[1];
        try {
            result = EngineClient.runInstall(
                    EnginePaths.current(),
                    new EngineRequests.InstallRequest(
                            projectDir,
                            cacheDir,
                            m2Dir(),
                            graalHome,
                            buildOpts.skipTests,
                            session.offline(),
                            session.force(),
                            global.verbose),
                    steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, target),
                    testResultHolder);
        } catch (JobCancelledException e) {
            CommandWedge.printFail("Install", "job was cancelled");
            return Exit.FAILURE;
        } catch (IOException e) {
            CommandWedge.printFail("Install", e.getMessage());
            return Exit.SOFTWARE;
        }
        testResult = testResultHolder[0];

        if (!result.success()) {
            if (testResult != null && !testResult.allPassed()) return 4;
            return failureExit(result, "jk install", cacheDir);
        }

        Coordinate coord = Coordinate.of(proj.group(), proj.name(), proj.version());
        Path launcher = null;
        // Same gate as the workspace path: a declared product-lib module routes through
        // EngineInstall (downgrade refusal, pointer stamping) no matter which entry point ran the
        // install — the generic copy below provides none of that.
        boolean productLib = !proj.productLib().isBlank();
        boolean productBin = !proj.productBin().isBlank();
        if (productLib || productBin || (!isPluginWorker(proj, projectDir) && proj.application())) {
            try {
                launcher = applyInstallPlan(projectDir, cacheDir, proj.productLib(), proj.productBin());
            } catch (IOException e) {
                CommandWedge.printFail("Install", "make install failed: " + e.getMessage());
                return Exit.FAILURE;
            }
        }
        if (!global.outputIsJson()) {
            for (String line :
                    installedLines(Coords.gav(coord), launcher, binDir, proj.productLib(), proj.productBin())) {
                CliOutput.out(line);
            }
        }
        return 0;
    }

    /**
     * Set once a pass has replaced the engine: the pass that follows re-shelves under the new
     * engine and is the last. A pass is run by the engine the home names when it starts; when it
     * materializes another engine, that engine runs one more pass, which re-packages and re-shelves
     * every artifact the displaced engine produced.
     */
    private boolean reshelving;

    /**
     * The PATH client a pass put in place ({@code [install] product-bin}), or null when none was.
     * A client of another version than the engine it installed hands the re-shelving pass to
     * this one and names it.
     */
    private @Nullable Path installedClient;

    private int runWorkspaceInstall(Path wsRoot, CwdModuleScope.Resolved cwdScope) throws IOException {
        Optional<String> engineBefore = liveEngineSha();
        Path cacheDir = cacheDir();
        ProjectInfo root = projectInfo(wsRoot);
        if (root.error() != null) {
            CommandWedge.printFail("Install", root.error());
            return Exit.CONFIG;
        }
        List<Path> moduleDirs = new ArrayList<>();
        for (String rel : root.moduleDirs()) {
            Path d = Path.of(rel).isAbsolute() ? Path.of(rel) : wsRoot.resolve(rel);
            moduleDirs.add(d.toAbsolutePath().normalize());
        }
        // One projectInfo request per module: the Graal scan, the product-lib stale check and the
        // copy step after the build all read the same parsed-manifest summary, and every call is a full
        // engine round-trip — three sweeps over a 30-module workspace is ~90 requests for nothing.
        Map<Path, ProjectInfo> infoByDir = new LinkedHashMap<>();
        for (Path mod : moduleDirs) infoByDir.put(mod, projectInfo(mod));
        List<AlwaysNativeGraal.Module> alwaysNative = new ArrayList<>();
        for (Path mod : moduleDirs) {
            var info = Objects.requireNonNull(infoByDir.get(mod), () -> "no project info for " + mod);
            if (info.error() != null || !"ALWAYS".equals(info.nativeMode())) continue;
            alwaysNative.add(new AlwaysNativeGraal.Module(mod, info.graal()));
        }
        Optional<Map<Path, Path>> resolved = AlwaysNativeGraal.homes(
                alwaysNative, new GraalResolver(null, false, BuildPlanConsole.modeFor(global))::resolve);
        if (resolved.isEmpty()) return 1;
        Map<Path, Path> graalByDir = resolved.get();
        List<String> tokens = cwdScope.scoped() ? List.of(cwdScope.modulesSpec()) : List.of();
        Set<Path> selected = cwdScope.scoped() ? Set.of(cwdScope.workingDir()) : Set.of();
        WorkspaceRequest req = new WorkspaceRequest(
                        wsRoot,
                        cacheDir,
                        null,
                        0,
                        null,
                        buildOpts.skipTests,
                        global.verbose,
                        0,
                        selected.isEmpty() ? null : selected,
                        true,
                        true)
                .withModules(tokens)
                .withSpec(WorkspaceSpec.install(selected, graalByDir, m2Dir()));
        // The shared workspace renderer, exactly as build/test/native drive it, on the same mode
        // axis they choose on: a live region for a terminal, the append-only block for
        // --output json / --verbose. A hand-rolled listener here is what made a failed workspace
        // install print nothing at all on either stream; the headless listener on a terminal is
        // what made a succeeding one look like a log instead of a build.
        boolean json = global.outputIsJson();
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        boolean live = mode == BuildPlanConsole.Mode.AUTO || mode == BuildPlanConsole.Mode.QUIET;
        var pass = new WorkspacePass(wsRoot, req, moduleDirs, infoByDir, cacheDir);
        int exit = live ? installWorkspaceLive(pass, mode) : installWorkspaceHeadless(pass, json);
        if (exit != 0) return exit;
        Optional<String> engineAfter = liveEngineSha();
        if (!reshelving && engineReplaced(engineBefore, engineAfter)) {
            // An engine serves only clients of its own version. When the pass materialized an
            // engine of another version than this client's, no request from here can reach it, so
            // the re-shelving pass is the tree's own client's to run: the install is complete as
            // far as this client can take it, and says what runs the rest.
            String handover = handoverNotice(JkVersion.VERSION, liveEngineVersion(), installedClient);
            if (handover != null) {
                if (!json) CommandWedge.printOk("Install", handover);
                return Exit.SUCCESS;
            }
            reshelving = true;
            if (!json) {
                CommandWedge.printOk(
                        "Install",
                        "the engine changed under this install — re-shelving the workers it packaged"
                                + " with the freshly built engine");
            }
            // The pointer names another jar now; the next request probes again and takes the
            // resident engine over, so the second pass runs on the engine this tree built. That
            // handoff is asserted, not assumed: the pass re-shelves under whichever engine serves
            // it, and a shelf packaged by the displaced engine would need yet another install.
            int refused = handoffRefusal(engineAfter);
            if (refused != Exit.SUCCESS) return refused;
            return runWorkspaceInstall(wsRoot, cwdScope);
        }
        String notice = shelfBehindEngineNotice(reshelving, engineBefore, engineAfter);
        if (notice != null && !json) {
            Theme t = Theme.active();
            CliOutput.err(Theme.colorize(Glyphs.BANG, t.warning()) + " " + notice);
        }
        return 0;
    }

    /** One pass of a workspace install: the request the engine runs and what the copy step needs after it. */
    private record WorkspacePass(
            Path wsRoot,
            WorkspaceRequest req,
            List<Path> moduleDirs,
            Map<Path, ProjectInfo> infoByDir,
            Path cacheDir) {}

    /** What the copy step put in place: the module count for the wedge, the lines naming where each went. */
    private record Applied(int modules, List<String> lines) {}

    /**
     * Workspace install in a live region, exactly as {@code jk build} renders a workspace: the
     * wedge, the bar calibrated to the whole graph with its countdown, the modules building right
     * now, a completion tail. The copy step that makes this an install runs after the engine
     * settles and before the region does, so its lines land above the one success wedge.
     */
    private int installWorkspaceLive(WorkspacePass pass, BuildPlanConsole.Mode mode) throws IOException {
        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        // Start the engine before the region opens, as build does: its wedge must not paint under it.
        EnginePrewarm.ensure();
        long start = Clock.SYSTEM.nanos();
        JkManager view = JkManager.plan(CliOutput.stdout(), "Install", animate);
        view.setPlanCoord(BuildCommand.projectGaLabel(pass.wsRoot()));
        view.setWindowTitle("JumpKick - Installing " + BuildCommand.projectGavLabel(pass.wsRoot()) + "...");
        AggregateContext agg = new AggregateContext(view);
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Install", true), pass.wsRoot(), null, false);
        WorkspaceResult result;
        try {
            result = EngineClient.buildWorkspace(EnginePaths.current(), pass.req(), run.live(view, agg));
        } catch (JobCancelledException e) {
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            view.finishBuildPlanCancelled(List.of());
            return Exit.FAILURE;
        } catch (IOException e) {
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        long elapsed = BuildTails.elapsedMsSince(start);
        int modules = 0;
        if (result.success() && !result.cancelled() && result.errors().isEmpty()) {
            Applied applied;
            try {
                applied = applyWorkspaceInstall(pass, result);
            } catch (IOException e) {
                run.finishEvent(false, elapsed);
                view.finishBuildPlanFailure("make install failed: " + e.getMessage(), run.deferredOutput());
                return Exit.FAILURE;
            }
            modules = applied.modules();
            for (String line : applied.lines()) run.defer(line);
        }
        int installed = modules;
        var tails = new WorkspaceRunView.Tails(
                (r, planned) -> installTail(installed, start),
                r -> BuildTails.failureTail(WorkspaceRunView.failedSubject(r, "install"), start));
        return run.settleLive(view, agg, result, elapsed, tails, settled -> {});
    }

    /**
     * Workspace install without a region ({@code --output json} / {@code --verbose}): the
     * append-only block per module {@code jk build} prints in those modes, then the copy step's
     * lines and one wedge.
     */
    private int installWorkspaceHeadless(WorkspacePass pass, boolean json) throws IOException {
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Install", true), pass.wsRoot(), null, json);
        long start = Clock.SYSTEM.nanos();
        WorkspaceResult result;
        try {
            result = EngineClient.buildWorkspace(EnginePaths.current(), pass.req(), run.headless());
        } catch (JobCancelledException e) {
            return cancelled(run, start, json);
        } catch (IOException e) {
            run.finishEvent(false, BuildTails.elapsedMsSince(start));
            CommandWedge.printFail("Install", e.getMessage());
            return Exit.SOFTWARE;
        }
        long elapsed = BuildTails.elapsedMsSince(start);
        // A cancel is not a failure: it names no module and deserves no error list.
        if (result.cancelled()) return cancelled(run, start, json);
        if (!result.success()) {
            run.finishEvent(false, elapsed);
            if (!json) {
                // Graph/lock errors never reach a module listener, so they have no module to blame
                // and nothing else prints them.
                for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
                CommandWedge.printFail(
                        "Install", BuildTails.failureTail(WorkspaceRunView.failedSubject(result, "install"), start));
            }
            return result.exitCode() == 0 ? Exit.FAILURE : result.exitCode();
        }
        run.finishEvent(true, elapsed);
        Applied applied;
        try {
            applied = applyWorkspaceInstall(pass, result);
        } catch (IOException e) {
            CommandWedge.printFail("Install", "make install failed: " + e.getMessage());
            return Exit.FAILURE;
        }
        if (json) return 0;
        for (String line : applied.lines()) CliOutput.out(line);
        CommandWedge.printOk("Install", installTail(applied.modules(), start));
        return 0;
    }

    /**
     * The copy step — the client half of a workspace install, after the engine's half succeeded.
     * Modules the engine built, plus any whose declared product-lib destination this process owns
     * and finds stale. The second half is the point: a module the forecast skipped as clean has
     * correct BUILD outputs, which says nothing about whether the artifact reached jk's product
     * layout — and that destination is the client's to answer for, because the engine daemon's
     * product layout is not necessarily the caller's.
     */
    private Applied applyWorkspaceInstall(WorkspacePass pass, WorkspaceResult result) throws IOException {
        Path binDir = binDir();
        Map<Path, ProjectInfo> infoByDir = pass.infoByDir();
        Set<Path> installed = new LinkedHashSet<>();
        for (var m : result.modules()) {
            if (m.success()) installed.add(m.dir());
        }
        for (Path mod : pass.moduleDirs()) {
            if (installed.contains(mod)) continue;
            ProjectInfo info = infoByDir.get(mod);
            if (productLibStale(info) || productBinStale(info)) installed.add(mod);
        }
        int modules = 0;
        List<String> lines = new ArrayList<>();
        for (Path mod : installed) {
            // Engine-reported module dirs are normalized the same way moduleDirs was; fall back to
            // a fresh request only for a dir the sweep above never saw.
            ProjectInfo info = infoByDir.containsKey(mod) ? infoByDir.get(mod) : projectInfo(mod);
            // A coordinator root builds as a unit but publishes nothing — announcing it would
            // claim an install the plan never carried a `cache-install` step for.
            if (info.coordinatorOnly()) continue;
            Path launcher = null;
            boolean productLib = !info.productLib().isBlank();
            boolean productBin = !info.productBin().isBlank();
            if (productLib || productBin || (!isPluginWorker(info, mod) && info.application())) {
                launcher = applyInstallPlan(mod, pass.cacheDir(), info.productLib(), info.productBin());
            }
            if (productBin && launcher != null) installedClient = launcher;
            String coord = Coords.gav(Coordinate.of(info.group(), info.name(), info.version()));
            lines.addAll(installedLines(coord, launcher, binDir, info.productLib(), info.productBin()));
            modules++;
        }
        return new Applied(modules, lines);
    }

    /** The success wedge of a workspace install: what this pass put in place, or that nothing needed to be. */
    static String installTail(int modules, long start) {
        String ok = Theme.colorize("Install successful", Theme.active().success());
        if (modules == 0) return ok + ", everything already installed " + BuildTails.elapsedSince(start);
        return ok
                + ", installed "
                + Theme.colorize(String.valueOf(modules), Theme.active().focused())
                + " module"
                + (modules == 1 ? "" : "s")
                + " "
                + BuildTails.elapsedSince(start);
    }

    /**
     * What the last pass leaves unsaid when it too replaced the engine: the passes are bounded at
     * two, so a shelf packaged by an engine the home no longer names is the user's to finish. Null
     * when the home names the engine that ran the final pass — the shelf is that engine's.
     */
    public static @Nullable String shelfBehindEngineNotice(
            boolean lastPass, Optional<String> before, Optional<String> after) {
        if (!lastPass || !engineReplaced(before, after)) return null;
        return "the re-shelving pass ended on engine " + shortSha(after) + " while its shelf was packaged by engine "
                + shortSha(before) + " — run `jk install` once more so the shelf is the live engine's";
    }

    /**
     * What a pass that installed an engine of another version than {@code clientVersion} leaves
     * to the tree's own client: the home names that engine now, an engine serves only clients of
     * its own version, so the re-shelving pass runs when {@code client} (the PATH client the pass
     * installed, when it did) is the one asking. Null when the versions agree — this client is
     * served by the new engine and runs the pass itself — or when the home names no engine.
     */
    public static @Nullable String handoverNotice(
            String clientVersion, Optional<String> engineVersion, @Nullable Path client) {
        if (engineVersion.isEmpty() || engineVersion.get().equals(clientVersion)) return null;
        String tree = engineVersion.get();
        String next = client != null ? "`" + client + " install`" : "`jk install` as jk " + tree;
        return "the home names jk " + tree + "'s engine now, which serves jk " + tree + " clients; this one is jk "
                + clientVersion + ", so the re-shelving pass runs on the tree's own client — run " + next
                + " once more";
    }

    /**
     * Forget the engine this process ensured, bring up the one the next request will be served
     * by, and check it is the one {@code pointer} names. {@link Exit#SUCCESS} when the re-shelving
     * pass may run; otherwise the failure is printed and its exit code returned.
     */
    private static int handoffRefusal(Optional<String> pointer) {
        EngineClient.forgetEnsuredEngine();
        String refused;
        try {
            EngineProbe.Handshake live = EngineClient.ensureRunning(EnginePaths.current(), JkVersion.VERSION);
            refused = handoffMismatch(live.buildId(), pointer);
        } catch (IOException e) {
            refused = "re-shelving pass: " + e.getMessage();
        }
        if (refused == null) return Exit.SUCCESS;
        CommandWedge.printFail("Install", refused);
        return Exit.SOFTWARE;
    }

    /**
     * Why the re-shelving pass must not run: the engine answering the endpoint ({@code
     * liveBuildId}, a prefix of its jar's digest) is not the one the home's pointer names. Null
     * when they agree, or when either side has no identity to compare (an engine run from a
     * classes directory, a home with no pointer).
     */
    public static @Nullable String handoffMismatch(String liveBuildId, Optional<String> pointerSha) {
        if (liveBuildId.isEmpty() || pointerSha.isEmpty()) return null;
        if (pointerSha.get().toLowerCase(Locale.ROOT).startsWith(liveBuildId.toLowerCase(Locale.ROOT))) return null;
        return "the re-shelving pass would run on engine " + liveBuildId + " while the home names engine "
                + shortSha(pointerSha) + " — the shelf would be packaged by an engine the home no longer names;"
                + " run `jk engine stop` and then `jk install` again";
    }

    private static String shortSha(Optional<String> sha) {
        return sha.map(s -> s.length() > 12 ? s.substring(0, 12) : s).orElse("(none)");
    }

    /** The engine jar the product library's pointer names, by digest; empty when the home has none. */
    private static Optional<String> liveEngineSha() {
        return EngineInstall.current().currentInstall().map(EngineInstall.Materialized::engineSha);
    }

    /** The product version of the engine the pointer names; empty when the home has none. */
    private static Optional<String> liveEngineVersion() {
        return EngineInstall.current().currentInstall().map(EngineInstall.Materialized::version);
    }

    /**
     * Whether an install pass left the home naming another engine than the one it started under.
     * Every artifact-shaped action key names the engine that packaged the artifact, so a shelf the
     * displaced engine filled is one the new engine packages afresh: one more pass under it brings
     * the shelf to the tree. A home that named no engine before is treated the same way — whatever
     * ran the pass is not the engine the home names now.
     */
    public static boolean engineReplaced(Optional<String> before, Optional<String> after) {
        if (after.isEmpty()) return false;
        return before.isEmpty() || !before.get().equalsIgnoreCase(after.get());
    }

    private static int cancelled(WorkspaceRunView run, long startNanos, boolean json) {
        run.finishEvent(false, BuildTails.elapsedMsSince(startNanos));
        if (!json) CommandWedge.printFail("Install", "job was cancelled");
        return Exit.FAILURE;
    }

    /**
     * The artifact a product-lib install materializes: the jar the exec plan links, which for an
     * assembly module is the {@code -all.jar}. {@code null} when the plan links nothing, which is
     * a plan with no packaged output to install.
     */
    private static @Nullable Path productLibSource(ExecPlan plan) {
        for (String src : plan.linkSrcs()) {
            if (src.endsWith(".jar")) return Path.of(src);
        }
        return plan.linkSrcs().isEmpty() ? null : Path.of(plan.linkSrcs().get(0));
    }

    /**
     * Whether {@code info}'s declared product-lib destination is missing or holds other bytes than
     * the artifact this tree built. False for every module that declares none.
     */
    public static boolean productLibStale(@Nullable ProjectInfo info) {
        if (info == null || info.error() != null || info.productLib().isBlank()) return false;
        String builtPath = info.assembly() ? info.assemblyJarPath() : info.mainJarPath();
        if (builtPath == null || builtPath.isBlank()) return false;
        Path built = Path.of(builtPath);
        if (!Files.isRegularFile(built)) return false; // nothing built to install
        try {
            String builtSha = Hashing.sha256Hex(built);
            return new EngineInstall(JkDirs.productLib())
                    .engineSha(info.version())
                    .filter(sha -> sha.equalsIgnoreCase(builtSha))
                    .isEmpty();
        } catch (IOException | RuntimeException unreadable) {
            return true; // cannot prove it current — install
        }
    }

    /**
     * Whether {@code info}'s declared PATH client is missing or holds other bytes than the native
     * binary this tree built. False for every module that declares none.
     */
    public static boolean productBinStale(@Nullable ProjectInfo info) {
        if (info == null || info.error() != null || info.productBin().isBlank()) return false;
        return productBinStale(info, JkDirs.binDir());
    }

    public static boolean productBinStale(ProjectInfo info, Path binDir) {
        // The JVM launcher beside the client is part of the install: a home without it is stale
        // whether or not a native client was built, since the launcher needs no GraalVM.
        if (!Files.isRegularFile(binDir.resolve(AppLauncher.launcherFileName(info.productBin() + "-jvm")))) return true;
        String builtPath = info.nativeBinPath();
        if (builtPath == null || builtPath.isBlank()) return false;
        Path built = Path.of(builtPath);
        if (!Files.isRegularFile(built)) return false; // nothing built to install
        Path live = binDir.resolve(BuildLayout.nativeExecutableFileName(info.productBin()));
        if (!Files.isRegularFile(live)) return true;
        try {
            return !Hashing.sha256Hex(live).equalsIgnoreCase(Hashing.sha256Hex(built));
        } catch (IOException | RuntimeException unreadable) {
            return true; // cannot prove it current — install
        }
    }

    private static boolean isPluginWorker(ProjectInfo proj, Path projectDir) {
        return PluginModule.isWorker(projectDir) || "cc.jumpkick.plugin.process.PluginMain".equals(proj.mainClass());
    }

    /** The engine's parsed-project summary. */
    private ProjectInfo projectInfo(Path projectDir) throws IOException {
        return EngineClient.projectInfo(EnginePaths.current(), projectDir);
    }

    /**
     * The {@code make install} step for applications, thin-client style: the engine computes the
     * plan (link set + launcher script or a direct native-binary link); this process applies it —
     * copy each pair, write the launcher, mark executables. Returns the launcher path. A module
     * that declares {@code [install] product-lib} is materialized into jk's own product library
     * instead of linked into {@code ~/.jk/bin}.
     */
    private @Nullable Path applyInstallPlan(Path projectDir, Path cacheDir, String productLib, String productBin)
            throws IOException {
        ExecPlan plan = EngineClient.execPlan(
                EnginePaths.current(),
                projectDir,
                cacheDir,
                "install",
                mainClass,
                binName,
                binDirOverride,
                libDirOverride);
        if (plan.error() != null) {
            throw new IOException(plan.error());
        }
        if (plan.linkSrcs().isEmpty()
                && plan.launcherScript().isEmpty()
                && plan.binPath().isEmpty()) {
            return null;
        }
        // Declared product-lib install: route through EngineInstall so a new jar is published
        // beside any mapped predecessor, a downgrade is refused, and the pointer toml is written
        // coherently — none of which the generic copy + AppInstallConfig.write below provides.
        // The manifest declares product-lib intent; a destination path cannot establish it.
        if (!productLib.isBlank()) {
            Path src = productLibSource(plan);
            if (src == null) return null;
            String version = engineInstallVersion(projectDir, src);
            new EngineInstall(JkDirs.productLib()).materializeFromFiles(version, JkStores.storeCas(), src);
            return null; // a jar the client launches — no launcher/bin to link
        }
        if (!productBin.isBlank()) {
            // The JVM launcher first: on a machine that linked no native client it is the whole
            // install and the path reported; beside a native client it is written and the native
            // swap is what is reported, as before.
            Path jvmLauncher = null;
            if (!plan.launcherScript().isEmpty()) {
                jvmLauncher = Path.of(plan.launcherPath());
                Files.createDirectories(jvmLauncher.getParent());
                Files.writeString(jvmLauncher, plan.launcherScript());
                markExecutable(jvmLauncher);
            }
            if (plan.linkSrcs().isEmpty()) {
                if (jvmLauncher != null) {
                    CliOutput.err("no native client was built here — " + jvmLauncher.getFileName()
                            + " runs jk on a JVM; the PATH client " + productBin + " is unchanged");
                }
                return jvmLauncher;
            }
            return installProductBin(Path.of(plan.linkSrcs().get(0)), binDir(), JkStores.storeCas());
        }
        for (int i = 0; i < plan.linkSrcs().size(); i++) {
            Path src = Path.of(plan.linkSrcs().get(i));
            Path dest = Path.of(plan.linkDests().get(i));
            Files.createDirectories(dest.getParent());
            // COPY, never link: src is a target/ artifact the next build rewrites in place —
            // an installed tool must be a stable snapshot, not an alias of the build tree.
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        writeAppInstallConfig(projectDir, plan);
        if (!plan.launcherScript().isEmpty()) {
            Path launcher = Path.of(plan.launcherPath());
            Files.createDirectories(launcher.getParent());
            Files.writeString(launcher, plan.launcherScript());
            markExecutable(launcher);
            return launcher;
        }
        // Native binary linked directly into bin — no script, just the exec bit.
        Path bin = Path.of(plan.binPath());
        markExecutable(bin);
        return bin;
    }

    /**
     * jk's own client replaces the PATH client. The built binary is ingested into the CAS and the
     * {@code bin/} entry linked from that immutable blob, exactly as {@code jk self update} installs
     * a release — a link straight into {@code target/} would make the installed client an alias of
     * whatever the next build writes there. The previous client is parked as {@code .old}, so the
     * process running this install keeps its inode; {@code jkx} is re-linked to the new binary.
     */
    public static Path installProductBin(Path builtBinary, Path binDir, Cas cas) throws IOException {
        String sha = Hashing.sha256Hex(builtBinary);
        cas.putFile(builtBinary, sha);
        EngineInstall.installBinaries(cas.pathFor(sha), binDir);
        return binDir.resolve(BuildLayout.nativeExecutableFileName("jk"));
    }

    private static @Nullable Integer rejectInvalidLauncherName(String name) {
        var error = LauncherName.validationError(name);
        if (error.isEmpty()) return null;
        CommandWedge.printFail("Install", error.get());
        return Exit.USAGE;
    }

    /**
     * Persist {@code <home>/config/<bin>/config.toml} for fat/minified installs (jar under
     * {@code productLib/<bin>/}). Honors {@code [application].config} templates and {@code
     * jk-config.*} system properties. The engine is not this path — it writes {@code jk-engine.toml}
     * beside the jar via {@link EngineInstall}.
     */
    private void writeAppInstallConfig(Path projectDir, ExecPlan plan) throws IOException {
        if (plan.linkDests().isEmpty()) return;
        Path dest = Path.of(plan.linkDests().get(0));
        Path parent = dest.getParent();
        if (parent == null) return;
        // Only fat/minified installs (jar at productLib/<bin>/<jar>) carry a config entry keyed by
        // <bin>. A native binary lands directly in the PATH bin dir, so its parent is that bin dir —
        // treating it as the app name wrote a junk config/bin/config.toml.
        Path grand = parent.getParent();
        Path productLib = JkDirs.current().productLibDir().toAbsolutePath().normalize();
        if (grand == null || !grand.toAbsolutePath().normalize().equals(productLib)) return;
        String bin = parent.getFileName().toString();
        if (bin.isBlank()) return;
        Map<String, String> keys = new LinkedHashMap<>(AppInstallConfig.jkConfigProperties());
        putInstalledJarKeys(keys, dest);
        keys.putIfAbsent("name", bin);
        String templateRel = "";
        try {
            var info = projectInfo(projectDir);
            if (info.version() != null && !info.version().isBlank()) {
                keys.putIfAbsent("version", info.version());
            }
            if (info.applicationConfig() != null && !info.applicationConfig().isBlank()) {
                templateRel = info.applicationConfig();
            }
        } catch (IOException ignored) {
            // version / template path are best-effort
        }
        if (!templateRel.isBlank()) {
            Path templateFile = projectDir.resolve(templateRel);
            if (Files.isRegularFile(templateFile)) {
                AppInstallConfig.writeTemplate(JkDirs.current(), bin, Files.readString(templateFile), keys);
                return;
            }
        }
        AppInstallConfig.write(JkDirs.current(), bin, keys);
    }

    /** Record the installed jar basename for a {@code jk install} app. */
    static void putInstalledJarKeys(Map<String, String> keys, Path installedJar) {
        keys.put("jar", installedJar.getFileName().toString());
    }

    private static void markExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem.
        }
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Maps a failed install plan to exit code 1. Kept as a named helper so the various call sites
     * read uniformly; the listener already printed the "✗ Error" diagnostic so we don't repeat
     * ourselves.
     */
    private static int failureExit(BuildPlanResult result, String label, Path cache) {
        return 1;
    }

    /** Engine version for a self-host install: the project version, else parsed from the jar name. */
    private String engineInstallVersion(Path projectDir, Path engineJar) {
        try {
            var info = projectInfo(projectDir);
            if (info.version() != null && !info.version().isBlank()) return info.version();
        } catch (IOException ignored) {
            // fall through to the jar-name form
        }
        return EngineInstall.versionFromJarName(engineJar.getFileName().toString())
                .orElseThrow(() ->
                        new IllegalStateException("cannot determine engine version for " + engineJar.getFileName()));
    }

    private Path cacheDir() {
        return cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
    }

    private Path stateDir() {
        return stateDirOverride != null ? stateDirOverride : JkDirs.state();
    }

    private Path binDir() {
        return binDirOverride != null ? binDirOverride : JkDirs.binDir();
    }

    private Path m2Dir() {
        if (m2DirOverride != null) return m2DirOverride;
        return Path.of(System.getProperty("user.home", "."), ".m2");
    }

    private void announceInstall(String coord, Path launcher, Path binDir) {
        if (global.outputIsJson()) return;
        CliOutput.out("Installed " + coord + " → " + launcher);
        CliOutput.out("Add to PATH if needed:");
        CliOutput.out("  export PATH=\"" + binDir + ":$PATH\"");
    }

    /**
     * The lines that say where an install went. A product-lib install went into jk's own product
     * layout, not the local cache — "to the local cache" would name the one place that artifact
     * did not go. A product-bin install replaced the PATH client, and its path is the answer to
     * "which jk am I running now". An application names its launcher; a library is cache-only.
     */
    private static List<String> installedLines(
            String coord, @Nullable Path launcher, Path binDir, String productLib, String productBin) {
        if (!productLib.isBlank()) {
            return List.of("Installed " + coord + " → "
                    + PathDisplay.styledRaw(JkDirs.productLib().resolve(productLib)));
        }
        if (!productBin.isBlank()) {
            return List.of("Installed " + coord + " → "
                    + (launcher == null ? "(no native binary built)" : PathDisplay.styledRaw(launcher)));
        }
        if (launcher == null) return List.of("Installed " + coord + " to the local cache");
        return List.of(
                "Installed " + coord + " → " + launcher,
                "Add to PATH if needed:",
                "  export PATH=\"" + binDir + ":$PATH\"");
    }

    /**
     * True when {@code source} resolves to an existing regular file on disk. Checked after git-URL
     * detection so {@code file://...} URIs are handled by the git path. Uses filesystem existence as
     * the discriminator: Maven coords ({@code g:a:v}) and bare names never match an actual file.
     */
    private static boolean looksLikeFilePath(String source) {
        try {
            return Files.isRegularFile(Path.of(source));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean looksLikeGitUrl(String input) {
        return input.startsWith("git@")
                || input.startsWith("http://")
                || input.startsWith("https://")
                || input.startsWith("ssh://")
                || input.startsWith("git://")
                || input.startsWith("file://")
                || input.startsWith("gh:")
                || input.startsWith("gl:")
                || input.startsWith("bb:")
                || input.startsWith("sr:");
    }

    /** {@code <url>} or {@code <url>@<ref>} or {@code <url>#<ref>}. */
    public record UrlAndRef(String url, @Nullable String ref) {}

    public static UrlAndRef splitUrlRef(String input) {
        int hash = input.lastIndexOf('#');
        if (hash >= 0) {
            return new UrlAndRef(input.substring(0, hash), input.substring(hash + 1));
        }
        // For `@`, only treat as ref-separator when the suffix doesn't look
        // like part of a URL (no `/`, `:`, or another `@`). This avoids
        // misreading `git@github.com:foo/bar` as a ref-suffix.
        int at = input.lastIndexOf('@');
        if (at > 0) {
            String suffix = input.substring(at + 1);
            if (!suffix.isEmpty() && suffix.indexOf('/') < 0 && suffix.indexOf(':') < 0 && suffix.indexOf('@') < 0) {
                return new UrlAndRef(input.substring(0, at), suffix);
            }
        }
        return new UrlAndRef(input, null);
    }
}

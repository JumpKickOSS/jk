// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import cc.jumpkick.runtime.WorkspaceSpec;
import cc.jumpkick.tool.JarManifest;
import cc.jumpkick.tool.ToolEnv;
import cc.jumpkick.tool.ToolLauncher;
import cc.jumpkick.util.AppInstallConfig;
import cc.jumpkick.util.GitUrl;
import cc.jumpkick.util.Hashing;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * App-install plan used by {@code jk tool install} / {@code jk install}: current project, Maven
 * coordinate, or git URL (optional {@code @}/{@code #} ref; {@code gh:owner/repo} shorthands).
 * Cache-installs the thin jar and POM into {@code repos/local}; applications also get a launcher
 * under {@code ~/.local/bin}. Plugin workers are those same repo jars — launch reconstructs the
 * classpath from the POM.
 */
public final class InstallCommand {

    String source;
    String groupFlag;
    String nameFlag;
    String verFlag;
    String binName;
    String mainClass;
    Path cacheDirOverride;
    Path stateDirOverride;
    Path binDirOverride;
    Path libDirOverride;
    Path m2DirOverride;
    URI repoUrl;
    cc.jumpkick.cli.BuildOptions buildOpts;
    GlobalOptions global;

    // --- mode 1: current project -----------------------------------------

    private int installCurrentProject() throws IOException {
        Path projectDir = global.workingDir();
        Path manifest = projectDir.resolve("jk.toml");
        if (!Files.exists(manifest)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Install", "no jk.toml in " + cc.jumpkick.cli.PathDisplay.styledRaw(projectDir));
            return Exit.CONFIG;
        }
        return runProjectInstallBuildPlan(projectDir, "install");
    }

    // --- mode 2: local file ----------------------------------------------

    /**
     * Store a local file in the CAS and mirror it into the m2 local repo under its Maven coordinate
     * (the {@code mvn install} equivalent for pre-built artifacts). The coordinate is auto-detected
     * from {@code META-INF/maven/.../pom.properties} for {@code .jar} files; {@code --group}, {@code
     * --name}, and {@code --ver} override or supply missing fields.
     */
    /** Package-private: `jk tool install <file> --group/--name/--ver` delegates here. */
    int installFromFile(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Install", PathDisplay.styled(filePath) + ": no such file");
            return Exit.CONFIG;
        }

        boolean isJar = filePath.getFileName().toString().toLowerCase().endsWith(".jar");
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
                cc.jumpkick.cli.tui.CommandWedge.printFail(
                        "Install", "--group, --name, and --ver are required for non-JAR files");
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
        // File-install writes directly to repos/local/ (the JAR is already on disk, no project
        // metadata for a POM, so ~/.m2 write is not appropriate here). Route to the store root:
        // resolvers read repos/local and the classpath CAS from the store.
        cc.jumpkick.repo.RepoArtifactStore.writeToLocalStore(
                cc.jumpkick.cache.JkStores.storeRootFor(cache), MavenLayout.artifactPath(coord), filePath);

        if (!global.outputIsJson()) {
            CliOutput.out("Installed " + cc.jumpkick.cli.theme.Coords.gav(coord) + " to the local store");
        }
        return 0;
    }

    private static String coalesce(String flag, String detected) {
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
        Path cacheDir = cacheDir();
        Path envsRoot = stateDir().resolve("tools").resolve("envs");
        Path binDir = binDir();
        Files.createDirectories(cacheDir);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);

        ToolEnv env;
        cc.jumpkick.cli.engine.EngineRequests.ToolResolveOutcome outcome;
        try {
            outcome = cc.jumpkick.cli.engine.EngineClient.runToolResolve(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineRequests.ToolResolveRequest(
                            resolved.coordSpec(), List.of(), bin, mainClass, repoUrl, cacheDir),
                    steps -> BuildPlanConsole.chooseConsoleListener("install-maven", steps, mode));
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Install", e.getMessage());
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
                new cc.jumpkick.tool.ToolProvenance("gav", coord, env.primary().toGav()),
                List.of());
        announceInstall(Coords.gav(env.primary()), launcher, binDir);
        return 0;
    }

    // --- mode 4: git URL -------------------------------------------------

    /** Package-private: {@code jk tool install <git-url>} delegates here. */
    int installFromGit(String input) throws IOException, InterruptedException {
        UrlAndRef split = splitUrlRef(input);
        String expanded = GitUrl.expand(split.url());
        String canonical = GitUrl.canonicalize(split.url());
        String refStr = split.ref() != null ? split.ref() : "main";
        Path cacheDir = cacheDir();
        Files.createDirectories(cacheDir);
        boolean refresh = cc.jumpkick.config.SessionContext.current().config().forceOr(false);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);

        BuildPlanResult fetchResult;
        Path checkout;
        String sha;
        // Engine-hosted clone: checkout path + sha ride the terminal plan-finish.
        cc.jumpkick.cli.engine.EngineRequests.GitFetchOutcome outcome;
        try {
            outcome = cc.jumpkick.cli.engine.EngineClient.runGitFetch(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineRequests.GitFetchRequest(
                            expanded, canonical, refStr, cacheDir, refresh),
                    steps -> BuildPlanConsole.chooseConsoleListener("install-git-fetch", steps, mode));
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Install", e.getMessage());
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
        return runProjectInstallBuildPlan(checkout, "install-git");
    }

    // --- shared project-install plan ---------------------------------

    /** Package-private: {@code jk tool install <project-dir>} delegates here. */
    int runProjectInstallBuildPlan(Path projectDir, String planName) throws IOException {
        Path cacheDir = cacheDir();
        Path binDir = binDir();
        Path libDir = libDir();

        // Validate up front: a non-native application needs a main class for its
        // launcher. (Done here, not in a step, so we fail before building.) Spring Boot
        // projects are exempt — the boot jar carries Start-Class in its manifest (resolved
        // by scan at package time) and the launcher runs it with -jar. Thin client: the
        // parsed summary comes from the engine, never a client-side parse.
        cc.jumpkick.engine.protocol.ProjectInfo proj = projectInfo(projectDir);
        if (proj.error() != null) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Install", proj.error());
            return Exit.CONFIG;
        }
        CwdModuleScope.Resolved cwdScope = CwdModuleScope.resolve(projectDir, null, proj);
        if (proj.workspaceRoot() || cwdScope.workspaceMember()) {
            return runWorkspaceInstall(cwdScope.workspaceRoot(), cwdScope, planName);
        }
        if (proj.application()
                && "DISABLED".equals(proj.nativeMode())
                && proj.mainClass().isEmpty()
                && !proj.springBoot()) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Install",
                    "application project at " + cc.jumpkick.cli.PathDisplay.styledRaw(projectDir)
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
            Optional<Path> resolved = new cc.jumpkick.cli.GraalResolver(null, false).resolve(projectDir, proj.graal());
            if (resolved.isEmpty()) return 1; // GraalResolver already printed why
            graalHome = resolved.get();
        }

        // Build + cache-install through the shared InstallPlans plan (jar always; assembly/native
        // per jk.toml; jar + generated pom into ~/.m2 / repos/local) — engine-hosted for a real
        // invocation, in-process for the test-only bypass. The make-install half runs below,
        // client-side either way: it writes the user-home launcher/binary this process owns.
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        BuildPlanResult result;
        TestSummary testResult;
        var session = cc.jumpkick.config.SessionContext.current();
        TestSummary[] testResultHolder = new TestSummary[1];
        try {
            result = cc.jumpkick.cli.engine.EngineClient.runInstall(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineRequests.InstallRequest(
                            projectDir,
                            cacheDir,
                            m2Dir(),
                            graalHome,
                            buildOpts.skipTests,
                            session.offline(),
                            session.force(),
                            global.verbose),
                    steps -> BuildPlanConsole.chooseConsoleListener(planName, steps, mode),
                    testResultHolder);
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Install", e.getMessage());
            return Exit.SOFTWARE;
        }
        testResult = testResultHolder[0];

        if (!result.success()) {
            if (testResult != null && !testResult.allPassed()) return 4;
            return failureExit(result, "jk install", cacheDir);
        }

        Coordinate coord = Coordinate.of(proj.group(), proj.name(), proj.version());
        Path launcher = null;
        if (!isPluginWorker(proj, projectDir) && proj.application()) {
            try {
                launcher = applyInstallPlan(projectDir, cacheDir);
            } catch (IOException e) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Install", "make install failed: " + e.getMessage());
                return 1;
            }
        }
        announceProjectInstall(Coords.gav(coord), launcher, binDir);
        return 0;
    }

    private int runWorkspaceInstall(Path wsRoot, CwdModuleScope.Resolved cwdScope, String planName) throws IOException {
        Path cacheDir = cacheDir();
        Path binDir = binDir();
        cc.jumpkick.engine.protocol.ProjectInfo root = projectInfo(wsRoot);
        if (root.error() != null) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Install", root.error());
            return Exit.CONFIG;
        }
        List<Path> moduleDirs = new ArrayList<>();
        for (String rel : root.moduleDirs()) {
            Path d = Path.of(rel).isAbsolute() ? Path.of(rel) : wsRoot.resolve(rel);
            moduleDirs.add(d.toAbsolutePath().normalize());
        }
        Map<Path, Path> graalByDir = new LinkedHashMap<>();
        for (Path mod : moduleDirs) {
            var info = projectInfo(mod);
            if (info.error() != null || !"ALWAYS".equals(info.nativeMode())) continue;
            Optional<Path> graal = new cc.jumpkick.cli.GraalResolver(null, false).resolve(mod, info.graal());
            if (graal.isEmpty()) return 1;
            graalByDir.put(mod, graal.get());
        }
        List<String> tokens = cwdScope.scoped() ? List.of(cwdScope.modulesSpec()) : List.of();
        Set<Path> selected = cwdScope.scoped() ? Set.of(cwdScope.workingDir()) : Set.of();
        WorkspaceRequest req = new WorkspaceRequest(
                        wsRoot,
                        cacheDir,
                        null,
                        0,
                        null,
                        buildOpts != null && buildOpts.skipTests,
                        global.verbose,
                        0,
                        selected.isEmpty() ? null : selected,
                        true,
                        true)
                .withModules(tokens)
                .withSpec(WorkspaceSpec.install(selected, graalByDir));
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        WorkspaceResult result;
        try {
            result = cc.jumpkick.cli.engine.EngineClient.buildWorkspace(
                    cc.jumpkick.engine.EnginePaths.current(), req, new cc.jumpkick.runtime.WorkspaceBuildListener() {
                        @Override
                        public cc.jumpkick.run.BuildPlanListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                            return BuildPlanConsole.chooseConsoleListener(
                                    planName, m.plan().steps(), mode);
                        }
                    });
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Install", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!result.success()) return result.exitCode() == 0 ? 1 : result.exitCode();
        for (var m : result.modules()) {
            if (!m.success()) continue;
            Path mod = m.dir();
            var info = projectInfo(mod);
            Path launcher = null;
            if (!isPluginWorker(info, mod) && info.application()) {
                try {
                    launcher = applyInstallPlan(mod, cacheDir);
                } catch (IOException e) {
                    cc.jumpkick.cli.tui.CommandWedge.printFail("Install", "make install failed: " + e.getMessage());
                    return 1;
                }
            }
            announceProjectInstall(m.coord(), launcher, binDir);
        }
        return 0;
    }

    private static boolean isPluginWorker(cc.jumpkick.engine.protocol.ProjectInfo proj, Path projectDir) {
        return cc.jumpkick.plugin.PluginModule.isWorker(projectDir)
                || "cc.jumpkick.plugin.process.PluginMain".equals(proj.mainClass());
    }

    /** The engine's parsed-project summary. */
    private cc.jumpkick.engine.protocol.ProjectInfo projectInfo(Path projectDir) throws IOException {
        return cc.jumpkick.cli.engine.EngineClient.projectInfo(cc.jumpkick.engine.EnginePaths.current(), projectDir);
    }

    /**
     * The {@code make install} step for applications, thin-client style: the engine computes the
     * plan (link set + launcher script or a direct native-binary link); this process applies it —
     * hard-link/copy each pair, write the launcher, mark executables. Returns the launcher path.
     */
    private Path applyInstallPlan(Path projectDir, Path cacheDir) throws IOException {
        cc.jumpkick.engine.protocol.ExecPlan plan = cc.jumpkick.cli.engine.EngineClient.execPlan(
                cc.jumpkick.engine.EnginePaths.current(),
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
        // Self-host engine install: route through EngineInstall so the live jar is replaced
        // atomically under the install lock, the previous jar is parked for the drain window, a
        // downgrade is refused, and config.toml is written coherently — none of which the generic
        // copy + AppInstallConfig.write below provides (JK-2311).
        Path productLib = JkDirs.current().productLibDir().toAbsolutePath().normalize();
        for (int i = 0; i < plan.linkDests().size(); i++) {
            Path dest = Path.of(plan.linkDests().get(i)).toAbsolutePath().normalize();
            Path parent = dest.getParent();
            if (parent != null
                    && EngineInstall.BIN_NAME.equals(parent.getFileName().toString())
                    && productLib.equals(parent.getParent())) {
                Path src = Path.of(plan.linkSrcs().get(i));
                String version = engineInstallVersion(projectDir, src);
                new EngineInstall(JkDirs.productLib())
                        .materializeFromFiles(version, cc.jumpkick.cache.JkStores.cas(cacheDir), src);
                return null; // the engine is a jar the client launches — no launcher/bin to link
            }
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
     * Persist {@code $JK_CONFIG_DIR/<bin>/config.toml} for fat/minified installs (jar under
     * {@code productLib/<bin>/}). Honors {@code [application].config} templates and {@code
     * jk-config.*} system properties. For {@code jk-engine}, always refreshes {@code engine-sha256}
     * to the installed jar bytes so a self-host reinstall cannot leave a stale digest beside a
     * new {@code jar =} name.
     */
    private void writeAppInstallConfig(Path projectDir, cc.jumpkick.engine.protocol.ExecPlan plan) throws IOException {
        if (plan.linkDests().isEmpty()) return;
        Path dest = Path.of(plan.linkDests().get(0));
        Path parent = dest.getParent();
        if (parent == null) return;
        // Only fat/minified installs (jar at productLib/<bin>/<jar>) carry a config entry keyed by
        // <bin>. A native binary lands directly in the PATH bin dir, so its parent is that bin dir —
        // treating it as the app name wrote a junk config/bin/config.toml (JK-2312).
        Path grand = parent.getParent();
        Path productLib = JkDirs.current().productLibDir().toAbsolutePath().normalize();
        if (grand == null || !grand.toAbsolutePath().normalize().equals(productLib)) return;
        String bin = parent.getFileName().toString();
        if (bin.isBlank()) return;
        Map<String, String> keys = new LinkedHashMap<>(AppInstallConfig.jkConfigProperties());
        putInstalledJarKeys(keys, dest, bin);
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

    /**
     * Record the installed jar basename, and for the engine also the content digest the client
     * pairs with that jar.
     */
    static void putInstalledJarKeys(Map<String, String> keys, Path installedJar, String bin) throws IOException {
        keys.put("jar", installedJar.getFileName().toString());
        if (EngineInstall.BIN_NAME.equals(bin)) {
            keys.put("engine-sha256", Hashing.sha256Hex(installedJar));
        }
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
                .orElseThrow(() -> new IllegalStateException(
                        "cannot determine engine version for " + engineJar.getFileName()));
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

    private Path libDir() {
        return libDirOverride != null ? libDirOverride : JkDirs.lib();
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

    /** Announce a project install: launcher path for an app, cache-only for a library. */
    private void announceProjectInstall(String coord, Path launcher, Path binDir) {
        if (global.outputIsJson()) return;
        if (launcher == null) {
            CliOutput.out("Installed " + coord + " to the local cache");
            return;
        }
        CliOutput.out("Installed " + coord + " → " + launcher);
        CliOutput.out("Add to PATH if needed:");
        CliOutput.out("  export PATH=\"" + binDir + ":$PATH\"");
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
    record UrlAndRef(String url, String ref) {}

    static UrlAndRef splitUrlRef(String input) {
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

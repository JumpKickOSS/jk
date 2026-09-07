// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.BuildOptions;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.script.ScriptHeaderParser;
import cc.jumpkick.tool.LauncherName;
import cc.jumpkick.tool.ToolEnv;
import cc.jumpkick.tool.ToolLauncher;
import cc.jumpkick.tool.ToolProvenance;
import cc.jumpkick.tool.ToolTarget;
import cc.jumpkick.tool.TrustedSources;
import cc.jumpkick.tool.UrlRewriter;
import cc.jumpkick.util.GitUrl;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.HostedEvents;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk tool install [<target>]} — install a catalog name, Maven coord, script/jar, project dir,
 * or git URL ({@code jk install} is the hidden alias). Resolve/fetch is engine-hosted; launcher
 * write under {@code <home>/bin} stays client-side.
 */
public final class ToolInstallCommand implements CliCommand {

    @Override
    public String name() {
        return "install";
    }

    @Override
    public String description() {
        return "Install a tool, script, project, or git repo";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<name>", "Launcher name under ~/.jk/bin. Default: the artifact id.", "--bin"),
                Opt.value("<class>", "Override Main-Class (from jar manifest)", "--main"),
                Opt.value("<coord>", "Extra dependency on tool classpath", "--with")
                        .repeat(),
                Opt.value("<group>", "Maven groupId (local-cache install mode)", "--group"),
                Opt.value("<name>", "Maven artifactId for a local-cache file install.", "--name"),
                Opt.value("<ver>", "Version for a local-cache file install.", "--ver"),
                Opt.flag("Skip compiling and running tests (project targets).", "--skip-tests"),
                CommonOpts.guard(),
                Opt.flag("Download a build tool rather than linking a host install.", "--no-discover"),
                Opt.value(
                                "<dir>",
                                "Override cache-tier directory (action outputs; not the artifact store). Default: $JK_CACHE_DIR or ~/.jk/cache.",
                                "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the tool state directory.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the bin directory.", "--bin-dir").hide(),
                Opt.value("<dir>", "Override the lib directory.", "--lib-dir").hide(),
                Opt.value("<dir>", "Override the local Maven repo root (~/.m2).", "--m2-dir")
                        .hide(),
                Opt.value("<url>", "Override the Maven repository URL (for tests).", "--repo-url")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "target",
                Arity.ZERO_OR_ONE,
                "Build tool (" + BuildTool.slugs() + ", optionally :<version>),\n"
                        + "catalog name, Maven coordinate spec (g:a[:version|@selector]),\n"
                        + "script/jar file, project directory, or git URL. Omit to\n"
                        + "install the current jk.toml project."));
    }

    @Nullable
    String coord;

    @Nullable
    String binName;

    @Nullable
    String mainClass;

    List<String> aliasDeps = List.of();
    List<String> aliasJavaOptions = List.of();

    @Nullable
    String groupFlag;

    @Nullable
    String nameFlag;

    @Nullable
    String verFlag;

    boolean skipTests;

    @Nullable
    Path cacheDirOverride;

    @Nullable
    Path stateDirOverride;

    @Nullable
    Path binDirOverride;

    @Nullable
    Path libDirOverride;

    @Nullable
    Path m2DirOverride;

    @Nullable
    URI repoUrl;

    GlobalOptions global;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.binName = in.value("bin").orElse(null);
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        this.stateDirOverride = in.value("state-dir").map(Path::of).orElse(null);
        this.binDirOverride = in.value("bin-dir").map(Path::of).orElse(null);
        this.groupFlag = in.value("group").orElse(null);
        this.nameFlag = in.value("name").orElse(null);
        this.verFlag = in.value("ver").orElse(null);
        this.skipTests = in.isSet("skip-tests");
        this.libDirOverride = in.value("lib-dir").map(Path::of).orElse(null);
        this.m2DirOverride = in.value("m2-dir").map(Path::of).orElse(null);
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.global = GlobalOptions.from(in);
        // A project install builds through the test stage, so --guard means what it means on build.
        if (!TestCommand.installSelection(in, "Install")) return Exit.CONFIG;

        Path base = global.workingDir();
        if (in.positionals().isEmpty()) {
            if (Files.isRegularFile(base.resolve(ManifestPaths.MANIFEST))) {
                return appInstallDelegate().runProjectInstallBuildPlan(base, "install");
            }
            CommandWedge.printFail(
                    "Install",
                    "no target specified — pass a coordinate, catalog name, path, or git URL, or run inside a directory with jk.toml");
            return Exit.USAGE;
        }
        this.coord = in.positionals().get(0);
        // A build tool is neither a coordinate nor a launcher: `kotlin:latest` names a
        // distribution the engine unpacks into the tools root and consumes as a home. Checked
        // before classification because `<slug>:<version>` would otherwise read as a coordinate.
        Integer buildTool = installBuildTool(coord, in.isSet("no-discover"));
        if (buildTool != null) return buildTool;
        // A local script/jar installs as a snapshot env (launcher must not depend on the source
        // path). Project dirs and git URLs delegate to InstallCommand. Local paths resolve
        // against -C/--dir, not the process cwd.
        ToolTarget classified = ToolTarget.classify(coord);
        Integer direct = installDirectTarget(classified, base, in);
        if (direct != null) return direct;

        if (classified instanceof ToolTarget.JBangAlias) {
            Integer aliasExit = resolveJBangAliasForInstall();
            if (aliasExit != null) return aliasExit;
            // A GAV script-ref fell through: `coord` (and the default --bin) were rewritten.
        }

        ToolTargets.Resolved resolved;
        List<String> with;
        try {
            resolved = ToolTargets.resolve(coord);
            List<String> withInputs = new ArrayList<>(in.values("with"));
            withInputs.addAll(aliasDeps);
            with = ToolTargets.resolveWith(withInputs);
        } catch (ToolTargets.TargetException e) {
            CliOutput.err(e.getMessage());
            return Exit.USAGE;
        }
        String bin = binName != null && !binName.isBlank() ? binName : resolved.defaultBin();
        Integer invalidBin = rejectInvalidLauncherName(bin);
        if (invalidBin != null) return invalidBin;

        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envsRoot = stateDir.resolve("tools").resolve("envs");
        Files.createDirectories(cacheDir);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);

        ToolEnv env;
        EngineRequests.ToolResolveOutcome outcome;
        try {
            outcome = EngineClient.runToolResolve(
                    EnginePaths.current(),
                    new EngineRequests.ToolResolveRequest(
                            resolved.coordSpec(), with, bin, mainClass, repoUrl, cacheDir),
                    steps -> BuildPlanConsole.chooseConsoleListener("tool-install", steps, mode));
        } catch (IOException e) {
            CommandWedge.printFail("Tool", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!outcome.result().success() || outcome.mainClass() == null || outcome.coord() == null) return 1;
        env = new ToolEnv(bin, Coordinate.parse(outcome.coord()), outcome.mainClass(), outcome.classpath());

        // The "make install" half stays client-side: the launcher into the user-owned bin dir.
        Path javaHome = JavaHomes.runningJavaHome();
        String kind = classified instanceof ToolTarget.CatalogName ? "catalog" : "gav";
        Path launcher = ToolLauncher.install(
                envsRoot,
                binDir,
                javaHome,
                env,
                new ToolProvenance(kind, coord, env.primary().toGav()),
                aliasJavaOptions);

        if (!global.outputIsJson()) {
            CommandWedge.printOk("Tool", "Installed " + Coords.gav(env.primary()) + " → " + launcher);
            CliOutput.out("Add to PATH if needed:");
            CliOutput.out("  export PATH=\"" + binDir + ":$PATH\"");
        }
        return 0;
    }

    /**
     * A file, directory, git or URL target installs without the resolver: the exit code, or null
     * when {@code classified} is an alias, a catalog name or a coordinate and resolution follows.
     */
    private @Nullable Integer installDirectTarget(ToolTarget classified, Path base, Invocation in)
            throws IOException, InterruptedException {
        String coord = Objects.requireNonNull(this.coord, "coord");
        boolean m2Intent = groupFlag != null || nameFlag != null || verFlag != null;
        if (m2Intent && classified instanceof ToolTarget.RunnableFile file) {
            // Coordinate flags = "store this artifact in the local cache" (the mvn install
            // equivalent), not "give me a launcher".
            return appInstallDelegate()
                    .installFromFile(base.resolve(file.path()).toAbsolutePath().normalize());
        }
        if (m2Intent && classified instanceof ToolTarget.UnsupportedFile file) {
            return appInstallDelegate()
                    .installFromFile(base.resolve(file.path()).toAbsolutePath().normalize());
        }
        if (classified instanceof ToolTarget.RunnableFile file) {
            Path resolved = base.resolve(file.path()).normalize();
            List<String> fileWith;
            try {
                fileWith = ToolTargets.resolveWith(in.values("with"));
            } catch (ToolTargets.TargetException e) {
                CliOutput.err(e.getMessage());
                return Exit.USAGE;
            }
            return installFile(
                    resolved,
                    new ToolProvenance("file", coord, resolved.toAbsolutePath().toString()),
                    fileWith,
                    List.of());
        }
        if (classified instanceof ToolTarget.Directory dir) {
            Path projectDir = base.resolve(dir.path()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(projectDir.resolve(ManifestPaths.MANIFEST))) {
                CommandWedge.printFail(
                        "Tool", "no jk.toml in " + projectDir + " — a directory target must be a jk project.");
                return Exit.CONFIG;
            }
            return appInstallDelegate().runProjectInstallBuildPlan(projectDir, "install");
        }
        if (classified instanceof ToolTarget.Git git) {
            String raw = git.raw().startsWith("git+") ? git.raw().substring("git+".length()) : git.raw();
            String canonical =
                    GitUrl.canonicalize(InstallCommand.splitUrlRef(raw).url());
            Path stateDirForGit = stateDirOverride != null ? stateDirOverride : JkDirs.state();
            Integer gitGate = UrlToolSource.gate(UrlToolSource.gitTrustUrl(canonical), stateDirForGit, "jk install");
            if (gitGate != null) return gitGate;
            return appInstallDelegate().installFromGit(raw);
        }
        if (classified instanceof ToolTarget.Url u) {
            Path stateDirForTrust = stateDirOverride != null ? stateDirOverride : JkDirs.state();
            Integer gated = UrlToolSource.gate(u.raw(), stateDirForTrust, "jk tool install");
            if (gated != null) return gated;
            Path fetched;
            try {
                fetched = UrlToolSource.fetch(
                        u.raw(), cacheDirOverride != null ? cacheDirOverride : JkDirs.cache(), false);
            } catch (IOException e) {
                CommandWedge.printFail("Tool", e.getMessage());
                return Exit.SOFTWARE;
            }
            return installFile(fetched, new ToolProvenance("url", coord, UrlRewriter.rewrite(u.raw())));
        }
        return null;
    }

    /**
     * JBang {@code alias@catalog} install: trust-gate the catalog, then install the alias's
     * script-ref (coordinate refs rewrite {@code coord} and return null to fall through).
     */
    private @Nullable Integer resolveJBangAliasForInstall() throws IOException, InterruptedException {
        String coord = Objects.requireNonNull(this.coord, "coord");
        String aliasName = coord.substring(0, coord.indexOf('@'));
        Path stateDirForTrust = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        // Trust decides BEFORE any fetch — same rule as tool run: no request leaves the machine
        // for an origin the user never allowed.
        var trust = TrustedSources.load(stateDirForTrust);
        List<String> origins = JBangCatalog.origins(coord);
        boolean preTrusted = origins.stream().anyMatch(trust::isTrusted);
        if (!preTrusted) {
            Integer gated = UrlToolSource.gate(origins.get(0), stateDirForTrust, "jk tool install");
            if (gated != null) return gated;
        }
        JBangCatalog.Resolved r;
        try {
            r = JBangCatalog.resolve(coord, new Http(), preTrusted ? trust::isTrusted : o -> true);
        } catch (IOException e) {
            CommandWedge.printFail("Tool", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!r.pageOrigin().equals(origins.get(0)) && !trust.isTrusted(r.pageOrigin())) {
            // Forge fallback landed on a different origin than the one the user allowed.
            Integer gated = UrlToolSource.gate(r.pageOrigin(), stateDirForTrust, "jk tool install");
            if (gated != null) return gated;
        }
        if (!r.arguments().isEmpty()) {
            // Default arguments can't ride a launcher's "$@" cleanly yet.
            CommandWedge.printFail(
                    "Tool",
                    "warning — this alias declares default arguments,"
                            + " which installed launchers do not honor yet.");
        }
        if (binName == null || binName.isBlank()) binName = aliasName;
        String ref = r.scriptRef();
        if (!ref.contains("://") && ref.contains(":")) {
            coord = ref; // coordinate script-ref — the normal flow takes it from here
            aliasDeps = r.dependencies();
            aliasJavaOptions = r.javaOptions();
            return null;
        }
        String url = ref.contains("://") ? ref : r.rawBase().resolve(ref).toString();
        if (ref.contains("://")) {
            Integer urlGate = UrlToolSource.gate(url, stateDirForTrust, "jk tool install");
            if (urlGate != null) return urlGate;
        }
        Path fetched;
        try {
            fetched = UrlToolSource.fetch(url, cacheDirOverride != null ? cacheDirOverride : JkDirs.cache(), false);
        } catch (IOException e) {
            CommandWedge.printFail("Tool", e.getMessage());
            return Exit.SOFTWARE;
        }
        return installFile(fetched, new ToolProvenance("jbang-alias", coord, url), r.dependencies(), r.javaOptions());
    }

    /**
     * Install a local {@code .java}/{@code .kt}/{@code .jar}: engine script-prepare, then snapshot
     * into the env dir and write a launcher (independent of the source path).
     */
    private int installFile(Path file, ToolProvenance provenance) throws IOException, InterruptedException {
        return installFile(file, provenance, List.of(), List.of());
    }

    private int installFile(Path file, ToolProvenance provenance, List<String> with, List<String> jvmArgs)
            throws IOException, InterruptedException {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!Files.isRegularFile(file)) {
            CommandWedge.printFail("Tool", "file not found: " + file);
            return Exit.NO_INPUT;
        }
        String mode =
                lower.endsWith(".jar") ? "jar" : lower.endsWith(".kts") ? "kts" : lower.endsWith(".kt") ? "kt" : "java";
        String bin = binName != null && !binName.isBlank()
                ? binName
                : name.substring(0, name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        Integer invalidBin = rejectInvalidLauncherName(bin);
        if (invalidBin != null) return invalidBin;

        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envsRoot = stateDir.resolve("tools").resolve("envs");
        Files.createDirectories(cacheDir);
        BuildPlanConsole.Mode consoleMode = BuildPlanConsole.modeFor(global);

        EngineRequests.ScriptPrepareOutcome prep;
        try {
            prep = EngineClient.runScriptPrepare(
                    EnginePaths.current(),
                    new EngineRequests.ScriptPrepareRequest(
                            mode, file.toAbsolutePath(), cacheDir, stateDir, repoUrl, false, with),
                    steps -> BuildPlanConsole.chooseConsoleListener("tool-install", steps, consoleMode));
        } catch (IOException e) {
            CommandWedge.printFail("Tool", e.getMessage());
            return Exit.SOFTWARE;
        }

        if (!prep.result().success() || (prep.mainClass() == null && !"kts".equals(mode))) return 1;

        // Snapshot into the env dir so the launcher survives the source moving/vanishing.
        Path envDir = LauncherName.resolveChild(envsRoot, bin);
        List<Path> classpath = new ArrayList<>();
        if ("kts".equals(mode)) {
            // Kotlin script: snapshot a neutralized copy (jk resolved its @file:DependsOn) and
            // write a kotlinc -script launcher over it + the resolved dep classpath.
            if (prep.kotlincBin() == null) return 1;
            Files.createDirectories(envDir);
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String neutralized = ScriptHeaderParser.neutralizeKotlinAnnotations(source);
            Path scriptCopy = envDir.resolve(name);
            Files.writeString(scriptCopy, neutralized != null ? neutralized : source);
            ToolEnv ktsEnv = new ToolEnv(bin, Coordinate.of("script", bin, "local"), "kotlin-script", prep.classpath());
            Path ktsLauncher = ToolLauncher.installKotlinScript(
                    envsRoot, binDir, JavaHomes.runningJavaHome(), prep.kotlincBin(), scriptCopy, ktsEnv, provenance);
            if (!global.outputIsJson()) {
                CommandWedge.printOk("Tool", "Installed " + file.getFileName() + " → " + ktsLauncher);
                CliOutput.out("Add to PATH if needed:");
                CliOutput.out("  export PATH=\"" + binDir + ":$PATH\"");
            }
            return 0;
        }
        if ("jar".equals(mode)) {
            Files.createDirectories(envDir);
            Path jarCopy = envDir.resolve(name);
            Files.copy(file, jarCopy, StandardCopyOption.REPLACE_EXISTING);
            classpath.add(jarCopy);
            // prep.classpath() leads with the source jar; keep only the resolved deps.
            prep.classpath().stream().skip(1).forEach(classpath::add);
        } else {
            Path classesCopy = envDir.resolve("classes");
            copyTree(prep.classesDir(), classesCopy);
            classpath.add(classesCopy);
            classpath.addAll(prep.classpath());
            if (prep.stdlib() != null) classpath.add(prep.stdlib());
        }

        ToolEnv env = new ToolEnv(bin, Coordinate.of("script", bin, "local"), prep.mainClass(), classpath);
        Path launcher = ToolLauncher.install(envsRoot, binDir, JavaHomes.runningJavaHome(), env, provenance, jvmArgs);
        if (!global.outputIsJson()) {
            CommandWedge.printOk("Tool", "Installed " + file.getFileName() + " → " + launcher);
            CliOutput.out("Add to PATH if needed:");
            CliOutput.out("  export PATH=\"" + binDir + ":$PATH\"");
        }
        return 0;
    }

    /** The app-install plan, shared with {@code jk install}. */
    /**
     * Provision a build-tool distribution when {@code target} names one: {@code kotlin},
     * {@code kotlin:latest}, {@code maven:3.9.9}. Returns {@code null} when it does not, so the
     * caller falls through to the coordinate / file / project / git shapes.
     *
     * <p>Engine-side, through the same {@code ToolProvisioning} door a build uses when it needs the
     * tool mid-flight — so installing ahead of time makes that build a cache hit rather than
     * seeding a second copy the engine will not look at.
     */
    private @Nullable Integer installBuildTool(String target, boolean noDiscover) throws IOException {
        int colon = target.indexOf(':');
        String slug = colon < 0 ? target : target.substring(0, colon);
        String version = colon < 0 ? BuildTool.LATEST : target.substring(colon + 1);
        Optional<BuildTool> tool = BuildTool.bySlug(slug);
        if (tool.isEmpty()) return null;
        if (version.isBlank()) {
            CommandWedge.printFail(
                    "Tool",
                    "no version after ':' in '" + target + "' — use " + slug + ":<version> or " + slug + ":"
                            + BuildTool.LATEST);
            return Exit.USAGE;
        }

        Path toolsRoot = JkDirs.tools();
        HostedEvents.Provision p;
        try {
            p = EngineClient.provisionTool(EnginePaths.current(), slug, version, toolsRoot, noDiscover);
        } catch (IOException e) {
            CommandWedge.printFail("Tool", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (p.error() != null) {
            CommandWedge.printFail("Tool", p.error());
            return p.exit() == Exit.SUCCESS ? Exit.SOFTWARE : p.exit();
        }
        if (p.exit() != Exit.SUCCESS) return p.exit();
        String source = Objects.requireNonNullElse(p.source(), "");
        CliOutput.out(slug + " " + p.version() + " "
                + ("CACHED".equals(source) ? "already installed" : source.toLowerCase(Locale.ROOT))
                + " — " + p.bin());
        return Exit.SUCCESS;
    }

    private InstallCommand appInstallDelegate() {
        BuildOptions buildOpts = new BuildOptions();
        buildOpts.skipTests = skipTests;
        InstallCommand delegate = new InstallCommand(global, buildOpts);
        delegate.binName = binName;
        delegate.mainClass = mainClass;
        delegate.groupFlag = groupFlag;
        delegate.nameFlag = nameFlag;
        delegate.verFlag = verFlag;
        delegate.cacheDirOverride = cacheDirOverride;
        delegate.stateDirOverride = stateDirOverride;
        delegate.binDirOverride = binDirOverride;
        delegate.libDirOverride = libDirOverride;
        delegate.m2DirOverride = m2DirOverride;
        delegate.repoUrl = repoUrl;
        return delegate;
    }

    private static void copyTree(@Nullable Path from, Path to) throws IOException {
        PathUtil.copyTree(from, to);
    }

    private static @Nullable Integer rejectInvalidLauncherName(String name) {
        var error = LauncherName.validationError(name);
        if (error.isEmpty()) return null;
        CommandWedge.printFail("Tool", error.get());
        return Exit.USAGE;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.tool.ToolEnv;
import cc.jumpkick.tool.ToolLauncher;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code jk tool install [<target>]} — install a catalog name, Maven coord, script/jar, project dir,
 * or git URL ({@code jk install} is the hidden alias). Resolve/fetch is engine-hosted; launcher
 * write under {@code $JK_BIN_DIR} stays client-side.
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
                Opt.value("<name>", "Launcher name under $JK_BIN_DIR. Default: the artifact id.", "--bin"),
                Opt.value("<class>", "Override Main-Class (from jar manifest)", "--main"),
                Opt.value("<coord>", "Extra dependency on tool classpath", "--with")
                        .repeat(),
                Opt.value("<group>", "Maven groupId (local-cache install mode)", "--group"),
                Opt.value("<name>", "Maven artifactId for a local-cache file install.", "--name"),
                Opt.value("<ver>", "Version for a local-cache file install.", "--ver"),
                Opt.flag("Skip compiling and running tests (project targets).", "--skip-tests"),
                Opt.value(
                                "<dir>",
                                "Override cache-tier directory (action outputs; not the artifact store). Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the tool state directory.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the bin directory.", "--bin-dir").hide(),
                Opt.value("<dir>", "Override the lib directory.", "--lib-dir").hide(),
                Opt.value("<dir>", "Override the local Maven repo root (~/.m2) for m2install.", "--m2-dir")
                        .hide(),
                Opt.value("<url>", "Override the Maven repository URL (for tests).", "--repo-url")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "target",
                Arity.ZERO_OR_ONE,
                "Catalog name, Maven coordinate spec (g:a[:version|@selector]),\n"
                        + "script/jar file, project directory, or git URL. Omit to\n"
                        + "install the current jk.toml project."));
    }

    String coord;
    String binName;
    String mainClass;
    List<String> aliasDeps = List.of();
    List<String> aliasJavaOptions = List.of();
    String groupFlag;
    String nameFlag;
    String verFlag;
    boolean skipTests;
    Path cacheDirOverride;
    Path stateDirOverride;
    Path binDirOverride;
    Path libDirOverride;
    Path m2DirOverride;
    URI repoUrl;
    GlobalOptions global;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.coord = in.positionals().isEmpty() ? "." : in.positionals().get(0);
        this.binName = in.value("bin").orElse(null);
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
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

        // A local script/jar installs as a snapshot env (launcher must not depend on the source
        // path). Project dirs and git URLs delegate to InstallCommand. Local paths (including
        // ".") resolve against -C/--dir, not the process cwd.
        Path base = global.workingDir();
        cc.jumpkick.tool.ToolTarget classified = cc.jumpkick.tool.ToolTarget.classify(coord);
        boolean m2Intent = groupFlag != null || nameFlag != null || verFlag != null;
        if (m2Intent && classified instanceof cc.jumpkick.tool.ToolTarget.RunnableFile file) {
            // Coordinate flags = "store this artifact in the local cache" (the mvn install
            // equivalent), not "give me a launcher".
            return appInstallDelegate()
                    .installFromFile(base.resolve(file.path()).toAbsolutePath().normalize());
        }
        if (m2Intent && classified instanceof cc.jumpkick.tool.ToolTarget.UnsupportedFile file) {
            return appInstallDelegate()
                    .installFromFile(base.resolve(file.path()).toAbsolutePath().normalize());
        }
        if (classified instanceof cc.jumpkick.tool.ToolTarget.RunnableFile file) {
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
                    new cc.jumpkick.tool.ToolProvenance(
                            "file", coord, resolved.toAbsolutePath().toString()),
                    fileWith,
                    List.of());
        }
        if (classified instanceof cc.jumpkick.tool.ToolTarget.Directory dir) {
            Path projectDir = base.resolve(dir.path()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(projectDir.resolve("jk.toml"))) {
                cc.jumpkick.cli.tui.CommandWedge.printFail(
                        "Tool", "no jk.toml in " + projectDir + " — a directory target must be a jk project.");
                return Exit.CONFIG;
            }
            return appInstallDelegate().runProjectInstallBuildPlan(projectDir, "install");
        }
        if (classified instanceof cc.jumpkick.tool.ToolTarget.Git git) {
            String raw = git.raw().startsWith("git+") ? git.raw().substring("git+".length()) : git.raw();
            String canonical = cc.jumpkick.util.GitUrl.canonicalize(
                    InstallCommand.splitUrlRef(raw).url());
            Path stateDirForGit = stateDirOverride != null ? stateDirOverride : JkDirs.state();
            Integer gitGate = UrlToolSource.gate(UrlToolSource.gitTrustUrl(canonical), stateDirForGit, "jk install");
            if (gitGate != null) return gitGate;
            return appInstallDelegate().installFromGit(raw);
        }
        if (classified instanceof cc.jumpkick.tool.ToolTarget.Url u) {
            Path stateDirForTrust = stateDirOverride != null ? stateDirOverride : JkDirs.state();
            Integer gated = UrlToolSource.gate(u.raw(), stateDirForTrust, "jk tool install");
            if (gated != null) return gated;
            Path fetched;
            try {
                fetched = UrlToolSource.fetch(
                        u.raw(), cacheDirOverride != null ? cacheDirOverride : JkDirs.cache(), false);
            } catch (IOException e) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Tool", e.getMessage());
                return Exit.SOFTWARE;
            }
            return installFile(
                    fetched,
                    new cc.jumpkick.tool.ToolProvenance("url", coord, cc.jumpkick.tool.UrlRewriter.rewrite(u.raw())));
        }

        if (classified instanceof cc.jumpkick.tool.ToolTarget.JBangAlias) {
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

        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envsRoot = stateDir.resolve("tools").resolve("envs");
        Files.createDirectories(cacheDir);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);

        ToolEnv env;
        cc.jumpkick.cli.engine.EngineRequests.ToolResolveOutcome outcome;
        try {
            outcome = cc.jumpkick.cli.engine.EngineClient.runToolResolve(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineRequests.ToolResolveRequest(
                            resolved.coordSpec(), with, bin, mainClass, repoUrl, cacheDir),
                    steps -> BuildPlanConsole.chooseConsoleListener("tool-install", steps, mode));
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Tool", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!outcome.result().success() || outcome.mainClass() == null || outcome.coord() == null) return 1;
        env = new ToolEnv(bin, Coordinate.parse(outcome.coord()), outcome.mainClass(), outcome.classpath());

        // The "make install" half stays client-side: the launcher into the user-owned bin dir.
        Path javaHome = JavaHomes.runningJavaHome();
        String kind = classified instanceof cc.jumpkick.tool.ToolTarget.CatalogName ? "catalog" : "gav";
        Path launcher = ToolLauncher.install(
                envsRoot,
                binDir,
                javaHome,
                env,
                new cc.jumpkick.tool.ToolProvenance(kind, coord, env.primary().toGav()),
                aliasJavaOptions);

        if (!global.outputIsJson()) {
            cc.jumpkick.cli.tui.CommandWedge.printOk(
                    "Tool", "Installed " + Coords.gav(env.primary()) + " → " + launcher);
            CliOutput.out("Add to PATH if needed:");
            CliOutput.out("  export PATH=\"" + binDir + ":$PATH\"");
        }
        return 0;
    }

    /**
     * JBang {@code alias@catalog} install: trust-gate the catalog, then install the alias's
     * script-ref (coordinate refs rewrite {@code coord} and return null to fall through).
     */
    private Integer resolveJBangAliasForInstall() throws IOException, InterruptedException {
        String aliasName = coord.substring(0, coord.indexOf('@'));
        Path stateDirForTrust = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        // Trust decides BEFORE any fetch — same rule as tool run: no request leaves the machine
        // for an origin the user never allowed.
        var trust = cc.jumpkick.tool.TrustedSources.load(stateDirForTrust);
        List<String> origins = JBangCatalog.origins(coord);
        boolean preTrusted = origins.stream().anyMatch(trust::isTrusted);
        if (!preTrusted) {
            Integer gated = UrlToolSource.gate(origins.get(0), stateDirForTrust, "jk tool install");
            if (gated != null) return gated;
        }
        JBangCatalog.Resolved r;
        try {
            r = JBangCatalog.resolve(coord, new cc.jumpkick.http.Http(), preTrusted ? trust::isTrusted : o -> true);
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Tool", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!r.pageOrigin().equals(origins.get(0)) && !trust.isTrusted(r.pageOrigin())) {
            // Forge fallback landed on a different origin than the one the user allowed.
            Integer gated = UrlToolSource.gate(r.pageOrigin(), stateDirForTrust, "jk tool install");
            if (gated != null) return gated;
        }
        if (!r.arguments().isEmpty()) {
            // Default arguments can't ride a launcher's "$@" cleanly yet.
            cc.jumpkick.cli.tui.CommandWedge.printFail(
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
            cc.jumpkick.cli.tui.CommandWedge.printFail("Tool", e.getMessage());
            return Exit.SOFTWARE;
        }
        return installFile(
                fetched,
                new cc.jumpkick.tool.ToolProvenance("jbang-alias", coord, url),
                r.dependencies(),
                r.javaOptions());
    }

    /**
     * Install a local {@code .java}/{@code .kt}/{@code .jar}: engine script-prepare, then snapshot
     * into the env dir and write a launcher (independent of the source path).
     */
    private int installFile(Path file, cc.jumpkick.tool.ToolProvenance provenance)
            throws IOException, InterruptedException {
        return installFile(file, provenance, List.of(), List.of());
    }

    private int installFile(
            Path file, cc.jumpkick.tool.ToolProvenance provenance, List<String> with, List<String> jvmArgs)
            throws IOException, InterruptedException {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!Files.isRegularFile(file)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Tool", "file not found: " + file);
            return Exit.NO_INPUT;
        }
        String mode =
                lower.endsWith(".jar") ? "jar" : lower.endsWith(".kts") ? "kts" : lower.endsWith(".kt") ? "kt" : "java";
        String bin = binName != null && !binName.isBlank()
                ? binName
                : name.substring(0, name.lastIndexOf('.')).toLowerCase(Locale.ROOT);

        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envsRoot = stateDir.resolve("tools").resolve("envs");
        Files.createDirectories(cacheDir);
        BuildPlanConsole.Mode consoleMode = BuildPlanConsole.modeFor(global);

        cc.jumpkick.cli.engine.EngineRequests.ScriptPrepareOutcome prep;
        try {
            prep = cc.jumpkick.cli.engine.EngineClient.runScriptPrepare(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineRequests.ScriptPrepareRequest(
                            mode, file.toAbsolutePath(), cacheDir, stateDir, repoUrl, false, with),
                    steps -> BuildPlanConsole.chooseConsoleListener("tool-install", steps, consoleMode));
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Tool", e.getMessage());
            return Exit.SOFTWARE;
        }

        if (!prep.result().success() || (prep.mainClass() == null && !"kts".equals(mode))) return 1;

        // Snapshot into the env dir so the launcher survives the source moving/vanishing.
        Path envDir = envsRoot.resolve(bin);
        List<Path> classpath = new ArrayList<>();
        if ("kts".equals(mode)) {
            // Kotlin script: snapshot a neutralized copy (jk resolved its @file:DependsOn) and
            // write a kotlinc -script launcher over it + the resolved dep classpath.
            if (prep.kotlincBin() == null) return 1;
            Files.createDirectories(envDir);
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String neutralized = cc.jumpkick.script.ScriptHeaderParser.neutralizeKotlinAnnotations(source);
            Path scriptCopy = envDir.resolve(name);
            Files.writeString(scriptCopy, neutralized != null ? neutralized : source);
            ToolEnv ktsEnv = new ToolEnv(bin, Coordinate.of("script", bin, "local"), "kotlin-script", prep.classpath());
            Path ktsLauncher = ToolLauncher.installKotlinScript(
                    envsRoot, binDir, JavaHomes.runningJavaHome(), prep.kotlincBin(), scriptCopy, ktsEnv, provenance);
            if (!global.outputIsJson()) {
                cc.jumpkick.cli.tui.CommandWedge.printOk(
                        "Tool", "Installed " + file.getFileName() + " → " + ktsLauncher);
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
            cc.jumpkick.cli.tui.CommandWedge.printOk("Tool", "Installed " + file.getFileName() + " → " + launcher);
            CliOutput.out("Add to PATH if needed:");
            CliOutput.out("  export PATH=\"" + binDir + ":$PATH\"");
        }
        return 0;
    }

    /** The app-install plan, shared with {@code jk install}. */
    private InstallCommand appInstallDelegate() {
        InstallCommand delegate = new InstallCommand();
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
        delegate.buildOpts = new cc.jumpkick.cli.BuildOptions();
        delegate.buildOpts.skipTests = skipTests;
        delegate.global = global;
        return delegate;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path src : walk.toList()) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}

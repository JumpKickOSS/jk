// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.BuildPlanConsole;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@code jk run [<target>] [<args>…]} — universal runner, mounted as top-level {@code run} and
 * {@code jk tool run} ({@code jkx} is the argv[0] alias). No target runs the current project; first
 * positional is always the target ({@code jk run. <args>} to pass project args). Resolve/fetch is
 * engine-hosted; exec stays client-side with inherited stdio.
 */
public final class ToolRunCommand implements CliCommand {

    @Override
    public String name() {
        return "run";
    }

    @Override
    public List<String> aliases() {
        // `jk tool exec` — dotnet-tool muscle memory. Hidden per the
        // hidden-surface policy; documented in docs/aliases.md.
        return List.of("exec");
    }

    @Override
    public String description() {
        return "Run a project, tool, script, path, git repo, or URL";
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = List.of(
                Opt.value("<class>", "Override Main-Class (coordinate targets)", "--main"),
                Opt.value("<coord>", "Extra dependency on tool classpath", "--with")
                        .repeat(),
                Opt.value(
                                "<dir>",
                                "Override cache-tier directory (action outputs; not the artifact store). Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the jk state directory.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
                Opt.value("<url>", "Override the Maven repository URL (for tests).", "--repo-url")
                        .hide());
        var all = new ArrayList<>(opts);
        all.addAll(VariantSelection.options()); // project targets only; tool/script targets ignore them
        return all;
    }

    @Override
    public List<Param> parameters() {
        // The tool args after the target are captured as trailing positionals via ZERO_OR_MORE
        return List.of(
                Param.of(
                        "target",
                        Arity.ZERO_OR_ONE,
                        "Catalog name, Maven coordinate (g:a[:version|@selector]),\n"
                                + ".java/.kt/.kts/.jar file, directory, git URL, web URL, or\n"
                                + "alias@catalog. Omit to run the current jk.toml project\n"
                                + "(pass its args via `jk run . <args>`)."),
                Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to the program."));
    }

    String target;
    String mainClass;
    Path cacheDirOverride;
    Path stateDirOverride;
    Path jdksDir;
    URI repoUrl;
    boolean forceRecompile;
    List<String> toolArgs = new ArrayList<>();
    GlobalOptions global;
    // Set by a JBang alias whose script-ref is a coordinate: the alias's dependencies and
    // java-options ride the normal coordinate flow (extra deps + exec JVM args).
    List<String> aliasDeps = List.of();
    List<String> aliasJavaOptions = List.of();

    /**
     * If {@code name} matches a workspace module (full relative path or trailing segment), return
     * that module directory; otherwise {@code null}. Non-workspace dirs and names that look like
     * files/coords/URLs are ignored.
     */
    // Package-visible for tests (leaf ambiguity + local-path precedence,.
    static Path resolveWorkspaceModule(Path cwd, String name) {
        if (name == null || name.isBlank() || ".".equals(name) || name.contains(":") || name.contains("@")) {
            return null;
        }
        // Obvious non-module targets.
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")
                || lower.endsWith(".kt")
                || lower.endsWith(".kts")
                || lower.endsWith(".jar")
                || lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("git+")) {
            return null;
        }
        try {
            Path start = cwd.toAbsolutePath().normalize();
            Path wsRoot = null;
            if (Files.isRegularFile(start.resolve("jk.toml"))) {
                var peek = BuildCommand.projectInfoOrNull(start);
                if (peek != null && peek.workspaceRoot()) wsRoot = start;
                else if (peek == null
                        && !workspaceModules(start.resolve("jk.toml")).isEmpty()) {
                    wsRoot = start;
                }
            }
            if (wsRoot == null) {
                var peek = BuildCommand.projectInfoOrNull(start);
                if (peek != null && !peek.workspaceRootDir().isBlank()) {
                    wsRoot = Path.of(peek.workspaceRootDir());
                }
            }
            if (wsRoot == null) {
                // Cwd is not in a workspace — still allow path-as-module if it has jk.toml
                Path direct = start.resolve(name).normalize();
                if (Files.isRegularFile(direct.resolve("jk.toml"))) return direct;
                return null;
            }
            var rootBuild = BuildCommand.projectInfoOrNull(wsRoot);
            List<String> moduleDirs = rootBuild != null && rootBuild.workspaceRoot()
                    ? rootBuild.moduleDirs()
                    : workspaceModules(wsRoot.resolve("jk.toml"));
            if (moduleDirs.isEmpty()) return null;
            String want = name.replace('\\', '/');
            while (want.startsWith("./")) want = want.substring(2);
            if (want.endsWith("/")) want = want.substring(0, want.length() - 1);
            // A target naming an existing local path keeps its file/dir meaning: only an EXACT
            // declared-path match may claim it — a leaf shortcut must not shadow `./web`.
            boolean localExists = Files.exists(start.resolve(want));
            List<Path> suffixHits = new ArrayList<>();
            for (String mod : moduleDirs) {
                Path dir = Path.of(mod).isAbsolute()
                        ? Path.of(mod).normalize()
                        : wsRoot.resolve(mod).normalize();
                String m = wsRoot.relativize(dir).toString().replace('\\', '/');
                if (!Files.isRegularFile(dir.resolve("jk.toml"))) continue;
                if (m.equals(want)) return dir; // exact declared path — always unambiguous
                // Trailing-segment shortcut: `jk run cli` → clients/cli.
                if (m.endsWith("/" + want)) suffixHits.add(dir);
            }
            if (!suffixHits.isEmpty() && !localExists) {
                if (suffixHits.size() > 1) {
                    // Two modules share the leaf: picking whichever is declared first silently
                    // runs the wrong one — name the candidates instead.
                    String candidates =
                            suffixHits.stream().map(d -> wsRoot(d, start)).collect(Collectors.joining(", "));
                    throw new AmbiguousModuleTarget("`" + want + "` matches several workspace modules (" + candidates
                            + ") — use the full module path");
                }
                return suffixHits.get(0);
            }
            // Unlisted dirs under the workspace stay on the standalone/file classifiers.
        } catch (AmbiguousModuleTarget e) {
            throw e;
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static List<String> workspaceModules(Path jkToml) {
        return cc.jumpkick.config.TomlScan.scan(jkToml, "workspace.modules").stringArray("workspace.modules");
    }

    /** Render a module dir relative to its workspace for an error message. */
    private static String wsRoot(Path moduleDir, Path start) {
        try {
            return cc.jumpkick.config.WorkspaceLocator.findRoot(start)
                    .map(r -> r.relativize(moduleDir).toString())
                    .orElse(moduleDir.toString());
        } catch (Exception e) {
            return moduleDir.toString();
        }
    }

    /** {@code jk run <leaf>} matched more than one workspace module. */
    static final class AmbiguousModuleTarget extends RuntimeException {
        AmbiguousModuleTarget(String message) {
            super(message);
        }
    }

    /**
     * Directory target: jk project builds (tests skipped) and execs; JBang-style {@code main.java};
     * or a single script file in the folder.
     */
    private int runDirectory(Path dir, List<String> args) throws IOException, InterruptedException {
        if (Files.isRegularFile(dir.resolve("jk.toml"))) {
            RunCommand delegate = new RunCommand();
            delegate.cacheDirOverride = cacheDirOverride;
            delegate.jdksDir = jdksDir;
            delegate.buildOpts = new cc.jumpkick.cli.BuildOptions();
            delegate.buildOpts.skipTests = true;
            delegate.global = global;
            return delegate.runProject(dir, args);
        }
        if (Files.isRegularFile(dir.resolve("jbang-catalog.json"))) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Tool", dir + " is a JBang catalog — `alias@…` references aren't" + " supported yet.");
            return Exit.USAGE;
        }
        ScriptRunner runner = new ScriptRunner(global, cacheDirOverride, stateDirOverride, repoUrl, forceRecompile);
        Path mainJava = dir.resolve("main.java");
        if (Files.isRegularFile(mainJava)) return runner.run(mainJava, args);
        List<Path> scripts;
        try (var listing = Files.list(dir)) {
            scripts = listing.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".kts");
                    })
                    .sorted()
                    .toList();
        }
        if (scripts.size() == 1) return runner.run(scripts.get(0), args);
        cc.jumpkick.cli.tui.CommandWedge.printFail(
                "Tool",
                "nothing runnable in " + dir
                        + " — looked for jk.toml, main.java, or exactly one .java/.kt/.kts (found "
                        + scripts.size() + ").");
        return Exit.USAGE;
    }

    /**
     * Git target: trust-gate, engine-hosted clone at the ref, then apply directory-target rules to
     * the checkout.
     */
    private int runGit(String input, List<String> args) throws IOException, InterruptedException {
        String raw = input.startsWith("git+") ? input.substring("git+".length()) : input;
        // url[@ref|#rev][!subdir] — the same embedded-subdir grammar git deps use.
        String subdir = null;
        int bang = raw.indexOf('!');
        if (bang > 0) {
            subdir = raw.substring(bang + 1);
            raw = raw.substring(0, bang);
        }
        InstallCommand.UrlAndRef split = InstallCommand.splitUrlRef(raw);
        String expanded = cc.jumpkick.util.GitUrl.expand(split.url());
        String canonical = cc.jumpkick.util.GitUrl.canonicalize(split.url());
        Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        Integer gated = UrlToolSource.gate(UrlToolSource.gitTrustUrl(canonical), stateDir, "jk tool run");
        if (gated != null) return gated;

        String refStr = split.ref() != null ? split.ref() : "main";
        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Files.createDirectories(cacheDir);
        boolean refresh = cc.jumpkick.config.SessionContext.current().config().forceOr(false);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);

        Path checkout;
        cc.jumpkick.cli.engine.EngineRequests.GitFetchOutcome outcome;
        try {
            outcome = cc.jumpkick.cli.engine.EngineClient.runGitFetch(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineRequests.GitFetchRequest(
                            expanded, canonical, refStr, cacheDir, refresh, /* requireJkToml */ false),
                    steps -> BuildPlanConsole.chooseConsoleListener("tool-git-fetch", steps, mode));
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Tool", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!outcome.result().success() || outcome.checkout() == null) return 1;
        checkout = outcome.checkout();

        if (subdir != null) {
            Path sub = checkout.resolve(subdir).normalize();
            if (!sub.startsWith(checkout) || !Files.isDirectory(sub)) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Tool", "no directory `" + subdir + "` in " + input);
                return Exit.USAGE;
            }
            checkout = sub;
        }
        return runDirectory(checkout, args);
    }

    /**
     * JBang {@code alias@catalog}: trust-gate the catalog origin, then run the alias's {@code
     * script-ref}. Coordinate refs rewrite {@code target}/{@code toolArgs} and return null.
     */
    private Integer resolveJBangAlias(String command) throws IOException, InterruptedException {
        Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        // Trust decides BEFORE any fetch: the catalog URL is derived from user input, and no
        // request may leave the machine for an origin the user never allowed.
        var trust = cc.jumpkick.tool.TrustedSources.load(stateDir);
        List<String> origins = JBangCatalog.origins(target);
        boolean preTrusted = origins.stream().anyMatch(trust::isTrusted);
        if (!preTrusted) {
            Integer gated = UrlToolSource.gate(origins.get(0), stateDir, command);
            if (gated != null) return gated;
        }
        JBangCatalog.Resolved r;
        try {
            r = JBangCatalog.resolve(target, new cc.jumpkick.http.Http(), preTrusted ? trust::isTrusted : o -> true);
        } catch (IOException e) {
            CliOutput.err(command + ": " + e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!r.pageOrigin().equals(origins.get(0)) && !trust.isTrusted(r.pageOrigin())) {
            // Forge fallback landed on a different origin than the one the user allowed.
            Integer gated = UrlToolSource.gate(r.pageOrigin(), stateDir, command);
            if (gated != null) return gated;
        }
        List<String> merged = new ArrayList<>(r.arguments());
        merged.addAll(toolArgs);
        String ref = r.scriptRef();
        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        if (ref.contains("://")) {
            Integer urlGate = UrlToolSource.gate(ref, stateDir, command);
            if (urlGate != null) return urlGate;
            Path fetched = UrlToolSource.fetch(ref, cacheDir, forceRecompile);
            return new ScriptRunner(
                            global,
                            cacheDirOverride,
                            stateDirOverride,
                            repoUrl,
                            forceRecompile,
                            r.dependencies(),
                            r.javaOptions())
                    .run(fetched, merged);
        }
        if (ref.contains(":")) {
            // Coordinate script-ref: rewrite the target and let the normal flow resolve it.
            target = ref;
            toolArgs = merged;
            aliasDeps = r.dependencies();
            aliasJavaOptions = r.javaOptions();
            return null;
        }
        Path fetched = UrlToolSource.fetch(r.rawBase().resolve(ref).toString(), cacheDir, forceRecompile);
        return new ScriptRunner(
                        global,
                        cacheDirOverride,
                        stateDirOverride,
                        repoUrl,
                        forceRecompile,
                        r.dependencies(),
                        r.javaOptions())
                .run(fetched, merged);
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        List<String> positionals = in.positionals();
        // No target = the current project (the `.` directory rules: jk.toml → build + exec).
        this.target = positionals.isEmpty() ? "." : positionals.get(0);
        this.toolArgs = positionals.size() > 1 ? positionals.subList(1, positionals.size()) : List.of();
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(cc.jumpkick.cli.CliPaths::abs).orElse(null);
        this.stateDirOverride = in.value("state-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.forceRecompile = in.isSet("force");
        this.global = GlobalOptions.from(in);
        // --release / --variant parameterize project targets (current dir or a directory target):
        // the selection rides the ambient session into the delegate's build + deploy command.
        VariantSelection.install(in, global.workingDir());

        // Workspace module selector: `jk run clients/cli` or `jk run cli` from the workspace root
        // (or any cwd) resolves against workspace.modules before other target classifiers.
        Path moduleHit;
        try {
            moduleHit = resolveWorkspaceModule(global.workingDir(), target);
        } catch (AmbiguousModuleTarget e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Run", e.getMessage());
            return cc.jumpkick.model.command.Exit.USAGE;
        }
        if (moduleHit != null) {
            return runDirectory(moduleHit, toolArgs);
        }

        // A local file target (by extension) is compiled/run by ScriptRunner; the
        // extension is the signal even when the file is missing, so the user gets
        // a proper "not found" error from the matching mode handler. Routing goes
        // through the classifier so a remote `https://…/tool.jar` is NOT a file.
        cc.jumpkick.tool.ToolTarget classified = cc.jumpkick.tool.ToolTarget.classify(target);
        if (classified instanceof cc.jumpkick.tool.ToolTarget.RunnableFile file) {
            List<String> fileWith;
            try {
                fileWith = ToolTargets.resolveWith(in.values("with"));
            } catch (ToolTargets.TargetException e) {
                CliOutput.err(e.getMessage());
                return Exit.USAGE;
            }
            return new ScriptRunner(
                            global, cacheDirOverride, stateDirOverride, repoUrl, forceRecompile, fileWith, List.of())
                    .run(file.path(), toolArgs);
        }
        if (classified instanceof cc.jumpkick.tool.ToolTarget.Directory dir) {
            return runDirectory(global.workingDir().resolve(dir.path()).normalize(), toolArgs);
        }
        if (classified instanceof cc.jumpkick.tool.ToolTarget.Git g) {
            return runGit(g.raw(), toolArgs);
        }
        if (classified instanceof cc.jumpkick.tool.ToolTarget.Url u) {
            Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
            Integer gated = UrlToolSource.gate(u.raw(), stateDir, "jk tool run");
            if (gated != null) return gated;
            Path fetched;
            try {
                fetched = UrlToolSource.fetch(
                        u.raw(), cacheDirOverride != null ? cacheDirOverride : JkDirs.cache(), forceRecompile);
            } catch (IOException e) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Tool", e.getMessage());
                return Exit.SOFTWARE;
            }
            return new ScriptRunner(global, cacheDirOverride, stateDirOverride, repoUrl, forceRecompile)
                    .run(fetched, toolArgs);
        }

        if (classified instanceof cc.jumpkick.tool.ToolTarget.JBangAlias) {
            Integer aliasExit = resolveJBangAlias("jk tool run");
            if (aliasExit != null) return aliasExit;
            // A GAV script-ref fell through: `target`/`toolArgs` were rewritten in place.
        }

        ToolTargets.Resolved resolved;
        List<String> with;
        try {
            resolved = ToolTargets.resolve(target);
            List<String> withInputs = new ArrayList<>(in.values("with"));
            withInputs.addAll(aliasDeps);
            with = ToolTargets.resolveWith(withInputs);
        } catch (ToolTargets.TargetException e) {
            CliOutput.err(e.getMessage());
            return Exit.USAGE;
        }
        String bin = resolved.defaultBin();
        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Files.createDirectories(cacheDir);

        ToolEnv env;
        cc.jumpkick.cli.engine.EngineRequests.ToolResolveOutcome outcome;
        try {
            outcome = cc.jumpkick.cli.engine.EngineClient.runToolResolve(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineRequests.ToolResolveRequest(
                            resolved.coordSpec(), with, bin, mainClass, repoUrl, cacheDir),
                    steps -> BuildPlanConsole.chooseConsoleListener(
                            "tool-run", steps, BuildPlanConsole.modeFor(global)));
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Tool", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!outcome.result().success() || outcome.mainClass() == null || outcome.coord() == null) return 1;
        env = new ToolEnv(bin, Coordinate.parse(outcome.coord()), outcome.mainClass(), outcome.classpath());

        // The exec deliberately stays client-side: the tool inherits this terminal's stdio.
        Path javaHome = JavaHomes.runningJavaHome();
        return ToolLauncher.execEphemeral(javaHome, env, aliasJavaOptions, toolArgs);
    }
}

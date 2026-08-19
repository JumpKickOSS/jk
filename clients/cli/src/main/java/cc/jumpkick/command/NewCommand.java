// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Answers;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Wizard;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jline.terminal.Terminal;

/**
 * {@code jk new} — scaffold a project or workspace module (aliases: {@code init}, {@code create}).
 * TTY with no flags → interactive wizard; otherwise flags with defaults. Both paths write via
 * {@code NewProjectVerb}. Walks up for a parent {@code jk.toml} (module) unless
 * {@code --no-module}; modules inherit parent defaults and register under {@code [workspace].modules}.
 *
 * <p>Wizard construction lives in {@link NewWizard}. This type is the command facade (under 1,200).
 */
public final class NewCommand implements CliCommand {

    @Override
    public String name() {
        return "new";
    }

    @Override
    public String description() {
        return "Create a new jk project (or workspace module)";
    }

    @Override
    public List<String> aliases() {
        return List.of("create");
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<name>", "Project name and target directory leaf.", "--name"),
                Opt.value("<group>", "Maven groupId (default: from git config).", "--group"),
                // --jdk rides the GLOBAL option (same canonical key "jdk"); a local
                // re-declaration would collide with it in the dispatcher.
                Opt.value("<lang>", "Language: java | kotlin | groovy. Default: java.", "--lang"),
                Opt.flag("Executable project (default is a library).", "--executable")
                        .negate(),
                Opt.flag("Assembly (fat) jar. Implies --executable.", "--assembly"),
                Opt.flag("Wire a GraalVM native-image build.", "--native"),
                Opt.flag("Spring Boot application (implies --executable).", "--spring"),
                Opt.flag("Grails app (--executable, --lang groovy)", "--grails"),
                Opt.flag("Quarkus application (implies --executable).", "--quarkus"),
                Opt.flag("Micronaut application (implies --executable).", "--micronaut"),
                Opt.flag("Scaffold a jk build-plugin authoring project.", "--plugin"),
                Opt.value("<ref>", "Giter8 template path, short name, or URL", "--template"),
                Opt.value("<k=v>", "Template property k=v (repeatable)", "--param")
                        .repeat(),
                Opt.value("<url>", "Extra git template source (repeatable)", "--template-source")
                        .repeat(),
                Opt.value("<deps>", "Curated deps, comma-separated.", "--deps"),
                Opt.value("<layout>", "Layout: simple | traditional.", "--layout"),
                Opt.value("<module>", "Kotlin module name (-> project.module).", "--kotlin-module"),
                Opt.flag("Force a standalone project (not a module).", "--no-module"),
                Opt.flag("", "--no-member").hide()); // undocumented synonym for --no-module
    }

    @Override
    public List<Param> parameters() {
        return List.of(
                Param.of("directory", Arity.ZERO_OR_ONE, "Target directory. Default: cwd or a ./<name> subdir."));
    }

    String name;
    String group;
    String jdk;
    String lang;
    Boolean executable;
    boolean assembly;
    boolean nativeImage;
    boolean spring;
    boolean grails;
    boolean quarkus;
    boolean micronaut;
    boolean plugin;
    String templateRef;
    List<String> templateParams = List.of();
    /** One-shot third-party git sources for short-name lookup. */
    List<String> templateSources = List.of();

    String depsCsv;
    String layoutFlag;
    String kotlinModule;
    boolean noModule;
    Path directory;
    GlobalOptions global;

    @SuppressWarnings("rawtypes")
    private static final BuildPlanKey<List> CANDIDATES = BuildPlanKey.of("candidates", List.class);

    private static final BuildPlanKey<Terminal> TERMINAL = BuildPlanKey.of("terminal", Terminal.class);
    private static final BuildPlanKey<cc.jumpkick.jdk.JdkCatalog> CATALOG =
            BuildPlanKey.of("catalog", cc.jumpkick.jdk.JdkCatalog.class);
    private static final BuildPlanKey<Answers> ANSWERS = BuildPlanKey.of("answers", Answers.class);
    private static final BuildPlanKey<NewJdkCandidate> PICKED = BuildPlanKey.of("picked", NewJdkCandidate.class);
    private static final BuildPlanKey<NewInputs> INPUTS = BuildPlanKey.of("inputs", NewInputs.class);

    /** Set during scaffold when the new project was registered as a workspace module. */
    private record Module(Path root, String rel, String projectName) {}

    private volatile Module registered;

    /**
     * The enclosing project/workspace this invocation will add a module to, or {@code null} when
     * we're creating a standalone project. Resolved once in {@link #call} and consumed by the
     * wizard (UX + inherited defaults), the flag path, and scaffolding.
     */
    private ParentInfo parent;

    /** Global {@code default-jdk} id from {@code ~/.jk/config/jk.toml}, or empty. */
    private Optional<String> defaultJdk = Optional.empty();

    /** Inherited context from the parent project's identity keys. */
    record ParentInfo(Path root, cc.jumpkick.engine.protocol.ProjectInfo info) {
        String displayName() {
            return info.name();
        }

        String group() {
            return info.group();
        }

        boolean kotlin() {
            return info.kotlin();
        }

        boolean groovy() {
            return info.groovy();
        }

        /** The JDK toolchain version (which JDK runs the build). */
        int jdkMajor() {
            int major = cc.jumpkick.model.JkBuild.Project.majorOf(info.jdk());
            return major > 0 ? major : info.javaRelease();
        }

        /**
         * The {@code java = N} compile target, which flows through even when it diverges from {@link
         * #jdkMajor}.
         */
        int javaRelease() {
            return info.javaRelease();
        }
    }

    /**
     * Walk up from {@code startDir} for a parent {@code jk.toml}; stop at {@code.git} or
     * {@code $HOME}. {@code --no-module} → empty.
     */
    static Optional<Path> detectParentDir(Path startDir, Path home, boolean noModule) {
        if (noModule) return Optional.empty();
        Path normHome = home == null ? null : home.toAbsolutePath().normalize();
        for (Path dir = startDir.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("jk.toml"))) return Optional.of(dir);
            if (Files.isDirectory(dir.resolve(".git"))) return Optional.empty(); // exited the repo
            if (dir.equals(normHome)) return Optional.empty(); // hit $HOME
        }
        return Optional.empty();
    }

    /**
     * Where to begin the parent search — the directory the project will live <em>in</em> (its
     * target's parent). For {@code jk new foo} that's the cwd; for {@code jk new /abs/foo} it's
     * {@code /abs}; for {@code.} / no arg it's the cwd (the module is the cwd itself, or
     * cwd/&lt;name&gt;).
     */
    private Path detectionStartDir(Path cwd) {
        if (directory == null || isCurrentDirArg(directory)) return cwd;
        Path parentDir = cwd.resolve(directory).normalize().getParent();
        return parentDir != null ? parentDir : cwd;
    }

    /**
     * Resolve {@link #parent} by parsing the detected parent's manifest (null if none / unparseable).
     */
    private ParentInfo resolveParent(Path startDir) {
        Path home = Optional.ofNullable(System.getProperty("user.home"))
                .map(Path::of)
                .orElse(null);
        Optional<Path> root = detectParentDir(startDir, home, noModule);
        if (root.isEmpty()) return null;
        var info = BuildCommand.projectInfoOrNull(root.get());
        if (info == null) return null; // unreadable/unparseable parent — treat as standalone
        return new ParentInfo(root.get(), info);
    }

    @Override
    public int run(Invocation in) throws IOException {
        this.name = in.value("name").orElse(null);
        this.group = in.value("group").orElse(null);
        this.jdk = in.value("jdk").orElse(null);
        this.lang = in.value("lang").orElse(null);
        this.executable = in.flag("executable").orElse(null);
        this.assembly = in.isSet("assembly");
        this.nativeImage = in.isSet("native");
        this.spring = in.isSet("spring");
        this.grails = in.isSet("grails");
        this.quarkus = in.isSet("quarkus");
        this.micronaut = in.isSet("micronaut");
        this.plugin = in.isSet("plugin");
        this.templateRef = in.value("template").orElse(null);
        this.templateParams = in.values("param");
        this.templateSources = in.values("template-source");
        this.depsCsv = in.value("deps").orElse(null);
        this.layoutFlag = in.value("layout").orElse(null);
        this.kotlinModule = in.value("kotlin-module").orElse(null);
        this.noModule = in.isSet("no-module") || in.isSet("no-member");
        this.directory =
                in.positionals().isEmpty() ? null : Path.of(in.positionals().get(0));
        this.global = GlobalOptions.from(in);
        return callBody();
    }

    /** The body of run(), callable after fields are populated (used by InitCommand delegation). */
    int callBody() throws IOException {
        Path cwd = Path.of(".").toAbsolutePath().normalize();

        // Fail-fast for `jk new.` when the cwd already has a project.
        // For any other invocation we defer the existing-manifest check to
        // after the target is fully resolved (the project name may come from
        // the wizard or from `--name`).
        if (directory != null && isCurrentDirArg(directory) && Files.exists(cwd.resolve("jk.toml"))) {
            String existing = wizardPresetName(directory, cwd)
                    .orElseGet(
                            () -> cwd.getFileName() != null ? cwd.getFileName().toString() : "this directory");
            emitProjectExistsError(existing, parent != null, true, null);
            return Exit.CONFIG;
        }

        // Are we adding a module to an existing project/workspace, or creating
        // a standalone project? Search up from where the project will live (the
        // target's parent). Drives the wizard UX and inherited defaults.
        this.parent = resolveParent(detectionStartDir(cwd));
        this.defaultJdk = readDefaultJdk();

        if (templateRef != null && !templateRef.isBlank()) {
            return runTemplateBuildPlan(cwd);
        }

        if (shouldRunWizard()) {
            return runWizardBuildPlan(cwd);
        }
        return runFlagBuildPlan(cwd);
    }

    /**
     * {@code jk new --template <local-path|short-name|git-uri|owner/repo>}.
     */
    private int runTemplateBuildPlan(Path cwd) {
        if (spring || grails || quarkus || micronaut || plugin) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "New",
                    "--template cannot be combined with --spring, --grails, --quarkus, --micronaut, or --plugin");
            return Exit.USAGE;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String p : templateParams) {
            int eq = p.indexOf('=');
            if (eq <= 0) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("New", "--param expects key=value, got: " + p);
                return Exit.USAGE;
            }
            params.put(p.substring(0, eq), p.substring(eq + 1));
        }
        var presetName = wizardPresetName(directory, cwd);
        String fileBase = Path.of(templateRef).getFileName().toString();
        if (fileBase.endsWith(".g8")) fileBase = fileBase.substring(0, fileBase.length() - 3);
        String resolvedName =
                (name != null && !name.isBlank()) ? name : params.getOrDefault("name", presetName.orElse(fileBase));
        params.putIfAbsent("name", resolvedName);
        if (group != null && !group.isBlank()) {
            params.putIfAbsent("organization", group);
            params.putIfAbsent("group", group);
            params.putIfAbsent("package", group);
        }
        Path target = resolveTarget(directory, cwd, resolvedName);
        Path parentDir = target.getParent() == null ? cwd : target.getParent();
        if (officialTemplateShortName(templateRef)) {
            cc.jumpkick.cli.engine.EngineClient.freshenCatalog(
                    cc.jumpkick.engine.EnginePaths.current(), "templates", global.offline, null, null);
        }
        try {
            var ack = cc.jumpkick.cli.engine.EngineClient.newProject(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineRequests.NewProjectRequest(
                            resolvedName,
                            parentDir.toString(),
                            group,
                            "java",
                            "simple",
                            templateRef,
                            false,
                            null,
                            null,
                            0,
                            false,
                            false,
                            false,
                            null,
                            List.of(),
                            true,
                            parent == null,
                            params,
                            true,
                            target.toString()));
            if (ack.error() != null && !ack.error().isBlank()) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("New", ack.error());
                return ack.error().contains("not found") ? Exit.USAGE : Exit.SOFTWARE;
            }
            cc.jumpkick.cli.tui.CommandWedge.envelopeStart();
            CliOutput.out(cc.jumpkick.cli.tui.JkWedge.chipLine(
                    cc.jumpkick.cli.tui.Glyphs.CHECK,
                    "New Project",
                    cc.jumpkick.config.GlobalConfig.nerdFont(),
                    "Applied template (" + ack.filesWritten() + " files) → " + target.getFileName()));
            return Exit.SUCCESS;
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("New", e.getMessage());
            return Exit.SOFTWARE;
        }
    }

    /**
     * Interactive wizard plan (prewarm → wizard → optional install-jdk → scaffold). The terminal
     * lives across steps via a {@link BuildPlanKey} and is closed in a {@code finally}.
     */
    private int runWizardBuildPlan(Path cwd) throws IOException {
        Path cache = JkDirs.cache();

        Task prewarm = Task.builder(TaskNames.PREWARM)
                .kind(TaskKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("discover JDKs + fetch catalog + open terminal");
                    var jdkOptionsFuture = CompletableFuture.supplyAsync(NewJdkOptions::discover, JkThreads.io());
                    var catalogFuture = CompletableFuture.supplyAsync(NewCommand::fetchCatalogQuiet, JkThreads.io());
                    Terminal terminal;
                    try {
                        terminal = Wizard.openTerminal();
                    } catch (IOException e) {
                        ctx.error("terminal", "failed to open terminal: " + e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.put(TERMINAL, terminal);
                    var jdkOptions = jdkOptionsFuture.join();
                    var catalog = catalogFuture.join();
                    var candidates = NewJdkCandidate.build(
                            jdkOptions,
                            catalog,
                            LATEST_LTS_MAJOR,
                            cc.jumpkick.jdk.HostPlatform.currentOs(),
                            cc.jumpkick.jdk.HostPlatform.currentArch());
                    if (candidates.isEmpty()) {
                        ctx.error("no-jdks", "no JDKs found on this system");
                        throw new RuntimeException("no jdks");
                    }
                    ctx.put(CANDIDATES, candidates);
                    catalog.ifPresent(c -> ctx.put(CATALOG, c)); // drives the two-track language list
                    ctx.progress(1);
                })
                .build();

        Task wizardStep = Task.builder(TaskNames.WIZARD)
                .requires(TaskNames.PREWARM)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("run wizard");
                    Terminal terminal = ctx.require(TERMINAL);
                    @SuppressWarnings("unchecked")
                    List<NewJdkCandidate> candidates = (List<NewJdkCandidate>) ctx.require(CANDIDATES);

                    Answers preset = wizardPresetName(directory, cwd)
                            .map(n -> Answers.of(Map.of("name", (Object) n)))
                            .orElseGet(() -> Answers.of(Map.of()));
                    var groupGuess = NewGroupGuess.guess(
                            cwd,
                            Optional.ofNullable(System.getProperty("user.home"))
                                    .map(Path::of)
                                    .orElse(null));
                    var wizard = buildWizard(
                            candidates,
                            ctx.get(CATALOG).orElse(null),
                            groupGuess,
                            parent,
                            defaultJdk.isPresent(),
                            directory != null && isCurrentDirArg(directory));
                    var wizardResult = wizard.run(terminal, preset);
                    if (wizardResult.isEmpty()) {
                        // Cancelled via Ctrl-C. Wizard.printCancellation
                        // preserves the cyan active-rail closer and prints
                        // the red marker beside it. Runtime.halt skips
                        // shutdown hooks — JLine's cleanup hook would block
                        // on the NonBlockingReader.
                        Wizard.printCancellation(
                                terminal, parent != null ? "Module creation canceled" : "Project creation canceled");
                        Runtime.getRuntime().halt(130);
                    }
                    ctx.put(ANSWERS, wizardResult.get());
                    ctx.put(PICKED, pickCandidate(wizardResult.get(), candidates));
                    ctx.progress(1);
                })
                .build();

        Task installJdk = Task.builder(TaskNames.INSTALL_JDK)
                .kind(TaskKind.IO)
                .requires(TaskNames.WIZARD)
                .ticks(1)
                .execute(ctx -> {
                    NewJdkCandidate picked = ctx.require(PICKED);
                    if (picked.installed()) {
                        ctx.label("JDK already installed");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("install missing JDK");
                    var installed = installCandidate(picked);
                    if (installed.isEmpty()) {
                        ctx.error("jdk-install", "JDK install failed");
                        throw new RuntimeException("jdk install failed");
                    }
                    ctx.put(PICKED, installed.get());
                    ctx.progress(1);
                })
                .build();

        Task scaffold = Task.builder(TaskNames.SCAFFOLD)
                .requires(TaskNames.INSTALL_JDK)
                .ticks(1)
                .execute(ctx -> {
                    NewJdkCandidate resolved = ctx.require(PICKED);
                    // After install-jdk, picked is always Installed; unwrap
                    // for fromAnswers.
                    var pickedOpt = ((NewJdkCandidate.Installed) resolved).option();
                    var inputs = fromAnswers(ctx.require(ANSWERS), cwd, pickedOpt);
                    ctx.put(INPUTS, inputs);
                    if (Files.exists(inputs.directory().resolve("jk.toml"))) {
                        ctx.error("exists", "project " + inputs.name() + " already exists at " + inputs.directory());
                        throw new RuntimeException("project exists");
                    }
                    ctx.label("scaffold " + inputs.name());
                    Files.createDirectories(inputs.directory());
                    scaffoldAndRegister(inputs);
                    ctx.put(INPUTS, inputs);
                    ctx.progress(1);
                })
                .build();

        BuildPlan plan = BuildPlan.builder("new")
                .interactive(true)
                .addTask(prewarm)
                .addTask(wizardStep)
                .addTask(installJdk)
                .addTask(scaffold)
                .build();

        // Single try/finally wrapping the whole plan lifecycle plus the
        // success-emit path: emitSuccessOnTerminal writes through the
        // wizard's JLine terminal handle, so the terminal has to stay
        // open until after that call. The finally closes it on the way
        // out whether scaffold succeeded, failed, or threw.
        try {
            BuildPlanResult result = BuildPlanConsole.run(plan, BuildPlanConsole.modeFor(global), cache);

            if (!result.success()) {
                for (BuildPlanResult.Diagnostic d : result.errors()) {
                    if ("no-jdks".equals(d.code())) {
                        emitNoJdksError();
                        return Exit.CONFIG;
                    }
                    if ("exists".equals(d.code())) {
                        NewInputs partial = plan.get(INPUTS).orElse(null);
                        String coord = partial != null ? partial.group() + ":" + partial.name() : "project";
                        boolean isInit = directory != null && isCurrentDirArg(directory);
                        Terminal term = plan.get(TERMINAL).orElse(null);
                        emitProjectExistsError(coord, parent != null, isInit, term);
                        return Exit.CONFIG;
                    }
                }
                return Exit.CONFIG;
            }

            NewInputs inputs = plan.get(INPUTS).orElseThrow();
            boolean isInit = directory != null && isCurrentDirArg(directory);
            plan.get(TERMINAL)
                    .ifPresentOrElse(
                            t -> emitSuccessOnTerminal(inputs, t, registered, isInit),
                            () -> emitSuccessPlain(inputs, registered, isInit));
            return 0;
        } finally {
            plan.get(TERMINAL).ifPresent(t -> {
                try {
                    t.close();
                } catch (IOException ignored) {
                }
            });
        }
    }

    /**
     * Flag mode: validate inputs, scaffold. Not interactive (no wizard, no progress widgets in the
     * command's own output). Wrapping it in a plan still gives us a run-log entry for `jk new
     * --name=X` etc.
     */
    private int runFlagBuildPlan(Path cwd) {
        NewInputs inputs;
        try {
            inputs = fromFlags(cwd);
        } catch (IllegalArgumentException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("New", e.getMessage());
            return Exit.USAGE;
        }
        if (assembly && inputs.main().isEmpty()) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("New", "--assembly requires --executable");
            return Exit.USAGE;
        }
        if (Files.exists(inputs.directory().resolve("jk.toml"))) {
            emitProjectExistsError(
                    inputs.group() + ":" + inputs.name(),
                    parent != null,
                    directory != null && isCurrentDirArg(directory),
                    null);
            return Exit.CONFIG;
        }
        Path cache = JkDirs.cache();

        Task scaffold = Task.builder(TaskNames.SCAFFOLD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("scaffold " + inputs.name());
                    Files.createDirectories(inputs.directory());
                    scaffoldAndRegister(inputs);
                    ctx.progress(1);
                })
                .build();

        BuildPlan plan = BuildPlan.builder("new").addTask(scaffold).build();

        BuildPlanResult result = BuildPlanConsole.run(plan, BuildPlanConsole.modeFor(global), cache);
        if (!result.success()) return 1;
        if (!global.outputIsJson())
            emitSuccessPlain(inputs, registered, directory != null && isCurrentDirArg(directory));
        return 0;
    }

    /**
     * TTY + no real flags. A bare positional ({@code jk new my-project} or {@code jk new.}) still
     * runs the wizard — the positional only pre-seeds the name.
     */
    private boolean shouldRunWizard() {
        if (!isInteractiveTerminal()) {
            return false;
        }
        return !anyFlagSupplied();
    }

    private static boolean isInteractiveTerminal() {
        // Gates the interactive `jk new` wizard — input axis, so key on the controlling terminal.
        return cc.jumpkick.cli.tui.Interactivity.canPrompt();
    }

    private boolean anyFlagSupplied() {
        return name != null
                || group != null
                || jdk != null
                || lang != null
                || executable != null
                || assembly
                || nativeImage
                || spring
                || grails
                || quarkus
                || micronaut
                || plugin
                || (templateRef != null && !templateRef.isBlank())
                || depsCsv != null
                || layoutFlag != null
                || kotlinModule != null;
    }

    /**
     * Scaffold the project, then — if it lands inside an existing workspace — skip the per-module
     * {@code jk-lock.toml} and register the new module in the root {@code [workspace].modules} (Cargo/uv:
     * {@code cargo new} / {@code uv init} edit the workspace manifest). Records the registration for
     * the success message.
     */
    private void scaffoldAndRegister(NewInputs inputs) throws IOException {
        Path target = inputs.directory();
        Path parentDir = target.getParent() == null ? target : target.getParent();
        String framework = inputs.frameworkScaffold() ? inputs.frameworkPluginFlag() : null;
        var ack = cc.jumpkick.cli.engine.EngineClient.newProject(
                cc.jumpkick.engine.EnginePaths.current(),
                new cc.jumpkick.cli.engine.EngineRequests.NewProjectRequest(
                        inputs.name(),
                        parentDir.toString(),
                        inputs.group(),
                        inputs.lang().hoconValue(),
                        inputs.layout(),
                        null,
                        inputs.isRunnable(),
                        framework,
                        inputs.jdk(),
                        inputs.javaRelease(),
                        inputs.assembly(),
                        inputs.nativeImage(),
                        inputs.plugin(),
                        inputs.kotlinModuleName().orElse(null),
                        inputs.deps(),
                        inputs.sample(),
                        parent == null,
                        Map.of(),
                        true,
                        target.toString()));
        if (ack.error() != null && !ack.error().isBlank()) {
            throw new IOException(ack.error());
        }
        if (parent != null) {
            Path root = parent.root();
            String rel = root.relativize(inputs.directory()).toString().replace('\\', '/');
            Path rootToml = root.resolve("jk.toml");
            // Registers the module, promoting a plain project into a workspace
            // root (creating the [workspace] table) when this is its first module.
            EngineEdits.apply(rootToml, "register-workspace-module", List.of(rel));
            registered = new Module(root, rel, parent.displayName());
        }
    }

    private NewInputs fromFlags(Path cwd) {
        if (plugin && (spring || grails || quarkus || micronaut || nativeImage)) {
            throw new IllegalArgumentException(
                    "--plugin scaffolds a build-plugin project and can't be combined with --spring, --grails,"
                            + " --quarkus, --micronaut, or --native");
        }
        int frameworks = (spring ? 1 : 0) + (grails ? 1 : 0) + (quarkus ? 1 : 0) + (micronaut ? 1 : 0);
        if (frameworks > 1) {
            throw new IllegalArgumentException("--spring, --grails, --quarkus, and --micronaut are mutually exclusive");
        }
        if (grails && lang != null && !lang.isBlank() && !"groovy".equalsIgnoreCase(lang)) {
            throw new IllegalArgumentException("--grails scaffolds a Groovy application (--lang " + lang + "?)");
        }
        var presetName = wizardPresetName(directory, cwd);
        var resolvedName = (name != null && !name.isBlank()) ? name : presetName.orElse("untitled");
        Path target = resolveTarget(directory, cwd, resolvedName);
        var resolvedGroup = (group != null && !group.isBlank())
                ? group
                : parent != null
                        ? parent.group()
                        : NewGroupGuess.guess(
                                cwd,
                                Optional.ofNullable(System.getProperty("user.home"))
                                        .map(Path::of)
                                        .orElse(null));
        // Resolve the JDK pin written to jk.toml: an explicit --jdk wins (keeping
        // a vendor only when the user typed one); else inherit the parent's major
        // (module); else adopt the global default JDK's major; else the latest
        // LTS. Every non-explicit path writes a bare major — the vendor stays out
        // of jk.toml unless the user asked for it.
        NewJdkPlan.Spec jdkSpec;
        if (jdk != null && !jdk.isBlank()) {
            jdkSpec = resolveJdkArg(jdk);
        } else if (parent != null && parent.jdkMajor() > 0) {
            int m = parent.jdkMajor();
            jdkSpec = new NewJdkPlan.Spec(m, Integer.toString(m));
        } else if (defaultJdk.map(cc.jumpkick.model.JkBuild.Project::majorOf).orElse(0) > 0) {
            int m = cc.jumpkick.model.JkBuild.Project.majorOf(defaultJdk.get());
            jdkSpec = new NewJdkPlan.Spec(m, Integer.toString(m));
        } else {
            jdkSpec = new NewJdkPlan.Spec(LATEST_LTS_MAJOR, Integer.toString(LATEST_LTS_MAJOR));
        }
        var resolvedJdk = jdkSpec.pin();
        int resolvedJdkMajor = jdkSpec.major();
        // The compile target flows through from the parent (workspace-wide
        // release) even when it diverges from the JDK toolchain; standalone
        // projects target their JDK.
        int resolvedJavaRelease = parent != null ? parent.javaRelease() : resolvedJdkMajor;
        // A native binary needs a GraalVM (native-image) JDK; reject a Java release no GraalVM
        // ships yet (e.g. 26 today). Same two-track rule the wizard enforces, checked here for the
        // non-interactive flag path. Consult the catalog only when --native is set.
        if (nativeImage && resolvedJavaRelease > 0) {
            int maxNative = maxNativeMajor(fetchCatalogQuiet().orElse(null));
            if (resolvedJavaRelease > maxNative) {
                throw new IllegalArgumentException("--native requires a GraalVM (native-image) JDK; the newest "
                        + "native-image-capable Java is " + maxNative + ", but Java " + resolvedJavaRelease
                        + " was requested");
            }
        }
        var resolvedLang = grails
                ? NewInputs.Language.GROOVY
                : (lang != null && !lang.isBlank())
                        ? parseLanguage(lang)
                        : (parent != null && parent.kotlin())
                                ? NewInputs.Language.KOTLIN
                                : (parent != null && parent.groovy())
                                        ? NewInputs.Language.GROOVY
                                        : NewInputs.Language.JAVA;
        var isExecutable =
                Boolean.TRUE.equals(executable) || assembly || nativeImage || spring || grails || quarkus || micronaut;
        // A plugin project uses the Maven layout so jk-plugin.toml lands at the jar root
        // (src/main/resources). PluginMain is implied by that file — no [application] table.
        // Boot / Quarkus / Grails users also expect the Maven layout. An explicit --layout still wins.
        var resolvedLayout = (layoutFlag != null && !layoutFlag.isBlank())
                ? layoutFlag.toLowerCase()
                : (spring || grails || quarkus || micronaut || plugin) ? "traditional" : "simple";
        var resolvedMain = plugin
                ? Optional.<String>empty()
                : (spring || grails || quarkus || micronaut)
                        // Kotlin's top-level main lives on the ApplicationKt facade class.
                        // Quarkus scaffold uses an object Application with @JvmStatic main → Application.
                        ? Optional.of(resolvedGroup
                                + (resolvedLang == NewInputs.Language.KOTLIN && !quarkus
                                        ? ".ApplicationKt"
                                        : ".Application"))
                        : isExecutable
                                ? Optional.of(deriveMainFqcn(
                                        resolvedGroup, resolvedLang, "simple".equalsIgnoreCase(resolvedLayout)))
                                : Optional.<String>empty();
        // A plugin's jk-plugin-sdk dependency is emitted by NewJkBuildRenderer, not via curated deps.
        var resolvedDeps = plugin ? List.<String>of() : parseDeps(depsCsv);
        var resolvedKotlinModule = (kotlinModule != null && !kotlinModule.isBlank())
                ? Optional.of(kotlinModule)
                : Optional.<String>empty();
        return new NewInputs(
                resolvedGroup,
                resolvedName,
                resolvedJdk,
                resolvedJdkMajor,
                resolvedJavaRelease,
                Optional.<String>empty(), // flag path doesn't resolve to a specific install
                resolvedMain,
                assembly, // NewInputs also forces it on for --plugin / --micronaut
                nativeImage,
                spring,
                grails,
                quarkus,
                micronaut,
                plugin,
                resolvedLang,
                resolvedLayout,
                resolvedKotlinModule,
                resolvedDeps,
                true,
                target);
    }

    /**
     * Pre-seed for the wizard's project-name step from the positional arg ({@code "."} → cwd leaf;
     * a path → its file name; null → empty). Package-private for tests.
     */
    static Optional<String> wizardPresetName(Path directoryArg, Path cwd) {
        return NewWizard.wizardPresetName(directoryArg, cwd);
    }

    /**
     * Final target directory, given the positional arg, the cwd, and the resolved project name.
     * Package-private for unit testing.
     */
    static Path resolveTarget(Path directoryArg, Path cwd, String projectName) {
        return NewWizard.resolveTarget(directoryArg, cwd, projectName);
    }

    /** Match the literal {@code "."} forms the user might type. */
    static boolean isCurrentDirArg(Path arg) {
        return NewWizard.isCurrentDirArg(arg);
    }

    private static void emitProjectExistsError(String coord, boolean isModule, boolean isInit, Terminal terminal) {
        Theme t = Theme.active();
        NerdFontCaps nerdFont = cc.jumpkick.config.GlobalConfig.nerdFont();
        String noun = isModule ? "module" : "project";

        // Style the coord: group:name in coordGroup/coordName; bare name in coordName.
        int colon = coord.indexOf(':');
        String coordStyled = colon > 0
                ? Theme.colorize(coord.substring(0, colon), t.coordGroup())
                        + ":"
                        + Theme.colorize(coord.substring(colon + 1), t.coordName())
                : Theme.colorize(coord, t.coordName());

        // Line 1: ‼ The group:name project already exists in this directory.
        String warnLine = Theme.colorize(Glyphs.BANG, t.warning()) + " The " + coordStyled + " " + noun
                + " already exists in this directory.";

        // Line 2: ✘ New Project Failed to create project large. Project already exists.
        // Use chipLine (not failureLine) — failureLine auto-prepends "Failed to {command}"
        // which would double up if the tail also starts with "Failed to".
        String chipCommand = isModule ? "New Module" : "New Project";
        String bareName = colon > 0 ? coord.substring(colon + 1) : coord;
        String failTail = "Failed to " + (isInit ? "initialize" : "create") + " " + noun + " " + bareName
                + ". Project already exists.";
        String chipLine = cc.jumpkick.cli.tui.JkWedge.chipLine(Glyphs.CROSS, chipCommand, nerdFont, failTail);

        if (terminal != null) {
            var writer = terminal.writer();
            cc.jumpkick.cli.tui.CommandWedge.markEnvelopeStarted();
            writer.println(warnLine);
            writer.println(chipLine);
            writer.flush();
        } else {
            cc.jumpkick.cli.tui.CommandWedge.envelopeStartErr();
            CliOutput.err(warnLine);
            CliOutput.err(chipLine);
        }
    }

    /** Same styling as {@link #emitProjectExistsError} for the no-JDK case. */
    private static void emitNoJdksError() {
        var warn = Theme.active().warning();
        var label = Theme.active().activeStep();
        var body = Theme.active().normalGray();
        cc.jumpkick.cli.tui.CommandWedge.envelopeStartErr();
        CliOutput.err(Theme.colorize(Glyphs.BANG, warn)
                + " "
                + Theme.colorize("Jk", label)
                + Theme.colorize(": No JDKs found on this system. Run ", body)
                + Theme.colorize("jk jdk install", warn)
                + Theme.colorize(" first, then re-run ", body)
                + Theme.colorize("jk new", warn)
                + Theme.colorize(".", body));
    }

    static NewInputs.Language parseLanguage(String value) {
        return NewWizard.parseLanguage(value);
    }

    static List<String> parseDeps(String csv) {
        return NewWizard.parseDeps(csv);
    }

    static int parseJdkMajorOrDefault(String jdk) {
        return NewWizard.parseJdkMajorOrDefault(jdk);
    }

    /** Current Java LTS feature release. Bumped on each new LTS. */
    static final int LATEST_LTS_MAJOR = 25;

    /** The user's global default JDK identifier, or empty (best-effort — never throws). */
    private static Optional<String> readDefaultJdk() {
        try {
            return cc.jumpkick.jdk.GlobalDefaultJdk.current().currentIdentifier();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Resolve an explicit {@code --jdk <spec>} into its major + {@code jk.toml} pin. Keywords ({@code
     * lts} / {@code stable} / {@code latest}) resolve to a major against the JetBrains feed (falling
     * back to the latest LTS offline) and always pin a bare major; a {@code <vendor>-<major>} spec
     * keeps its vendor; a bare major stays bare. Throws {@link IllegalArgumentException} (caught by
     * the flag path) on a point release or a spec with no major.
     */
    private NewJdkPlan.Spec resolveJdkArg(String arg) {
        String a = arg.trim();
        if (cc.jumpkick.jdk.JdkKeywords.isKeyword(a) && !"native".equalsIgnoreCase(a)) {
            String os = cc.jumpkick.jdk.HostPlatform.currentOs();
            String arch = cc.jumpkick.jdk.HostPlatform.currentArch();
            int major = fetchCatalogQuiet()
                    .flatMap(c -> cc.jumpkick.jdk.JdkKeywords.resolveToMajorSpec(c, a, os, arch))
                    .map(cc.jumpkick.model.JkBuild.Project::majorOf)
                    .filter(m -> m > 0)
                    .orElse(LATEST_LTS_MAJOR);
            return new NewJdkPlan.Spec(major, Integer.toString(major));
        }
        return NewJdkPlan.parseExplicit(a);
    }

    /**
     * Best-effort catalog fetch for the wizard's "Select a JDK" step. Network failures (offline, DNS,
     * 5xx) degrade to an empty optional rather than killing the wizard: the user still sees whatever
     * installs are on disk.
     */
    private static Optional<cc.jumpkick.jdk.JdkCatalog> fetchCatalogQuiet() {
        try {
            return Optional.of(new cc.jumpkick.jdk.JdkCatalogClient().fetch());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Resolve the wizard's {@code jdk} answer back to a candidate. The candidate's {@code id}
     * matches one of the entries we surfaced via {@link NewJdkCandidate#filter}, so this is just a
     * lookup.
     */
    private NewJdkCandidate pickCandidate(Answers answers, List<NewJdkCandidate> candidates) {
        if (answers.has("jdk")) { // the "Select a JDK" step ran
            var pickedId = answers.get("jdk");
            return candidates.stream()
                    .filter(c -> c.id().equals(pickedId))
                    .findFirst()
                    .orElseGet(() -> candidates.getFirst());
        }
        // Step was skipped — resolve silently: inherit the parent's major
        // (module), adopt the global default's major, else auto-pick by the
        // chosen Java level (sole eligible install, or lts to install).
        int preferred = parent != null
                ? (parent.jdkMajor() > 0 ? parent.jdkMajor() : parent.javaRelease())
                : defaultJdk.map(cc.jumpkick.model.JkBuild.Project::majorOf).orElse(0);
        int floor = jdkFloor(answers, parent);
        return NewJdkPlan.autoCandidate(candidates, floor, preferred, LATEST_LTS_MAJOR)
                .orElseGet(() -> candidates.getFirst());
    }

    /**
     * Download + extract an installable candidate. Reuses the same progress UI as {@code jk jdk
     * install}. On success, returns the freshly-resolved installed candidate (so its {@code home}
     * points at the new JDK). On failure, prints the error and returns empty so the caller exits.
     */
    private Optional<NewJdkCandidate> installCandidate(NewJdkCandidate candidate) {
        if (!(candidate instanceof NewJdkCandidate.Installable installable)) {
            return Optional.of(candidate);
        }
        try {
            var entry = installable.entry();
            var installer =
                    new cc.jumpkick.jdk.JdkInstaller(new cc.jumpkick.http.Http(), new cc.jumpkick.jdk.JdkRegistry());
            // Download (progress bar) then extract (spinner).
            var label = entry.vendor() + " " + entry.product() + " " + entry.majorVersion();
            long total = entry.archiveSize();
            try (var pb = cc.jumpkick.cli.tui.JdkDownloadBar.show(CliOutput.stdout(), label)) {
                var dl = installer.download(entry, bytes -> pb.update(bytes, total));
                pb.finish();
                try (var sp = cc.jumpkick.cli.tui.Spinner.show(CliOutput.stdout(), "Installing " + label + "...")) {
                    var installed = installer.extractInstalled(entry, dl);
                    CliOutput.out("✓ Installed " + label + " → " + installed.home());
                    var opt = new NewJdkOptions.Option(
                            installed.identifier(),
                            installed.identifier() + "  (JDK " + entry.majorVersion() + ")",
                            installed.home(),
                            entry.majorVersion(),
                            "jk");
                    return Optional.of(new NewJdkCandidate.Installed(opt, installable.vendor()));
                }
            }
        } catch (Exception e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("New", "failed to install JDK: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fully-qualified main class name for an executable project — must match where {@link
     * NewScaffolder} actually writes the file.
     *
     * <ul>
     * <li>Java (both layouts) → {@code <group>.Main}; the scaffolder always packages {@code Main}
     * under {@code <group>}, even in the simple layout (it just lives in {@code src/<group>/}
     * rather than {@code src/main/java/<group>/}).
     * <li>Kotlin compact → {@code MainKt} (no package; Kotlin emits a {@code FilenameKt} synthetic
     * class for top-level {@code fun main}).
     * <li>Kotlin standard → {@code <group>.MainKt}.
     * <li>Groovy compact → {@code Main} (package-less, like compact Kotlin); standard →
     * {@code <group>.Main}.
     * </ul>
     */
    static String deriveMainFqcn(String group, NewInputs.Language lang, boolean compact) {
        return NewWizard.deriveMainFqcn(group, lang, compact);
    }

    static Wizard buildWizard(
            List<NewJdkCandidate> candidates,
            cc.jumpkick.jdk.JdkCatalog catalog,
            String groupGuess,
            ParentInfo parent,
            boolean hasDefaultJdk,
            boolean isInit) {
        return NewWizard.buildWizard(candidates, catalog, groupGuess, parent, hasDefaultJdk, isInit);
    }

    /** Curated defaults + host declared-dep frequency for the New wizard library picker. */
    static List<cc.jumpkick.cli.tui.Choice> libraryPickerChoices() {
        return NewWizard.libraryPickerChoices();
    }

    static List<cc.jumpkick.cli.tui.Choice> libraryPickerChoices(String lang) {
        return NewWizard.libraryPickerChoices(lang);
    }

    /**
     * Lowest JDK feature-release the "Select a JDK" step may offer: a JDK can't compile a release
     * newer than itself. A module inherits the parent's {@code java} target; a standalone Java
     * project uses the chosen Java Language Version; Kotlin (no Java target) imposes no floor.
     */
    /** {@code primary} unless it's empty, in which case {@code fallback} (the offline default). */
    static List<Integer> orElseList(List<Integer> primary, List<Integer> fallback) {
        return NewWizard.orElseList(primary, fallback);
    }

    /**
     * Offline language-major list for one wizard track, from the compiled-in constants — used only
     * when the catalog can't be fetched. LTS majors down to {@link cc.jumpkick.jdk.SupportedJdk#MIN_MAJOR},
     * then (standard track only) the latest non-LTS stable. The native track caps at the latest LTS,
     * since GraalVM tracks LTS releases and we can't know a newer native-capable major offline.
     */
    static List<Integer> offlineMajors(boolean nativeTrack) {
        return NewWizard.offlineMajors(nativeTrack);
    }

    /** The newest native-image-capable (GraalVM) Java major, from the catalog or the offline cap. */
    static int maxNativeMajor(cc.jumpkick.jdk.JdkCatalog catalog) {
        return NewWizard.maxNativeMajor(catalog);
    }

    static int jdkFloor(Answers answers, ParentInfo parent) {
        return NewWizard.jdkFloor(answers, parent);
    }

    private NewInputs fromAnswers(Answers answers, Path cwd, NewJdkOptions.Option pickedOpt) {
        var resolvedName = answers.has("name") && !answers.get("name").isBlank()
                ? answers.get("name")
                : wizardPresetName(directory, cwd).orElse("untitled");
        Path target = resolveTarget(directory, cwd, resolvedName);
        var resolvedGroup = answers.has("group") && !answers.get("group").isBlank()
                ? answers.get("group")
                : parent != null ? parent.group() : "com.example";

        // Resolve the JDK: a module inherits the parent's major; a global
        // default JDK is adopted; otherwise it's the candidate the user picked
        // (or the one auto-resolved when the "Select a JDK" step was skipped).
        // The pin written to jk.toml is always the bare major — the wizard never
        // emits a vendor (that's reserved for an explicit `--jdk <vendor>-<major>`).
        int resolvedJdkMajor;
        Optional<String> resolvedJdkIdentifier;
        if (parent != null) {
            resolvedJdkMajor = parent.jdkMajor() > 0 ? parent.jdkMajor() : parent.javaRelease();
            resolvedJdkIdentifier = Optional.empty(); // modules write no lock
        } else if (defaultJdk.isPresent()) {
            resolvedJdkMajor = cc.jumpkick.model.JkBuild.Project.majorOf(defaultJdk.get());
            resolvedJdkIdentifier = defaultJdk;
        } else {
            resolvedJdkMajor = pickedOpt.major();
            resolvedJdkIdentifier = Optional.of(pickedOpt.id());
        }
        var resolvedJdk = Integer.toString(resolvedJdkMajor);
        // Compile target: a module inherits the parent's; a standalone Java
        // project uses the Java Language Version it was asked for; otherwise
        // (Kotlin, or a default-JDK project that skipped the version step) it
        // falls back to the chosen JDK's major.
        int resolvedJavaRelease;
        if (parent != null) {
            resolvedJavaRelease = parent.javaRelease();
        } else if ("java".equalsIgnoreCase(answers.get("lang"))
                && answers.has("javaVersion")
                && !answers.get("javaVersion").isBlank()) {
            resolvedJavaRelease = parseJdkMajorOrDefault(answers.get("javaVersion"));
        } else {
            resolvedJavaRelease = resolvedJdkMajor;
        }

        var resolvedLang =
                "kotlin".equalsIgnoreCase(answers.get("lang")) ? NewInputs.Language.KOTLIN : NewInputs.Language.JAVA;
        var isExecutable = "executable".equals(answers.get("kind"));

        var targets = answers.getList("targets");
        boolean resolvedAssembly = isExecutable && targets.contains("assembly");
        boolean resolvedNative = isExecutable && targets.contains("native");

        // Layout comes from its own dedicated step; default to "simple" if not answered.
        String resolvedLayout =
                answers.has("layout") && !answers.get("layout").isBlank() ? answers.get("layout") : "simple";
        Optional<String> resolvedKotlinModule = Optional.empty();
        var deps = new ArrayList<String>(answers.getList("libraries"));
        if (resolvedLang == NewInputs.Language.KOTLIN) {
            var kotlinOpts = answers.getList("kotlinOptions");
            if (kotlinOpts.contains("module")) {
                resolvedKotlinModule = Optional.of(resolvedName);
            }
        }

        var resolvedMain = isExecutable
                ? Optional.of(deriveMainFqcn(resolvedGroup, resolvedLang, "simple".equals(resolvedLayout)))
                : Optional.<String>empty();

        return new NewInputs(
                resolvedGroup,
                resolvedName,
                resolvedJdk,
                resolvedJdkMajor,
                resolvedJavaRelease,
                resolvedJdkIdentifier,
                resolvedMain,
                resolvedAssembly,
                resolvedNative,
                resolvedLang,
                resolvedLayout,
                resolvedKotlinModule,
                deps,
                true,
                target);
    }

    private static void emitSuccessOnTerminal(NewInputs inputs, Terminal terminal, Module module, boolean isInit) {
        var writer = terminal.writer();
        // Wizard already opened the envelope (leading blank + closing spacer).
        cc.jumpkick.cli.tui.CommandWedge.markEnvelopeStarted();
        writer.println(successLine(inputs, module, isInit));
        writer.flush();
    }

    private static void emitSuccessPlain(NewInputs inputs, Module module, boolean isInit) {
        cc.jumpkick.cli.tui.CommandWedge.printLine(successLine(inputs, module, isInit));
    }

    private static String successLine(NewInputs inputs, Module module, boolean isInit) {
        NerdFontCaps nerdFont = cc.jumpkick.config.GlobalConfig.nerdFont();
        org.jline.utils.AttributedStyle accent = Theme.active().brightCyan().bold();
        if (module != null) {
            String message = "New module "
                    + Theme.colorize(inputs.name(), accent)
                    + Theme.colorize(" added to project ", Theme.active().normalGray())
                    + Theme.colorize(module.projectName(), accent);
            return cc.jumpkick.cli.tui.JkWedge.chipLine(
                    cc.jumpkick.cli.tui.Glyphs.CHECK, "New Module", nerdFont, message);
        }
        String chipCommand = isInit ? "Init" : "New Project";
        String action = isInit ? "Initialized" : "Created new";
        String message = action + " project " + Theme.colorize(inputs.name(), accent);
        return cc.jumpkick.cli.tui.JkWedge.chipLine(cc.jumpkick.cli.tui.Glyphs.CHECK, chipCommand, nerdFont, message);
    }

    static final List<String> CURATED_IDS =
            List.of("jspecify", "kotest", "commons-lang", "commons-io", "guava", "lombok");

    /** Official Giter8 short names (engine catalog). Used only to decide whether to freshen templates. */
    private static boolean officialTemplateShortName(String id) {
        // Giter8ShortNames is the single canonical table — a hand-copied set here silently
        // missed newly added templates (JK-2172).
        return id != null && cc.jumpkick.scaffold.Giter8ShortNames.find(id).isPresent();
    }
}

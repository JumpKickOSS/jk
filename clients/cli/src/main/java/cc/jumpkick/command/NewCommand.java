// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.tui.Answers;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.cli.tui.Wizard;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.JdkCatalog;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Layout;
import cc.jumpkick.model.Project;
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
import cc.jumpkick.scaffold.NewInputs;
import cc.jumpkick.terminal.TerminalSession;
import cc.jumpkick.terminal.Terminals;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

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
                Opt.value("<lang>", "Language: java|kotlin|groovy|scala; default java", "--lang"),
                Opt.flag("Executable project (default is a library).", "--executable")
                        .negate(),
                Opt.flag("Assembly (fat) jar. Implies --executable.", "--assembly"),
                Opt.flag("Wire a GraalVM native-image build.", "--native"),
                Opt.flag("Scaffold a jk build-plugin authoring project.", "--plugin"),
                Opt.value("<ref>", "Giter8 path, name, framework/name, or URL", "-t", "--template"),
                Opt.value("<k=v>", "Template property k=v (repeatable)", "--param")
                        .repeat(),
                Opt.value("<deps>", "Curated deps, comma-separated.", "--deps"),
                Opt.value("<layout>", "Tree: " + Layout.scaffoldHelp() + ".", "--layout"),
                Opt.value("<module>", "Kotlin module name (-> project.module).", "--kotlin-module"),
                Opt.flag("Force a standalone project (not a module).", "--no-module"),
                Opt.flag("", "--no-member").hide()); // undocumented synonym for --no-module
    }

    @Override
    public List<Param> parameters() {
        return List.of(
                Param.of("directory", Arity.ZERO_OR_ONE, "Target directory. Default: cwd or a ./<name> subdir."));
    }

    @Nullable
    String name;

    @Nullable
    String group;

    @Nullable
    String jdk;

    @Nullable
    String lang;

    @Nullable
    Boolean executable;

    boolean assembly;
    boolean nativeImage;
    boolean plugin;

    @Nullable
    String templateRef;

    List<String> templateParams = List.of();

    @Nullable
    String depsCsv;

    @Nullable
    String layoutFlag;

    @Nullable
    String kotlinModule;

    boolean noModule;

    @Nullable
    Path directory;

    GlobalOptions global;

    private static final BuildPlanKey<List<NewJdkCandidate>> CANDIDATES =
            BuildPlanKey.list("candidates", NewJdkCandidate.class);

    private static final BuildPlanKey<TerminalSession> TERMINAL =
            BuildPlanKey.scalar("terminal", TerminalSession.class);
    private static final BuildPlanKey<JdkCatalog> CATALOG = BuildPlanKey.scalar("catalog", JdkCatalog.class);
    private static final BuildPlanKey<Answers> ANSWERS = BuildPlanKey.scalar("answers", Answers.class);
    private static final BuildPlanKey<NewJdkCandidate> PICKED = BuildPlanKey.scalar("picked", NewJdkCandidate.class);
    private static final BuildPlanKey<NewInputs> INPUTS = BuildPlanKey.scalar("inputs", NewInputs.class);

    /** Set during scaffold when the new project was registered as a workspace module. */
    private record Module(Path root, String rel, String projectName) {}

    private volatile @Nullable Module registered;

    /** The enclosing project's display name when this run registered a module in it, else null. */
    private @Nullable String parentProjectName() {
        return registered == null ? null : registered.projectName();
    }

    /**
     * The enclosing project/workspace this invocation will add a module to, or {@code null} when
     * we're creating a standalone project. Resolved once in {@link #call} and consumed by the
     * wizard (UX + inherited defaults), the flag path, and scaffolding.
     */
    private @Nullable ParentInfo parent;

    /** Global default JDK id from the managed inventory, or empty. */
    private Optional<String> defaultJdk = Optional.empty();

    /** Inherited context from the parent project's identity keys. */
    record ParentInfo(Path root, ProjectInfo info) {
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

        boolean scala() {
            return info.scala();
        }

        /** The JDK toolchain version (which JDK runs the build). */
        int jdkMajor() {
            int major = Project.majorOf(info.jdk());
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
     * Walk up from {@code startDir} for a parent {@code jk.toml}; stop at {@code .git} or
     * {@code $HOME}. {@code --no-module} → empty.
     */
    static Optional<Path> detectParentDir(Path startDir, @Nullable Path home, boolean noModule) {
        if (noModule) return Optional.empty();
        Path normHome = home == null ? null : home.toAbsolutePath().normalize();
        for (Path dir = startDir.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(ManifestPaths.MANIFEST))) return Optional.of(dir);
            if (Files.isDirectory(dir.resolve(".git"))) return Optional.empty(); // exited the repo
            if (dir.equals(normHome)) return Optional.empty(); // hit $HOME
        }
        return Optional.empty();
    }

    /**
     * Where to begin the parent search — the directory the project will live <em>in</em> (its
     * target's parent). For {@code jk new foo} that's the cwd; for {@code jk new /abs/foo} it's
     * {@code /abs}; for {@code .} / no arg it's the cwd (the module is the cwd itself, or
     * cwd/&lt;name&gt;).
     */
    private Path detectionStartDir(Path cwd) {
        if (directory == null || NewWizard.isCurrentDirArg(directory)) return cwd;
        Path parentDir = cwd.resolve(directory).normalize().getParent();
        return parentDir != null ? parentDir : cwd;
    }

    /**
     * Resolve {@link #parent} by parsing the detected parent's manifest (null if none / unparseable).
     */
    private @Nullable ParentInfo resolveParent(Path startDir) {
        Path home = Optional.ofNullable(System.getProperty("user.home"))
                .map(Path::of)
                .orElse(null);
        Optional<Path> root = detectParentDir(startDir, home, noModule);
        if (root.isEmpty()) return null;
        var info = ProjectInfos.orNull(root.get());
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
        this.plugin = in.isSet("plugin");
        this.templateRef = in.value("template").orElse(null);
        this.templateParams = in.values("param");
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
        if (directory != null
                && NewWizard.isCurrentDirArg(directory)
                && Files.exists(cwd.resolve(ManifestPaths.MANIFEST))) {
            String existing = NewWizard.wizardPresetName(directory, cwd)
                    .orElseGet(
                            () -> cwd.getFileName() != null ? cwd.getFileName().toString() : "this directory");
            NewChrome.projectExists(existing, parent != null, true, null);
            return Exit.CONFIG;
        }

        // Are we adding a module to an existing project/workspace, or creating
        // a standalone project? Search up from where the project will live (the
        // target's parent). Drives the wizard UX and inherited defaults.
        this.parent = resolveParent(detectionStartDir(cwd));
        this.defaultJdk = NewJdkChoice.defaultJdk();

        if (templateRef != null && !templateRef.isBlank()) {
            return NewTemplate.apply(new NewTemplate.Args(
                    templateRef,
                    templateParams,
                    name,
                    group,
                    lang,
                    layoutFlag,
                    directory,
                    cwd,
                    plugin,
                    parent == null,
                    global.offline));
        }

        if (shouldRunWizard()) {
            return runWizardBuildPlan(cwd);
        }
        return runFlagBuildPlan(cwd);
    }

    /**
     * Interactive wizard plan (prewarm → wizard → optional install-jdk → scaffold). The terminal
     * lives across steps via a {@link BuildPlanKey} and is closed in a {@code finally}.
     */
    private int runWizardBuildPlan(Path cwd) throws IOException {
        Path cache = JkDirs.cache();

        Task prewarm = prewarmStep();
        Task wizardStep = wizardStep(cwd);
        Task installJdk = installJdkStep();
        Task scaffold = scaffoldStep(cwd);

        BuildPlan plan = BuildPlan.builder("new")
                .interactive(true)
                .stateKeys(CANDIDATES, TERMINAL, CATALOG, ANSWERS, PICKED, INPUTS)
                .addTask(prewarm)
                .addTask(wizardStep)
                .addTask(installJdk)
                .addTask(scaffold)
                .build();

        BuildPlanResult result = BuildPlanConsole.run(plan, BuildPlanConsole.modeFor(global), cache);

        if (!result.success()) return wizardFailure(plan, result);

        NewInputs inputs = plan.get(INPUTS).orElseThrow();
        boolean isInit = directory != null && NewWizard.isCurrentDirArg(directory);
        NewChrome.created(
                inputs, parentProjectName(), isInit, plan.get(TERMINAL).orElse(null));
        return 0;
    }

    /** Discover JDKs, fetch the catalog and open the terminal, in parallel. */
    private Task prewarmStep() {
        return Task.builder(TaskNames.PREWARM)
                .kind(TaskKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("discover JDKs + fetch catalog + open terminal");
                    var jdkOptionsFuture = CompletableFuture.supplyAsync(NewJdkOptions::discover, JkThreads.io());
                    var catalogFuture = CompletableFuture.supplyAsync(NewJdkChoice::catalogQuiet, JkThreads.io());
                    TerminalSession terminal = Terminals.controlling();
                    ctx.put(TERMINAL, terminal);
                    var jdkOptions = jdkOptionsFuture.join();
                    var catalog = catalogFuture.join();
                    var candidates = NewJdkCandidate.build(
                            jdkOptions,
                            catalog,
                            NewWizard.LATEST_LTS_MAJOR,
                            HostPlatform.currentOs(),
                            HostPlatform.currentArch());
                    if (candidates.isEmpty()) {
                        ctx.error("no-jdks", "no JDKs found on this system");
                        throw new RuntimeException("no jdks");
                    }
                    ctx.put(CANDIDATES, candidates);
                    catalog.ifPresent(c -> ctx.put(CATALOG, c)); // drives the two-track language list
                    ctx.progress(1);
                })
                .build();
    }

    /** Run the wizard on the prewarmed terminal; Ctrl-C halts with the cancellation marker. */
    private Task wizardStep(Path cwd) {
        return Task.builder(TaskNames.WIZARD)
                .requires(TaskNames.PREWARM)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("run wizard");
                    TerminalSession terminal = ctx.require(TERMINAL);
                    List<NewJdkCandidate> candidates = ctx.require(CANDIDATES);

                    Answers preset = NewWizard.wizardPresetName(directory, cwd)
                            .map(n -> Answers.of(Map.of("name", (Object) n)))
                            .orElseGet(() -> Answers.of(Map.of()));
                    var groupGuess = NewGroupGuess.guess(
                            cwd,
                            Optional.ofNullable(System.getProperty("user.home"))
                                    .map(Path::of)
                                    .orElse(null));
                    var wizard = NewWizard.buildWizard(
                            candidates,
                            ctx.get(CATALOG).orElse(null),
                            groupGuess,
                            parent,
                            defaultJdk.isPresent(),
                            directory != null && NewWizard.isCurrentDirArg(directory));
                    var wizardResult = wizard.run(terminal, preset);
                    if (wizardResult.isEmpty()) {
                        // Cancelled via Ctrl-C. Wizard.printCancellation
                        // preserves the cyan active-rail closer and prints
                        // the red marker beside it. Runtime.halt skips
                        // shutdown hooks — JLine's cleanup hook would block
                        // on the NonBlockingReader.
                        Wizard.printCancellation(
                                terminal, parent != null ? "Module creation canceled" : "Project creation canceled");
                        Runtime.getRuntime().halt(Exit.INTERRUPTED);
                    }
                    ctx.put(ANSWERS, wizardResult.get());
                    ctx.put(PICKED, NewJdkChoice.pick(wizardResult.get(), candidates, parent, defaultJdk));
                    ctx.progress(1);
                })
                .build();
    }

    /** Install the picked JDK when it is not on this machine yet. */
    private Task installJdkStep() {
        return Task.builder(TaskNames.INSTALL_JDK)
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
                    var installed = NewJdkChoice.install(picked);
                    if (installed.isEmpty()) {
                        ctx.error("jdk-install", "JDK install failed");
                        throw new RuntimeException("jdk install failed");
                    }
                    ctx.put(PICKED, installed.get());
                    ctx.progress(1);
                })
                .build();
    }

    /** Scaffold the project from the answers and register it. */
    private Task scaffoldStep(Path cwd) {
        return Task.builder(TaskNames.SCAFFOLD)
                .requires(TaskNames.INSTALL_JDK)
                .ticks(1)
                .execute(ctx -> {
                    NewJdkCandidate resolved = ctx.require(PICKED);
                    // After install-jdk, picked is always Installed; unwrap
                    // for fromAnswers.
                    var pickedOpt = ((NewJdkCandidate.Installed) resolved).option();
                    var inputs = fromAnswers(ctx.require(ANSWERS), cwd, pickedOpt);
                    ctx.put(INPUTS, inputs);
                    if (Files.exists(inputs.directory().resolve(ManifestPaths.MANIFEST))) {
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
    }

    /** The wizard plan failed: the chrome for a missing JDK or an existing project, else the bare code. */
    private int wizardFailure(BuildPlan plan, BuildPlanResult result) {
        for (BuildPlanResult.Diagnostic d : result.errors()) {
            if ("no-jdks".equals(d.code())) {
                NewChrome.noJdks();
                return Exit.CONFIG;
            }
            if ("exists".equals(d.code())) {
                NewInputs partial = plan.get(INPUTS).orElse(null);
                String coord = partial != null ? partial.group() + ":" + partial.name() : "project";
                boolean isInit = directory != null && NewWizard.isCurrentDirArg(directory);
                TerminalSession term = plan.get(TERMINAL).orElse(null);
                NewChrome.projectExists(coord, parent != null, isInit, term);
                return Exit.CONFIG;
            }
        }
        return Exit.CONFIG;
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
            CommandWedge.printFail("New", e.getMessage());
            return Exit.USAGE;
        }
        if (assembly && inputs.mainOpt().isEmpty()) {
            CommandWedge.printFail("New", "--assembly requires --executable");
            return Exit.USAGE;
        }
        if (Files.exists(inputs.directory().resolve(ManifestPaths.MANIFEST))) {
            NewChrome.projectExists(
                    inputs.group() + ":" + inputs.name(),
                    parent != null,
                    directory != null && NewWizard.isCurrentDirArg(directory),
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
            NewChrome.created(
                    inputs, parentProjectName(), directory != null && NewWizard.isCurrentDirArg(directory), null);
        return 0;
    }

    /**
     * TTY + no real flags. A bare positional ({@code jk new my-project} or {@code jk new.}) still
     * runs the wizard — the positional only pre-seeds the name.
     */
    private boolean shouldRunWizard() {
        if (!isInteractiveTerminalSession()) {
            return false;
        }
        return !anyFlagSupplied();
    }

    private static boolean isInteractiveTerminalSession() {
        // Gates the interactive `jk new` wizard — input axis, so key on the controlling terminal.
        return Interactivity.canPrompt();
    }

    private boolean anyFlagSupplied() {
        return name != null
                || group != null
                || jdk != null
                || lang != null
                || executable != null
                || assembly
                || nativeImage
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
        var ack = EngineClient.newProject(
                EnginePaths.current(),
                new EngineRequests.NewProjectRequest(
                        inputs.name(),
                        parentDir.toString(),
                        inputs.group(),
                        inputs.lang().hoconValue(),
                        inputs.layout().token(),
                        null,
                        inputs.isRunnable(),
                        inputs.jdk(),
                        inputs.javaRelease(),
                        inputs.assembly(),
                        inputs.nativeImage(),
                        inputs.plugin(),
                        inputs.kotlinModuleNameOpt().orElse(null),
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
            Path rootToml = root.resolve(ManifestPaths.MANIFEST);
            // Registers the module, promoting a plain project into a workspace
            // root (creating the [workspace] table) when this is its first module.
            EngineEdits.apply(rootToml, "register-workspace-module", List.of(rel));
            registered = new Module(root, rel, parent.displayName());
        }
    }

    private NewInputs fromFlags(Path cwd) {
        if (plugin && nativeImage) {
            throw new IllegalArgumentException("--plugin can't be combined with --native");
        }
        var presetName = NewWizard.wizardPresetName(directory, cwd);
        var resolvedName = (name != null && !name.isBlank()) ? name : presetName.orElse("untitled");
        Path target = NewWizard.resolveTarget(directory, cwd, resolvedName);
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
            jdkSpec = NewJdkChoice.fromArg(jdk);
        } else if (parent != null && parent.jdkMajor() > 0) {
            int m = parent.jdkMajor();
            jdkSpec = new NewJdkPlan.Spec(m, Integer.toString(m));
        } else if (defaultJdk.map(Project::majorOf).orElse(0) > 0) {
            int m = Project.majorOf(defaultJdk.get());
            jdkSpec = new NewJdkPlan.Spec(m, Integer.toString(m));
        } else {
            jdkSpec = new NewJdkPlan.Spec(NewWizard.LATEST_LTS_MAJOR, Integer.toString(NewWizard.LATEST_LTS_MAJOR));
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
            int maxNative = NewWizard.maxNativeMajor(NewJdkChoice.catalogQuiet().orElse(null));
            if (resolvedJavaRelease > maxNative) {
                throw new IllegalArgumentException("--native requires a GraalVM (native-image) JDK; the newest "
                        + "native-image-capable Java is " + maxNative + ", but Java " + resolvedJavaRelease
                        + " was requested");
            }
        }
        var resolvedLang = (lang != null && !lang.isBlank())
                ? NewWizard.parseLanguage(lang)
                : (parent != null && parent.kotlin())
                        ? NewInputs.Language.KOTLIN
                        : (parent != null && parent.groovy())
                                ? NewInputs.Language.GROOVY
                                : (parent != null && parent.scala())
                                        ? NewInputs.Language.SCALA
                                        : NewInputs.Language.JAVA;
        var isExecutable = Boolean.TRUE.equals(executable) || assembly || nativeImage;
        // Traditional (Maven) is the product default. --layout simple opts into the Mill-like tree.
        Layout resolvedLayout =
                (layoutFlag != null && !layoutFlag.isBlank()) ? Layout.parse(layoutFlag) : Layout.TRADITIONAL;
        var resolvedMain = plugin
                ? Optional.<String>empty()
                : isExecutable
                        ? Optional.of(
                                NewWizard.deriveMainFqcn(resolvedGroup, resolvedLang, resolvedLayout == Layout.SIMPLE))
                        : Optional.<String>empty();
        // A plugin's jk-plugin-sdk dependency is emitted by NewJkBuildRenderer, not via curated deps.
        var resolvedDeps = plugin ? List.<String>of() : NewWizard.parseDeps(depsCsv);
        var resolvedKotlinModule = (kotlinModule != null && !kotlinModule.isBlank())
                ? Optional.of(kotlinModule)
                : Optional.<String>empty();
        return new NewInputs(
                resolvedGroup,
                resolvedName,
                resolvedJdk,
                resolvedJdkMajor,
                resolvedJavaRelease,
                null, // flag path doesn't resolve to a specific install
                resolvedMain.orElse(null),
                assembly,
                nativeImage,
                plugin,
                resolvedLang,
                resolvedLayout,
                resolvedKotlinModule.orElse(null),
                resolvedDeps,
                true,
                target);
    }

    private NewInputs fromAnswers(Answers answers, Path cwd, NewJdkOptions.Option pickedOpt) {
        var resolvedName = answers.has("name") && !answers.get("name").isBlank()
                ? answers.get("name")
                : NewWizard.wizardPresetName(directory, cwd).orElse("untitled");
        Path target = NewWizard.resolveTarget(directory, cwd, resolvedName);
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
            resolvedJdkMajor = Project.majorOf(defaultJdk.get());
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
            resolvedJavaRelease = NewWizard.parseJdkMajorOrDefault(answers.get("javaVersion"));
        } else {
            resolvedJavaRelease = resolvedJdkMajor;
        }

        var resolvedLang =
                "kotlin".equalsIgnoreCase(answers.get("lang")) ? NewInputs.Language.KOTLIN : NewInputs.Language.JAVA;
        var isExecutable = "executable".equals(answers.get("kind"));

        var targets = answers.getList("targets");
        boolean resolvedAssembly = isExecutable && targets.contains("assembly");
        boolean resolvedNative = isExecutable && targets.contains("native");

        // Layout comes from its own dedicated step; traditional if the step was skipped.
        Layout resolvedLayout = answers.has("layout") && !answers.get("layout").isBlank()
                ? Layout.parse(answers.get("layout"))
                : Layout.TRADITIONAL;
        Optional<String> resolvedKotlinModule = Optional.empty();
        var deps = new ArrayList<String>(answers.getList("libraries"));
        if (resolvedLang == NewInputs.Language.KOTLIN) {
            var kotlinOpts = answers.getList("kotlinOptions");
            if (kotlinOpts.contains("module")) {
                resolvedKotlinModule = Optional.of(resolvedName);
            }
        }

        var resolvedMain = isExecutable
                ? Optional.of(NewWizard.deriveMainFqcn(resolvedGroup, resolvedLang, resolvedLayout == Layout.SIMPLE))
                : Optional.<String>empty();

        return new NewInputs(
                resolvedGroup,
                resolvedName,
                resolvedJdk,
                resolvedJdkMajor,
                resolvedJavaRelease,
                resolvedJdkIdentifier.orElse(null),
                resolvedMain.orElse(null),
                resolvedAssembly,
                resolvedNative,
                resolvedLang,
                resolvedLayout,
                resolvedKotlinModule.orElse(null),
                deps,
                true,
                target);
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.cli.tui.form.Asker;
import cc.jumpkick.command.EngineEdits;
import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCommand;
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
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.scaffold.NewInputs;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.NewProjectFormAck;
import cc.jumpkick.wire.protocol.NewProjectFormRequest;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk new} — scaffold a project or workspace module (aliases: {@code init}, {@code create}).
 * On a terminal it walks the new-project form ({@link NewForm}) and scaffolds a template; with no
 * terminal a {@code -t} template takes its defaults ({@link NewTemplate}), and no {@code -t} is the
 * blank scaffolder's flags. Every path writes via {@code NewProjectVerb}. Walks up for a parent
 * {@code jk.toml} (module) unless {@code --no-module}; modules inherit parent defaults and register
 * under {@code [workspace].modules}.
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
                Opt.value("<name>", "Project name and target directory leaf.", "-W", "--name"),
                Opt.value("<group>", "Maven groupId (default: from git config).", "-U", "--group"),
                // --jdk rides the GLOBAL option (same canonical key "jdk"); a local
                // re-declaration would collide with it in the dispatcher.
                Opt.value("<lang>", "java (default), kotlin, groovy, scala or node", "-l", "--lang"),
                Opt.flag("Executable project (default is a library).", "-e", "--executable")
                        .negate(),
                Opt.flag("Assembly (fat) jar. Implies --executable.", "-A", "--assembly"),
                Opt.flag("Wire a GraalVM native-image build.", "-n", "--native"),
                Opt.flag("Scaffold a jk build-plugin authoring project.", "-P", "--plugin"),
                Opt.value("<ref>", "Giter8 path, name, framework/name, or URL", "-t", "--template"),
                Opt.value("<fw>", "Swap web/ for this front end", "-f", "--frontend"),
                Opt.value("<k=v>", "Template property k=v (repeatable)", "-p", "--param")
                        .repeat(),
                Opt.value("<deps>", "Curated deps, comma-separated.", "-d", "--deps"),
                Opt.value(
                        "<layout>",
                        "Tree: " + Layout.scaffoldHelp() + "; with -t, the template's own jk_layout is the default.",
                        "-L",
                        "--layout"),
                Opt.value("<module>", "Kotlin module name (-> project.module).", "-m", "--kotlin-module"),
                Opt.flag("Force a standalone project (not a module).", "-N", "--no-module"),
                Opt.flag("", "--no-member").hide()); // undocumented synonym for --no-module
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("dir", Arity.ZERO_OR_ONE, "Target directory. Default: cwd or a ./<name> subdir."));
    }

    /** {@code jk new -t <ref> --help}: the template's own questions, generated from its form. */
    @Override
    public String helpFor(List<String> args) {
        String template = optionValue(args, "-t", "--template");
        if (template == null || template.isBlank()) return "";
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        try {
            NewProjectFormAck ack = EngineClient.newProjectForm(
                    EnginePaths.current(),
                    new NewProjectFormRequest(template, optionValue(args, "-l", "--lang"), cwd.toString()));
            String error = ack.error();
            if (error != null && !error.isBlank()) return "Template " + template + ": " + error + "\n";
            return NewForm.help(Form.fromJson(ack.form()), ack.template());
        } catch (IOException | RuntimeException e) {
            return "Template " + template + ": " + e.getMessage() + "\n";
        }
    }

    /** The value of an option spelled {@code -x v}, {@code --long v} or {@code --long=v}; the last one wins. */
    static @Nullable String optionValue(List<String> args, String shortName, String longName) {
        String value = null;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--")) break;
            if ((a.equals(shortName) || a.equals(longName)) && i + 1 < args.size()) {
                value = args.get(++i);
            } else if (a.startsWith(longName + "=") || a.startsWith(shortName + "=")) {
                value = a.substring(a.indexOf('=') + 1);
            }
        }
        return value;
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
    String frontend;

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
     * form (inherited defaults), the flag path, and scaffolding.
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
    public static Optional<Path> detectParentDir(Path startDir, @Nullable Path home, boolean noModule) {
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
        if (directory == null || NewArgs.isCurrentDirArg(directory)) return cwd;
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
        this.frontend = in.value("frontend").orElse(null);
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
        // the form or from `--name`).
        if (directory != null
                && NewArgs.isCurrentDirArg(directory)
                && Files.exists(cwd.resolve(ManifestPaths.MANIFEST))) {
            String existing = NewArgs.presetName(directory, cwd)
                    .orElseGet(
                            () -> cwd.getFileName() != null ? cwd.getFileName().toString() : "this directory");
            NewChrome.projectExists(existing, parent != null, true);
            return Exit.CONFIG;
        }

        // Are we adding a module to an existing project/workspace, or creating
        // a standalone project? Search up from where the project will live (the
        // target's parent). Drives the form's defaults and inherited settings.
        this.parent = resolveParent(detectionStartDir(cwd));
        this.defaultJdk = NewJdkChoice.defaultJdk();

        if ("node".equalsIgnoreCase(lang)) {
            return NewNode.apply(new NewNode.Args(
                    templateRef,
                    templateParams,
                    name,
                    group,
                    directory,
                    cwd,
                    parent == null ? null : parent.root(),
                    global.offline));
        }
        Asker asker = formAsker();
        if (asker != null) return runForm(asker, cwd);
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
                    parent,
                    global.offline,
                    frontend));
        }
        return runFlagBuildPlan(cwd);
    }

    /**
     * Flag mode: validate inputs, scaffold. Not interactive (no form, no progress widgets in the
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
                    directory != null && NewArgs.isCurrentDirArg(directory));
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
            NewChrome.created(inputs, parentProjectName(), directory != null && NewArgs.isCurrentDirArg(directory));
        return 0;
    }

    /**
     * The asker for the form, or {@code null} when it is not rendered: no human can answer, the
     * output is for a program, or a flag only the blank scaffolder takes was given.
     */
    private @Nullable Asker formAsker() {
        if (!Interactivity.canPrompt() || global.outputIsJson() || blankScaffoldFlag()) return null;
        return Asker.interactive();
    }

    /** A flag that picks the blank scaffolder over a template. */
    private boolean blankScaffoldFlag() {
        return jdk != null
                || executable != null
                || assembly
                || nativeImage
                || plugin
                || depsCsv != null
                || kotlinModule != null;
    }

    /** Walks the new-project form, then scaffolds what it settled through the template path. */
    private int runForm(Asker asker, Path cwd) {
        Map<String, String> params = NewTemplate.params(templateParams);
        if (params == null) return Exit.USAGE;
        boolean init = directory != null && NewArgs.isCurrentDirArg(directory);
        String presetName = name != null && !name.isBlank()
                ? name
                : NewArgs.presetName(directory, cwd).orElse(null);
        // A bare leaf is the name as well as the directory, so the summary spells it once.
        boolean leaf = directory != null && !init && name == null && directory.getNameCount() == 1;
        String command = init
                ? "jk init"
                : directory == null || leaf ? "jk new" : "jk new " + FormCommand.quote(directory.toString());
        Map<String, FormCommand.Flag> flags = new LinkedHashMap<>();
        flags.put("lang", FormCommand.Flag.named("--lang"));
        flags.put("template", FormCommand.Flag.named("-t"));
        flags.put("name", directory == null || leaf ? FormCommand.Flag.positional() : FormCommand.Flag.named("--name"));
        flags.put("group", FormCommand.Flag.named("--group"));
        flags.put("layout", FormCommand.Flag.named("-L"));
        Path home = Optional.ofNullable(System.getProperty("user.home"))
                .map(Path::of)
                .orElse(null);
        DerivedDefaults derived = from ->
                !"git-email".equals(from) ? null : parent != null ? parent.group() : NewGroupGuess.guess(cwd, home);
        NewForm.Forms forms = (template, language) -> EngineClient.newProjectForm(
                EnginePaths.current(), new NewProjectFormRequest(template, language, cwd.toString()));
        Optional<NewForm.Answered> answered;
        try {
            answered = NewForm.walk(
                    asker,
                    forms,
                    new NewForm.Given(lang, templateRef, presetName, group, layoutFlag, params),
                    derived,
                    cwd,
                    command,
                    flags);
        } catch (IOException e) {
            CommandWedge.printFail("New", e.getMessage());
            return e.getMessage() != null && e.getMessage().contains("not found") ? Exit.USAGE : Exit.SOFTWARE;
        }
        if (answered.isEmpty()) {
            asker.println(parent != null ? "Module creation canceled" : "Project creation canceled");
            return Exit.INTERRUPTED;
        }
        NewForm.Answered a = answered.get();
        List<String> pairs = new ArrayList<>();
        a.params().forEach((k, v) -> pairs.add(k + "=" + v));
        return NewTemplate.apply(new NewTemplate.Args(
                a.template(),
                pairs,
                a.name(),
                a.text("group"),
                a.text("lang"),
                a.text("layout"),
                directory,
                cwd,
                false,
                parent,
                global.offline,
                frontend));
    }

    /**
     * Scaffold the project, then — if it lands inside an existing workspace — skip the per-module
     * {@code jk-lock.toml} and register the new module in the root {@code [workspace].modules} (Cargo/uv:
     * {@code cargo new} / {@code uv init} edit the workspace manifest). Records the registration for
     * the success message.
     */
    private void scaffoldAndRegister(NewInputs inputs) throws IOException {
        if (global.offline && needsVersionLookup(inputs)) {
            throw new IOException("offline: jk new pins the newest stable "
                    + (inputs.lang() == NewInputs.Language.JAVA
                            ? "release"
                            : inputs.lang().hoconValue() + " compiler")
                    + (inputs.deps().isEmpty() ? "" : " and dependency versions")
                    + " from your repositories; run without --offline");
        }
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
        if (parent == null) AgentWiring.report(target);
        if (parent != null) {
            String rel = registerModule(parent.root(), inputs.directory());
            registered = new Module(parent.root(), rel, parent.displayName());
        }
    }

    /**
     * Lists {@code moduleDir} in {@code root}'s {@code [workspace].modules}, promoting a plain
     * project into a workspace root (creating the {@code [workspace]} table) when this is its first
     * module. Every {@code jk new} mode, and {@code jk init} adding node members, registers through here. Returns
     * the module's path relative to the root, as written.
     */
    static String registerModule(Path root, Path moduleDir) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        String rel = base.relativize(moduleDir.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        EngineEdits.apply(base.resolve(ManifestPaths.MANIFEST), "register-workspace-module", List.of(rel));
        return rel;
    }

    /** A non-Java compiler or any dependency pick means the scaffold must look a version up. */
    static boolean needsVersionLookup(NewInputs inputs) {
        return !inputs.plugin()
                && (inputs.lang() != NewInputs.Language.JAVA || !inputs.deps().isEmpty());
    }

    private NewInputs fromFlags(Path cwd) {
        if (plugin && nativeImage) {
            throw new IllegalArgumentException("--plugin can't be combined with --native");
        }
        var presetName = NewArgs.presetName(directory, cwd);
        var resolvedName = (name != null && !name.isBlank()) ? name : presetName.orElse("untitled");
        Path target = NewArgs.resolveTarget(directory, cwd, resolvedName);
        var resolvedGroup = (group != null && !group.isBlank())
                ? group
                : parent != null
                        ? parent.group()
                        : NewGroupGuess.guess(
                                cwd,
                                Optional.ofNullable(System.getProperty("user.home"))
                                        .map(Path::of)
                                        .orElse(null));
        // The Java level: an explicit --jdk names it (and is the one pin jk.toml
        // carries, vendor and all); else the parent's major (module); else the
        // global default JDK's major; else the latest LTS. Every non-explicit path
        // writes the level alone — the host JDK compiles to it, so no toolchain
        // pin is written for the user to learn.
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
            jdkSpec = new NewJdkPlan.Spec(NewArgs.LATEST_LTS_MAJOR, Integer.toString(NewArgs.LATEST_LTS_MAJOR));
        }
        String resolvedJdk = jdk != null && !jdk.isBlank() ? jdkSpec.pin() : null;
        int resolvedJdkMajor = jdkSpec.major();
        // The compile target flows through from the parent (workspace-wide
        // release) even when it diverges from the JDK toolchain; standalone
        // projects target their JDK.
        int resolvedJavaRelease = parent != null ? parent.javaRelease() : resolvedJdkMajor;
        // A native binary needs a GraalVM (native-image) JDK; reject a Java release no GraalVM
        // ships yet (e.g. 26 today). The two-track rule, checked here for the
        // non-interactive flag path. Consult the catalog only when --native is set.
        if (nativeImage && resolvedJavaRelease > 0) {
            int maxNative = NewArgs.maxNativeMajor(NewJdkChoice.catalogQuiet().orElse(null));
            if (resolvedJavaRelease > maxNative) {
                throw new IllegalArgumentException("--native requires a GraalVM (native-image) JDK; the newest "
                        + "native-image-capable Java is " + maxNative + ", but Java " + resolvedJavaRelease
                        + " was requested");
            }
        }
        var resolvedLang = (lang != null && !lang.isBlank())
                ? NewArgs.parseLanguage(lang)
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
                                NewArgs.deriveMainFqcn(resolvedGroup, resolvedLang, resolvedLayout == Layout.SIMPLE))
                        : Optional.<String>empty();
        // A plugin's jk-plugin-sdk dependency is emitted by NewJkBuildRenderer, not via curated deps.
        var resolvedDeps = plugin ? List.<String>of() : NewArgs.parseDeps(depsCsv);
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
}

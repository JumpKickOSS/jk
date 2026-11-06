// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.cli.tui.form.Asker;
import cc.jumpkick.command.EngineEdits;
import cc.jumpkick.config.SearchCeiling;
import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCommand;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Layout;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.scaffold.NewProjectStep;
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
 * {@code jk new} — scaffold a project or workspace module from a template (aliases: {@code init},
 * {@code create}). Every project comes from the new-project form ({@link NewForm}): on a terminal
 * it asks what the flags left open, with no terminal the defaults answer it, so no {@code -t} is the
 * {@code app} template. Flags answer jk's step ({@code --lang}, {@code -t}, the name, {@code --group},
 * {@code --dir}, {@code -L}) and {@code -p key=value} the template's. The scaffold is one engine call
 * ({@link NewTemplate}). Walks up for a parent {@code jk.toml} (module) unless {@code --no-module};
 * a module is listed under the parent's {@code [workspace].modules}.
 */
public final class NewCommand implements CliCommand {

    /** The template {@code -P} names: a build-plugin authoring project. */
    static final String PLUGIN_TEMPLATE = "plugin";

    @Override
    public String name() {
        return "new";
    }

    @Override
    public String description() {
        return "Create a new jk project (or workspace module) from a template";
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
                // The parent directory is the GLOBAL -C/--dir, and --jdk (the template's java = N) is
                // the global option too; a local re-declaration would collide in the dispatcher.
                Opt.value("<lang>", "java (default), kotlin, groovy or node", "-l", "--lang"),
                Opt.value(
                        "<ref>",
                        "Template: name, framework/name, Giter8 path or URL (default app)",
                        "-t",
                        "--template"),
                Opt.value("<k=v>", "Answer a template question (repeatable)", "-p", "--param")
                        .repeat(),
                Opt.value(
                        "<layout>",
                        "Tree: " + Layout.scaffoldHelp() + "; the template's own jk_layout is the default.",
                        "-L",
                        "--layout"),
                Opt.flag("The app template (the default); --no-executable is -t library.", "-e", "--executable")
                        .negate(),
                Opt.flag("The build-plugin template: -t plugin.", "-P", "--plugin"),
                Opt.value("<fw>", "Swap web/ for this front end", "-f", "--frontend"),
                Opt.flag("Force a standalone project (not a module).", "-N", "--no-module"),
                Opt.flag("", "--no-member").hide(), // undocumented synonym for --no-module
                // Former blank-project flags: each is refused with the answer that replaces it.
                Opt.flag("", "-A", "--assembly").hide(),
                Opt.flag("", "-n", "--native").hide(),
                Opt.value("<deps>", "", "-d", "--deps").hide(),
                Opt.value("<module>", "", "-m", "--kotlin-module").hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("dir", Arity.ZERO_OR_ONE, "Target directory. Default: <dir>/<name>."));
    }

    /**
     * {@code jk new -t <ref> --help}: the template's own questions, generated from its form. A
     * relative template path is the working directory's, {@code -C}/{@code --dir} included.
     */
    @Override
    public String helpFor(List<String> args) {
        String template = optionValue(args, "-t", "--template");
        if (template == null || template.isBlank()) return "";
        Path cwd = new GlobalOptions().workingDir();
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

    /**
     * The enclosing project/workspace this invocation will add a module to, or {@code null} when
     * we're creating a standalone project. Resolved once in {@link #call} and consumed by the
     * form (inherited defaults), the flag path, and scaffolding.
     */
    private @Nullable ParentInfo parent;

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

        /** The {@code java = N} compile target a module inherits, even when it sits below the JDK. */
        int javaRelease() {
            return info.javaRelease();
        }
    }

    /**
     * Walk up from {@code startDir} for a parent {@code jk.toml}; stop at a repository's top (its
     * {@code .git} directory, or the {@code .git} file of a worktree or submodule), at {@code $HOME},
     * or before a {@link SearchCeiling}. {@code --no-module} → empty.
     */
    public static Optional<Path> detectParentDir(Path startDir, @Nullable Path home, boolean noModule) {
        if (noModule) return Optional.empty();
        SearchCeiling ceiling = SearchCeiling.current();
        Path normHome = home == null ? null : WorkingDirectory.resolve(home);
        for (Path dir = WorkingDirectory.resolve(startDir); dir != null; dir = ceiling.above(dir)) {
            if (Files.exists(dir.resolve(ManifestPaths.MANIFEST))) return Optional.of(dir);
            if (Files.exists(dir.resolve(".git"))) return Optional.empty(); // exited the repo
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

    /** {@code jk new} or {@code jk init}, as this invocation was spelled. */
    private String command() {
        return directory != null && NewArgs.isCurrentDirArg(directory) ? "jk init" : "jk new";
    }

    /**
     * Why a flag the blank scaffolder took is refused, naming what replaced it, or {@code null}. A
     * template asks its own questions, so the flags that answered one template's are now its
     * {@code -p} answers.
     */
    @Nullable
    String refusal() {
        String c = command() + ": ";
        if (assembly) return c + "--assembly is the template's question now: -p assembly=true";
        if (nativeImage) return c + "--native is the template's question now: -p native=true";
        if (depsCsv != null) {
            return c + "--deps is the template's question now: -p deps=guava,commons-lang,commons-io"
                    + " (jk new -t app --help lists them); jk add <library> adds any other";
        }
        if (kotlinModule != null) return c + "--kotlin-module is gone: set module = \"<name>\" in the new jk.toml";
        String executableFlag = executable == null ? null : executable ? "-e" : "--no-executable";
        if (plugin && executableFlag != null) {
            return c + executableFlag + " names a template, and so does --plugin; keep one";
        }
        String templateFlag = plugin ? "--plugin" : executableFlag;
        if (templateFlag != null && templateRef != null && !templateRef.isBlank()) {
            return c + templateFlag + " names a template, and so does -t; keep one";
        }
        if (jdk != null && javaLevel(jdk) == null) {
            return c + "--jdk sets the template's java = N, so it takes a Java major (--jdk 25); pin a"
                    + " vendor afterwards with jdk = \"" + jdk + "\" in jk.toml";
        }
        return null;
    }

    /** The Java major a {@code --jdk} value names, when it is a bare major; else {@code null}. */
    static @Nullable String javaLevel(String spec) {
        String s = spec.strip();
        return s.matches("[1-9][0-9]*") && Project.majorOf(s) > 0 ? s : null;
    }

    /** The template the flags name: {@code -t}, {@code -P}, or {@code -e} / {@code --no-executable}; else none. */
    @Nullable
    String template() {
        if (templateRef != null && !templateRef.isBlank()) return templateRef;
        if (plugin) return PLUGIN_TEMPLATE;
        if (executable != null) return executable ? NewProjectStep.DEFAULT_TEMPLATE : "library";
        return null;
    }

    /** The body of run(), callable after fields are populated (used by InitCommand delegation). */
    int callBody() throws IOException {
        // The global -C/--dir is the parent directory jk's step asks for.
        Path cwd = global.workingDir();
        String refused = refusal();
        if (refused != null) {
            CliOutput.err(refused);
            return Exit.USAGE;
        }

        // Fail-fast for `jk new .` when the cwd already has a project. For any other invocation the
        // existing-manifest check waits for the target to be settled (the name may come from the form).
        if (directory != null
                && NewArgs.isCurrentDirArg(directory)
                && Files.exists(cwd.resolve(ManifestPaths.MANIFEST))) {
            String existing = NewArgs.presetName(directory, cwd)
                    .orElseGet(
                            () -> cwd.getFileName() != null ? cwd.getFileName().toString() : "this directory");
            NewChrome.projectExists(existing, parent != null, true);
            return Exit.CONFIG;
        }

        // Are we adding a module to an existing project/workspace, or creating a standalone project?
        // Search up from where the project will live (the target's parent). Drives the form's
        // defaults and inherited settings.
        this.parent = resolveParent(detectionStartDir(cwd));

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
        Map<String, String> params = NewTemplate.params(templateParams);
        if (params == null) return Exit.USAGE;
        Asker asker = formAsker();
        return runForm(asker, cwd, params);
    }

    /** The asker for the form, or {@code null} when no human can answer or the output is for a program. */
    private @Nullable Asker formAsker() {
        if (!Interactivity.canPrompt() || global.outputIsJson()) return null;
        return Asker.interactive();
    }

    /**
     * Settles the new-project form, by asking on a terminal or from the flags and defaults without
     * one, then scaffolds what it settled.
     */
    private int runForm(@Nullable Asker asker, Path cwd, Map<String, String> params) {
        boolean init = directory != null && NewArgs.isCurrentDirArg(directory);
        String presetName = name != null && !name.isBlank()
                ? name
                : NewArgs.presetName(directory, cwd).orElse(null);
        // The positional names the target, so its parent is the directory jk's step settles on.
        Path defaultParent = directory == null ? cwd : parentOf(NewArgs.resolveTarget(directory, cwd, "x"), cwd);
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
        flags.put("dir", FormCommand.Flag.named("--dir"));
        flags.put("layout", FormCommand.Flag.named("-L"));
        Path home = Optional.ofNullable(System.getProperty("user.home"))
                .map(Path::of)
                .orElse(null);
        DerivedDefaults derived = from -> switch (from) {
            case NewProjectStep.GIT_EMAIL -> parent != null ? parent.group() : NewGroupGuess.guess(cwd, home);
            case NewProjectStep.PARENT_DIR -> defaultParent.toString();
            default -> null;
        };
        NewForm.Forms forms = (template, language) -> EngineClient.newProjectForm(
                EnginePaths.current(), new NewProjectFormRequest(template, language, cwd.toString()));
        NewForm.Given given = new NewForm.Given(
                lang != null ? lang : parentLanguage(),
                template(),
                presetName,
                group,
                directory == null ? null : defaultParent.toString(),
                layoutFlag,
                params);
        Optional<NewForm.Answered> answered;
        try {
            answered = asker != null
                    ? NewForm.walk(asker, forms, given, derived, cwd, command, flags)
                    : Optional.of(NewForm.settle(forms, given, derived, command, flags));
        } catch (NewForm.Unsettled e) {
            CliOutput.err(e.lines());
            return Exit.USAGE;
        } catch (IOException e) {
            CommandWedge.printFail("New", e.getMessage());
            return e.getMessage() != null && e.getMessage().contains("not found") ? Exit.USAGE : Exit.SOFTWARE;
        }
        if (answered.isEmpty()) {
            if (asker != null) asker.println(parent != null ? "Module creation canceled" : "Project creation canceled");
            return Exit.INTERRUPTED;
        }
        NewForm.Answered a = answered.get();
        Path base = settledBase(cwd, a.text("dir"), defaultParent);
        Map<String, String> settled = settledParams(a.params());
        if (Files.exists(NewArgs.resolveTarget(directory, base, a.name()).resolve(ManifestPaths.MANIFEST))) {
            NewChrome.projectExists(a.name(), parent != null, init);
            return Exit.CONFIG;
        }
        List<String> pairs = new ArrayList<>();
        settled.forEach((k, v) -> pairs.add(k + "=" + v));
        return NewTemplate.apply(new NewTemplate.Args(
                a.template(),
                pairs,
                a.name(),
                a.text("group"),
                a.text("lang"),
                a.text("layout"),
                directory,
                base,
                cwd,
                parent,
                global.offline,
                frontend));
    }

    /**
     * Where the project goes once the form settled: with no positional, under the directory answer,
     * which may not be the cwd. A directory other than {@code defaultParent} may sit in another
     * workspace, or in none, so the enclosing project is looked up again from there.
     */
    Path settledBase(Path cwd, @Nullable String dir, Path defaultParent) {
        if (directory == null && dir != null && !dir.isBlank()) {
            Path base = cwd.resolve(dir).normalize();
            if (!base.equals(defaultParent)) this.parent = resolveParent(base);
            return base;
        }
        return cwd;
    }

    /**
     * The template answers the scaffold carries, once the form settled where the project goes and
     * so which workspace, if any, encloses it (a directory answer may reveal another one, or none).
     * The {@code java} answer is a {@code -p java=N}, else {@code --jdk}, else a module's workspace
     * level; a standalone project left without one gets the engine's, the major of the machine's
     * default JDK.
     */
    Map<String, String> settledParams(Map<String, String> answered) {
        Map<String, String> settled = new LinkedHashMap<>(answered);
        String level = jdk == null ? null : javaLevel(jdk);
        if (level != null) settled.putIfAbsent("java", level);
        // A module inherits the workspace's compile target, which may sit below its JDK.
        if (parent != null && parent.javaRelease() > 0) {
            settled.putIfAbsent("java", Integer.toString(parent.javaRelease()));
        }
        return settled;
    }

    /** A module is written in its workspace's language unless {@code --lang} says otherwise. */
    private @Nullable String parentLanguage() {
        if (parent == null) return null;
        if (parent.kotlin()) return "kotlin";
        if (parent.groovy()) return "groovy";
        if (parent.scala()) return "scala";
        return null;
    }

    private static Path parentOf(Path target, Path fallback) {
        Path p = target.getParent();
        return p == null ? fallback : p;
    }

    /**
     * Lists {@code moduleDir} in {@code root}'s {@code [workspace].modules}, promoting a plain
     * project into a workspace root (creating the {@code [workspace]} table) when this is its first
     * module. Every {@code jk new} mode, and {@code jk init} adding node members, registers through here. Returns
     * the module's path relative to the root, as written.
     */
    static String registerModule(Path root, Path moduleDir) throws IOException {
        Path base = WorkingDirectory.resolve(root);
        String rel =
                base.relativize(WorkingDirectory.resolve(moduleDir)).toString().replace('\\', '/');
        EngineEdits.apply(base.resolve(ManifestPaths.MANIFEST), "register-workspace-module", List.of(rel));
        return rel;
    }
}

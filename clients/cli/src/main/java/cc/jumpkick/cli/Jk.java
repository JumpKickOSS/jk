// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.changes.ChangeActor;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.cli.engine.BuildStamps;
import cc.jumpkick.cli.engine.EngineCancel;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.engine.GlobalSuspend;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.theme.PlainAscii;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.GlobalCancel;
import cc.jumpkick.cli.tui.StartupPulse;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.JkConfigLoader;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.terminal.Terminals;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** jk CLI entrypoint — routes commands through {@link CommandDispatch}. */
public final class Jk {

    /** Alias of {@link cc.jumpkick.model.JkVersion#VERSION} for CLI-side callers. */
    public static final String VERSION = JkVersion.VERSION;

    /** Top-line blurb on bare {@code jk} and {@code jk --help}. */
    static final String HELP_TAGLINE = "JumpKick - the JVM build system built for coding agents";

    /**
     * What {@code --engine-server} on the slim client says. A dev client gets its engine from {@code
     * jk install}, which installs the client with the engine and workers built beside it.
     */
    static final String WIRE_ONLY_CLIENT = "jk: no engine in this binary (wire-only client)\n"
            + "  run `jk self update` or `jk self materialize`, or set JK_ENGINE_EXE\n"
            + "  in JumpKick's checkout, `jk install` installs the client with its engine";

    /**
     * Hidden command aliases for ergonomic migration from other build tools. Documented in {@code
     * docs/user/aliases.md}. Keys are alias names; values are the canonical command path (one or more
     * positionals). These are not registered commands (they stay out of {@code --help} and
     * shell completion); instead we rewrite the first positional arg before parsing — possibly
     * expanding it into multiple positionals.
     */
    static final Map<String, List<String>> VERB_ALIASES = Map.ofEntries(
            Map.entry("generate", List.of("new")), // Maven mvn archetype:generate
            Map.entry("dependencies", List.of("tree")), // Gradle gradle dependencies
            Map.entry("package", List.of("build")), // Maven mvn package
            Map.entry("deploy", List.of("publish")), // Maven mvn deploy
            Map.entry("upgrade", List.of("update")), // npm/yarn/apt vocabulary
            Map.entry("sh", List.of("shell")),
            Map.entry("bash", List.of("shell")),
            Map.entry("nativeCompile", List.of("native")), // Gradle :nativeCompile task
            Map.entry("verify-target", List.of("verify")), // Maven's `verify` step output naming
            Map.entry("why-rebuilt", List.of("explain")), // early-roadmap name for the cache-diff report
            Map.entry("plan", List.of("explain")), // hidden alias: build-plan forecast
            Map.entry("check", List.of("compile"))); // pre-v1.0 name of the compile-only verb

    public static void main(String[] args) {
        // Windows: CP_UTF8 + UTF-8 System.out/err before any chrome. OEM CP437 otherwise
        // turns ● into ΓùÅ. Must run before the first println.
        Terminals.bootstrap();
        // Invoked as `jkx` (hardlink/link to this binary): behave exactly like
        // `jk tool run …` in every case — including --help — so the alias has
        // one mental model.
        args = rewriteForProgramName(args, Argv0.programName());
        // From here a silent quarter second shows what jk is waiting on; the first output replaces it.
        StartupPulse.arm(args);
        // : the slim client never hosts the engine. Spawning uses
        // lib/jk-engine/<jar> / JK_ENGINE_EXE only — no --engine-server monolyth path.
        if (args.length > 0 && "--engine-server".equals(args[0])) {
            System.err.println(WIRE_ONLY_CLIENT);
            System.exit(Exit.SOFTWARE);
            return;
        }
        var superseded = SupersededClient.handOff(args);
        if (superseded.isPresent()) {
            StartupPulse.release();
            System.exit(superseded.getAsInt());
            return;
        }
        GlobalCancel.install(EngineCancel::cancelBestEffortForInterrupt);
        GlobalSuspend.install();
        // Threads this command starts journal their changes as its doing too.
        ChangeActor.setProcessDefault(shellActor(args));
        int code;
        try {
            code = execute(args);
            code = SupersededClient.handOffAfterCommand(args).orElse(code);
            SelfUpdatePrompt.afterCommand(commandName(args), List.of(args), code);
            NerdFontHint.afterCommand(commandName(args), List.of(args), code);
            ReadUsageHint.afterCommand(commandName(args), List.of(args), code);
        } catch (JkDirs.InvalidOverrideException e) {
            System.err.println("jk: invalid environment: " + e.getMessage());
            code = Exit.USAGE;
        } catch (CliFailure.WorkingDirectoryGone e) {
            code = CliFailure.workingDirectoryGone(args);
        } catch (Throwable t) {
            // A bad override first read by a static initializer arrives wrapped.
            JkDirs.InvalidOverrideException invalid = invalidOverride(t);
            if (invalid != null) {
                System.err.println("jk: invalid environment: " + invalid.getMessage());
                code = Exit.USAGE;
            } else {
                // Nothing below this frame handled it, so nothing below this frame can say it better:
                // one wedge, the stack in cli.log, and the exit code the docs already promise.
                code = CliFailure.unhandled(t, args);
            }
        } finally {
            StartupPulse.release();
            // Wake any JLine NonBlocking stdin reader before the JVM shutdown hooks run — also
            // on the exception path, where the JVM's default handler still runs those hooks. On
            // macOS, JLine's terminal closer otherwise blocks until a key arrives after
            // interactive plans (Ctrl-O key listener / canPrompt probe).
            Terminals.shutdown();
        }
        // A verb that unwound because the user pressed Ctrl-C exits 130 even though it returned an
        // ordinary failure code — GlobalCancel's own halt is only the backup for a wedged verb.
        System.exit(GlobalCancel.exitCodeFor(code));
    }

    /**
     * Run jk with the given argv. The first positional is rewritten if it's a known alias. Each call
     * is one invocation: the session it installs is its own, so two calls in one JVM (the JVM client,
     * in-process tests) never read each other's flags.
     */
    public static int execute(String... args) {
        try {
            // What this invocation changes outside a project is journaled as this command line's doing.
            return Request.actingAs(shellActor(args), () -> GlobalCancel.invocation(() -> executeInvocation(args)));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The command line as the change journal's actor: from the command on, global flags before it left out. */
    static ChangeActor shellActor(String[] args) {
        List<String> all = List.of(args);
        int at = CommandDispatch.commandIndex(all);
        return ChangeActor.shell(at >= 0 ? all.subList(at, all.size()) : all);
    }

    /** The command {@code args} name, before alias rewriting; null for bare {@code jk}, help and the version. */
    static @Nullable String commandName(String[] args) {
        int at = CommandDispatch.commandIndex(List.of(args));
        return at >= 0 ? args[at] : null;
    }

    /** The {@link JkDirs.InvalidOverrideException} in {@code t}'s cause chain, or null. */
    static JkDirs.@Nullable InvalidOverrideException invalidOverride(Throwable t) {
        return Errors.inChain(t, JkDirs.InvalidOverrideException.class);
    }

    private static int executeInvocation(String... args) {
        // `--list` is an undocumented synonym for `--help`. Rewrite it before any
        // arg scan so both the config loader and the dispatcher only ever see `--help`.
        args = rewriteListToHelp(args);
        // Engine summaries are memoized for one invocation; an embedding process runs many.
        ProjectInfos.forget();
        // The home and state roots are owner-only before anything writes into them: the engine
        // socket under state/ is trusted on those permissions alone.
        JkDirs.current().secureRoots();
        // Resolve configuration first — the dispatcher's subsequent option parsing only
        // determines explicit flag values; defaults still need to come from the
        // env / project jk.toml / user / system layers via JkConfigLoader.
        loadAndInstallConfig(args);
        // Fold the explicit-CLI layer in last so the rest of the runtime sees the
        // fully-resolved JkConfig before any subcommand dispatches.
        applyCliOverrides(args);
        // -q/--quiet must take effect before any println happens. Apply it now
        // based on the resolved config (which already knows about env/file/CLI layers). Help and
        // the version are the output the user asked for by name: never the noise -q silences.
        if (!CommandDispatch.asksForHelpOrVersion(List.of(args))) {
            Quietable.applyIfQuiet(SessionContext.current().config());
        }
        String[] rewritten = rewriteAlias(args);
        // Every command is now on the CliCommand model; CommandDispatch handles all
        // dispatch. The fallback below handles bare `jk` + --help + --version.
        Integer ported = CommandDispatch.tryDispatch(rewritten);
        if (ported != null) return ported;
        // No command: check for --help / --version / bare invocation.
        boolean ansi = CommandDispatch.ansiEnabled();
        List<String> argList = List.of(rewritten);
        if (argList.contains("-V") || argList.contains("--version")) {
            System.out.println("jk " + VERSION);
            if (argList.contains("-v") || argList.contains("--verbose")) {
                Optional<EngineProbe.Status> engine =
                        EngineProbe.status(EnginePaths.activeSocket(EnginePaths.current()));
                for (String line : BuildStamps.versionLines(BuildStamps.client(), engine)) System.out.println(line);
            }
            return 0;
        }
        if (argList.contains("-h") || argList.contains("--help")) {
            // Full help: all command groups + global options.
            CommandDispatch.printHelpText(fullHelp(ansi));
            return 0;
        }
        // Bare `jk` (no command, no flags): curated short-help screen.
        HelpRenderer.printShortHelp(
                CommandDispatch.commands(), HELP_TAGLINE, "jk", PlainAscii.wrapping(System.out), ansi);
        return 0;
    }

    /** Full `jk --help` screen: commands grouped + global options. */
    private static String fullHelp(boolean ansi) {
        Map<String, SubcommandModel> byName = new LinkedHashMap<>();
        for (var c : CommandDispatch.commands()) {
            if (!c.hidden()) byName.put(c.name(), new SubcommandModel(c.name(), new String[] {c.description()}, false));
        }
        List<OptionModel> globals = GlobalOptions.globalOpts().stream()
                .filter(o -> !o.hidden())
                .map(CommandModels::option)
                .toList();
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append(HELP_TAGLINE).append(nl).append(nl);
        // Usage line
        if (ansi) {
            sb.append(HelpRenderer.paint("Usage:", Theme.active().sectionHeading(), true))
                    .append(" ")
                    .append(HelpRenderer.paint("jk", Theme.active().commandName(), true))
                    .append(HelpRenderer.paint(
                            " <COMMAND> [OPTIONS]", Theme.active().paramLabel(), true))
                    .append(nl);
        } else {
            sb.append("Usage: jk <COMMAND> [OPTIONS]").append(nl);
        }
        // Group by UsageGroups (same grouping as before)
        Set<String> placed = new LinkedHashSet<>();
        boolean firstGroup = true;
        for (CommandGroup group : UsageGroups.COMMAND_GROUPS) {
            List<String> visible =
                    group.names().stream().filter(byName::containsKey).toList();
            if (visible.isEmpty()) continue;
            if (!firstGroup) sb.append(nl);
            firstGroup = false;
            sb.append(nl)
                    .append(HelpRenderer.paint(group.heading(), Theme.active().sectionHeading(), ansi))
                    .append(nl);
            int width = visible.stream().mapToInt(String::length).max().orElse(0) + 4;
            for (String name : visible) {
                SubcommandModel sub = Objects.requireNonNull(byName.get(name));
                String padding = " ".repeat(width - name.length());
                sb.append("  ")
                        .append(HelpRenderer.paint(name, Theme.active().commandName(), ansi))
                        .append(padding)
                        .append(sub.description().length > 0 ? sub.description()[0] : "")
                        .append(nl);
                placed.add(name);
            }
        }
        // Ungrouped leftover
        List<String> leftover = byName.keySet().stream()
                .filter(n -> !placed.contains(n))
                .sorted()
                .toList();
        if (!leftover.isEmpty()) {
            sb.append(nl)
                    .append(HelpRenderer.paint("Other commands:", Theme.active().sectionHeading(), ansi))
                    .append(nl);
            int width = leftover.stream().mapToInt(String::length).max().orElse(0) + 4;
            for (String name : leftover) {
                SubcommandModel sub = Objects.requireNonNull(byName.get(name));
                String padding = " ".repeat(width - name.length());
                sb.append("  ")
                        .append(HelpRenderer.paint(name, Theme.active().commandName(), ansi))
                        .append(padding)
                        .append(sub.description().length > 0 ? sub.description()[0] : "")
                        .append(nl);
            }
        }
        // Global options
        sb.append(nl)
                .append(HelpRenderer.paint("Global options:", Theme.active().sectionHeading(), ansi))
                .append(nl);
        sb.append(HelpRenderer.renderOptionRows(globals, ansi));
        return sb.toString();
    }

    /**
     * Fold the explicit global flags in argv into {@link SessionContext} before dispatch, so the
     * help and version paths and {@link Quietable}'s mute see them before the first println. argv
     * is read as {@link #scanGlobals} reads it. Dispatch folds the full parse once more for the
     * command it runs ({@link GlobalOptions#cliLayer}).
     */
    static void applyCliOverrides(String[] args) {
        Invocation in = scanGlobals(args);
        JkConfig cli = GlobalOptions.cliLayer(in);
        Path directory = in.value("dir").map(WorkingDirectory::resolve).orElse(null);
        if (directory != null) cli = cli.withDir(directory);
        SessionContext.installConfig(SessionContext.current().config().mergedWith(cli));
    }

    /**
     * The global options within jk's own args ({@link CommandDispatch#ownArgsEnd}), read against the
     * global option table itself ({@link ArgParser#scan}): short forms and bundles count, and so do
     * globals after the command, matched exactly.
     */
    private static Invocation scanGlobals(String[] args) {
        List<String> own = List.of(args).subList(0, CommandDispatch.ownArgsEnd(List.of(args)));
        int commandAt = CommandDispatch.commandIndex(own);
        return ArgParser.scan(GlobalOptions.globals(), own, commandAt < 0 ? own.size() : commandAt);
    }

    /**
     * Read {@code --config-file}, {@code --no-config} and {@code --dir} out of argv, then ask {@link
     * JkConfigLoader} to build the merged {@link JkConfig} from the project {@code --dir} names (else
     * the working directory) and install it on the {@link SessionContext}. This runs before dispatch
     * parses argv, so the rest of the CLI sees an already-resolved config.
     *
     * <p>CLI flag values (the highest layer) are folded in by {@link #applyCliOverrides}, then once
     * more by dispatch for the command it runs.
     */
    static void loadAndInstallConfig(String[] args) {
        ConfigSwitches switches = ConfigSwitches.scan(args);
        Path cwd;
        try {
            cwd = switches.dir().orElseGet(WorkingDirectory::current);
        } catch (Error e) {
            // The native image answers a vanished working directory with an Error from properties
            // initialisation; the shell is sitting in a directory that no longer exists.
            if (CliFailure.isWorkingDirectoryGone(e)) throw new CliFailure.WorkingDirectoryGone(e);
            throw e;
        }
        // One invocation is one shell: the process-static session starts from defaults, so a
        // host that runs several invocations in one JVM (a test suite, an IDE server) hands each
        // its own caller's environment — the previous caller's JK_REPO_* credentials, variant and
        // worker-JVM tuning end with the invocation that carried them. GlobalOptions layers the
        // shell's forward set under whatever the session already holds, which must be nothing.
        try {
            JkConfig resolved = JkConfigLoader.load(cwd, switches.noConfig(), switches.explicit());
            SessionContext.install(Session.defaults().withConfig(resolved));
        } catch (IOException e) {
            // Best-effort — a broken user/project config shouldn't kill the CLI.
            System.err.println("jk: warning: could not load config (" + e.getMessage() + "); using defaults.");
            SessionContext.install(Session.defaults().withConfig(JkConfig.empty()));
        }
    }

    /** {@code --no-config}, {@code --config-file} and {@code --dir} as they appear within jk's own args. */
    record ConfigSwitches(boolean noConfig, Optional<Path> explicit, Optional<Path> dir) {
        static ConfigSwitches scan(String[] args) {
            Invocation in = scanGlobals(args);
            return new ConfigSwitches(
                    in.isSet("no-config"),
                    in.value("config-file").map(WorkingDirectory::resolve),
                    in.value("dir").map(WorkingDirectory::resolve));
        }
    }

    /**
     * Rewrite any {@code --list} occurrence within jk's own args to {@code --help}. {@code --list}
     * is an undocumented alias so muscle memory from tools like {@code rustup} / {@code cargo} keeps
     * working; downstream code never sees it. Past {@link CommandDispatch#ownArgsEnd} the token is
     * someone else's — {@code jk run mytool -- --list} hands the tool {@code --list}.
     */
    static String[] rewriteListToHelp(String[] args) {
        // `jk skill -l` is the skill's own flag. Everywhere else `--list` is still `--help`.
        int commandAt = CommandDispatch.commandIndex(List.of(args));
        if (commandAt >= 0 && "skill".equals(args[commandAt])) return args;
        int end = CommandDispatch.ownArgsEnd(List.of(args));
        String[] out = null;
        for (int i = 0; i < end; i++) {
            if ("--list".equals(args[i])) {
                if (out == null) out = args.clone();
                out[i] = "--help";
            }
        }
        return out != null ? out : args;
    }

    /**
     * When the binary was invoked as {@code jkx} (per {@link Argv0}), prepend {@code tool run} so
     * the whole invocation is exactly {@code jk tool run …}. Any other program name (including
     * null) passes through untouched.
     */
    static String[] rewriteForProgramName(String[] args, @Nullable String programName) {
        if (!"jkx".equals(programName)) return args;
        String[] out = new String[args.length + 2];
        out[0] = "tool";
        out[1] = "run";
        System.arraycopy(args, 0, out, 2, args.length);
        return out;
    }

    /** Rewrite the command, the first positional after any leading globals, when it is a verb alias. */
    static String[] rewriteAlias(String[] args) {
        int at = CommandDispatch.commandIndex(List.of(args));
        if (at < 0) return args;
        List<String> mapped = VERB_ALIASES.get(args[at]);
        if (mapped == null) return args;
        String[] out = new String[mapped.size() + args.length - 1];
        System.arraycopy(args, 0, out, 0, at);
        for (int i = 0; i < mapped.size(); i++) out[at + i] = mapped.get(i);
        System.arraycopy(args, at + 1, out, at + mapped.size(), args.length - at - 1);
        return out;
    }
}

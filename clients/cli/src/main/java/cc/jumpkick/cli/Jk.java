// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.GlobalCancel;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.JkConfigLoader;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.terminal.Terminals;
import cc.jumpkick.util.JkDirs;
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
    static final String HELP_TAGLINE = "JumpKick - The best damn build system for the JVM";

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
        // : the slim client never hosts the engine. Spawning uses
        // lib/jk-engine/<jar> / JK_ENGINE_EXE only — no --engine-server monolyth path.
        if (args.length > 0 && "--engine-server".equals(args[0])) {
            System.err.println("jk: this binary does not include the engine (wire-only client)."
                    + " Materialize the engine (`./install.sh`, `jk self materialize`,"
                    + " or `jk self update`), or set JK_ENGINE_EXE.");
            System.exit(Exit.SOFTWARE);
            return;
        }
        GlobalCancel.install();
        int code;
        try {
            code = execute(args);
        } catch (JkDirs.InvalidOverrideException e) {
            System.err.println("jk: invalid environment: " + e.getMessage());
            code = Exit.USAGE;
        } catch (CliFailure.WorkingDirectoryGone e) {
            code = CliFailure.workingDirectoryGone(args);
        } catch (Throwable t) {
            // Nothing below this frame handled it, so nothing below this frame can say it better:
            // one wedge, the stack in cli.log, and the exit code the docs already promise.
            code = CliFailure.unhandled(t, args);
        } finally {
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

    /** Run jk with the given argv. The first positional is rewritten if it's a known alias. */
    public static int execute(String... args) {
        // `--list` is an undocumented synonym for `--help`. Rewrite it before any
        // arg scan so both the config loader and the dispatcher only ever see `--help`.
        args = rewriteListToHelp(args);
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
            return 0;
        }
        if (argList.contains("-h") || argList.contains("--help")) {
            // Full help: all command groups + global options.
            System.out.print(fullHelp(ansi));
            return 0;
        }
        // Bare `jk` (no command, no flags): curated short-help screen.
        HelpRenderer.printShortHelp(CommandDispatch.commands(), HELP_TAGLINE, "jk", System.out, ansi);
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
     * Argv-scan pass that folds explicit CLI flags into {@link cc.jumpkick.config.SessionContext}. This is intentionally a
     * small scan rather than reusing ArgParser: the highest-precedence layer needs to be
     * available <em>before</em> dispatch (e.g. so {@link Quietable} can mute
     * stdout before the first subcommand println). Only flags that affect global behavior are read
     * here; everything else flows through the dispatcher.
     */
    static void applyCliOverrides(String[] args) {
        int end = CommandDispatch.ownArgsEnd(List.of(args));
        JkConfig.ColorChoice color = null;
        Boolean offline = null;
        Boolean force = null;
        Boolean rebuild = null;
        Boolean noProgress = null;
        Boolean noAnsi = null;
        Boolean noOsc = null;
        JkConfig.NotifyChoice notify = null;
        Boolean quiet = null;
        Boolean verbose = null;
        Path directory = null;
        for (int i = 0; i < end; i++) {
            String a = args[i];
            switch (a) {
                case "-q", "--quiet" -> quiet = true;
                case "-v", "--verbose" -> verbose = true;
                case "--offline" -> offline = true;
                case "-F", "--force" -> force = true;
                case "-r", "--redo", "--rebuild" -> rebuild = true;
                case "--no-progress" -> noProgress = true;
                // --no-ansi: strip ALL ANSI (color + bold/italic + CSI). Progress still runs as
                // multi-line plain frames — use --no-progress to silence chrome entirely.
                // Distinct from --color never which strips color but preserves text attributes.
                case "--no-ansi" -> noAnsi = true;
                case "--no-osc" -> noOsc = true;
                case "--notify" -> notify = JkConfig.NotifyChoice.ALWAYS;
                case "--no-notify" -> notify = JkConfig.NotifyChoice.NEVER;
                case "--color" -> {
                    if (i + 1 < end)
                        color = JkConfig.ColorChoice.parse(args[++i]).orElse(null);
                }
                case "-C", "--dir", "--directory" -> {
                    if (i + 1 < end) directory = Path.of(args[++i]);
                }
                default -> {
                    if (a.startsWith("--color=")) {
                        color = JkConfig.ColorChoice.parse(a.substring("--color=".length()))
                                .orElse(null);
                    } else if (a.startsWith("--dir=")) {
                        directory = Path.of(a.substring("--dir=".length()));
                    } else if (a.startsWith("--directory=")) {
                        directory = Path.of(a.substring("--directory=".length()));
                    }
                }
            }
        }
        JkConfig cli = new JkConfig(
                color,
                offline,
                rebuild,
                noProgress,
                quiet,
                verbose,
                directory,
                force,
                noAnsi,
                null, // force-ansi: config/env only
                noOsc,
                notify,
                null); // build-output: config/env only
        SessionContext.installConfig(SessionContext.current().config().mergedWith(cli));
    }

    /**
     * Read {@code --config-file} / {@code --no-config} out of raw argv with a cheap linear scan, then
     * ask {@link JkConfigLoader} to build the merged {@link JkConfig} and install it on the {@link
     * cc.jumpkick.config.SessionContext}. This runs before picocli parsing so the rest of the CLI
     * sees an already-resolved config.
     *
     * <p>CLI flag values (the highest layer) are folded in lazily as each command's {@link
     * GlobalOptions} mixin reads them after parsing.
     */
    private static void loadAndInstallConfig(String[] args) {
        ConfigSwitches switches = ConfigSwitches.scan(args);
        Path cwd;
        try {
            cwd = Path.of("").toAbsolutePath();
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

    /** {@code --no-config} / {@code --config-file} as they appear within jk's own args. */
    record ConfigSwitches(boolean noConfig, Optional<Path> explicit) {
        static ConfigSwitches scan(String[] args) {
            int end = CommandDispatch.ownArgsEnd(List.of(args));
            boolean noConfig = false;
            Optional<Path> explicit = Optional.empty();
            for (int i = 0; i < end; i++) {
                String a = args[i];
                if ("--no-config".equals(a)) {
                    noConfig = true;
                } else if ("--config-file".equals(a) && i + 1 < end) {
                    explicit = Optional.of(Path.of(args[++i]));
                } else if (a.startsWith("--config-file=")) {
                    explicit = Optional.of(Path.of(a.substring("--config-file=".length())));
                }
            }
            return new ConfigSwitches(noConfig, explicit);
        }
    }

    /**
     * Rewrite any {@code --list} occurrence within jk's own args to {@code --help}. {@code --list}
     * is an undocumented alias so muscle memory from tools like {@code rustup} / {@code cargo} keeps
     * working; downstream code never sees it. Past {@link CommandDispatch#ownArgsEnd} the token is
     * someone else's — {@code jk run mytool -- --list} hands the tool {@code --list}.
     */
    static String[] rewriteListToHelp(String[] args) {
        // `jk skill --list` is the skill's own flag. Everywhere else `--list` is still `--help`.
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

    static String[] rewriteAlias(String[] args) {
        if (args.length == 0) return args;
        List<String> mapped = VERB_ALIASES.get(args[0]);
        if (mapped == null) return args;
        String[] out = new String[mapped.size() + args.length - 1];
        for (int i = 0; i < mapped.size(); i++) out[i] = mapped.get(i);
        System.arraycopy(args, 1, out, mapped.size(), args.length - 1);
        return out;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.args.Abbreviations;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.cli.args.ParseException;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.LiveRegion;
import cc.jumpkick.command.ActivateCommand;
import cc.jumpkick.command.ActivityCommand;
import cc.jumpkick.command.AddCommand;
import cc.jumpkick.command.AssemblyCommand;
import cc.jumpkick.command.AuditCommand;
import cc.jumpkick.command.AuthCommand;
import cc.jumpkick.command.BspCommand;
import cc.jumpkick.command.BuildCommand;
import cc.jumpkick.command.CacheCommand;
import cc.jumpkick.command.CancelCommand;
import cc.jumpkick.command.CleanCommand;
import cc.jumpkick.command.CompileCommand;
import cc.jumpkick.command.CompletionCommand;
import cc.jumpkick.command.DeactivateCommand;
import cc.jumpkick.command.DenyCommand;
import cc.jumpkick.command.DevCommand;
import cc.jumpkick.command.DoctorCommand;
import cc.jumpkick.command.EngineCommand;
import cc.jumpkick.command.EnvCommand;
import cc.jumpkick.command.ExplainCommand;
import cc.jumpkick.command.ExportCommand;
import cc.jumpkick.command.FormatCommand;
import cc.jumpkick.command.GradleCommand;
import cc.jumpkick.command.HistoryCommand;
import cc.jumpkick.command.HookEnvCommand;
import cc.jumpkick.command.IdeCommand;
import cc.jumpkick.command.ImageCommand;
import cc.jumpkick.command.ImportCommand;
import cc.jumpkick.command.InitCommand;
import cc.jumpkick.command.InspectCommand;
import cc.jumpkick.command.JdkCommand;
import cc.jumpkick.command.JshellCommand;
import cc.jumpkick.command.LibraryCommand;
import cc.jumpkick.command.LockCommand;
import cc.jumpkick.command.ManualCommand;
import cc.jumpkick.command.MvnCommand;
import cc.jumpkick.command.NativeCommand;
import cc.jumpkick.command.NewCommand;
import cc.jumpkick.command.OutdatedCommand;
import cc.jumpkick.command.PublishCommand;
import cc.jumpkick.command.RemoveCommand;
import cc.jumpkick.command.RepoCommand;
import cc.jumpkick.command.ResultsCommand;
import cc.jumpkick.command.SelectiveCommand;
import cc.jumpkick.command.SelfCommand;
import cc.jumpkick.command.ShellCommand;
import cc.jumpkick.command.ShowCommand;
import cc.jumpkick.command.StatusCommand;
import cc.jumpkick.command.StorageCommand;
import cc.jumpkick.command.SyncCommand;
import cc.jumpkick.command.TasksCommand;
import cc.jumpkick.command.TestCommand;
import cc.jumpkick.command.ToolCommand;
import cc.jumpkick.command.ToolInstallCommand;
import cc.jumpkick.command.ToolRunCommand;
import cc.jumpkick.command.TrainCommand;
import cc.jumpkick.command.TreeCommand;
import cc.jumpkick.command.TrustCommand;
import cc.jumpkick.command.UpdateCommand;
import cc.jumpkick.command.VerifyBuildCommand;
import cc.jumpkick.command.VscodeCommand;
import cc.jumpkick.command.WatchCommand;
import cc.jumpkick.command.WebCommand;
import cc.jumpkick.command.WhyCommand;
import cc.jumpkick.command.WrapperCommand;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.plugin.PluginJarNotFoundException;
import cc.jumpkick.engine.protocol.PluginCommandReport;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Command;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Routes every command through jk's own {@link ArgParser} + {@link HelpRenderer} (picocli is long
 * gone; the registry below is the complete command surface).
 *
 * <p>Handles both leaf commands and parent groups (a {@link CliCommand} with subcommands) — {@code jk
 * jdk install …} descends into the parent, finds the sub-command, and dispatches it; {@code jk
 * jdk}/{@code jk jdk --help} prints the parent's command list.
 */
public final class CommandDispatch {

    private CommandDispatch() {}

    /** Commands parsed/run by jk's own parser. Grows as commands are ported. */
    private static final List<CliCommand> COMMANDS = List.of(
            new CleanCommand(),
            new EngineCommand(),
            new SelfCommand(),
            new WrapperCommand(),
            new HistoryCommand(),
            new ActivityCommand(), // name() = jobs; activity/act aliases
            new ResultsCommand(),
            new ManualCommand(),
            new CancelCommand(),
            new StatusCommand(),
            new TreeCommand(),
            new WhyCommand(),
            new ExplainCommand(),
            new TasksCommand(),
            new ShowCommand(),
            new InspectCommand(),
            new JshellCommand(),
            new DeactivateCommand(),
            new ShellCommand(),
            new HookEnvCommand(),
            new EnvCommand(),
            new LockCommand(),
            new SyncCommand(),
            new AddCommand(),
            new RemoveCommand(),
            new UpdateCommand(),
            new OutdatedCommand(),
            new DoctorCommand(),
            new DenyCommand(),
            new VerifyBuildCommand(),
            new AuditCommand(),
            new AuthCommand(),
            new CacheCommand(),
            new StorageCommand(),
            new JdkCommand(),
            new ToolCommand(),
            new TrustCommand(),
            new LibraryCommand(),
            new RepoCommand(),
            new IdeCommand(),
            new VscodeCommand(),
            new ExportCommand(),
            new ImportCommand(),
            new PublishCommand(),
            // `run` and `install` are also mounted under `jk tool` (one implementation, two mounts).
            new ToolRunCommand(),
            new ToolInstallCommand(),
            new CompileCommand(),
            new DevCommand(),
            new WatchCommand(),
            new BuildCommand(),
            new AssemblyCommand(),
            new BspCommand(),
            new SelectiveCommand(),
            new TestCommand(),
            new FormatCommand(),
            new NativeCommand(),
            new TrainCommand(),
            new ImageCommand(),
            new MvnCommand(),
            new GradleCommand(),
            new ActivateCommand(),
            new CompletionCommand(),
            new NewCommand(),
            new InitCommand(),
            new WebCommand());

    private static final Map<String, CliCommand> BY_NAME = index();

    private static Map<String, CliCommand> index() {
        Map<String, CliCommand> m = new LinkedHashMap<>();
        for (CliCommand c : COMMANDS) {
            m.put(c.name(), c);
            for (String alias : c.aliases()) m.put(alias, c);
        }
        return m;
    }

    /** The ported commands — used to list them in the top-level (picocli) help. */
    /** Top-level name/prefix resolution, exposed for tests (prefix regressions). */
    static Abbreviations.Result<CliCommand> resolveName(String token) {
        return Abbreviations.resolve(token, BY_NAME);
    }

    public static List<CliCommand> commands() {
        return COMMANDS;
    }

    /**
     * If the command names a ported command, parse and run it (descending through subcommands as
     * needed), returning the exit code; otherwise return {@code null} so the caller falls back to
     * picocli.
     */
    public static Integer tryDispatch(String[] args) {
        List<String> all = List.of(args);
        int commandAt = commandIndex(all);
        if (commandAt < 0) return null;
        String command = all.get(commandAt);
        Abbreviations.Result<CliCommand> r = Abbreviations.resolve(command, BY_NAME);
        if (r.kind() == Abbreviations.Kind.AMBIGUOUS) {
            printAmbiguousCommand(command, r.candidates(), ansiEnabled());
            return Exit.USAGE;
        }
        CliCommand cmd = r.value();
        if (cmd == null) {
            // Not a jk command — a build plugin may declare it. Only when a project manifest is
            // present, and never by SPAWNING an engine for a typo: the hosted attempt requires a
            // live socket; the test seam runs in-process.
            Integer pluginExit = tryPluginCommand(command, all.subList(commandAt + 1, all.size()));
            if (pluginExit != null) return pluginExit;
            return null; // let the caller show top-level help
        }
        return dispatch(cmd, "jk " + cmd.name(), carryGlobals(all, commandAt), ansiEnabled());
    }

    /**
     * Args for the resolved command: the tokens after it, with any global flags that appeared
     * <em>before</em> it carried along so the leaf parse still sees them ({@code jk -y self purge}
     * must reach {@code Confirm.setAssumeYes} exactly like {@code jk self nuke -y}). A literal
     * {@code --} separator is not carried — it only marked the command boundary.
     */
    private static List<String> carryGlobals(List<String> args, int commandAt) {
        if (commandAt == 0) return args.subList(1, args.size());
        List<String> carried = new ArrayList<>();
        for (String a : args.subList(0, commandAt)) {
            if (!a.equals("--")) carried.add(a);
        }
        carried.addAll(args.subList(commandAt + 1, args.size()));
        return carried;
    }

    /**
     * Attempt {@code command} as a plugin-declared command. Returns the exit code when a plugin
     * owned and ran it, else {@code null} (no jk.toml, no reachable engine, no owning plugin —
     * the normal unknown-command help follows).
     */
    private static Integer tryPluginCommand(String command, List<String> args) {
        Path dir = Path.of("").toAbsolutePath().normalize();
        if (!Files.isRegularFile(dir.resolve(ManifestPaths.MANIFEST))) return null;
        try {
            PluginCommandReport report;
            var paths = EnginePaths.current();
            if (!EngineClient.ping(EnginePaths.activeSocket(paths))) return null;
            report = EngineClient.pluginCommand(paths, dir, JkDirs.cache(), command, args);

            if (!report.found()) return null;
            // A plugin command is a leaf command: same envelope, same close after the failure
            // wedge, so its output is spaced like every other command's. Its raw args are
            // forwarded to the engine unparsed, so honor -O/--output (and JK_OUTPUT) here the
            // way registry commands do at dispatch — machine consumers of a plugin command must
            // not get the envelope or the PlainAscii rewrite.
            CliOutput.beginCommand(pluginArgsAskJson(args));
            try {
                if (report.error() != null) {
                    CommandWedge.printFail(command, report.error());
                    return 1;
                }
                for (String line : report.output()) CliOutput.out(line);
                return report.exit();
            } finally {
                CliOutput.closeEnvelope();
            }
        } catch (Exception e) {
            return null; // best-effort — fall back to the normal help
        }
    }

    /**
     * {@code -O json} / {@code --output=jsonl} scanned out of a plugin command's raw arg list
     * (never parsed client-side), falling back to {@code JK_OUTPUT} via the same resolution
     * registry commands use.
     */
    static boolean pluginArgsAskJson(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            String v = null;
            if (a.equals("-O") || a.equals("--output")) {
                v = i + 1 < args.size() ? args.get(i + 1) : null;
            } else if (a.startsWith("--output=")) {
                v = a.substring("--output=".length());
            }
            if (v != null) return GlobalOptions.outputIsJson(v);
        }
        return GlobalOptions.outputIsJson((String) null); // JK_OUTPUT fallback
    }

    /** Dispatch {@code cmd} against {@code rest} (its arguments), descending into subcommands. */
    /**
     * Run one command, envelope and all. Package-private: a test drives a leaf command straight
     * through here to cover the paths no shipped command can be made to take on demand — an
     * exception escaping {@code run} into the error line below.
     */
    static int dispatch(CliCommand cmd, String qualified, List<String> rest, boolean ansi) {
        if (!cmd.subcommands().isEmpty()) {
            int subAt = commandIndex(rest);
            if (subAt < 0) {
                // Bare group: optional default leaf, else print the subcommand list.
                // `--help` always shows help.
                if (helpRequested(cmd, rest)) {
                    System.out.print(renderHelp(cmd, qualified, ansi));
                    return 0;
                }
                CliCommand def = cmd.defaultSubcommand();
                if (def != null) {
                    return dispatch(def, qualified + " " + def.name(), rest, ansi);
                }
                System.out.print(renderHelp(cmd, qualified, ansi));
                return Exit.USAGE;
            }
            String subName = rest.get(subAt);
            Abbreviations.Result<CliCommand> r = resolveSub(cmd, subName);
            if (r.kind() == Abbreviations.Kind.AMBIGUOUS) {
                printAmbiguousSubcommand(cmd, qualified, subName, r.candidates(), ansi);
                return Exit.USAGE;
            }
            CliCommand sub = r.value();
            if (sub == null) {
                printUnknownSubcommand(cmd, qualified, subName, ansi);
                return Exit.USAGE;
            }
            return dispatch(sub, qualified + " " + sub.name(), carryGlobals(rest, subAt), ansi);
        }

        // --help wins over parse validation (e.g. a missing required argument),
        // matching picocli — so `jk <cmd> --help` always shows help.
        if (helpRequested(cmd, rest)) {
            System.out.print(renderHelp(cmd, qualified, ansi));
            return 0;
        }
        Invocation in;
        try {
            in = ArgParser.parse(withGlobals(cmd), rest, cmd.passthrough());
        } catch (ParseException e) {
            printError(qualified, cmd, e, ansi);
            return Exit.USAGE;
        }
        if (in.isSet("version")) {
            System.out.println("jk " + Jk.VERSION);
            return 0;
        }
        boolean script = GlobalOptions.outputIsJson(in) || cmd.scriptMode(in);
        try {
            // One envelope per leaf command: the leading blank on the first chrome write, one
            // trailing blank after the last — including after the error line below. Machine stdout
            // (JSON/JSONL, or a script-mode command) opts out of both and of the ASCII rewrite.
            CliOutput.beginCommand(script);
            // Hidden global -y/--yes: skip Confirm prompts for this leaf command only.
            Confirm.setAssumeYes(in.isSet("yes"));
            try {
                return cmd.run(in);
            } finally {
                Confirm.clearAssumeYes();
            }
        } catch (PluginJarNotFoundException e) {
            closeActiveLiveRegion();
            printWorkerJarError(e, ansi);
            return 1;
        } catch (Exception e) {
            closeActiveLiveRegion();
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            CliOutput.err(HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi) + " " + msg);
            return 1;
        } finally {
            // Trailing blank after the last chrome, on whichever stream wrote it — settles never
            // print it themselves, and an exec handoff suppresses it outright.
            CliOutput.closeEnvelope();
        }
    }

    /**
     * An escaping exception must not leave a live region owning the terminal — hidden cursor,
     * animator repainting over the error, stale OSC taskbar progress. Close (idempotent) restores
     * the captured streams first, so the error line prints to the real stderr.
     */
    static void closeActiveLiveRegion() {
        LiveRegion region = LiveRegion.active();
        if (region instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Best-effort terminal restore.
            }
        }
    }

    /** Resolve a sub-command (exact or unique prefix) against a parent's subcommands and their aliases. */
    private static Abbreviations.Result<CliCommand> resolveSub(CliCommand parent, String name) {
        Map<String, CliCommand> byName = new LinkedHashMap<>();
        for (CliCommand sub : parent.subcommands()) {
            byName.put(sub.name(), sub);
            for (String alias : sub.aliases()) byName.put(alias, sub);
        }
        return Abbreviations.resolve(name, byName);
    }

    /**
     * A parse model = the command's own options plus the shared global options. A name collision
     * between the two is a programming error and fails loudly: ArgParser indexes names last-wins,
     * so a silent merge would let a global quietly replace a command's option (this once broke
     * `jk self update <version>` — the global -V/--version flag ate the command's value option).
     */
    private static Command withGlobals(CliCommand cmd) {
        List<Opt> opts = new ArrayList<>(cmd.options());
        Set<String> own = new HashSet<>();
        for (Opt opt : opts) own.addAll(opt.allNames());
        for (Opt global : GlobalOptions.globalOpts()) {
            for (String n : global.allNames()) {
                if (own.contains(n)) {
                    throw new IllegalStateException("command '" + cmd.name() + "' declares option " + n
                            + ", which collides with the global option " + n
                            + " — rename the command's option");
                }
            }
        }
        opts.addAll(GlobalOptions.globalOpts());
        return new Command() {
            @Override
            public String name() {
                return cmd.name();
            }

            @Override
            public String description() {
                return cmd.description();
            }

            @Override
            public List<Opt> options() {
                return opts;
            }

            @Override
            public List<Param> parameters() {
                return cmd.parameters();
            }
        };
    }

    private static String renderHelp(CliCommand cmd, String qualified, boolean ansi) {
        List<OptionModel> globals = new ArrayList<>();
        for (Opt g : GlobalOptions.globalOpts()) {
            if (!g.hidden()) globals.add(CommandModels.option(g));
        }
        CommandModel model = CommandModels.from(cmd, qualified, globals);
        return HelpRenderer.renderHelp(model, ansi);
    }

    private static void printWorkerJarError(PluginJarNotFoundException e, boolean ansi) {
        Theme t = Theme.active();
        String label = HelpRenderer.paint(Glyphs.CROSS + " Error:", t.errorLabel(), ansi);
        String jar = HelpRenderer.paint(e.artifactId() + ".jar", t.warning(), ansi);
        String coord = HelpRenderer.paint(e.coordinate(), t.cyan(), ansi);
        String jk = ansi ? Ansi.sgr(t.helpHint()) + "jk" + Ansi.RESET : "jk";
        CliOutput.err(label + " " + jar + " not found.");
        CliOutput.err("  Coordinate: " + coord);
        for (Path p : e.pathsChecked()) {
            CliOutput.err("  Checked:    " + HelpRenderer.paint(p.toString(), t.path(), ansi));
        }
        CliOutput.err("  Reinstall " + jk + " or set -D" + e.jarProperty() + "=<path>");
    }

    // Parse and usage errors print before the envelope opens (dispatch has not begun a command
    // yet), so they write the real streams directly. Everything after beginCommand goes through
    // CliOutput.
    private static void printError(String qualified, CliCommand cmd, ParseException e, boolean ansi) {
        String label = HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi);
        String message =
                switch (e.kind()) {
                    case UNKNOWN_OPTION ->
                        "unrecognized option '"
                                + HelpRenderer.paint(e.token(), Theme.active().highlight(), ansi)
                                + "'";
                    case MISSING_REQUIRED ->
                        "missing required argument "
                                + HelpRenderer.paint(e.token(), Theme.active().highlight(), ansi);
                    default -> e.getMessage();
                };
        System.err.println(label + " " + message);
        System.err.println();
        System.err.println(usageLine(cmd, qualified, ansi));
        System.err.println();
        System.err.println(helpHint(ansi));
    }

    private static void printAmbiguousCommand(String command, List<String> candidates, boolean ansi) {
        System.err.println(HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi)
                + " '"
                + HelpRenderer.paint(command, Theme.active().highlight(), ansi)
                + "' is ambiguous: "
                + String.join(", ", candidates));
        System.err.println();
        System.err.println(helpHint(ansi));
    }

    private static void printAmbiguousSubcommand(
            CliCommand parent, String qualified, String sub, List<String> candidates, boolean ansi) {
        System.err.println(HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi)
                + " subcommand '"
                + HelpRenderer.paint(sub, Theme.active().highlight(), ansi)
                + "' is ambiguous: "
                + String.join(", ", candidates));
        System.err.println();
        System.err.println(usageLine(parent, qualified, ansi));
        System.err.println();
        System.err.println(helpHint(ansi));
    }

    private static void printUnknownSubcommand(CliCommand parent, String qualified, String sub, boolean ansi) {
        System.err.println(HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi)
                + " unrecognized subcommand '"
                + HelpRenderer.paint(sub, Theme.active().highlight(), ansi)
                + "'");
        System.err.println();
        System.err.println(usageLine(parent, qualified, ansi));
        System.err.println();
        System.err.println(helpHint(ansi));
    }

    private static String usageLine(CliCommand cmd, String qualified, boolean ansi) {
        StringBuilder suffix = new StringBuilder();
        if (cmd.isLeaf()) {
            for (Param p : cmd.parameters()) {
                if (p.hidden()) continue;
                suffix.append(" ").append(p.arity().required() ? "<" + p.name() + ">" : "[" + p.name() + "]");
            }
        } else {
            suffix.append(" <COMMAND>");
        }
        suffix.append(" [OPTIONS]");
        if (ansi) {
            return HelpRenderer.paint("Usage:", Theme.active().sectionHeading(), true)
                    + " "
                    + HelpRenderer.paint(qualified, Theme.active().commandName(), true)
                    + HelpRenderer.paint(suffix.toString(), Theme.active().paramLabel(), true);
        }
        return "Usage: " + qualified + suffix;
    }

    private static String helpHint(boolean ansi) {
        return ansi
                ? "For more information, try '" + Ansi.sgr(Theme.active().helpHint()) + "--help" + Ansi.RESET + "'"
                : "For more information, try '--help'";
    }

    /**
     * Index of the command — the first non-option token, skipping any leading global options (and the
     * value of a value-taking one, e.g. {@code -C dir}). Returns -1 when there's none (bare {@code
     * jk}, or {@code jk --help}).
     */
    static int commandIndex(List<String> args) {
        Map<String, Opt> valueGlobals = new LinkedHashMap<>();
        for (Opt o : GlobalOptions.globalOpts()) {
            if (o.takesValue()) {
                for (String n : o.allNames()) valueGlobals.put(n, o);
            }
        }
        int i = 0;
        while (i < args.size()) {
            String a = args.get(i);
            if (a.equals("--")) return i + 1 < args.size() ? i + 1 : -1;
            if (!a.startsWith("-") || a.equals("-")) return i; // first positional = command
            String name = a.contains("=") ? a.substring(0, a.indexOf('=')) : a;
            // A value-taking global before the command (exact or unique prefix, e.g. --di for
            // --dir) consumes the next token, so skip both to reach the command.
            boolean consumesNext = !a.contains("=")
                    && Abbreviations.resolve(name, valueGlobals).resolved();
            i += consumesNext ? 2 : 1;
        }
        return -1;
    }

    /**
     * True when the argument list requests help — {@code -h}/{@code --help} exactly, or a unique
     * {@code --} prefix of {@code --help} that isn't ambiguous with any of this command's other
     * options (e.g. {@code --hel}). An ambiguous prefix is left for the parser to reject.
     */
    private static boolean helpRequested(CliCommand cmd, List<String> args) {
        Map<String, Opt> byName = new LinkedHashMap<>();
        for (Opt o : withGlobals(cmd).options()) {
            for (String n : o.allNames()) byName.put(n, o);
        }
        Opt help = byName.get("--help");
        for (String tok : args) {
            if (tok.equals("--")) break;
            if (tok.equals("-h") || tok.equals("--help")) return true;
            if (help != null && tok.startsWith("--")) {
                String name = tok.contains("=") ? tok.substring(0, tok.indexOf('=')) : tok;
                Abbreviations.Result<Opt> r = Abbreviations.resolve(name, byName);
                if (r.resolved() && r.value() == help) return true;
            }
        }
        return false;
    }

    static boolean ansiEnabled() {
        JkConfig.ColorChoice choice = SessionContext.current().config().colorOr(JkConfig.ColorChoice.AUTO);
        return switch (choice) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> {
                String nc = System.getenv("NO_COLOR");
                yield nc == null || nc.isEmpty();
            }
        };
    }
}

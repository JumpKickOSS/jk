// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Answers;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.cli.tui.Wizard;
import cc.jumpkick.cli.tui.WizardStep;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.terminal.TerminalSession;
import cc.jumpkick.terminal.Terminals;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@code jk activate [<shell>]} — print the full shell integration script (PATH + hooks +
 * completions) or install a one-line marker-bounded rc block in every discovered profile.
 *
 * <p>The rc block names one home, so only the default home ({@code $HOME/.jk}) may claim it
 * unasked: for a {@code JK_HOME} anywhere else the installer form leaves the rc files alone and
 * prints the line that activates that install in the current shell, and {@code --rc} asks for the
 * block anyway. The same rule the installers apply.
 */
public final class ActivateCommand implements CliCommand {

    private final Supplier<List<CliCommand>> commands;

    public ActivateCommand(Supplier<List<CliCommand>> commands) {
        this.commands = commands;
    }

    private static final String SUPPORTED = "bash, zsh, fish, pwsh, powershell";

    @Override
    public String name() {
        return "activate";
    }

    @Override
    public String description() {
        return "Install shell integration or print hook script";
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "shell",
                Arity.ZERO_OR_ONE,
                "Target shell: " + SUPPORTED + ".\n"
                        + "With a name: print PATH + hooks + completions (for eval/source).\n"
                        + "Omit to install the rc marker block in every discovered profile."));
    }

    @Override
    public List<Opt> options() {
        // Global -y/--yes skips the interactive installer prompt (see Confirm / GlobalOptions).
        // Only the default home ($HOME/.jk) claims the rc block unasked; --rc asks for it elsewhere.
        return List.of(Opt.flag("Write the rc block for a non-default JK_HOME too", "--rc"));
    }

    /** True for {@code jk activate <shell>}, false for the bare rc-block installer. */
    private static boolean printsScript(Invocation in) {
        return !in.positionals().isEmpty() && !in.positionals().getFirst().isBlank();
    }

    /** {@code eval "$(jk activate bash)"} feeds stdout straight to the shell. */
    @Override
    public boolean scriptMode(Invocation in) {
        return printsScript(in);
    }

    @Override
    public int run(Invocation in) throws IOException {
        if (printsScript(in)) {
            return printScript(in.positionals().getFirst());
        }
        return runInstaller(in.isSet("yes"), in.isSet("rc"));
    }

    private int printScript(String shellName) {
        var shell = Shell.byName(shellName);
        if (shell.isEmpty()) {
            CommandWedge.printFail("Activate", "unsupported shell `" + shellName + "` (supported: " + SUPPORTED + ")");
            return Exit.USAGE;
        }
        ensureJkxLauncher();
        // stdout is eval'd shell code — PATH ensure + hooks + completions; keep silent aside from that.
        CliOutput.outRaw(shell.get().fullActivateScript(resolveJkExe(), JkDirs.binDir(), JkDirs.store(), home()));
        return 0;
    }

    /**
     * The half of activation a profile block cannot do on Windows: the registry User PATH, which
     * is what makes {@code ~/.jk/bin} reachable from cmd.exe and GUI-launched processes too.
     * No-op everywhere else, and never fatal — see {@link WindowsUserPath}.
     */
    private static WindowsUserPath.Result ensureBinOnSystemPath() {
        return WindowsUserPath.ensure(JkDirs.binDir());
    }

    private static JkxLink.Result ensureJkxLauncher() {
        String exe = resolveJkExe();
        Path jkExe = exe.startsWith("/") || exe.contains(":\\") ? Path.of(exe) : null;
        return JkxLink.ensure(JkDirs.binDir(), jkExe);
    }

    private int runInstaller(boolean assumeYes, boolean rcRequested) throws IOException {
        List<Shell> targets = Shell.installTargets(home());
        if (!rcRequested && !isDefaultHome()) {
            return printPrivateHome(targets);
        }
        if (assumeYes) {
            return writeActivation(targets);
        }
        if (!isInteractiveTerminalSession()) {
            return printManualInstructions(targets);
        }
        return runWizard(targets);
    }

    private int writeActivation(List<Shell> targets) throws IOException {
        List<Shell> changed = new ArrayList<>();
        for (Shell shell : targets) {
            if (writeOne(shell)) {
                changed.add(shell);
            }
        }
        finishInstall();
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();
        Theme t = Theme.active();
        CommandWedge.envelopeStart();
        if (changed.isEmpty()) {
            CliOutput.out(JkWedge.chipLine(
                    Glyphs.CHECK,
                    "Activate",
                    nerdFont,
                    "Shell integration is already configured in " + coloredRcList(targets, t)));
            return 0;
        }
        CliOutput.out(JkWedge.chipLine(
                Glyphs.CHECK, "Activate", nerdFont, "Shell integration configured in " + coloredRcList(targets, t)));
        return 0;
    }

    /** True when the file was created or the block changed. */
    private boolean writeOne(Shell shell) throws IOException {
        Path rcFile = shell.rcFile(home());
        String block = ShellInstallerBlock.render(shell, JkDirs.binDir(), home());
        String previous = Files.exists(rcFile) ? Files.readString(rcFile, StandardCharsets.UTF_8) : "";
        String next = ShellInstallerBlock.upsert(previous, block);
        if (ShellInstallerBlock.present(previous) && previous.equals(next)) {
            return false;
        }
        if (rcFile.getParent() != null) {
            Files.createDirectories(rcFile.getParent());
        }
        Files.writeString(rcFile, next.endsWith("\n") ? next : next + "\n", StandardCharsets.UTF_8);
        return true;
    }

    /** True when the home jk runs from is {@code $HOME/.jk}, the one home an rc block may name unasked. */
    private static boolean isDefaultHome() {
        Path live = JkDirs.home().toAbsolutePath().normalize();
        Path dflt = home().resolve(JkDirs.HOME_DIR).toAbsolutePath().normalize();
        return live.equals(dflt);
    }

    /**
     * A private {@code JK_HOME}: completions and the {@code jkx} launcher land inside that home, the
     * rc files (and, on Windows, the User PATH) stay as they are, and the line that activates this
     * install in the current shell is printed for every target shell.
     */
    private int printPrivateHome(List<Shell> targets) throws IOException {
        ensureJkxLauncher();
        ShellCompletions.writeAll(commands.get());
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();
        Theme t = Theme.active();
        Path jkExe = JkDirs.binDir().toAbsolutePath().normalize().resolve("jk");
        CommandWedge.envelopeStart();
        CliOutput.out(JkWedge.chipLine(
                Glyphs.BANG,
                "Activate",
                nerdFont,
                "JK_HOME is " + Theme.colorize(JkDirs.home().toString(), t.path()) + ", not the default "
                        + Theme.colorize(home().resolve(JkDirs.HOME_DIR).toString(), t.path())
                        + ": the shell rc files are left alone."));
        CliOutput.out("  To use this install in the current shell, run:");
        for (Shell shell : targets) {
            String line = shell.activationLine(shell.commandExpr(jkExe, home()));
            CliOutput.out(
                    "    " + Theme.colorize(line, t.shell()) + "  " + Theme.colorize("# " + shell.name(), t.path()));
        }
        CliOutput.out("  To write the rc block for this home anyway: " + Theme.colorize("jk activate --rc", t.shell()));
        return 0;
    }

    private int printManualInstructions(List<Shell> targets) throws IOException {
        List<Shell> missing = new ArrayList<>();
        for (Shell shell : targets) {
            Path rcFile = shell.rcFile(home());
            if (Files.exists(rcFile) && ShellInstallerBlock.present(Files.readString(rcFile, StandardCharsets.UTF_8))) {
                continue;
            }
            missing.add(shell);
        }
        finishInstall();
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();
        Theme t = Theme.active();
        CommandWedge.envelopeStart();
        if (missing.isEmpty()) {
            CliOutput.out(JkWedge.chipLine(
                    Glyphs.CHECK,
                    "Activate",
                    nerdFont,
                    "Shell integration is already configured in " + coloredRcList(targets, t)));
            return 0;
        }
        CliOutput.out(JkWedge.chipLine(Glyphs.BANG, "Activate", nerdFont, "Add these blocks to finish activation:"));
        for (Shell shell : missing) {
            String block = ShellInstallerBlock.render(shell, JkDirs.binDir(), home());
            CliOutput.out("  " + Theme.colorize(shell.rcFileDisplay(), t.path()));
            for (String line : block.split("\n", -1)) {
                CliOutput.out("    " + Theme.colorize(line, t.shell()));
            }
        }
        return 0;
    }

    private int runWizard(List<Shell> targets) throws IOException {
        if (allConfigured(targets)) {
            finishInstall();
            Theme t = Theme.active();
            NerdFontCaps nerdFont = GlobalConfig.nerdFont();
            CommandWedge.envelopeStart();
            CliOutput.out(JkWedge.chipLine(
                    Glyphs.CHECK,
                    "Activate",
                    nerdFont,
                    "Shell integration is already configured in " + coloredRcList(targets, t)));
            return 0;
        }

        String rcList = targets.stream().map(Shell::rcFileDisplay).collect(Collectors.joining(", "));
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();
        Wizard wizard = Wizard.builder()
                .command("Activate")
                .subtitle("Shell integration")
                .step(WizardStep.RadioStep.horizontal("modify", "Allow Jk to modify " + rcList + "?")
                        .choice("yes", "Yes")
                        .choice("no", "No")
                        .defaultChoice("yes")
                        .build())
                .build();

        TerminalSession terminal = Terminals.controlling();
        Optional<Answers> result = wizard.run(terminal);
        if (result.isEmpty() || "no".equals(result.get().get("modify"))) {
            Theme t = Theme.active();
            CommandWedge.envelopeStart();
            CliOutput.out(JkWedge.chipLine(Glyphs.BANG, "Activate", nerdFont, "Skipped — add these blocks manually:"));
            for (Shell shell : targets) {
                String block = ShellInstallerBlock.render(shell, JkDirs.binDir(), home());
                CliOutput.out("  " + Theme.colorize(shell.rcFileDisplay(), t.path()));
                for (String line : block.split("\n", -1)) {
                    CliOutput.out("    " + Theme.colorize(line, t.shell()));
                }
            }
            return 0;
        }
        return writeActivation(targets);
    }

    private boolean allConfigured(List<Shell> targets) throws IOException {
        for (Shell shell : targets) {
            Path rcFile = shell.rcFile(home());
            if (!Files.exists(rcFile)) {
                return false;
            }
            if (!ShellInstallerBlock.present(Files.readString(rcFile, StandardCharsets.UTF_8))) {
                return false;
            }
        }
        return true;
    }

    private void finishInstall() throws IOException {
        ensureJkxLauncher();
        ensureBinOnSystemPath();
        ensureProfileExecutionPolicy();
        ShellCompletions.writeAll(commands.get());
    }

    /**
     * The other half of Windows activation a profile block cannot do: allow PowerShell to
     * <em>load</em> that profile. Default client policy is Restricted. Never fatal — see
     * {@link WindowsExecutionPolicy}.
     */
    private static WindowsExecutionPolicy.Result ensureProfileExecutionPolicy() {
        return WindowsExecutionPolicy.ensure();
    }

    private static String coloredRcList(List<Shell> shells, Theme t) {
        return shells.stream()
                .map(s -> Theme.colorize(s.rcFileDisplay(), t.path()))
                .collect(Collectors.joining(", "));
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    private static boolean isInteractiveTerminalSession() {
        return Interactivity.canPrompt();
    }

    /**
     * Path embedded in hook scripts as {@code __JK_EXE}. Prefer the stable platform bin
     * {@code jk} (do not {@code toRealPath}) so self-update is picked up without re-activate.
     */
    private static String resolveJkExe() {
        var envOverride = System.getenv("JK_EXE");
        if (envOverride != null && !envOverride.isBlank()) return envOverride;
        try {
            Path shim = JkDirs.binDir().resolve("jk");
            // On Windows the shim is a .cmd and never a symlink, so the old isExecutable arm paid the
            // 64x access check on every probe to learn what its extension already said.
            if (Files.isSymbolicLink(shim) || PathUtil.isRunnable(shim)) {
                return shim.toAbsolutePath().normalize().toString();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        try {
            var info = ProcessHandle.current().info();
            var cmd = info.command();
            if (cmd.isPresent()) {
                var path = Path.of(cmd.get());
                if (path.getFileName() != null && path.getFileName().toString().contains("jk"))
                    return path.toAbsolutePath().toString();
            }
        } catch (RuntimeException ignored) {
        }
        var argv0 = System.getProperty("sun.java.command");
        return argv0 != null && argv0.startsWith("/") ? argv0 : "jk";
    }
}

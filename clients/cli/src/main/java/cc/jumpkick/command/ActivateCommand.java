// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Answers;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.cli.tui.Wizard;
import cc.jumpkick.cli.tui.WizardStep;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jline.terminal.Terminal;

/**
 * {@code jk activate [<shell>]} — print the full shell integration script (PATH + hooks +
 * completions) or install a one-line marker-bounded rc block that evals it.
 */
public final class ActivateCommand implements CliCommand {

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
                "Target shell: bash, zsh, fish, pwsh.\n"
                        + "With a name: print PATH + hooks + completions (for eval/source).\n"
                        + "Omit to install the rc marker block."));
    }

    @Override
    public List<Opt> options() {
        // Global -y/--yes skips the interactive installer prompt (see Confirm / GlobalOptions).
        return List.of();
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
        return runInstaller(in.isSet("yes"));
    }

    private int printScript(String shellName) {
        var shell = Shell.byName(shellName);
        if (shell.isEmpty()) {
            CommandWedge.printFail(
                    "Activate", "unsupported shell `" + shellName + "` (supported: bash, zsh, fish, pwsh)");
            return Exit.USAGE;
        }
        ensureJkxLauncher();
        // stdout is eval'd shell code — PATH ensure + hooks + completions; keep silent aside from that.
        CliOutput.outRaw(shell.get().fullActivateScript(resolveJkExe(), JkDirs.binDir(), JkDirs.data(), home()));
        return 0;
    }

    private static JkxLink.Result ensureJkxLauncher() {
        String exe = resolveJkExe();
        Path jkExe = exe.startsWith("/") || exe.contains(":\\") ? Path.of(exe) : null;
        return JkxLink.ensure(JkDirs.binDir(), jkExe);
    }

    private int runInstaller(boolean assumeYes) throws IOException {
        var shell = Shell.detect();
        if (shell.isEmpty()) {
            CommandWedge.printFail(
                    "Activate",
                    "couldn't detect your shell from $SHELL (value: `" + System.getenv("SHELL")
                            + "`). Pass an explicit shell, e.g. `jk activate zsh`.");
            return Exit.USAGE;
        }
        if (assumeYes) {
            return writeActivation(shell.get());
        }
        if (!isInteractiveTerminal()) {
            return printManualInstructions(shell.get());
        }
        return runWizard(shell.get());
    }

    private int writeActivation(Shell shell) throws IOException {
        Path rcFile = shell.rcFile(home());
        String rcDisplay = shell.rcFileDisplay();
        String block = ShellInstallerBlock.render(shell, JkDirs.binDir(), home());
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();
        Theme t = Theme.active();

        String previous = Files.exists(rcFile) ? Files.readString(rcFile, StandardCharsets.UTF_8) : "";
        boolean hadBlock = ShellInstallerBlock.present(previous);
        String next = ShellInstallerBlock.upsert(previous, block);
        if (hadBlock && previous.equals(next)) {
            ensureJkxLauncher();
            ShellCompletions.writeAll();
            CommandWedge.envelopeStart();
            CliOutput.out(JkWedge.chipLine(
                    Glyphs.CHECK,
                    "Activate",
                    nerdFont,
                    "Shell integration is already configured in " + Theme.colorize(rcDisplay, t.path())));
            return 0;
        }

        if (rcFile.getParent() != null) Files.createDirectories(rcFile.getParent());
        Files.writeString(rcFile, next.endsWith("\n") ? next : next + "\n", StandardCharsets.UTF_8);
        ensureJkxLauncher();
        ShellCompletions.writeAll();
        CommandWedge.envelopeStart();
        CliOutput.out(JkWedge.chipLine(
                Glyphs.CHECK,
                "Activate",
                nerdFont,
                "Shell integration configured in " + Theme.colorize(rcDisplay, t.path())));
        return 0;
    }

    private int printManualInstructions(Shell shell) throws IOException {
        Path rcFile = shell.rcFile(home());
        String rcDisplay = shell.rcFileDisplay();
        String block = ShellInstallerBlock.render(shell, JkDirs.binDir(), home());
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();
        Theme t = Theme.active();

        if (Files.exists(rcFile)) {
            String existing = Files.readString(rcFile, StandardCharsets.UTF_8);
            if (ShellInstallerBlock.present(existing)) {
                ensureJkxLauncher();
                ShellCompletions.writeAll();
                CommandWedge.envelopeStart();
                CliOutput.out(JkWedge.chipLine(
                        Glyphs.CHECK,
                        "Activate",
                        nerdFont,
                        "Shell integration is already configured in " + Theme.colorize(rcDisplay, t.path())));
                return 0;
            }
        }

        ensureJkxLauncher();
        ShellCompletions.writeAll();
        CommandWedge.envelopeStart();
        CliOutput.out(JkWedge.chipLine(
                Glyphs.BANG,
                "Activate",
                nerdFont,
                "Add this block to " + Theme.colorize(rcDisplay, t.path()) + " to finish activation:"));
        for (String line : block.split("\n", -1)) {
            CliOutput.out("  " + Theme.colorize(line, t.shell()));
        }
        return 0;
    }

    private int runWizard(Shell shell) throws IOException {
        Path rcFile = shell.rcFile(home());
        String rcDisplay = shell.rcFileDisplay();
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();

        if (Files.exists(rcFile)) {
            String existing = Files.readString(rcFile, StandardCharsets.UTF_8);
            if (ShellInstallerBlock.present(existing)) {
                ensureJkxLauncher();
                ShellCompletions.writeAll();
                Theme t = Theme.active();
                CommandWedge.envelopeStart();
                CliOutput.out(JkWedge.chipLine(
                        Glyphs.CHECK,
                        "Activate",
                        nerdFont,
                        "Shell integration is already configured in " + Theme.colorize(rcDisplay, t.path())));
                return 0;
            }
        }

        Wizard wizard = Wizard.builder()
                .command("Activate")
                .subtitle("Shell integration")
                .step(WizardStep.RadioStep.horizontal("modify", "Allow Jk to modify your " + rcDisplay + " file?")
                        .choice("yes", "Yes")
                        .choice("no", "No")
                        .defaultChoice("yes")
                        .build())
                .build();

        Terminal terminal;
        try {
            terminal = Wizard.openTerminal();
        } catch (IOException e) {
            throw new IOException("failed to open terminal: " + e.getMessage(), e);
        }
        Optional<Answers> result;
        try (terminal) {
            result = wizard.run(terminal);
        }
        if (result.isEmpty() || "no".equals(result.get().get("modify"))) {
            Theme t = Theme.active();
            String block = ShellInstallerBlock.render(shell, JkDirs.binDir(), home());
            CommandWedge.envelopeStart();
            CliOutput.out(JkWedge.chipLine(
                    Glyphs.BANG,
                    "Activate",
                    nerdFont,
                    "Skipped — add this block to " + Theme.colorize(rcDisplay, t.path()) + " manually:"));
            for (String line : block.split("\n", -1)) {
                CliOutput.out("  " + Theme.colorize(line, t.shell()));
            }
            return 0;
        }
        return writeActivation(shell);
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    private static boolean isInteractiveTerminal() {
        return cc.jumpkick.cli.tui.Interactivity.canPrompt();
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
            if (Files.isSymbolicLink(shim) || Files.isExecutable(shim)) {
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

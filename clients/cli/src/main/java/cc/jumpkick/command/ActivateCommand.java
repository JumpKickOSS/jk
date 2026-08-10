// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Answers;
import cc.jumpkick.cli.tui.BuildPlanWedge;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Wizard;
import cc.jumpkick.cli.tui.WizardStep;
import cc.jumpkick.config.GlobalConfig;
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
 * {@code jk activate [<shell>]} — print hook scripts or install a marker-bounded rc block (PATH +
 * hook-env eval + completions).
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
                        + "With a name: print hook-env script (for eval/source).\n"
                        + "Omit to install the rc marker block."));
    }

    @Override
    public List<Opt> options() {
        // Global -y/--yes skips the interactive installer prompt (see Confirm / GlobalOptions).
        return List.of();
    }

    @Override
    public int run(Invocation in) throws IOException {
        String shellName = in.positionals().isEmpty() ? null : in.positionals().get(0);
        if (shellName != null && !shellName.isBlank()) {
            return printScript(shellName);
        }
        return runInstaller(in.isSet("yes"));
    }

    private int printScript(String shellName) {
        var shell = Shell.byName(shellName);
        if (shell.isEmpty()) {
            CliOutput.err(CommandWedge.fail(
                    "Activate", "unsupported shell `" + shellName + "` (supported: bash, zsh, fish, pwsh)"));
            return Exit.USAGE;
        }
        ensureJkxLauncher();
        // stdout is eval'd shell code — keep it silent aside from the script.
        CliOutput.outRaw(shell.get().activateScript(resolveJkExe()));
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
            CliOutput.err(CommandWedge.fail(
                    "Activate",
                    "couldn't detect your shell from $SHELL (value: `" + System.getenv("SHELL")
                            + "`). Pass an explicit shell, e.g. `jk activate zsh`."));
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
        Path binDir = JkDirs.binDir();
        Path dataDir = JkDirs.data();
        String block = ShellInstallerBlock.render(shell, binDir, dataDir);
        boolean nerdfont = GlobalConfig.nerdfont();
        Theme t = Theme.active();

        String previous = Files.exists(rcFile) ? Files.readString(rcFile, StandardCharsets.UTF_8) : "";
        boolean hadBlock = ShellInstallerBlock.present(previous);
        String next = ShellInstallerBlock.upsert(previous, block);
        if (hadBlock && previous.equals(next)) {
            ensureJkxLauncher();
            ShellCompletions.writeAll();
            CommandWedge.envelopeStart();
            CliOutput.out(BuildPlanWedge.chipLine(
                    Glyphs.CHECK,
                    "Activate",
                    nerdfont,
                    "Shell integration is already configured in " + Theme.colorize(rcDisplay, t.path())));
            return 0;
        }

        if (rcFile.getParent() != null) Files.createDirectories(rcFile.getParent());
        Files.writeString(rcFile, next.endsWith("\n") ? next : next + "\n", StandardCharsets.UTF_8);
        JkxLink.Result jkx = ensureJkxLauncher();
        ShellCompletions.writeAll();
        CommandWedge.envelopeStart();
        CliOutput.out(BuildPlanWedge.chipLine(
                Glyphs.CHECK,
                "Activate",
                nerdfont,
                "Shell integration configured in " + Theme.colorize(rcDisplay, t.path())));
        CliOutput.out("  PATH ← " + Theme.colorize(binDir.toString(), t.path()) + "  (real jk / jkx)");
        CliOutput.out("  hooks ← directory JAVA_HOME via hook-env");
        CliOutput.out("  completions ← "
                + Theme.colorize(dataDir.resolve("completions").toString(), t.path()));
        if (jkx.status() == JkxLink.Status.CREATED) {
            CliOutput.out("Installed " + Theme.colorize("jkx", t.shell()) + " → "
                    + Theme.colorize(jkx.path().toString(), t.path()));
        } else if (jkx.status() == JkxLink.Status.SKIPPED_FOREIGN) {
            CliOutput.out("Note: " + Theme.colorize(jkx.path().toString(), t.path())
                    + " exists but wasn't created by jk — left untouched.");
        }
        CliOutput.out("Open a new shell (or " + sourceHint(shell, rcDisplay, t) + ") to pick up the change.");
        return 0;
    }

    private int printManualInstructions(Shell shell) throws IOException {
        Path rcFile = shell.rcFile(home());
        String rcDisplay = shell.rcFileDisplay();
        String block = ShellInstallerBlock.render(shell, JkDirs.binDir(), JkDirs.data());
        boolean nerdfont = GlobalConfig.nerdfont();
        Theme t = Theme.active();

        if (Files.exists(rcFile)) {
            String existing = Files.readString(rcFile, StandardCharsets.UTF_8);
            if (ShellInstallerBlock.present(existing)) {
                ensureJkxLauncher();
                ShellCompletions.writeAll();
                CommandWedge.envelopeStart();
                CliOutput.out(BuildPlanWedge.chipLine(
                        Glyphs.CHECK,
                        "Activate",
                        nerdfont,
                        "Shell integration is already configured in " + Theme.colorize(rcDisplay, t.path())));
                return 0;
            }
        }

        ensureJkxLauncher();
        ShellCompletions.writeAll();
        CommandWedge.envelopeStart();
        CliOutput.out(BuildPlanWedge.chipLine(
                Glyphs.BANG,
                "Activate",
                nerdfont,
                "Add this block to " + Theme.colorize(rcDisplay, t.path()) + " to finish activation:"));
        for (String line : block.split("\n", -1)) {
            CliOutput.out("  " + Theme.colorize(line, t.shell()));
        }
        return 0;
    }

    private int runWizard(Shell shell) throws IOException {
        Path rcFile = shell.rcFile(home());
        String rcDisplay = shell.rcFileDisplay();
        boolean nerdfont = GlobalConfig.nerdfont();

        if (Files.exists(rcFile)) {
            String existing = Files.readString(rcFile, StandardCharsets.UTF_8);
            if (ShellInstallerBlock.present(existing)) {
                ensureJkxLauncher();
                ShellCompletions.writeAll();
                Theme t = Theme.active();
                CommandWedge.envelopeStart();
                CliOutput.out(BuildPlanWedge.chipLine(
                        Glyphs.CHECK,
                        "Activate",
                        nerdfont,
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
            String block = ShellInstallerBlock.render(shell, JkDirs.binDir(), JkDirs.data());
            CommandWedge.envelopeStart();
            CliOutput.out(BuildPlanWedge.chipLine(
                    Glyphs.BANG,
                    "Activate",
                    nerdfont,
                    "Skipped — add this block to " + Theme.colorize(rcDisplay, t.path()) + " manually:"));
            for (String line : block.split("\n", -1)) {
                CliOutput.out("  " + Theme.colorize(line, t.shell()));
            }
            return 0;
        }
        return writeActivation(shell);
    }

    private static String sourceHint(Shell shell, String rcDisplay, Theme t) {
        return switch (shell.name()) {
            case "fish" -> "run " + Theme.colorize("source " + rcDisplay, t.shell());
            case "pwsh" -> "dot-source " + Theme.colorize(rcDisplay, t.shell());
            default -> "run " + Theme.colorize("source " + rcDisplay, t.shell());
        };
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

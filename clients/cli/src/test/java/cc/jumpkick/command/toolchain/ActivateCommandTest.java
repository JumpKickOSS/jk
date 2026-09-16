// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk activate --yes} writes a marker-bounded installer block without the interactive wizard.
 */
@DisabledOnOs(OS.WINDOWS)
@Tag("integration")
class ActivateCommandTest {

    @Test
    void yes_writes_marker_block_without_prompt(@TempDir Path home) throws Exception {
        String prevHome = System.getProperty("user.home");
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setProperty("user.home", home.toString());
        // Isolate product dirs under the temp home (jk.env.* beats the suite's JK_HOME env), so
        // bin/completions land in the fixture tree and the rc block contracts paths to $HOME.
        System.setProperty("jk.env.JK_HOME", home.resolve(".jk").toString());
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("activate", "--yes");
        } finally {
            System.setProperty("user.home", prevHome);
            System.clearProperty("jk.env.JK_HOME");
            System.setOut(origOut);
            System.setErr(origErr);
        }

        assertThat(exit).isZero();
        List<Shell> targets = Shell.installTargets(home);
        assertThat(targets).isNotEmpty();
        for (Shell shell : targets) {
            Path rc = shell.rcFile(home);
            assertThat(rc).exists();
            String content = Files.readString(rc);
            assertThat(content).contains(ShellInstallerBlock.BEGIN);
            assertThat(content).contains(ShellInstallerBlock.END);
            assertThat(content).contains(ShellInstallerBlock.COMMENT);
            assertThat(content).contains("activate");
            assertThat(content).contains("$HOME");
        }
        assertThat(out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8))
                .contains("configured");

        // Idempotent second run.
        System.setProperty("user.home", home.toString());
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            assertThat(Jk.execute("activate", "--yes")).isZero();
        } finally {
            System.setProperty("user.home", prevHome);
            System.setOut(origOut);
        }
        Path first = targets.getFirst().rcFile(home);
        assertThat(Files.readString(first).split(ShellInstallerBlock.BEGIN, -1)).hasSize(2);
    }

    /**
     * The rc block names one home, so a {@code JK_HOME} other than {@code $HOME/.jk} is not written
     * into the user's shells unasked: the seeded rc files keep their bytes and the output carries
     * the activation line for that home's own bin.
     */
    @Test
    void private_home_leaves_rc_files_alone_and_prints_the_activation_line(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve(".zshrc"), "export EDITOR=vi\n");
        Files.writeString(home.resolve(".bashrc"), "alias ll='ls -l'\n");
        String printed = runActivate(home, home.resolve("scratch-jk"), "activate", "--yes");

        assertThat(Files.readString(home.resolve(".zshrc"))).isEqualTo("export EDITOR=vi\n");
        assertThat(Files.readString(home.resolve(".bashrc"))).isEqualTo("alias ll='ls -l'\n");
        assertThat(printed)
                .contains("the shell rc files are left alone")
                .contains("scratch-jk/bin/jk\" activate")
                .contains("jk activate --rc");
        assertThat(printed).doesNotContain("configured");
    }

    /** {@code --rc} asks for the block on a private home; the block then names that home's bin. */
    @Test
    void rc_flag_writes_the_block_for_a_private_home(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve(".zshrc"), "# preexisting zsh\n");
        String printed = runActivate(home, home.resolve("scratch-jk"), "activate", "--yes", "--rc");

        assertThat(printed).contains("configured");
        String zshrc = Files.readString(home.resolve(".zshrc"));
        assertThat(zshrc).contains(ShellInstallerBlock.BEGIN).contains("scratch-jk/bin/jk\" activate zsh");
    }

    /** Runs {@code jk} with {@code user.home} and {@code JK_HOME} bound to the fixture; returns stdout+stderr. */
    private static String runActivate(Path home, Path jkHome, String... args) {
        String prevHome = System.getProperty("user.home");
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setProperty("user.home", home.toString());
        System.setProperty("jk.env.JK_HOME", jkHome.toString());
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            assertThat(Jk.execute(args)).isZero();
        } finally {
            System.setProperty("user.home", prevHome);
            System.clearProperty("jk.env.JK_HOME");
            System.setOut(origOut);
            System.setErr(origErr);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void yes_also_writes_existing_extra_shell_rcs(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve(".zshrc"), "# preexisting zsh\n");
        String prevHome = System.getProperty("user.home");
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setProperty("user.home", home.toString());
        System.setProperty("jk.env.JK_HOME", home.resolve(".jk").toString());
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            assertThat(Jk.execute("activate", "--yes")).isZero();
        } finally {
            System.setProperty("user.home", prevHome);
            System.clearProperty("jk.env.JK_HOME");
            System.setOut(origOut);
            System.setErr(origErr);
        }
        assertThat(Files.readString(home.resolve(".zshrc"))).contains(ShellInstallerBlock.BEGIN);
        assertThat(Files.readString(home.resolve(".zshrc"))).contains("activate zsh");
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

        var shell = Shell.detect();
        if (shell.isEmpty()) {
            assertThat(exit).isEqualTo(64);
            return;
        }
        assertThat(exit).isZero();
        Path rc = shell.get().rcFile(home);
        assertThat(rc).exists();
        String content = Files.readString(rc);
        assertThat(content).contains(ShellInstallerBlock.BEGIN);
        assertThat(content).contains(ShellInstallerBlock.END);
        assertThat(content).contains(ShellInstallerBlock.COMMENT);
        assertThat(content).contains("activate");
        assertThat(content).contains("$HOME");
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
        assertThat(Files.readString(rc).split(ShellInstallerBlock.BEGIN, -1)).hasSize(2);
    }
}

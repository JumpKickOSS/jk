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
 * {@code jk activate --yes} must write the rc line without opening the interactive wizard (installers
 * hang otherwise waiting for a keypress).
 */
@DisabledOnOs(OS.WINDOWS)
@Tag("integration")
class ActivateCommandTest {

    @Test
    void yes_writes_rc_without_prompt(@TempDir Path home) throws Exception {
        String prevHome = System.getProperty("user.home");
        String prevShell = System.getenv("SHELL"); // may be null in some CI; we only need detectable
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setProperty("user.home", home.toString());
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            // Prefer zsh if $SHELL is unset so detect() still works in minimal envs.
            if (prevShell == null || prevShell.isBlank()) {
                // Can't set env easily; skip path if detect fails — assert non-zero then.
            }
            exit = Jk.execute("activate", "--yes");
        } finally {
            System.setProperty("user.home", prevHome);
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
        assertThat(content).contains("jk activate");
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
        // One install block: comment mentions `jk activate` and the eval line does too — not doubled.
        assertThat(Files.readString(rc).split("# Added by", -1)).hasSize(2);
    }
}

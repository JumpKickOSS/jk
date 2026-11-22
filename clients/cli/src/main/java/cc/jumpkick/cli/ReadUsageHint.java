// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.config.ReadUsage;
import cc.jumpkick.host.JkDirs;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * After a command at a terminal, say once on this machine that jk reads agents' token counts while
 * {@code [agents] read-usage} is on, and how to turn it off. Never prompts, never changes the exit
 * code, and stays silent for machine output.
 */
final class ReadUsageHint {

    private ReadUsageHint() {}

    static void afterCommand(@Nullable String command, List<String> args, int exit) {
        try {
            if (!SelfUpdatePrompt.eligible(command, args, exit) || !Interactivity.canPrompt()) return;
            show(JkDirs.userConfigFile(), JkDirs.state(), CliOutput.stderr());
        } catch (RuntimeException e) {
            // A hint must never fail a command.
        }
    }

    /** Print the notice to {@code err} when reading is on and no terminal has shown it; true if shown. */
    static boolean show(Path userConfig, Path stateDir, PrintStream err) {
        if (!ReadUsage.on(userConfig) || !ReadUsage.firstNotice(stateDir, ReadUsage.CLI)) return false;
        err.println(ReadUsage.NOTICE);
        err.println("    " + ReadUsage.OFF);
        return true;
    }
}

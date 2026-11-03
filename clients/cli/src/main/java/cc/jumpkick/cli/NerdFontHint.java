// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.config.GlobalConfig;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * After a command at a terminal, say once when the stored glyph confirmation no longer matches the
 * terminal. Never prompts, never changes the exit code, and stays silent for machine output.
 */
final class NerdFontHint {

    private NerdFontHint() {}

    static void afterCommand(@Nullable String command, List<String> args, int exit) {
        try {
            if (!SelfUpdatePrompt.eligible(command, args, exit) || !Interactivity.canPrompt()) return;
            GlobalConfig.nerdFontStaleHint().ifPresent(h -> CliOutput.stderr().println("jk: " + h));
        } catch (RuntimeException e) {
            // A hint must never fail a command.
        }
    }
}

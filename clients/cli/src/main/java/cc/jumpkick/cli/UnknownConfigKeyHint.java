// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.config.UserConfigKeys;
import cc.jumpkick.host.JkDirs;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * After a command, say once per key on this machine that {@code ~/.jk/config.toml} holds a key this
 * jk does not read. {@code jk doctor} and the dashboard keep listing it; this line is not repeated.
 * Never changes the exit code, and stays silent for machine output.
 */
final class UnknownConfigKeyHint {

    private UnknownConfigKeyHint() {}

    static void afterCommand(@Nullable String command, List<String> args, int exit) {
        try {
            if (!SelfUpdatePrompt.eligible(command, args, exit)) return;
            show(JkDirs.userConfigFile(), JkDirs.state(), CliOutput.stderr());
        } catch (RuntimeException e) {
            // A hint must never fail a command.
        }
    }

    /** Print one line per unknown key no earlier command announced; the number printed. */
    static int show(Path userConfig, Path stateDir, PrintStream err) {
        List<String> fresh = UserConfigKeys.firstSeen(userConfig, stateDir);
        for (String key : fresh) err.println("jk: " + UserConfigKeys.notice(key, userConfig));
        return fresh.size();
    }
}

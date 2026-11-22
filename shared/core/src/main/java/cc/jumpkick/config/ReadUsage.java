// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.tomlj.TomlTable;

/**
 * {@code [agents] read-usage}: whether jk reads an agent's own token counts from its session files
 * (Claude Code's and Codex's) for an episode's receipt. On unless the user-global file says {@code
 * false}; a missing or unreadable file, or a value that is not a boolean, leaves it on. Machine-scoped:
 * {@code ~/.jk/config.toml} only. Each surface tells the person once that it is on ({@link #firstNotice}).
 */
public final class ReadUsage {

    /** The once-only notice, its first line. */
    public static final String NOTICE = "jk reads token counts, numbers only, from Claude Code and Codex session files";

    /** The once-only notice, its second line: how to turn it off. */
    public static final String OFF = "turn it off with [agents] read-usage = false in ~/.jk/config.toml";

    /** The surface that shows the notice in the dashboard's bell. */
    public static final String WEB = "web";

    /** The surface that shows the notice after a command at a terminal. */
    public static final String CLI = "cli";

    private static final MachineConfig<Boolean> READ = MachineConfig.of(true);

    private ReadUsage() {}

    /** Whether reading is on, from the user-global file. */
    public static boolean on() {
        return on(JkDirs.userConfigFile());
    }

    /** As {@link #on()} but against an explicit config file. */
    public static boolean on(Path userConfig) {
        TomlTable agents = TomlValues.parse(userConfig)
                .map(root -> root.getTable("agents"))
                .orElse(null);
        return READ.layer(TomlValues.optBoolean(agents, "read-usage").orElse(null));
    }

    /**
     * True exactly once per {@code surface} on this machine, the first time it asks: the marker it
     * leaves under {@code stateDir} answers every later ask, from any process. False when the marker
     * cannot be written, so a read-only home never shows the notice on every command.
     */
    public static boolean firstNotice(Path stateDir, String surface) {
        try {
            Files.createDirectories(stateDir);
            Files.createFile(stateDir.resolve("read-usage-noticed-" + surface));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

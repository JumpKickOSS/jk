// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * {@code [agents] read-usage}: whether jk reads an agent's own token counts from its session files
 * (Claude Code's, Codex's, Grok's, Copilot CLI's, Muse's and Antigravity's) for an episode's receipt. On unless
 * {@link #ENV} or the user-global file says {@code false}, the environment first; a missing or unreadable
 * file, or a value that is not a boolean, leaves the layer below in charge. Machine-scoped: {@code
 * ~/.jk/config.toml} only. The dashboard's bell tells the person once that it is on ({@link #firstNotice}).
 */
public final class ReadUsage {

    /** The once-only notice, its first line. */
    public static final String NOTICE = "jk reads token counts, numbers only, from coding agents' session files";

    /** The once-only notice, its second line: how to turn it off. */
    public static final String OFF = "turn it off with [agents] read-usage = false in ~/.jk/config.toml";

    /** The environment variable that overrides the file: {@code true} or {@code false}. */
    public static final String ENV = "JK_AGENTS_READ_USAGE";

    /** The {@code [agents]} table and its key. */
    public static final UserConfigTable AGENTS = UserConfigTable.of("agents");

    private static final UserConfigTable.Key KEY = AGENTS.key("read-usage");

    private static final MachineConfig<Boolean> READ = MachineConfig.of(true);

    private ReadUsage() {}

    /** Whether reading is on, from {@link #ENV} and the user-global file. */
    public static boolean on() {
        return on(JkDirs.userConfigFile());
    }

    /** As {@link #on()} but against an explicit config file. */
    public static boolean on(Path userConfig) {
        return on(userConfig, JkDirs::env);
    }

    /** As {@link #on()} but against an explicit config file and environment. */
    public static boolean on(Path userConfig, Function<String, @Nullable String> env) {
        TomlTable agents = TomlValues.parse(userConfig).map(AGENTS::in).orElse(null);
        return READ.layer(
                EnvValues.bool(env, ENV).orElse(null),
                TomlValues.optBoolean(agents, KEY.name()).orElse(null));
    }

    /**
     * True exactly once on this machine, the first time it is asked: the marker it
     * leaves under {@code stateDir} answers every later ask, from any process. False when the marker
     * cannot be written, so a read-only home never shows the notice on every command. The marker
     * names this wording of the notice, so a machine that saw an earlier wording sees this one once.
     */
    public static boolean firstNotice(Path stateDir) {
        try {
            Files.createDirectories(stateDir);
            Files.createFile(stateDir.resolve("read-usage-agents-noticed"));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

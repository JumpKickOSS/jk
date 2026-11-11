// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.ProcessEnv;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * {@code [ui] ask} / {@code JK_UI_ASK}: what jk does when a decision waits for a person and the
 * agent's host can ask neither by form nor by link. A missing or unreadable file, or a value that is
 * none of the three, means {@link #NOTIFY}.
 */
public enum UiAsk {
    /** A desktop notification carrying the decision's dashboard link. */
    NOTIFY,
    /** Open the browser on the decision's dashboard link. */
    OPEN,
    /** The link in the tool result only. */
    QUIET;

    private static final MachineConfig<UiAsk> ASK = MachineConfig.of(NOTIFY);

    /** The spelling in {@code config.toml} and {@code JK_UI_ASK}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code notify}, {@code open} or {@code quiet}, any case; empty for anything else. */
    public static Optional<UiAsk> parse(@Nullable String value) {
        if (value == null) return Optional.empty();
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "notify" -> Optional.of(NOTIFY);
            case "open" -> Optional.of(OPEN);
            case "quiet" -> Optional.of(QUIET);
            default -> Optional.empty();
        };
    }

    /** Effective machine value: {@code JK_UI_ASK} over the user-global file over {@link #NOTIFY}. */
    public static UiAsk resolve() {
        return resolve(JkDirs.userConfigFile(), ProcessEnv::get);
    }

    /** As {@link #resolve()} but against an explicit config file and env. */
    public static UiAsk resolve(Path userConfig, Function<String, @Nullable String> env) {
        TomlTable ui =
                TomlValues.parse(userConfig).map(root -> root.getTable("ui")).orElse(null);
        UiAsk fromEnv = EnvValues.string(env, "JK_UI_ASK").flatMap(UiAsk::parse).orElse(null);
        UiAsk fromFile = TomlValues.optString(ui, "ask").flatMap(UiAsk::parse).orElse(null);
        return ASK.layer(fromEnv, fromFile);
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.config.TomlValues;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The answer a machine gives the setup decision in advance, {@code [windows] fix} in the user config:
 * {@code ask} (the default) puts the decision to the person, {@code apply} takes every recommended
 * change without asking (Windows still shows its one prompt), and {@code never} records a decline.
 * An unknown value reads as {@code ask}.
 */
public enum SetupConsent {
    ASK,
    APPLY,
    NEVER;

    /** The consent {@code userConfig} gives; {@link #ASK} when the file or key is missing. */
    public static SetupConsent read(Path userConfig) {
        String value = TomlValues.parse(userConfig)
                .map(root -> root.getTable("windows"))
                .flatMap(windows -> TomlValues.optString(windows, "fix"))
                .orElse("");
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "apply" -> APPLY;
            case "never" -> NEVER;
            default -> ASK;
        };
    }

    /** One line naming where the answer came from, for a surface that does not ask. */
    public String settledBy() {
        return "[windows] fix = " + name().toLowerCase(Locale.ROOT) + " in config.toml";
    }
}

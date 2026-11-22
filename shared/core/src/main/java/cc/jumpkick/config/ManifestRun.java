// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.TomlTableReader.optionalString;
import static cc.jumpkick.config.TomlTableReader.optionalTable;
import static cc.jumpkick.config.TomlTableReader.rejectUnknownKeys;

import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/** {@code [run]}: what jk knows about the application it runs beyond its command. */
@NullMarked
public final class ManifestRun {

    private ManifestRun() {}

    public static final String TABLE = "run";

    /** The keys {@code [run]} may carry; the schema names exactly these. */
    public static final List<String> KEYS = List.of("log");

    /** {@code [run] log}: a file the app writes its log to, relative to the module; null when unset. */
    static @Nullable String parseLog(TomlTable root) {
        TomlTable run = optionalTable(root, TABLE, "[run]");
        if (run == null) return null;
        rejectUnknownKeys(run, KEYS, "[run]");
        String log = optionalString(run, "log", "[run] log");
        if (log != null && log.isBlank()) throw new JkBuildParseException("[run] log is empty; name a file");
        return log;
    }
}

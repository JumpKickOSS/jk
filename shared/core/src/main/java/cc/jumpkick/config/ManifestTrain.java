// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.TomlTableReader.optionalArray;
import static cc.jumpkick.config.TomlTableReader.optionalBool;
import static cc.jumpkick.config.TomlTableReader.optionalString;
import static cc.jumpkick.config.TomlTableReader.optionalTable;
import static cc.jumpkick.config.TomlTableReader.rejectUnknownKeys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The {@code [train]} table of a {@code jk.toml} → a {@link TrainConfig}. A table parser, not a
 * reader: the document comes from {@link JkBuildParser#trainConfig(java.nio.file.Path)}, which owns
 * the disk read, the syntax-error message and {@link Interpolation#guard}.
 */
final class ManifestTrain {

    static final List<String> KEYS = List.of("command", "commit-to", "require-fresh", "aot-cache", "profile");

    static final List<String> PROFILE_KEYS = List.of("name", "env", "properties", "args");

    private ManifestTrain() {}

    static TrainConfig parse(TomlTable root, String displayPath) {
        TomlTable train = optionalTable(root, "train", "`train`");
        if (train == null) return TrainConfig.EMPTY;
        rejectUnknownKeys(train, KEYS, "[train]");

        String command = optionalString(train, "command", "[train] command");
        String commitTo = optionalString(train, "commit-to", "[train] commit-to");
        boolean requireFresh = Boolean.TRUE.equals(optionalBool(train, "require-fresh", "[train] require-fresh"));
        boolean aotCache = Boolean.TRUE.equals(optionalBool(train, "aot-cache", "[train] aot-cache"));

        List<TrainConfig.Profile> profiles = new ArrayList<>();
        TomlArray arr = optionalArray(train, "profile", "[train] profile");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                if (!(arr.get(i) instanceof TomlTable t)) {
                    throw new JkBuildParseException(displayPath + ".train.profile[" + i + "] must be a table");
                }
                String where = displayPath + ".train.profile[" + i + "]";
                rejectUnknownKeys(t, PROFILE_KEYS, where);
                String name = optionalString(t, "name", where + " name");
                if (name == null || name.isBlank()) {
                    throw new JkBuildParseException(where + " requires name = \"…\"");
                }
                profiles.add(new TrainConfig.Profile(
                        name,
                        stringMap(optionalTable(t, "env", where + " env")),
                        stringMap(optionalTable(t, "properties", where + " properties")),
                        JkBuildParser.optionalStringList(t, "args", where + " args")));
            }
        }
        return new TrainConfig(command, commitTo, requireFresh, aotCache, profiles);
    }

    private static Map<String, String> stringMap(@Nullable TomlTable table) {
        if (table == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : table.keySet()) {
            Object v = table.get(List.of(key));
            if (v != null) out.put(key, String.valueOf(v));
        }
        return out;
    }
}

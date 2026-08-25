// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The {@code [train]} table of a {@code jk.toml} → a {@link TrainConfig}. A table parser, not a
 * reader: the document comes from {@link JkBuildParser#trainConfig(java.nio.file.Path)}, which owns
 * the disk read, the syntax-error message and {@link Interpolation#guard}.
 */
final class ManifestTrain {

    private ManifestTrain() {}

    static TrainConfig parse(TomlTable root, String displayPath) {
        TomlTable train = root.getTable("train");
        if (train == null) return TrainConfig.EMPTY;

        String command = train.getString("command");
        String commitTo = train.getString("commit-to");
        boolean requireFresh = Boolean.TRUE.equals(train.getBoolean("require-fresh"));
        boolean aotCache = Boolean.TRUE.equals(train.getBoolean("aot-cache"));

        List<TrainConfig.Profile> profiles = new ArrayList<>();
        TomlArray arr = train.getArray("profile");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                TomlTable t = arr.getTable(i);
                if (t == null) {
                    throw new JkBuildParseException(displayPath + ".train.profile[" + i + "] must be a table");
                }
                String name = t.getString("name");
                if (name == null || name.isBlank()) {
                    throw new JkBuildParseException(displayPath + ".train.profile[" + i + "] requires name = \"…\"");
                }
                profiles.add(new TrainConfig.Profile(
                        name,
                        stringMap(t.getTable("env")),
                        stringMap(t.getTable("properties")),
                        stringList(t, "args")));
            }
        }
        return new TrainConfig(command, commitTo, requireFresh, aotCache, profiles);
    }

    private static Map<String, String> stringMap(TomlTable table) {
        if (table == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : table.keySet()) {
            Object v = table.get(key);
            if (v != null) out.put(key, String.valueOf(v));
        }
        return out;
    }

    private static List<String> stringList(TomlTable table, String key) {
        TomlArray arr = table.getArray(key);
        if (arr == null) return List.of();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            String s = arr.getString(i);
            if (s != null) out.add(s);
        }
        return out;
    }
}

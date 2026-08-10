// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** Parses {@code [train]} from a {@code jk.toml} (or any TOML with that table). */
public final class TrainConfigParser {

    private TrainConfigParser() {}

    public static TrainConfig parse(Path jkToml) throws IOException {
        if (jkToml == null || !Files.isRegularFile(jkToml)) return TrainConfig.EMPTY;
        return parse(Files.readString(jkToml), jkToml.toString());
    }

    public static TrainConfig parse(String toml, String displayPath) {
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new JkBuildParseException(displayPath + " has invalid TOML: "
                    + result.errors().getFirst().getMessage());
        }
        TomlTable train = result.getTable("train");
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

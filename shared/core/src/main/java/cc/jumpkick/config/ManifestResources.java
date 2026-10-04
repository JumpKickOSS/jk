// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.BuildBlock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.TomlTable;

/** The {@code [resources]} table: extra and filtered resource roots, and the values filtering uses. */
final class ManifestResources {

    static final String TABLE = "resources";

    static final List<String> KEYS = List.of("dirs", "filtered", "test-dirs", "test-filtered", "properties");

    private ManifestResources() {}

    static BuildBlock.Resources parse(TomlTable root) {
        if (root.contains(TABLE) && !root.isTable(TABLE)) {
            throw new JkBuildParseException("`resources` must be a table — use [resources]");
        }
        TomlTable table = root.getTable(TABLE);
        if (table == null) return BuildBlock.Resources.EMPTY;
        for (String key : table.keySet()) {
            if (!KEYS.contains(key)) {
                throw new JkBuildParseException(
                        "[resources] unknown key `" + key + "` — expected one of: " + String.join(", ", KEYS));
            }
        }
        Map<String, String> properties = new LinkedHashMap<>();
        if (table.contains("properties")) {
            TomlTable props = table.getTable("properties");
            if (props == null) {
                throw new JkBuildParseException("[resources] properties must be a table of name = \"value\"");
            }
            collect(props, "", properties);
        }
        return new BuildBlock.Resources(
                dirs(table, "dirs"),
                dirs(table, "filtered"),
                dirs(table, "test-dirs"),
                dirs(table, "test-filtered"),
                properties);
    }

    /** Quoted ({@code "a.b" = …}) and dotted ({@code a.b = …}) names alike, by their full name. */
    private static void collect(TomlTable table, String prefix, Map<String, String> out) {
        for (String key : table.keySet()) {
            Object value = table.get(List.of(key));
            String name = prefix + key;
            if (value instanceof TomlTable nested) {
                collect(nested, name + ".", out);
            } else if (value instanceof String s) {
                out.put(name, s);
            } else {
                throw new JkBuildParseException("[resources.properties] `" + name + "` must be a string");
            }
        }
    }

    private static List<String> dirs(TomlTable table, String key) {
        List<String> dirs = JkBuildParser.optionalStringList(table, key, "resources." + key);
        for (String dir : dirs) {
            if (dir.isBlank() || dir.startsWith("/") || dir.contains("..")) {
                throw new JkBuildParseException("[resources] " + key
                        + " entries must be directories inside the module, such as src/filter/resources");
            }
        }
        return dirs;
    }
}

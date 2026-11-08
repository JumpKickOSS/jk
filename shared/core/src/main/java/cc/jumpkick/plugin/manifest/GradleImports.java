// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.JkBuildParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** The {@code [[import.gradle-plugin]]} rules a manifest declares, parsed at load. */
final class GradleImports {

    private GradleImports() {}

    static List<PluginDescriptor.GradleImport> parse(TomlParseResult result, String displayPath) {
        TomlTable importTable = result.getTable("import");
        if (importTable == null || !importTable.contains("gradle-plugin")) return List.of();
        String where = displayPath + ".import.gradle-plugin";
        TomlArray array = importTable.getArray("gradle-plugin");
        if (array == null) throw new JkBuildParseException(where + " must be an array of tables");
        List<PluginDescriptor.GradleImport> rules = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (!(array.get(i) instanceof TomlTable t))
                throw new JkBuildParseException(where + " entries must be tables");
            String id = t.getString("id");
            if (id == null || id.isBlank()) throw new JkBuildParseException(where + ".id is required");
            String extension = t.getString("extension");
            String task = t.getString("task");
            if (extension != null && task != null) {
                throw new JkBuildParseException(where + " " + id + ": set extension or task, not both");
            }
            Map<String, String> properties = strings(t, "properties", where);
            if (!properties.isEmpty() && extension == null && task == null) {
                throw new JkBuildParseException(where + " " + id + ": properties need an extension or task");
            }
            rules.add(new PluginDescriptor.GradleImport(
                    id,
                    t.getString("version-to"),
                    t.getString("missing-version-warning"),
                    t.getString("table"),
                    extension,
                    task,
                    properties,
                    strings(t, "files", where)));
        }
        return rules;
    }

    private static Map<String, String> strings(TomlTable t, String key, String where) {
        if (!t.contains(key)) return Map.of();
        TomlTable table = t.getTable(key);
        if (table == null) throw new JkBuildParseException(where + "." + key + " must be an inline table");
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : table.keySet()) {
            if (!(table.get(List.of(k)) instanceof String value)) {
                throw new JkBuildParseException(where + "." + key + "." + k + " must be a string");
            }
            out.put(k, value);
        }
        return out;
    }
}

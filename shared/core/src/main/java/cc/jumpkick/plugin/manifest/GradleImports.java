// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.JkBuildParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * The {@code [[import.gradle-plugin]]} rules a manifest declares, parsed at load, and those a build
 * or user keeps in an {@code import.toml} in the same grammar.
 */
public final class GradleImports {

    private static final Set<String> CREDENTIAL_KEYS = Set.of("username", "password");

    private GradleImports() {}

    /**
     * The rules in an {@code import.toml}; empty when the file does not exist. Each must name its
     * {@code table}: the file belongs to no plugin.
     */
    public static List<PluginDescriptor.GradleImport> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        String display = file.toString();
        TomlParseResult result = Toml.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (result.hasErrors()) {
            throw new JkBuildParseException(
                    display + ": " + result.errors().getFirst().toString());
        }
        List<PluginDescriptor.GradleImport> rules = parse(result, display);
        for (PluginDescriptor.GradleImport rule : rules) {
            if (rule.table() == null) {
                throw new JkBuildParseException(display + " " + rule.id() + ": set the table it writes");
            }
        }
        return rules;
    }

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
            Map<String, String> credentials = strings(t, "credentials", where);
            if ((!properties.isEmpty() || !credentials.isEmpty()) && extension == null && task == null) {
                throw new JkBuildParseException(where + " " + id + ": properties need an extension or task");
            }
            for (String key : credentials.keySet()) {
                if (!CREDENTIAL_KEYS.contains(key)) {
                    throw new JkBuildParseException(where + " " + id + ": credentials take username, password");
                }
            }
            rules.add(new PluginDescriptor.GradleImport(
                    id,
                    t.getString("version-to"),
                    t.getString("missing-version-warning"),
                    t.getString("table"),
                    extension,
                    task,
                    properties,
                    strings(t, "files", where),
                    credentials));
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

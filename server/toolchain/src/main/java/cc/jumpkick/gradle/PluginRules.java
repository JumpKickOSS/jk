// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.util.MinimalYaml;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The installed {@code [[import.gradle-plugin]]} rules and the tables they write: a Gradle plugin's
 * version, the extension or task properties the init script read for the rule, and keys of files
 * the plugin reads, each under the table key the rule names. Values are converted to the key's
 * schema type and a value equal to the schema default is left out.
 */
final class PluginRules {

    /** One installed rule: the manifest declaring it and the rule itself. */
    record Rule(String manifestId, PluginDescriptor.GradleImport spec) {

        /** The table the rule writes: its own {@code table}, else its manifest's. */
        @Nullable
        PluginDescriptor target() {
            String table = spec.table();
            if (table != null) return PluginTableRegistry.byTable(table).orElse(null);
            for (PluginDescriptor d : PluginTableRegistry.manifests()) {
                if (d.id().equals(manifestId)) return d;
            }
            return null;
        }
    }

    private PluginRules() {}

    /** Gradle plugin id → its rule, from every installed manifest. */
    static Map<String, Rule> installed() {
        Map<String, Rule> rules = new LinkedHashMap<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifests()) {
            for (PluginDescriptor.GradleImport rule : manifest.gradleImports()) {
                rules.put(rule.id(), new Rule(manifest.id(), rule));
            }
        }
        return rules;
    }

    /**
     * What the init script reads for the rules: per rule naming an extension or task, the plugin
     * id, the object and its property names, as JSON.
     */
    static String readsJson(Map<String, Rule> rules) {
        List<Map<String, Object>> reads = new ArrayList<>();
        for (Rule rule : rules.values()) {
            PluginDescriptor.GradleImport spec = rule.spec();
            if (spec.properties().isEmpty()) continue;
            Map<String, Object> read = new LinkedHashMap<>();
            read.put("id", spec.id());
            if (spec.extension() != null) read.put("extension", spec.extension());
            if (spec.task() != null) read.put("task", spec.task());
            read.put("properties", List.copyOf(spec.properties().keySet()));
            reads.add(read);
        }
        return MiniJson.write(reads);
    }

    /**
     * The tables the rules of the applied plugins write. {@code read} holds, per plugin id, the
     * properties the init script read; {@code moduleDir} is where a rule's files are, or null when
     * the import has no files to read.
     */
    static List<PluginConfig> map(
            Set<String> applied,
            Map<String, String> versions,
            Map<String, Map<String, Object>> read,
            @Nullable Path moduleDir,
            Map<String, Rule> rules,
            ImportReport.Builder report) {
        Map<String, Map<String, Object>> tables = new LinkedHashMap<>();
        for (Map.Entry<String, Rule> e : rules.entrySet()) {
            if (!applied.contains(e.getKey())) continue;
            PluginDescriptor.GradleImport spec = e.getValue().spec();
            Map<String, Object> raw = new LinkedHashMap<>();
            String versionTo = spec.versionTo();
            if (versionTo != null) {
                String version = versions.get(spec.id());
                if (version != null) raw.put(versionTo, version);
                else if (spec.missingVersionWarning() != null) report.warning(spec.missingVersionWarning());
            }
            Map<String, Object> props = read.getOrDefault(spec.id(), Map.of());
            spec.properties().forEach((property, key) -> {
                Object value = props.get(property);
                if (value != null) raw.put(key, value);
            });
            if (moduleDir != null) {
                spec.files().forEach((file, key) -> {
                    Object value = fileValue(moduleDir, file, spec.id(), report);
                    if (value != null) raw.put(key, value);
                });
            }
            if (raw.isEmpty() && spec.versionTo() == null) continue;
            PluginDescriptor target = e.getValue().target();
            if (target == null) {
                report.warning(spec.id() + ": no installed plugin owns [" + spec.table() + "]");
                continue;
            }
            Map<String, Object> table = tables.computeIfAbsent(target.id(), k -> new LinkedHashMap<>());
            raw.forEach((key, value) -> {
                PluginDescriptor.SchemaKey schema = target.schema().get(key);
                if (schema == null) {
                    report.warning(spec.id() + ": [" + target.table() + "] has no key " + key);
                    return;
                }
                Object typed = typed(value, schema.type());
                if (typed != null && !typed.equals(schema.defaultValue())) table.put(key, typed);
            });
        }
        List<PluginConfig> out = new ArrayList<>();
        tables.forEach((id, values) -> {
            if (!values.isEmpty()) out.add(new PluginConfig(id, values));
        });
        return out;
    }

    /**
     * The value at {@code file}: a module-relative path, with {@code #dotted.key} selecting a value
     * inside a YAML, JSON or properties file; the whole file's keys when it selects none.
     */
    static @Nullable Object fileValue(Path moduleDir, String file, String id, ImportReport.Builder report) {
        int hash = file.indexOf('#');
        String path = hash < 0 ? file : file.substring(0, hash);
        String select = hash < 0 ? "" : file.substring(hash + 1);
        Path at = moduleDir.resolve(path);
        if (!Files.isRegularFile(at)) return null;
        String text;
        try {
            text = Files.readString(at, StandardCharsets.UTF_8);
        } catch (IOException e) {
            report.warning(id + ": cannot read " + path);
            return null;
        }
        String name = path.toLowerCase(Locale.ROOT);
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            Map<String, String> keys = MinimalYaml.keysAt(text, select);
            if (keys == null) report.warning(id + ": " + path + " has no " + select);
            return keys;
        }
        if (name.endsWith(".properties")) {
            Properties props = new Properties();
            try {
                props.load(new StringReader(text));
            } catch (IOException e) {
                report.warning(id + ": cannot read " + path);
                return null;
            }
            if (!select.isEmpty()) return props.getProperty(select);
            Map<String, String> all = new LinkedHashMap<>();
            for (String k : props.stringPropertyNames()) all.put(k, props.getProperty(k));
            return all;
        }
        if (name.endsWith(".json")) {
            Object node = MiniJson.parse(text);
            if (!select.isEmpty()) {
                for (String segment : select.split("\\.")) node = MiniJson.get(node, segment);
            }
            return plain(node);
        }
        report.warning(id + ": " + path + " is not YAML, JSON or properties");
        return null;
    }

    private static @Nullable Object plain(@Nullable Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
            return out;
        }
        return node;
    }

    /** {@code value} as a key of {@code type} holds it, or null when it cannot be one. */
    static @Nullable Object typed(Object value, PluginDescriptor.SchemaKey.Type type) {
        return switch (type) {
            case STRING, COORDINATE -> value instanceof Map<?, ?> || value instanceof List<?> ? null : scalar(value);
            case BOOL -> value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
            case INT -> {
                if (value instanceof Number n) yield n.longValue();
                try {
                    yield Long.parseLong(String.valueOf(value).strip());
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case STRING_LIST -> {
                List<String> out = new ArrayList<>();
                if (value instanceof List<?> list) for (Object v : list) out.add(String.valueOf(v));
                else out.add(String.valueOf(value));
                yield out;
            }
            case STRING_MAP -> {
                if (!(value instanceof Map<?, ?> m)) yield null;
                Map<String, String> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), Objects.toString(v, "")));
                yield out;
            }
        };
    }

    /** A number Gradle hands back as a whole double prints as an integer. */
    private static String scalar(Object value) {
        if (value instanceof Double d && d == Math.rint(d)) return Long.toString(d.longValue());
        return String.valueOf(value);
    }
}

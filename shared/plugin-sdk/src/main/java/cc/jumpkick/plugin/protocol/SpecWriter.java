// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.ProjectFacts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Builds a {@link PluginProtocol} spec (engine→plugin) as JSONL lines — one writer for every
 * plugin, replacing the per-plugin KEY-value/tab spec builders. Fluent; call {@link #lines} and
 * write them to the spec file the plugin is forked with.
 */
public final class SpecWriter {

    private final List<String> lines = new ArrayList<>();

    public SpecWriter op(String op, @Nullable String name, String pluginId) {
        StringBuilder b = new StringBuilder("{\"t\":")
                .append(Jsonl.quote(PluginProtocol.OP))
                .append(",\"op\":")
                .append(Jsonl.quote(op));
        if (name != null) b.append(",\"name\":").append(Jsonl.quote(name));
        b.append(",\"plugin\":").append(Jsonl.quote(pluginId)).append('}');
        lines.add(b.toString());
        return this;
    }

    /**
     * The job's network policy. Written once per fork by the engine's worker launcher, not by the
     * code that assembled the rest of the spec — see {@link PluginProtocol#OFFLINE}. A spec that
     * never gets this line reads back as offline.
     */
    public SpecWriter offline(boolean offline) {
        lines.add("{\"t\":" + Jsonl.quote(PluginProtocol.OFFLINE) + ",\"value\":" + offline + "}");
        return this;
    }

    /** Serialize a whole validated config table (string/bool/int/list values). */
    public SpecWriter config(PluginConfig config) {
        return configValues(config.values());
    }

    /**
     * Same as {@link #config(PluginConfig)} from a raw table (front-end {@code model.PluginConfig}).
     * A {@code string-map} value is one {@code map} line; a group of entries (the table's
     * {@code [entries]}, a value whose values are themselves maps) is one line per leaf, addressed
     * by {@code entry} and {@code field}, so the worker rebuilds the same nested map.
     */
    public SpecWriter configValues(Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m && m.values().stream().anyMatch(x -> x instanceof Map)) {
                for (Map.Entry<?, ?> entry : m.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?> fields)) continue;
                    for (Map.Entry<?, ?> field : fields.entrySet()) {
                        configValue(
                                e.getKey(),
                                String.valueOf(entry.getKey()),
                                String.valueOf(field.getKey()),
                                field.getValue());
                    }
                }
                continue;
            }
            configValue(e.getKey(), null, null, v);
        }
        return this;
    }

    /**
     * A {@code null} value is "unset": no line is written and the worker's {@code stringOpt(key)}
     * is empty. Writing {@code "value":null} instead made both readers fail their non-null check
     * with a message that named the field, not the key.
     */
    public SpecWriter configString(String key, @Nullable String value) {
        if (value != null) configValue(key, null, null, value);
        return this;
    }

    public SpecWriter configBool(String key, boolean value) {
        configValue(key, null, null, value);
        return this;
    }

    public SpecWriter configInt(String key, long value) {
        configValue(key, null, null, value);
        return this;
    }

    public SpecWriter configList(String key, List<String> values) {
        configValue(key, null, null, values);
        return this;
    }

    /** One typed leaf under {@code key}; {@code entry}/{@code field} address a nested entry's field. */
    @SuppressWarnings("unchecked")
    private void configValue(String key, @Nullable String entry, @Nullable String field, @Nullable Object v) {
        JsonFields line = JsonFields.object()
                .string("t", "config")
                .string("key", key)
                .optionalString("entry", entry)
                .optionalString("field", field);
        if (v instanceof String s)
            lines.add(line.string("kind", "string").string("value", s).finish());
        else if (v instanceof Boolean b)
            lines.add(line.string("kind", "bool").bool("value", b).finish());
        else if (v instanceof Long l)
            lines.add(line.string("kind", "int").number("value", l).finish());
        else if (v instanceof Integer i)
            lines.add(line.string("kind", "int").number("value", i).finish());
        else if (v instanceof List<?> list) {
            List<String> strs = new ArrayList<>();
            for (Object o : list) strs.add(String.valueOf(o));
            lines.add(line.string("kind", "list").array("values", strs).finish());
        } else if (v instanceof Map<?, ?> m) {
            lines.add(line.string("kind", "map")
                    .map("values", (Map<String, String>) m)
                    .finish());
        }
    }

    public SpecWriter project(ProjectFacts p) {
        StringBuilder b = new StringBuilder("{\"t\":\"project\",\"group\":")
                .append(Jsonl.quote(p.group()))
                .append(",\"name\":")
                .append(Jsonl.quote(p.name()))
                .append(",\"version\":")
                .append(Jsonl.quote(p.version()))
                .append(",\"javaRelease\":")
                .append(p.javaRelease())
                .append(",\"nativeDeclared\":")
                .append(p.nativeDeclared())
                .append(",\"kotlin\":")
                .append(p.kotlin());
        @Nullable String mainClass = p.mainClass();
        if (mainClass != null && !mainClass.isBlank()) {
            b.append(",\"mainClass\":").append(Jsonl.quote(mainClass));
        }
        b.append('}');
        lines.add(b.toString());
        for (Map.Entry<String, String> e : p.manifest().entrySet()) {
            lines.add("{\"t\":\"manifest-attr\",\"key\":" + Jsonl.quote(e.getKey()) + ",\"value\":"
                    + Jsonl.quote(e.getValue()) + "}");
        }
        return this;
    }

    public SpecWriter layout(Map<String, Path> dirs) {
        StringBuilder b = new StringBuilder("{\"t\":\"layout\"");
        for (Map.Entry<String, Path> e : dirs.entrySet()) {
            b.append(',')
                    .append(Jsonl.quote(e.getKey()))
                    .append(':')
                    .append(Jsonl.quote(e.getValue().toAbsolutePath().toString()));
        }
        b.append('}');
        lines.add(b.toString());
        return this;
    }

    public SpecWriter javaHome(Path javaHome) {
        lines.add("{\"t\":\"java-home\",\"path\":"
                + Jsonl.quote(javaHome.toAbsolutePath().toString()) + "}");
        return this;
    }

    public SpecWriter artifact(Path path) {
        lines.add("{\"t\":\"artifact\",\"path\":"
                + Jsonl.quote(path.toAbsolutePath().toString()) + "}");
        return this;
    }

    public SpecWriter cp(Path path, String role) {
        lines.add("{\"t\":\"cp\",\"path\":" + Jsonl.quote(path.toAbsolutePath().toString()) + ",\"role\":"
                + Jsonl.quote(role) + "}");
        return this;
    }

    /** A compile-classpath entry's producer analysis — see {@link PluginProtocol#CP_ANALYSIS}. */
    public SpecWriter cpAnalysis(Path entry, Path analysis) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, PluginProtocol.CP_ANALYSIS)
                .string(PluginProtocol.PATH, entry.toAbsolutePath().toString())
                .string(PluginProtocol.ANALYSIS, analysis.toAbsolutePath().toString())
                .finish());
        return this;
    }

    /** Every entry with one role — build-plugin workers see the compile classpath this way. */
    public SpecWriter classpath(List<Path> entries, String role) {
        for (Path p : entries) cp(p, role);
        return this;
    }

    /** The build-plugin worker layout: classes tree, module dir, and the step scratch dir. */
    public SpecWriter layout(Path classesDir, Path moduleDir, Path scratch) {
        lines.add("{\"t\":\"layout\",\"classesDir\":" + Jsonl.quote(String.valueOf(classesDir))
                + ",\"moduleDir\":" + Jsonl.quote(String.valueOf(moduleDir))
                + ",\"scratch\":" + Jsonl.quote(String.valueOf(scratch)) + "}");
        return this;
    }

    public SpecWriter entry(String fileName, @Nullable Path jar, boolean snapshot, @Nullable Path container) {
        return entry(fileName, jar, snapshot, container, "", "", "");
    }

    /** As above with the entry's Maven identity (runtime-closure provenance for packagers). */
    public SpecWriter entry(
            String fileName,
            @Nullable Path jar,
            boolean snapshot,
            @Nullable Path container,
            String group,
            String artifact,
            String version) {
        StringBuilder b = new StringBuilder("{\"t\":\"entry\",\"file\":").append(Jsonl.quote(fileName));
        if (!group.isEmpty()) {
            b.append(",\"group\":")
                    .append(Jsonl.quote(group))
                    .append(",\"artifact\":")
                    .append(Jsonl.quote(artifact))
                    .append(",\"version\":")
                    .append(Jsonl.quote(version));
        }
        if (jar != null)
            b.append(",\"path\":").append(Jsonl.quote(jar.toAbsolutePath().toString()));
        b.append(",\"snapshot\":").append(snapshot);
        if (container != null)
            b.append(",\"container\":")
                    .append(Jsonl.quote(container.toAbsolutePath().toString()));
        b.append('}');
        lines.add(b.toString());
        return this;
    }

    public SpecWriter source(Path path) {
        lines.add("{\"t\":\"source\",\"path\":"
                + Jsonl.quote(path.toAbsolutePath().toString()) + "}");
        return this;
    }

    public SpecWriter arg(String value) {
        lines.add("{\"t\":\"arg\",\"value\":" + Jsonl.quote(value) + "}");
        return this;
    }

    public SpecWriter compilerPlugin(String id, Path jar, List<String> options) {
        lines.add("{\"t\":\"compiler-plugin\",\"id\":" + Jsonl.quote(id) + ",\"path\":"
                + Jsonl.quote(jar.toAbsolutePath().toString()) + ",\"options\":" + array(options) + "}");
        return this;
    }

    public SpecWriter stepOutput(String name, Path dir) {
        lines.add("{\"t\":\"step-output\",\"name\":" + Jsonl.quote(name) + ",\"dir\":"
                + Jsonl.quote(dir.toAbsolutePath().toString()) + "}");
        return this;
    }

    public SpecWriter extra(String name, Path path) {
        lines.add("{\"t\":\"extra\",\"name\":" + Jsonl.quote(name) + ",\"path\":"
                + Jsonl.quote(path.toAbsolutePath().toString()) + "}");
        return this;
    }

    /** One dependency sibling's directory under a declared {@code sibling:<key>} input. */
    public SpecWriter siblingFiles(String key, Path dir) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, PluginProtocol.SIBLING_FILES)
                .string(PluginProtocol.KEY, key)
                .string(PluginProtocol.PATH, dir.toAbsolutePath().toString())
                .finish());
        return this;
    }

    /** A resolved secret — package/publish specs only; never echoed by plugins. */
    public SpecWriter secret(String key, String value) {
        lines.add("{\"t\":\"secret\",\"key\":" + Jsonl.quote(key) + ",\"value\":" + Jsonl.quote(value) + "}");
        return this;
    }

    public SpecWriter commandArgs(List<String> args) {
        lines.add("{\"t\":\"command-args\",\"values\":" + array(args) + "}");
        return this;
    }

    public List<String> lines() {
        return lines;
    }

    /** Write the spec to a temp file (the path the worker is forked with). Caller deletes. */
    public Path writeTempSpec() throws IOException {
        Path spec = Files.createTempFile("jk-plugin-", ".spec");
        Files.write(spec, lines, StandardCharsets.UTF_8);
        return spec;
    }

    private static String array(List<String> values) {
        return Jsonl.array(values);
    }
}

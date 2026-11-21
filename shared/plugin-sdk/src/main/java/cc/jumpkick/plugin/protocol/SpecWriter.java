// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.build.RepositoryRoute;
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
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, PluginProtocol.OP)
                .string("op", op)
                .optionalString("name", name)
                .string("plugin", pluginId)
                .finish());
        return this;
    }

    /**
     * The job's network policy. Written once per fork by the engine's worker launcher, not by the
     * code that assembled the rest of the spec — see {@link PluginProtocol#OFFLINE}. A spec that
     * never gets this line reads back as offline.
     */
    public SpecWriter offline(boolean offline) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, PluginProtocol.OFFLINE)
                .bool("value", offline)
                .finish());
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
     * by {@code entry} and {@code field}, so the worker rebuilds the same nested map; an entry with
     * no fields is one line with {@code entry} and no {@code field}.
     */
    public SpecWriter configValues(Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m && m.values().stream().anyMatch(x -> x instanceof Map)) {
                for (Map.Entry<?, ?> entry : m.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?> fields)) continue;
                    if (fields.isEmpty()) {
                        // An entry that sets nothing is still named: one line with no field says so.
                        lines.add(JsonFields.object()
                                .string("t", "config")
                                .string("key", e.getKey())
                                .string("entry", String.valueOf(entry.getKey()))
                                .finish());
                        continue;
                    }
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
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, "project")
                .string("group", p.group())
                .string("name", p.name())
                .string("version", p.version())
                .number("javaRelease", p.javaRelease())
                .bool("nativeDeclared", p.nativeDeclared())
                .bool("kotlin", p.kotlin())
                .optionalNonBlankString("mainClass", p.mainClass())
                .finish());
        for (Map.Entry<String, String> e : p.manifest().entrySet()) {
            lines.add(JsonFields.object()
                    .string(PluginProtocol.T, "manifest-attr")
                    .string("key", e.getKey())
                    .string("value", e.getValue())
                    .finish());
        }
        return this;
    }

    public SpecWriter layout(Map<String, Path> dirs) {
        JsonFields line = JsonFields.object().string(PluginProtocol.T, "layout");
        for (Map.Entry<String, Path> e : dirs.entrySet()) {
            line.string(e.getKey(), e.getValue().toAbsolutePath().toString());
        }
        lines.add(line.finish());
        return this;
    }

    public SpecWriter javaHome(Path javaHome) {
        lines.add(pathLine("java-home", javaHome));
        return this;
    }

    public SpecWriter artifact(Path path) {
        lines.add(pathLine("artifact", path));
        return this;
    }

    public SpecWriter cp(Path path, String role) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, "cp")
                .string(PluginProtocol.PATH, path.toAbsolutePath().toString())
                .string("role", role)
                .finish());
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
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, "layout")
                .string("classesDir", String.valueOf(classesDir))
                .string("moduleDir", String.valueOf(moduleDir))
                .string("scratch", String.valueOf(scratch))
                .finish());
        return this;
    }

    /**
     * The module's compiled test classes, for a TEST-window step that declared {@code
     * In.testClasses()}: its own {@code test-classes} line, so a spec without it reads as before.
     */
    public SpecWriter testClasses(Path testClassesDir) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, "test-classes")
                .string("dir", String.valueOf(testClassesDir))
                .finish());
        return this;
    }

    /**
     * One of the module's configured source roots, for a step that declared {@code
     * In.sourceRoots()}: {@code test} false for a main root, true for a test root.
     */
    public SpecWriter sourceRoots(List<Path> main, List<Path> test) {
        lines.add(JsonFields.object().string(PluginProtocol.T, "source-roots").finish());
        for (Path dir : main) sourceRoot(dir, false);
        for (Path dir : test) sourceRoot(dir, true);
        return this;
    }

    private SpecWriter sourceRoot(Path dir, boolean test) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, "source-root")
                .string("dir", String.valueOf(dir))
                .bool("test", test)
                .finish());
        return this;
    }

    /**
     * The build is a recompile — the compile goal alone, which is what {@code jk compile} and
     * {@code jk dev}'s rebuild on change run — so a step may take a cheaper path whose output still
     * runs. Written only for a recompile.
     */
    public SpecWriter recompile() {
        lines.add(JsonFields.object().string(PluginProtocol.T, "recompile").finish());
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
        return entry(fileName, jar, snapshot, container, group, artifact, version, null);
    }

    /**
     * One entry of the test runtime closure, beside the production entries: what a {@link
     * PluginProtocol#OP_LOCK_CLOSURE} spec carries.
     */
    public SpecWriter testEntry(
            String fileName,
            @Nullable Path jar,
            boolean snapshot,
            @Nullable Path container,
            String group,
            String artifact,
            String version) {
        return entry(fileName, jar, snapshot, container, group, artifact, version, PluginProtocol.ROLE_TEST);
    }

    private SpecWriter entry(
            String fileName,
            @Nullable Path jar,
            boolean snapshot,
            @Nullable Path container,
            String group,
            String artifact,
            String version,
            @Nullable String role) {
        JsonFields line = JsonFields.object()
                .string(PluginProtocol.T, "entry")
                .string("file", fileName)
                .optionalString(PluginProtocol.ROLE, role);
        if (!group.isEmpty()) {
            line.string("group", group).string("artifact", artifact).string("version", version);
        }
        lines.add(line.optionalString(
                        PluginProtocol.PATH,
                        jar == null ? null : jar.toAbsolutePath().toString())
                .bool("snapshot", snapshot)
                .optionalString(
                        "container",
                        container == null ? null : container.toAbsolutePath().toString())
                .finish());
        return this;
    }

    public SpecWriter source(Path path) {
        lines.add(pathLine("source", path));
        return this;
    }

    public SpecWriter arg(String value) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, "arg")
                .string("value", value)
                .finish());
        return this;
    }

    public SpecWriter compilerPlugin(String id, Path jar, List<String> options) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, "compiler-plugin")
                .string("id", id)
                .string(PluginProtocol.PATH, jar.toAbsolutePath().toString())
                .array("options", options)
                .finish());
        return this;
    }

    public SpecWriter stepOutput(String name, Path dir) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, "step-output")
                .string("name", name)
                .string("dir", dir.toAbsolutePath().toString())
                .finish());
        return this;
    }

    public SpecWriter extra(String name, Path path) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, "extra")
                .string("name", name)
                .string(PluginProtocol.PATH, path.toAbsolutePath().toString())
                .finish());
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

    /** One routed remote repository, in resolve order — see {@link PluginProtocol#REPOSITORY}. */
    public SpecWriter repository(RepositoryRoute route) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, PluginProtocol.REPOSITORY)
                .string("id", route.id())
                .string("url", route.url().toString())
                .optionalString("username", route.username())
                .optionalString("secret", route.secret())
                .optionalString(
                        "proxy", route.proxy() == null ? null : route.proxy().toString())
                .finish());
        return this;
    }

    /**
     * A resolved secret of the plugin's: written to its step, packager and publish specs, never to a
     * describe; never echoed by plugins.
     */
    public SpecWriter secret(String key, String value) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, "secret")
                .string(PluginProtocol.KEY, key)
                .string("value", value)
                .finish());
        return this;
    }

    public SpecWriter commandArgs(List<String> args) {
        lines.add(JsonFields.object()
                .string(PluginProtocol.T, "command-args")
                .array("values", args)
                .finish());
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

    /** A line of type {@code t} whose one field is {@code path}'s absolute form. */
    private static String pathLine(String t, Path path) {
        return JsonFields.object()
                .string(PluginProtocol.T, t)
                .string(PluginProtocol.PATH, path.toAbsolutePath().toString())
                .finish();
    }
}

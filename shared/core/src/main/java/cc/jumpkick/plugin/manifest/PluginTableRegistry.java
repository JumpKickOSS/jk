// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.PluginConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * Installed plugins keyed by owned jk.toml table (built-ins + third-party). {@link #validate}
 * schema-checks a raw table into a {@link PluginConfig}.
 */
public final class PluginTableRegistry {

    private static final List<String> BUILT_IN = List.of(
            "spring-boot.jk-plugin.toml",
            "grails.jk-plugin.toml",
            "quarkus.jk-plugin.toml",
            "android.jk-plugin.toml",
            "protobuf.jk-plugin.toml",
            "minified.jk-plugin.toml",
            "micronaut.jk-plugin.toml");

    /** Load a plugin resource relative to its manifest ({@code <id>/<relPath>}). */
    public static String resourceText(PluginDescriptor manifest, String relPath) {
        String resource = manifest.id() + "/" + relPath;
        try (InputStream in = openBuiltIn(resource)) {
            if (in == null) {
                throw new cc.jumpkick.config.JkBuildParseException(
                        "plugin " + manifest.id() + " names a missing resource: " + relPath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new cc.jumpkick.config.JkBuildParseException(
                    "plugin " + manifest.id() + " resource " + relPath + " is unreadable: " + e.getMessage());
        }
    }

    private static final Map<String, PluginDescriptor> BY_TABLE = loadBuiltIns();

    private PluginTableRegistry() {}

    /** Every installed manifest, in registration order. */
    public static List<PluginDescriptor> manifests() {
        return List.copyOf(BY_TABLE.values());
    }

    /** True when {@code id} names a first-party manifest shipped inside jk itself. */
    public static boolean isBuiltIn(String id) {
        for (PluginDescriptor m : BY_TABLE.values()) {
            if (m.id().equals(id)) return true;
        }
        return false;
    }

    /**
     * The manifests a build of {@code moduleDir} sees: built-ins plus every {@code [plugins]}
     * declaration whose manifest is locked + materialized ({@link PluginDescriptorStore}). An
     * unresolved declaration is silently absent — its table validates on the next parse after the
     * engine materializes (sync/lock/build pre-flight). A third-party manifest claiming a built-in
     * id or an already-owned table is a parse error, not a shadow.
     */
    public static List<PluginDescriptor> manifestsFor(Path moduleDir, List<cc.jumpkick.model.PluginDeclaration> decls) {
        if (decls == null || decls.isEmpty()) return manifests();
        List<PluginDescriptor> out = new ArrayList<>(manifests());
        Set<String> ids = new HashSet<>();
        Set<String> tables = new HashSet<>();
        for (PluginDescriptor m : out) {
            ids.add(m.id());
            tables.add(m.table());
        }
        for (cc.jumpkick.model.PluginDeclaration decl : decls) {
            PluginDescriptor m =
                    PluginDescriptorStore.manifestFor(moduleDir, decl).orElse(null);
            if (m == null) continue;
            if (!ids.add(m.id())) {
                throw new cc.jumpkick.config.JkBuildParseException("plugin " + decl.coordinateWithVersion()
                        + " declares id `" + m.id() + "`, which is already provided by another installed plugin");
            }
            if (!tables.add(m.table())) {
                throw new cc.jumpkick.config.JkBuildParseException("plugin " + decl.coordinateWithVersion()
                        + " claims table [" + m.table() + "], which is already owned by another installed plugin");
            }
            out.add(m);
        }
        return out;
    }

    /** The manifest owning jk.toml table {@code table}, when a plugin declares it. */
    public static Optional<PluginDescriptor> byTable(String table) {
        return Optional.ofNullable(BY_TABLE.get(table));
    }

    /** Schema-validate {@code table} (the raw {@code [<manifest.table>]} TOML) into a config. */
    public static PluginConfig validate(PluginDescriptor manifest, TomlTable table) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (PluginDescriptor.SchemaKey key : manifest.schema().values()) {
            // A schema key sharing its name with a sub-table group: the TOML value decides which
            // spelling this module used — a nested table is the group (handled below), a string
            // is the reference read here.
            if (manifest.subTables().containsKey(key.name()) && readTable(table, key.name()) != null) continue;
            Object value = read(manifest.table(), key, table);
            if (value == null) value = key.normalizedDefault();
            if (value == null) {
                if (key.required()) throw new JkBuildParseException(requiredMessage(manifest.table(), key));
                continue; // tri-state: absent stays absent
            }
            values.put(key.name(), value);
        }
        // Declared nested-table groups ([sub-tables.<name>]): each entry validates against its
        // sub-schema and rides the config as a nested map, resolved by name from a schema key
        // (signing = "release") and flattened into the effective config by VariantApply.
        for (PluginDescriptor.SubTable group : manifest.subTables().values()) {
            TomlTable groupTable = readTable(table, group.table());
            if (groupTable == null) continue;
            Map<String, PluginDescriptor.SchemaKey> subSchema =
                    manifest.subSchemas().get(group.schema());
            String whereBase = manifest.table() + "." + group.table();
            Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
            for (String entry : groupTable.keySet()) {
                TomlTable entryTable = readTable(groupTable, entry);
                if (entryTable == null) {
                    throw new JkBuildParseException("[" + whereBase + "]." + entry + " must be a table");
                }
                entries.put(entry, validateSub(whereBase + "." + entry, subSchema, entryTable));
            }
            values.put(group.table(), entries);
        }
        return new PluginConfig(manifest.id(), values);
    }

    /**
     * Schema-validate a variant overlay ({@code [variants.<dim>.<value>.<plugin-table>]}) — the
     * partial-table cousin of {@link #validate}: keys coerce against the plugin's top-level
     * schema, but nothing is required and no defaults apply (an overlay only carries what it
     * sets). Nested groups (signing definitions) don't belong in overlays — reference them by
     * name via their schema key instead ({@code signing = "release"}).
     */
    public static Map<String, Object> validateOverlay(PluginDescriptor manifest, String where, TomlTable table) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String raw : table.keySet()) {
            // Group DEFINITIONS don't belong in overlays; the string REFERENCE spelling does.
            if (manifest.subTables().containsKey(raw) && readTable(table, raw) != null) {
                throw new JkBuildParseException("[" + where + "." + raw + "] — define ["
                        + manifest.table() + "." + raw + ".<name>] groups on the plugin table and reference"
                        + " them from the overlay by name (" + raw + " = \"<name>\")");
            }
            PluginDescriptor.SchemaKey key = manifest.schema().get(raw);
            if (key == null) {
                throw new JkBuildParseException("[" + where + "]." + raw + " is not a [" + manifest.table()
                        + "] key (schema: " + manifest.schema().keySet() + ")");
            }
            Object value = read(where, key, table);
            if (value != null) values.put(key.name(), value);
        }
        return values;
    }

    private static TomlTable readTable(TomlTable parent, String key) {
        Object raw = parent.contains(key) ? parent.get(key) : null;
        return raw instanceof TomlTable t ? t : null;
    }

    /** One nested entry against its sub-schema — same coercion + required semantics as the table. */
    private static Map<String, Object> validateSub(
            String where, Map<String, PluginDescriptor.SchemaKey> subSchema, TomlTable entryTable) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (PluginDescriptor.SchemaKey key : subSchema.values()) {
            Object value = read(where, key, entryTable);
            if (value == null) value = key.normalizedDefault();
            if (value == null) {
                if (key.required()) {
                    throw new JkBuildParseException("[" + where + "] requires `" + key.name() + "`"
                            + (key.example() != null ? " (e.g. " + key.name() + " = \"" + key.example() + "\")" : ""));
                }
                continue;
            }
            out.put(key.name(), value);
        }
        return out;
    }

    private static Object read(String tableName, PluginDescriptor.SchemaKey key, TomlTable table) {
        if (!table.contains(key.name())) return null;
        String where = "[" + tableName + "]." + key.name();
        return switch (key.type()) {
            case STRING -> {
                String v = getOr(() -> table.getString(key.name()), where + " must be a string");
                if (v == null || v.isBlank()) yield null;
                yield v;
            }
            case BOOL -> {
                Boolean v = getOr(() -> table.getBoolean(key.name()), where + " must be a boolean");
                if (v == null) throw new JkBuildParseException(where + " must be a boolean");
                yield v;
            }
            case INT -> {
                Long v = getOr(() -> table.getLong(key.name()), where + " must be an integer");
                if (v == null) throw new JkBuildParseException(where + " must be an integer");
                yield v;
            }
            case STRING_LIST -> {
                TomlArray arr = getOr(() -> table.getArray(key.name()), where + " must be an array of strings");
                if (arr == null) throw new JkBuildParseException(where + " must be an array of strings");
                List<String> out = new ArrayList<>(arr.size());
                for (int i = 0; i < arr.size(); i++) {
                    Object val = arr.get(i);
                    if (!(val instanceof String str)) {
                        throw new JkBuildParseException(where + " must be an array of strings");
                    }
                    out.add(str);
                }
                yield out;
            }
        };
    }

    /** tomlj throws {@code TomlInvalidTypeException} on mistyped keys — normalize to a parse error. */
    private static <T> T getOr(Supplier<T> read, String message) {
        try {
            return read.get();
        } catch (org.tomlj.TomlInvalidTypeException e) {
            throw new JkBuildParseException(message);
        }
    }

    /** Message parity with the old hand-written parsers: example + hint when the schema has them. */
    private static String requiredMessage(String tableName, PluginDescriptor.SchemaKey key) {
        StringBuilder sb = new StringBuilder("[" + tableName + "]." + key.name() + " is required");
        if (key.example() != null) {
            sb.append(" (e.g. ")
                    .append(key.name())
                    .append(" = \"")
                    .append(key.example())
                    .append("\")");
        }
        if (key.hint() != null) sb.append(" — ").append(key.hint());
        return sb.toString();
    }

    /**
     * Built-in manifests sit on the engine classpath (JK-2149), not next to this class in {@code
     * :core}. Try the class, then the context loader, then the defining loader with the full path.
     */
    private static InputStream openBuiltIn(String resource) {
        InputStream in = PluginTableRegistry.class.getResourceAsStream(resource);
        if (in != null) return in;
        String full = "cc/jumpkick/plugin/manifest/" + resource;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            in = ctx.getResourceAsStream(full);
            if (in != null) return in;
        }
        ClassLoader def = PluginTableRegistry.class.getClassLoader();
        return def == null ? null : def.getResourceAsStream(full);
    }

    private static Map<String, PluginDescriptor> loadBuiltIns() {
        Map<String, PluginDescriptor> byTable = new LinkedHashMap<>();
        int missing = 0;
        for (String resource : BUILT_IN) {
            try (InputStream in = openBuiltIn(resource)) {
                if (in == null) {
                    missing++;
                    continue;
                }
                PluginDescriptor manifest =
                        PluginDescriptors.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), resource);
                if (manifest.code() != null && manifest.code().worker() == null) {
                    throw new IllegalStateException("built-in plugin manifest " + resource
                            + " must name its registered worker jar ([code] worker)");
                }
                byTable.put(manifest.table(), manifest);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to load built-in plugin manifest " + resource, e);
            }
        }
        // Native CLI / :core main have no baked manifests (JK-2149). Engine and workers do.
        if (missing == 0) return byTable;
        if (missing == BUILT_IN.size()) return Map.of();
        throw new IllegalStateException("missing built-in plugin manifest resources ("
                + missing
                + "/"
                + BUILT_IN.size()
                + "; first-party manifests live on the engine and worker classpaths, not :core)");
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.JkBuildParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * The manifest keys a framework plugin sets a module's floors and defaults with: {@code [plugin]
 * min-java}, a string schema key's {@code one-of} and {@code min-version}, {@code
 * [contribute.test-system-properties]} and {@code [contribute.lib-dir]}.
 */
final class ManifestDefaults {

    private ManifestDefaults() {}

    /** {@code [plugin] min-java}: a Java feature release, or 0 when absent. */
    static int minJava(TomlTable plugin, String displayPath) {
        if (!plugin.contains("min-java")) return 0;
        Long raw = plugin.isLong("min-java") ? plugin.getLong("min-java") : null;
        if (raw == null || raw < 8 || raw > 1000) {
            throw new JkBuildParseException(displayPath + ".plugin.min-java must be a Java feature release such as 27");
        }
        return raw.intValue();
    }

    /** {@code [contribute.test-system-properties]}: a table of string values, by key. */
    static Map<String, String> testSystemProperties(TomlTable contribute, String displayPath) {
        if (!contribute.contains("test-system-properties")) return Map.of();
        String where = displayPath + ".contribute.test-system-properties";
        TomlTable t =
                contribute.isTable("test-system-properties") ? contribute.getTable("test-system-properties") : null;
        if (t == null) throw new JkBuildParseException(where + " must be a table of string values");
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : t.keySet()) {
            // By path, not by name: a quoted key holding dots ("helidon.config.profile") is one key.
            if (!(t.get(List.of(key)) instanceof String value)) {
                throw new JkBuildParseException(where + ".\"" + key + "\" must be a string");
            }
            out.put(key, value);
        }
        return out;
    }

    /**
     * {@code [contribute.lib-dir]}: one table, optionally gated by a {@code when} the parse can
     * evaluate — the layout is decided with the manifest, before any classpath exists.
     */
    static PluginDescriptor.@Nullable LibDir libDir(TomlTable contribute, String displayPath) {
        if (!contribute.contains("lib-dir")) return null;
        String where = displayPath + ".contribute.lib-dir";
        TomlTable t = contribute.isTable("lib-dir") ? contribute.getTable("lib-dir") : null;
        if (t == null) throw new JkBuildParseException(where + " must be a table");
        for (String key : t.keySet()) {
            if (!key.equals("when")) throw new JkBuildParseException(where + ": unknown key `" + key + "` (when)");
        }
        PluginDescriptor.Condition when = PluginConditions.parse(t, where);
        if (when instanceof PluginDescriptor.Condition.ClasspathHas) {
            throw new JkBuildParseException(
                    where + ": classpath-has cannot gate the layout (it is decided before resolution)");
        }
        return new PluginDescriptor.LibDir(when);
    }

    /** {@code one-of} and {@code min-version} belong to string keys, and a default must be one of the values. */
    static void checkStringConstraints(
            String where,
            PluginDescriptor.SchemaKey.Type type,
            @Nullable Object defaultValue,
            List<String> oneOf,
            @Nullable String minVersion) {
        if ((!oneOf.isEmpty() || minVersion != null) && type != PluginDescriptor.SchemaKey.Type.STRING) {
            throw new JkBuildParseException(where + ": one-of and min-version apply to string keys");
        }
        if (!oneOf.isEmpty() && defaultValue instanceof String d && !oneOf.contains(d)) {
            throw new JkBuildParseException(where + ": the default \"" + d + "\" is not one of " + oneOf);
        }
        if (minVersion != null && minVersion.isBlank()) {
            throw new JkBuildParseException(where + ".min-version must name a version");
        }
    }
}

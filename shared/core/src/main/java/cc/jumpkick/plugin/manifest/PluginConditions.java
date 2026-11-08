// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.JkBuildParseException;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/** The {@code when} predicate a manifest contribution carries: one of a closed set, parsed at load. */
final class PluginConditions {

    private PluginConditions() {}

    /**
     * Parse the optional {@code when} inline table into exactly one {@link
     * PluginDescriptor.Condition} — the closed predicate set; two predicates in one {@code when}
     * (or an unknown one) is a load error, never a silently-false condition.
     */
    static PluginDescriptor.@Nullable Condition parse(TomlTable entry, String where) {
        if (!entry.contains("when")) return null;
        TomlTable when = entry.getTable("when");
        if (when == null) {
            throw new JkBuildParseException(where + ".when must be a table of exactly one predicate");
        }
        if (when.keySet().size() > (when.contains("config") ? 2 : 1)) {
            throw new JkBuildParseException(where + ".when has more than one condition; use one");
        }
        if (when.contains("classpath-has")) {
            String module = when.getString("classpath-has");
            if (module == null || module.isBlank()) {
                throw new JkBuildParseException(where + ".when.classpath-has must be \"group:artifact\"");
            }
            return new PluginDescriptor.Condition.ClasspathHas(module);
        }
        if (when.contains("config") && (when.contains("at-least") || when.contains("below"))) {
            String key = when.getString("config");
            boolean atLeast = when.contains("at-least");
            String bound = when.getString(atLeast ? "at-least" : "below");
            if (key == null || bound == null || bound.isBlank()) {
                throw new JkBuildParseException(where + ".when needs `config = \"<key>\"` and a version");
            }
            return new PluginDescriptor.Condition.ConfigVersion(key, bound, atLeast);
        }
        if (when.contains("config") && when.contains("set")) {
            String key = when.getString("config");
            Boolean set = when.getBoolean("set");
            if (key == null || set == null) {
                throw new JkBuildParseException(where + ".when needs `config = \"<key>\"` and `set = true|false`");
            }
            return new PluginDescriptor.Condition.ConfigSet(key, set);
        }
        if (when.contains("config")) {
            String key = when.getString("config");
            String equals = when.getString("equals");
            if (key == null || equals == null) {
                throw new JkBuildParseException(
                        where + ".when config predicate needs both `config = \"<key>\"` and `equals = \"<value>\"`");
            }
            return new PluginDescriptor.Condition.ConfigEquals(key, equals);
        }
        if (when.contains("native-declared")) {
            requireTrue(when, "native-declared", where);
            return new PluginDescriptor.Condition.NativeDeclared();
        }
        if (when.contains("kotlin-project")) {
            requireTrue(when, "kotlin-project", where);
            return new PluginDescriptor.Condition.KotlinProject();
        }
        throw new JkBuildParseException(where + ".when has no known predicate (classpath-has, config/equals,"
                + " config/at-least, config/below, config/set, native-declared, kotlin-project)");
    }

    private static void requireTrue(TomlTable when, String key, String where) {
        if (!Boolean.TRUE.equals(when.getBoolean(key))) {
            throw new JkBuildParseException(
                    where + ".when." + key + " must be `true` (omit the" + " condition entirely for the false case)");
        }
    }
}

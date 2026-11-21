// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * Strict reads of one {@code jk.toml} table. A key the table does not know, or a value of the
 * wrong type, fails the parse with a {@link JkBuildParseException} located at that key: never a
 * raw tomlj exception, never a silent drop. {@link TomlValues} is the lenient counterpart for
 * config views that fall back to defaults.
 *
 * <p>{@code where} names the table as the message shows it ({@code "[format]"}); {@code path}
 * names the key ({@code "format.enforce"}). Absent keys read as {@code null}, the caller's default.
 */
@NullMarked
final class TomlTableReader {

    private TomlTableReader() {}

    /** Fail on the first key of {@code table} not in {@code known}. */
    static void rejectUnknownKeys(TomlTable table, Collection<String> known, String where) {
        for (String key : table.keySet()) {
            if (!known.contains(key)) {
                throw JkBuildParseException.at(
                        table,
                        key,
                        where + " unknown key `" + key + "` — expected one of: " + String.join(", ", known));
            }
        }
    }

    /** The boolean at {@code key}, or null when absent. */
    static @Nullable Boolean optionalBool(TomlTable table, String key, String path) {
        Object value = value(table, key);
        if (value == null || value instanceof Boolean) return (Boolean) value;
        throw JkBuildParseException.at(table, key, path + " must be true or false");
    }

    /** The string at {@code key}, or null when absent. */
    static @Nullable String optionalString(TomlTable table, String key, String path) {
        Object value = value(table, key);
        if (value == null || value instanceof String) return (String) value;
        throw JkBuildParseException.at(table, key, path + " must be a string");
    }

    /** The integer at {@code key}, or null when absent; a value outside {@code int} is refused too. */
    static @Nullable Integer optionalInt(TomlTable table, String key, String path) {
        Object value = value(table, key);
        if (value == null) return null;
        if (value instanceof Long n && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) return n.intValue();
        throw JkBuildParseException.at(table, key, path + " must be an integer");
    }

    /** The table at {@code key}, or null when absent. */
    static @Nullable TomlTable optionalTable(TomlTable table, String key, String path) {
        Object value = value(table, key);
        if (value == null || value instanceof TomlTable) return (TomlTable) value;
        throw JkBuildParseException.at(table, key, path + " must be a table");
    }

    /** The array at {@code key}, or null when absent; its elements are the caller's to check. */
    static @Nullable TomlArray optionalArray(TomlTable table, String key, String path) {
        Object value = value(table, key);
        if (value == null || value instanceof TomlArray) return (TomlArray) value;
        throw JkBuildParseException.at(table, key, path + " must be an array");
    }

    /** One key, never split on dots: {@code "Implementation-Title"} and {@code "a.b"} are both a single key. */
    private static @Nullable Object value(TomlTable table, String key) {
        return table.get(List.of(key));
    }
}

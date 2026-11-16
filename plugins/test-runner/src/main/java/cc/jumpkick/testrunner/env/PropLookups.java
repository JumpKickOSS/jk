// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Where a probed class's system-property reads land: {@code System.getProperty}, {@code
 * System.getProperties}, {@code Integer.getInteger}, {@code Long.getLong} and {@code
 * Boolean.getBoolean}. Each read is noted by the property's name, whether it had a value and the
 * test class running, and passed on unchanged. Values are never kept.
 *
 * <p>Each method carries the exact name and descriptor of the call it stands for: {@link EnvProbe}
 * repoints the call's owner at this class and leaves the rest as compiled. A property is read far
 * more often than a variable, so a repeated read costs two map lookups and nothing else.
 */
public final class PropLookups {

    /** The name a whole-table read is noted under. */
    public static final String WHOLE_TABLE = "*";

    /** By test class running, each property's name and whether a read found it set. */
    private static final Map<String, Map<String, Boolean>> SEEN = new ConcurrentHashMap<>();

    private PropLookups() {}

    public static @Nullable String getProperty(String key) {
        String value = System.getProperty(key);
        note(key, value != null);
        return value;
    }

    public static @Nullable String getProperty(String key, @Nullable String def) {
        String value = System.getProperty(key);
        note(key, value != null);
        return value != null ? value : def;
    }

    public static Properties getProperties() {
        note(WHOLE_TABLE, true);
        return System.getProperties();
    }

    public static @Nullable Integer getInteger(String key) {
        note(key, System.getProperty(key) != null);
        return Integer.getInteger(key);
    }

    public static @Nullable Integer getInteger(String key, int def) {
        note(key, System.getProperty(key) != null);
        return Integer.getInteger(key, def);
    }

    public static @Nullable Integer getInteger(String key, @Nullable Integer def) {
        note(key, System.getProperty(key) != null);
        return Integer.getInteger(key, def);
    }

    public static @Nullable Long getLong(String key) {
        note(key, System.getProperty(key) != null);
        return Long.getLong(key);
    }

    public static @Nullable Long getLong(String key, long def) {
        note(key, System.getProperty(key) != null);
        return Long.getLong(key, def);
    }

    public static @Nullable Long getLong(String key, @Nullable Long def) {
        note(key, System.getProperty(key) != null);
        return Long.getLong(key, def);
    }

    public static boolean getBoolean(String key) {
        note(key, System.getProperty(key) != null);
        return Boolean.getBoolean(key);
    }

    private static void note(@Nullable String key, boolean set) {
        if (key == null) return;
        String test = System.getProperty(EnvLookups.CURRENT_TEST, "");
        Map<String, Boolean> names = SEEN.get(test);
        if (names == null) names = SEEN.computeIfAbsent(test, t -> new ConcurrentHashMap<>());
        // Set once seen set: a property set by one test and read unset by another reads as set.
        if (set) names.put(key, Boolean.TRUE);
        else names.putIfAbsent(key, Boolean.FALSE);
    }

    /** Every read noted so far, one {@code test\tname\tset|unset} line each; an empty file says the probe ran and saw none. */
    static void writeTo(Path file) {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Map<String, Boolean>> t : SEEN.entrySet()) {
                Set<Map.Entry<String, Boolean>> names = t.getValue().entrySet();
                for (Map.Entry<String, Boolean> n : names) {
                    lines.add(
                            t.getKey() + '\t' + n.getKey() + '\t' + (n.getValue() ? EnvLookups.SET : EnvLookups.UNSET));
                }
            }
            String text = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
            Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // a record the JVM could not write is a record not given, never a failed suite
        }
    }
}

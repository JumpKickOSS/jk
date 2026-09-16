// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Schema-validated plugin-owned {@code jk.toml} table. Values: String, Boolean, Long, List,
 * or nested group maps (via {@link #group}); a string may also reference a group entry. Absent
 * key + no default = tri-state unset. Insertion order preserved.
 */
public record PluginConfig(String id, Map<String, Object> values) {

    public PluginConfig {
        Objects.requireNonNull(id, "id");
        values = values.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** A required-by-schema string — the schema guarantees presence, so absent is a bug. */
    public String string(String key) {
        Object v = values.get(key);
        if (v instanceof String s) return s;
        throw new IllegalStateException("[" + id + "]." + key + " missing — schema should have required it");
    }

    /** An optional string, empty when the key is absent. */
    public Optional<String> stringOpt(String key) {
        return values.get(key) instanceof String s ? Optional.of(s) : Optional.empty();
    }

    /** A tri-state bool: empty when unset (no default in the schema). */
    public Optional<Boolean> bool(String key) {
        return values.get(key) instanceof Boolean b ? Optional.of(b) : Optional.empty();
    }

    /** A bool with a call-site fallback (schema defaults are already applied at parse). */
    public boolean bool(String key, boolean fallback) {
        return bool(key).orElse(fallback);
    }

    /** A string list; empty when absent. */
    @SuppressWarnings("unchecked")
    public List<String> stringList(String key) {
        return values.get(key) instanceof List<?> l ? (List<String>) l : List.of();
    }

    /**
     * A nested-table group ({@code [android.signing.<name>]} entries): entry name → its
     * validated key/value map. Empty when the group is absent. Values inside follow the same
     * vocabulary as top-level config values.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> group(String key) {
        Object v = values.get(key);
        if (!(v instanceof Map<?, ?> m)) return Map.of();
        return (Map<String, Map<String, Object>>) m;
    }

    public long intValue(String key, long fallback) {
        return values.get(key) instanceof Long l ? l : fallback;
    }

    /** A {@code string-map} key ({@code options = { a = "1" }}); empty when absent. */
    @SuppressWarnings("unchecked")
    public Map<String, String> stringMap(String key) {
        return values.get(key) instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
    }

    /**
     * The key the owned table's {@code [entries]} ride under: entry name → its validated values.
     * Not a bare TOML key, so no schema key or entry name can collide with it.
     */
    public static final String ENTRIES = "*";

    /** The {@code [<table>.<name>]} entries, in declaration order; empty when the table has none. */
    public Map<String, Map<String, Object>> entries() {
        return group(ENTRIES);
    }
}

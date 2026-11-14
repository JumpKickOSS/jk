// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.EnvValues;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The one decoder from a job request's JSON fields to a {@link JobSpec}: {@code POST /api/build},
 * MCP {@code run} and its aliases all go through it, so a field means the same on every surface and
 * a misspelt one is refused rather than dropped. Errors are {@link IllegalArgumentException}s naming
 * the field; REST answers them with {@code 400}, MCP with {@code -32602}.
 */
public final class JobRequests {

    /** How a field's JSON value is read. */
    public enum Type {
        /** A string. */
        STRING,
        /** A string array, or one comma-separated string. */
        LIST,
        /** A JSON boolean, or a string in jk's truth set. */
        BOOL,
        /** A non-negative integer. */
        INT
    }

    /** One accepted field: its JSON name and type. */
    public record Field(String name, Type type) {}

    /** Every field a job request may carry, in the order schemas list them. */
    public static final List<Field> FIELDS = List.of(
            new Field("kind", Type.STRING),
            new Field("dir", Type.STRING),
            new Field("modules", Type.LIST),
            new Field("classes", Type.LIST),
            new Field("suites", Type.LIST),
            new Field("include_tags", Type.LIST),
            new Field("exclude_tags", Type.LIST),
            new Field("affected", Type.BOOL),
            new Field("skip_tests", Type.BOOL),
            new Field("deadline_s", Type.INT));

    /** The module token {@code affected} stands for when the request names no modules. */
    static final String AFFECTED_MODULES = "affected-wip";

    private JobRequests() {}

    /**
     * Decode {@code fields} into a spec for {@code origin}. {@code dir} falls back to {@code
     * fallbackDir} (an MCP connection's bind), {@code kind} to {@code defaultKind}. {@code
     * callerFields} are the surface's own arguments (an MCP {@code goal}, {@code wait}), skipped
     * here; any other name not in {@link #FIELDS} is refused.
     */
    public static JobSpec decode(
            Map<String, ?> fields,
            JobOrigin origin,
            String defaultKind,
            @Nullable String fallbackDir,
            Set<String> callerFields) {
        return decode(fields, FIELDS, origin, defaultKind, fallbackDir, callerFields);
    }

    /** As above, accepting only the fields in {@code table} (a subset of {@link #FIELDS}). */
    public static JobSpec decode(
            Map<String, ?> fields,
            List<Field> table,
            JobOrigin origin,
            String defaultKind,
            @Nullable String fallbackDir,
            Set<String> callerFields) {
        Set<String> unknown = new TreeSet<>();
        for (String key : fields.keySet()) {
            if (!accepted(table, key) && !callerFields.contains(key)) unknown.add(key);
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    (unknown.size() == 1 ? "unknown field " : "unknown fields ") + String.join(", ", unknown));
        }
        String dir = string(fields, "dir");
        if (dir == null || dir.isBlank()) dir = fallbackDir;
        if (dir == null || dir.isBlank()) throw new IllegalArgumentException("missing \"dir\"");
        String kind = string(fields, "kind");
        if (kind == null || kind.isBlank()) kind = defaultKind;
        List<String> modules = list(fields, "modules");
        boolean affected = bool(fields, "affected");
        if (affected && modules.isEmpty()) modules = List.of(AFFECTED_MODULES);
        Long deadlineS = nonNegative(fields, "deadline_s");
        return new JobSpec(
                kind,
                dir,
                modules,
                list(fields, "include_tags"),
                list(fields, "exclude_tags"),
                list(fields, "suites"),
                list(fields, "classes"),
                bool(fields, "skip_tests"),
                affected,
                deadlineS == null ? null : deadlineS * 1000L,
                origin);
    }

    /** The JSON Schema {@code properties} for {@link #FIELDS}: types only, so a tool card stays small. */
    public static Map<String, Object> schemaProperties() {
        return schemaProperties(FIELDS);
    }

    /** As above for {@code table}. */
    public static Map<String, Object> schemaProperties(List<Field> table) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (Field f : table) {
            props.put(
                    f.name(),
                    switch (f.type()) {
                        case STRING -> Map.of("type", "string");
                        case LIST -> Map.of("type", "array", "items", Map.of("type", "string"));
                        case BOOL -> Map.of("type", "boolean");
                        case INT -> Map.of("type", "integer");
                    });
        }
        return props;
    }

    private static @Nullable String string(Map<String, ?> fields, String key) {
        Object raw = fields.get(key);
        if (raw == null) return null;
        if (raw instanceof String s) return s;
        throw new IllegalArgumentException(key + " must be a string");
    }

    private static List<String> list(Map<String, ?> fields, String key) {
        Object raw = fields.get(key);
        if (raw == null) return List.of();
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> many) {
            for (Object o : many) {
                if (!(o instanceof String s)) throw new IllegalArgumentException(key + " must be strings");
                if (!s.isBlank()) out.add(s.strip());
            }
            return out;
        }
        if (!(raw instanceof String one)) throw new IllegalArgumentException(key + " must be a string array");
        for (String part : one.split(",", -1)) {
            if (!part.isBlank()) out.add(part.strip());
        }
        return out;
    }

    private static boolean bool(Map<String, ?> fields, String key) {
        Object raw = fields.get(key);
        if (raw == null) return false;
        if (raw instanceof Boolean b) return b;
        return EnvValues.parseBool(String.valueOf(raw))
                .orElseThrow(() -> new IllegalArgumentException(key + " must be true or false"));
    }

    private static @Nullable Long nonNegative(Map<String, ?> fields, String key) {
        Object raw = fields.get(key);
        if (raw == null) return null;
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        if (n.longValue() < 0) throw new IllegalArgumentException(key + " must be >= 0 (0 = no deadline)");
        return n.longValue();
    }

    private static boolean accepted(List<Field> table, String key) {
        for (Field f : table) if (f.name().equals(key)) return true;
        return false;
    }
}

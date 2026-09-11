// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.explain;

import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The slice of JSON Schema the vendored SARIF 2.1.0 schema uses — {@code $ref}, {@code type},
 * {@code enum}, {@code properties} with {@code additionalProperties: false}, {@code required},
 * {@code items}, {@code minItems}, {@code uniqueItems}, {@code minimum}, {@code pattern},
 * {@code anyOf}, {@code oneOf} — enough to fail a document the real validators would fail, with no
 * validator dependency on the guard module's classpath.
 */
final class SarifSchema {

    private final Object root;

    private SarifSchema(Object root) {
        this.root = root;
    }

    static SarifSchema vendored() throws IOException {
        try (InputStream in =
                Objects.requireNonNull(SarifSchema.class.getResourceAsStream("sarif-2.1.0.json"), "sarif-2.1.0.json")) {
            return new SarifSchema(Objects.requireNonNull(
                    MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)), "sarif-2.1.0.json"));
        }
    }

    /** Every violation as {@code path: message}; empty when the document validates. */
    List<String> validate(@Nullable Object document) {
        List<String> out = new ArrayList<>();
        check(document, root, "$", out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void check(@Nullable Object node, @Nullable Object schemaObj, String path, List<String> out) {
        if (!(schemaObj instanceof Map<?, ?> schema)) return;
        Object ref = schema.get("$ref");
        if (ref != null) {
            check(node, resolve(String.valueOf(ref)), path, out);
            return;
        }
        Object type = schema.get("type");
        if (type != null && !typeMatches(node, type)) {
            out.add(path + ": expected type " + type + ", got " + kind(node));
            return;
        }
        Object en = schema.get("enum");
        if (en instanceof List<?> values && !values.contains(node))
            out.add(path + ": " + node + " is not one of " + values);
        Object min = schema.get("minimum");
        if (min instanceof Number m && node instanceof Number n && n.doubleValue() < m.doubleValue())
            out.add(path + ": " + n + " below minimum " + m);
        Object pattern = schema.get("pattern");
        if (pattern != null
                && node instanceof String s
                && !Pattern.compile(String.valueOf(pattern)).matcher(s).find())
            out.add(path + ": `" + s + "` does not match " + pattern);
        if (node instanceof Map<?, ?> obj) {
            Map<String, Object> o = (Map<String, Object>) obj;
            Object required = schema.get("required");
            if (required instanceof List<?> req)
                for (Object k : req)
                    if (!o.containsKey(String.valueOf(k))) out.add(path + ": missing required `" + k + "`");
            Object props = schema.get("properties");
            Map<String, Object> declared = props instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of();
            for (var e : o.entrySet()) {
                Object sub = declared.get(e.getKey());
                if (sub != null) check(e.getValue(), sub, path + "." + e.getKey(), out);
                else if (Boolean.FALSE.equals(schema.get("additionalProperties")))
                    out.add(path + ": unexpected property `" + e.getKey() + "`");
                else if (schema.get("additionalProperties") instanceof Map<?, ?> ap)
                    check(e.getValue(), ap, path + "." + e.getKey(), out);
            }
        }
        if (node instanceof List<?> list) {
            Object items = schema.get("items");
            Object minItems = schema.get("minItems");
            if (minItems instanceof Number m && list.size() < m.intValue())
                out.add(path + ": fewer than " + m + " items");
            if (Boolean.TRUE.equals(schema.get("uniqueItems"))) {
                Set<String> seen = new HashSet<>();
                for (Object item : list)
                    if (!seen.add(MiniJson.write(item))) out.add(path + ": duplicate item " + MiniJson.write(item));
            }
            if (items != null)
                for (int i = 0; i < list.size(); i++) check(list.get(i), items, path + "[" + i + "]", out);
        }
        Object anyOf = schema.get("anyOf");
        if (anyOf instanceof List<?> branches) {
            boolean ok = false;
            for (Object b : branches) {
                List<String> errs = new ArrayList<>();
                check(node, b, path, errs);
                if (errs.isEmpty()) ok = true;
            }
            if (!ok) out.add(path + ": matches no anyOf branch of " + MiniJson.write(anyOf));
        }
        Object oneOf = schema.get("oneOf");
        if (oneOf instanceof List<?> branches) {
            int ok = 0;
            for (Object b : branches) {
                List<String> errs = new ArrayList<>();
                check(node, b, path, errs);
                if (errs.isEmpty()) ok++;
            }
            if (ok != 1) out.add(path + ": matches " + ok + " oneOf branches");
        }
    }

    private Object resolve(String ref) {
        if (!ref.startsWith("#/")) throw new IllegalArgumentException("only local refs: " + ref);
        Object node = root;
        for (String seg : ref.substring(2).split("/")) node = Objects.requireNonNull(((Map<?, ?>) node).get(seg), ref);
        return node;
    }

    private static boolean typeMatches(@Nullable Object node, @Nullable Object type) {
        if (type instanceof List<?> types) {
            for (Object t : types) if (typeMatches(node, t)) return true;
            return false;
        }
        return switch (String.valueOf(type)) {
            case "object" -> node instanceof Map;
            case "array" -> node instanceof List;
            case "string" -> node instanceof String;
            case "boolean" -> node instanceof Boolean;
            case "integer" -> node instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue());
            case "number" -> node instanceof Number;
            case "null" -> node == null;
            default -> true;
        };
    }

    private static String kind(@Nullable Object node) {
        return node == null ? "null" : node.getClass().getSimpleName();
    }
}

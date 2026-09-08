// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jsonl;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The one way jk writes a compact JSON object line: fields in call order, strings through {@link
 * Jsonl#quote}, numbers and booleans as Java prints them, and {@link #token} for a value already in
 * wire form (a number-or-null token, a nested object). {@link #object()} opens a bare object; a wire
 * frame opens with its discriminator through the wire package's own factory. Every encoder builds
 * through here so the separator, the escaping and the null spelling have one owner.
 */
public final class JsonFields {
    private final StringBuilder json;
    private final boolean object;
    private boolean first = true;

    private JsonFields(boolean object) {
        this.object = object;
        this.json = new StringBuilder(object ? "{" : "");
    }

    /** An object, opened. */
    public static JsonFields object() {
        return new JsonFields(true);
    }

    /** A run of fields with no braces of their own, for splicing after an object's first fields ({@link #suffix}). */
    public static JsonFields fields() {
        return new JsonFields(false);
    }

    public JsonFields bool(String name, boolean value) {
        return raw(name, Boolean.toString(value));
    }

    public JsonFields number(String name, int value) {
        return raw(name, Integer.toString(value));
    }

    public JsonFields number(String name, long value) {
        return raw(name, Long.toString(value));
    }

    public JsonFields string(String name, @Nullable String value) {
        return raw(name, Jsonl.quote(value));
    }

    /** {@code value}, or {@code defaultValue} when null — the sites that spell a missing string as {@code ""}. */
    public JsonFields string(String name, @Nullable String value, String defaultValue) {
        return string(name, value == null ? defaultValue : value);
    }

    public JsonFields array(String name, @Nullable List<String> values) {
        return raw(name, Jsonl.array(values == null ? List.of() : values));
    }

    public JsonFields map(String name, @Nullable Map<String, String> values) {
        return raw(name, Jsonl.map(values == null ? Map.of() : values));
    }

    /** A value already in wire form — a number-or-null token, a nested object — appended verbatim. */
    public JsonFields token(String name, String jsonToken) {
        return raw(name, jsonToken);
    }

    public JsonFields optionalTrue(String name, boolean value) {
        return value ? bool(name, true) : this;
    }

    public JsonFields optionalString(String name, @Nullable String value) {
        return value == null ? this : string(name, value);
    }

    public JsonFields optionalNonBlankString(String name, @Nullable String value) {
        return value == null || value.isBlank() ? this : string(name, value);
    }

    /** {@code value} unless null or empty — the additive fields that ride only when they say something. */
    public JsonFields optionalNonEmptyString(String name, @Nullable String value) {
        return value == null || value.isEmpty() ? this : string(name, value);
    }

    public JsonFields optionalNumber(String name, long value, long omitAtOrBelow) {
        return value <= omitAtOrBelow ? this : number(name, value);
    }

    public JsonFields optionalArray(String name, @Nullable List<String> values) {
        return values == null || values.isEmpty() ? this : array(name, values);
    }

    public JsonFields optionalMap(String name, @Nullable Map<String, String> values) {
        return values == null || values.isEmpty() ? this : map(name, values);
    }

    /** Whether any field has been written. */
    public boolean isEmpty() {
        return first;
    }

    /** The closed object. */
    public String finish() {
        if (!object) throw new IllegalStateException("field fragments use suffix()");
        return json.append('}').toString();
    }

    /** The fields with a leading comma, or {@code ""} when none — for appending to an object built elsewhere. */
    public String suffix() {
        if (object) throw new IllegalStateException("objects use finish()");
        return json.isEmpty() ? "" : "," + json;
    }

    private JsonFields raw(String name, String value) {
        if (!first) json.append(',');
        first = false;
        json.append(Jsonl.quote(name)).append(':').append(value);
        return this;
    }
}

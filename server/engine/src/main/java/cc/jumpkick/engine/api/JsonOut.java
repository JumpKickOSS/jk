// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import cc.jumpkick.jsonl.MiniJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Fluent sugar for the flat JSON objects the REST surface emits — the same deliberate
 * flat-scalar-fields discipline as the engine wire protocol ({@code EngineProtocol}). Rendering
 * (and therefore escaping) is {@link MiniJson}, the engine's single JSON home; this class only
 * contributes the builder ergonomics the HTTP handlers use in ~40 places. Public (not
 * package-private) because {@code EngineServer} builds {@code HttpEvents} payloads.
 */
public final class JsonOut {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    public static JsonOut object() {
        return new JsonOut();
    }

    /**
     * Wrap an already-built map (nested lists/maps allowed) so SSE payloads can carry structured
     * mid-flight snapshots without a second JSON dialect.
     */
    public static JsonOut rawObject(Map<String, Object> fields) {
        JsonOut o = new JsonOut();
        if (fields != null) o.fields.putAll(fields);
        return o;
    }

    public JsonOut put(String key, @Nullable String value) {
        fields.put(key, value);
        return this;
    }

    /** Nested object/array (maps/lists) — MiniJson serializes them recursively. */
    public JsonOut putObject(String key, @Nullable Object value) {
        fields.put(key, value);
        return this;
    }

    public JsonOut put(String key, long value) {
        fields.put(key, value);
        return this;
    }

    /** Floating progress percent (0–100) and similar scalars — MiniJson writes a JSON number. */
    public JsonOut put(String key, double value) {
        fields.put(key, value);
        return this;
    }

    public JsonOut put(String key, boolean value) {
        fields.put(key, value);
        return this;
    }

    /** Put a nullable number (e.g. {@code progress: null} until known). */
    public JsonOut putNullable(String key, @Nullable Double value) {
        fields.put(key, value);
        return this;
    }

    /** A flat array of strings — the one non-scalar shape the jk wire discipline allows. */
    public JsonOut putStrings(String key, List<String> values) {
        fields.put(key, List.copyOf(values));
        return this;
    }

    /** Render the object (compact). */
    @Override
    public String toString() {
        return MiniJson.write(fields);
    }
}

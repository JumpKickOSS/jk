// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Renders a test-event payload as JSON. Serialization and escaping are {@link MiniJson}'s — this
 * class only widens the accepted input to the value types a listener payload actually carries,
 * which is the whole of what a second encoder here was ever for.
 *
 * <p>{@link MiniJson#write} is total over the JSON value domain and throws on anything outside it.
 * A test event is assembled from JUnit's callbacks, so a payload can hold a {@link Path}, a
 * {@code Duration} or an enum; a listener firing during someone else's test run is the worst place
 * to discover a missing case, so those normalize to their string form instead.
 */
final class JsonOut {

    private JsonOut() {}

    /** {@code value} as compact JSON. */
    static String string(@Nullable Object value) {
        return MiniJson.write(normalize(value));
    }

    /**
     * {@code value} in {@link MiniJson}'s value domain: null, String, Boolean, Number, Map or List.
     */
    private static @Nullable Object normalize(@Nullable Object value) {
        if (value == null || value instanceof Boolean || value instanceof Number) return value;
        if (value instanceof CharSequence cs) return cs.toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, @Nullable Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            return out;
        }
        // Before the Iterable arm: a Path IS an Iterable<Path> whose elements are themselves
        // single-name Paths, so the array branch recurses forever and blows the stack. A file
        // location on an event payload is one string.
        if (value instanceof Path path) return path.toString();
        if (value instanceof Iterable<?> it) {
            List<@Nullable Object> out = new ArrayList<>();
            for (Object v : it) out.add(normalize(v));
            return out;
        }
        // Enums, Duration, Instant, …: one string, not a parse error mid-run.
        return value.toString();
    }
}

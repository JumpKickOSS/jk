// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.util.Objects.requireNonNull;

import cc.jumpkick.jsonl.MiniJson;
import java.util.List;
import java.util.Map;

/**
 * Typed reads of a decoded JSON object whose members the protocol under test guarantees. A
 * {@code Map.get} is nullable by the map's contract and present by the wire contract the test
 * pins; reading through here makes an absence fail at the read, with the member's name, instead
 * of at whatever dereference came next.
 */
public final class JsonFields {

    private JsonFields() {}

    /** The top-level object a JSON document decodes to. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        return (Map<String, Object>) requireNonNull(MiniJson.parse(json));
    }

    /** The member {@code key}, which must be present. */
    public static Object value(Map<String, Object> object, String key) {
        return requireNonNull(object.get(key), () -> "missing \"" + key + "\" in " + object.keySet());
    }

    /** The object-valued member {@code key}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Map<String, Object> object, String key) {
        return (Map<String, Object>) value(object, key);
    }

    /** The member {@code key}, an array of objects. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Map<String, Object> object, String key) {
        return (List<Map<String, Object>>) value(object, key);
    }

    /** The string-valued member {@code key}. */
    public static String string(Map<String, Object> object, String key) {
        return (String) value(object, key);
    }

    /** The numeric member {@code key}; MiniJson decodes every number as a double. */
    public static Number number(Map<String, Object> object, String key) {
        return (Number) value(object, key);
    }
}

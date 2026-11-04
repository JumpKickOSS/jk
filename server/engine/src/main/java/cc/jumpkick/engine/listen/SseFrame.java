// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.jsonl.MiniJson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * An event's wire record as an SSE frame: {@code schema}, {@code type} (the name SSE gives the
 * event) and {@code jid}, then the record's own fields as it spells them, its free text masked.
 * The dashboard and MCP read the fields the wire and {@code -O jsonl} carry, under the same names.
 */
public final class SseFrame {

    /** Free text an event may carry from a build's environment; masked before a frame leaves. */
    private static final Set<String> FREE_TEXT = Set.of("label", "line", "message", "stack");

    private SseFrame() {}

    public static JsonOut of(String type, long requestId, String record) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("schema", 1);
        fields.put("type", type);
        fields.put("jid", requestId);
        if (MiniJson.parse(record) instanceof Map<?, ?> parsed) {
            String dir = parsed.get("dir") instanceof String d ? d : null;
            for (Map.Entry<?, ?> e : parsed.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (fields.containsKey(key)) continue;
                Object value = e.getValue();
                if (FREE_TEXT.contains(key) && value instanceof String text) {
                    value = EventRedaction.redactEnv(dir, text);
                }
                fields.put(key, value);
            }
        }
        return JsonOut.rawObject(fields);
    }
}

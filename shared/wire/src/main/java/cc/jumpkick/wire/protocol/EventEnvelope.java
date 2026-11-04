// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A surface's projection of one event record: the surface's envelope, then the record's own
 * encoded fields as they are. The console's envelope is {@code schema}, {@code ts} and the type it
 * names the event by; SSE's adds {@code jid}. A projection may drop a field it does not show (the
 * console's {@code dir} on a step's events, a download frame's per-transfer rows), never rename
 * one: every surface spells an event's fields as its record does.
 */
public final class EventEnvelope {

    private EventEnvelope() {}

    /**
     * {@code envelope}'s fields, then {@code record}'s — a line a wire record encoded — less those
     * the envelope already set (its {@code type} names the event on this surface) and those named
     * in {@code dropped}. Both are JSON objects.
     */
    public static String project(String envelope, String record, Set<String> dropped) {
        List<String> head = topLevel(envelope);
        Set<String> set = new HashSet<>();
        for (String pair : head) set.add(keyOf(pair));
        List<String> out = new ArrayList<>(head);
        for (String pair : topLevel(record)) {
            String key = keyOf(pair);
            if (key.equals("schema") || set.contains(key) || dropped.contains(key)) continue;
            out.add(pair);
        }
        return "{" + String.join(",", out) + "}";
    }

    /** The top-level keys of a JSON object line, in order. */
    public static List<String> keys(String line) {
        List<String> out = new ArrayList<>();
        for (String pair : topLevel(line)) out.add(keyOf(pair));
        return out;
    }

    /** The top-level {@code "key":value} pairs of a JSON object, each as written. */
    static List<String> topLevel(String json) {
        String body = json.strip();
        if (!body.startsWith("{") || !body.endsWith("}")) {
            throw new IllegalArgumentException("not a JSON object: " + json);
        }
        List<String> pairs = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 1;
        for (int i = 1; i < body.length() - 1; i++) {
            char c = body.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> depth++;
                case '}', ']' -> depth--;
                case ',' -> {
                    if (depth == 0) {
                        pairs.add(body.substring(start, i).strip());
                        start = i + 1;
                    }
                }
                default -> {}
            }
        }
        String last = body.substring(start, body.length() - 1).strip();
        if (!last.isEmpty()) pairs.add(last);
        return pairs;
    }

    private static String keyOf(String pair) {
        int open = pair.indexOf('"');
        int close = pair.indexOf('"', open + 1);
        return pair.substring(open + 1, close);
    }
}

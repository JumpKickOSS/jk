// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import cc.jumpkick.engine.savings.AgentUsage.Entry;
import cc.jumpkick.engine.savings.AgentUsage.Parsed;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Antigravity's {@code <home>/conversations/<conversation>.db}, an SQLite database whose rows hold
 * protocol buffers. Its checkout is the {@code file:} URI first in the {@code
 * trajectory_metadata_blob} row; each step's {@code metadata} holds when it was created and, for a
 * step that called a model, that call's {@code ModelUsageStats}; the model's id is in the {@code
 * gen_metadata} row of the same call, matched by the call's message id. Antigravity counts input
 * apart from the prompt cache.
 */
final class AntigravityUsage {

    /** {@code steps.metadata}, the fifth column: {@code CortexStepMetadata}. */
    private static final int STEP_METADATA = 4;

    /** {@code CortexStepMetadata}: {@code created_at} and {@code model_usage}. */
    private static final int CREATED_AT = 1;

    private static final int MODEL_USAGE = 9;

    /** {@code ModelUsageStats}' token counts and message id. */
    private static final int INPUT = 2;

    private static final int OUTPUT = 3;
    private static final int CACHE_WRITE = 4;
    private static final int CACHE_READ = 5;
    private static final int MESSAGE_ID = 7;

    /** {@code CortexGeneratorMetadata.chat_model}: {@code usage} and {@code response_model}. */
    private static final int CHAT_MODEL = 1;

    private static final int CHAT_USAGE = 4;
    private static final int RESPONSE_MODEL = 19;

    private AntigravityUsage() {}

    static Parsed parse(Path file, long size, long modified) throws IOException {
        try (SqliteFile db = SqliteFile.open(file)) {
            Map<String, String> models = new HashMap<>();
            for (Object row : db.column("gen_metadata", 1)) {
                if (!(row instanceof byte[] data)) continue;
                try {
                    Map<Integer, List<Object>> chat = Proto.message(Proto.first(Proto.fields(data), CHAT_MODEL));
                    String id = Proto.text(Proto.first(Proto.message(Proto.first(chat, CHAT_USAGE)), MESSAGE_ID));
                    if (id != null) models.put(id, AgentTokens.model(Proto.text(Proto.first(chat, RESPONSE_MODEL))));
                } catch (IllegalArgumentException e) {
                    // Only names the model; the step's count still counts, as unknown.
                }
            }
            Map<String, Entry> byCall = new LinkedHashMap<>();
            int n = 0;
            for (Object row : db.column("steps", STEP_METADATA)) {
                n++;
                if (!(row instanceof byte[] data)) continue;
                Map<Integer, List<Object>> step;
                Map<Integer, List<Object>> usage;
                long at;
                try {
                    step = Proto.fields(data);
                    usage = Proto.message(Proto.first(step, MODEL_USAGE));
                    at = millis(Proto.message(Proto.first(step, CREATED_AT)));
                } catch (IllegalArgumentException e) {
                    AgentUsage.skipped(file, n);
                    continue;
                }
                if (usage.isEmpty()) continue;
                if (at < 0) {
                    AgentUsage.skipped(file, n);
                    continue;
                }
                String id = Proto.text(Proto.first(usage, MESSAGE_ID));
                AgentTokens.Counts counts = new AgentTokens.Counts(
                        Proto.count(usage, INPUT),
                        Proto.count(usage, CACHE_WRITE),
                        Proto.count(usage, CACHE_READ),
                        Proto.count(usage, OUTPUT));
                String model = id == null ? AgentTokens.UNKNOWN : models.getOrDefault(id, AgentTokens.UNKNOWN);
                byCall.putIfAbsent(id == null ? "step " + n : id, new Entry(at, model, counts));
            }
            return new Parsed(size, modified, checkout(db), List.copyOf(byCall.values()));
        }
    }

    /** The checkout of a conversation: the {@code file:} URI its trajectory's metadata starts with. */
    private static @Nullable String checkout(SqliteFile db) throws IOException {
        for (Object row : db.column("trajectory_metadata_blob", 1)) {
            if (!(row instanceof byte[] data)) continue;
            try {
                Map<Integer, List<Object>> workspace = Proto.message(Proto.first(Proto.fields(data), 1));
                String uri = Proto.text(Proto.first(workspace, 1));
                if (uri != null && uri.startsWith("file:"))
                    return Path.of(URI.create(uri)).toString();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** A {@code google.protobuf.Timestamp} as epoch millis, or -1. */
    private static long millis(Map<Integer, List<Object>> timestamp) {
        long seconds = Proto.count(timestamp, 1);
        long nanos = Proto.count(timestamp, 2);
        return seconds > 0 ? seconds * 1000 + nanos / 1_000_000 : -1;
    }

    /** Just enough of the protocol buffer wire format to read numbers and nested messages. */
    static final class Proto {

        private Proto() {}

        /** Each field of a message by number: a varint as a {@code Long}, a length-delimited one as {@code byte[]}. */
        static Map<Integer, List<Object>> fields(byte[] b) {
            Map<Integer, List<Object>> out = new HashMap<>();
            int[] at = {0};
            while (at[0] < b.length) {
                long key = varint(b, at);
                int field = (int) (key >>> 3);
                Object value =
                        switch ((int) (key & 7)) {
                            case 0 -> varint(b, at);
                            case 1 -> skip(b, at, 8);
                            case 2 -> {
                                long len = varint(b, at);
                                if (len < 0 || len > b.length - at[0])
                                    throw new IllegalArgumentException("a field past the end");
                                byte[] v = Arrays.copyOfRange(b, at[0], at[0] + (int) len);
                                at[0] += (int) len;
                                yield v;
                            }
                            case 5 -> skip(b, at, 4);
                            default -> throw new IllegalArgumentException("wire type " + (key & 7));
                        };
                if (field < 1) throw new IllegalArgumentException("field " + field);
                if (value != null)
                    out.computeIfAbsent(field, k -> new ArrayList<>()).add(value);
            }
            return out;
        }

        private static @Nullable Object skip(byte[] b, int[] at, int n) {
            if (n > b.length - at[0]) throw new IllegalArgumentException("a field past the end");
            at[0] += n;
            return null;
        }

        private static long varint(byte[] b, int[] at) {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (at[0] >= b.length) throw new IllegalArgumentException("a varint past the end");
                int c = b[at[0]++] & 0xff;
                v |= (long) (c & 0x7f) << shift;
                if (c < 0x80) return v;
            }
            throw new IllegalArgumentException("a varint too long");
        }

        static @Nullable Object first(Map<Integer, List<Object>> message, int field) {
            List<Object> values = message.get(field);
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        /** A nested message's fields; empty when {@code value} is not one. */
        static Map<Integer, List<Object>> message(@Nullable Object value) {
            return value instanceof byte[] b ? fields(b) : Map.of();
        }

        /** A string field's text, or null. */
        static @Nullable String text(@Nullable Object value) {
            return value instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : null;
        }

        /** A number field, 0 when absent and when it is not a whole non-negative number. */
        static long count(Map<Integer, List<Object>> message, int field) {
            return first(message, field) instanceof Long l && l >= 0 ? l : 0;
        }
    }
}

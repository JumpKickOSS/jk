// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import cc.jumpkick.engine.savings.AgentUsage.Entry;
import cc.jumpkick.engine.savings.AgentUsage.Parsed;
import cc.jumpkick.jsonl.MiniJson;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Muse's {@code <data home>/muse/sessions/<yyyy>/<mm>/<dd>/<session>/session.jsonl}, a subagent's
 * one level further down: its checkout from the {@code runtime.session.metadata} record's {@code
 * workspace_root}, and the usage of each {@code model_completed} and {@code
 * automated_review_completed} event, at its {@code recorded_at} (epoch microseconds), each record
 * counted once by its {@code id}. Muse's {@code input_tokens} include the tokens read from and
 * written to the prompt cache.
 */
final class MuseUsage {

    private MuseUsage() {}

    static Parsed parse(Path file, long size, long modified) throws IOException {
        Map<String, Entry> byRecord = new LinkedHashMap<>();
        String cwd = null;
        int n = 0;
        try (BufferedReader in = AgentUsage.reader(file)) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                n++;
                boolean meta = line.contains("\"runtime.session.metadata\"");
                if (!meta && !line.contains("\"model_completed\"") && !line.contains("\"automated_review_completed\""))
                    continue;
                Object root = AgentUsage.json(file, n, line);
                Object payload = MiniJson.get(root, "payload");
                if ("runtime.session.metadata".equals(MiniJson.str(root, "payload_type"))) {
                    if (cwd == null) cwd = MiniJson.str(MiniJson.get(payload, "record"), "workspace_root");
                    continue;
                }
                Object event = MiniJson.get(payload, "event");
                String kind = MiniJson.str(event, "kind");
                boolean review = "automated_review_completed".equals(kind);
                if (!review && !"model_completed".equals(kind)) continue;
                Object usage = MiniJson.get(event, "usage");
                if (usage == null && review) continue; // a review that called no model
                long at = MiniJson.get(root, "recorded_at") instanceof Long micros && micros >= 0 ? micros / 1000 : -1;
                AgentTokens.Counts counts = review ? review(usage) : completed(usage);
                if (counts == null || at < 0) {
                    AgentUsage.skipped(file, n);
                    continue;
                }
                Object model =
                        review ? MiniJson.get(MiniJson.get(event, "model"), "model_id") : MiniJson.get(event, "model");
                String id = MiniJson.str(root, "id");
                byRecord.put(id == null ? "line " + n : id, new Entry(at, AgentTokens.model(model), counts));
            }
        }
        return new Parsed(size, modified, cwd, List.copyOf(byRecord.values()));
    }

    /** A model call's usage: {@code cache_read_tokens}, or {@code cached_tokens} where that is all it says. */
    private static AgentTokens.@Nullable Counts completed(@Nullable Object usage) {
        if (!(usage instanceof Map<?, ?>)) return null;
        long input = AgentUsage.count(usage, "input_tokens");
        long read = MiniJson.get(usage, "cache_read_tokens") != null
                ? AgentUsage.count(usage, "cache_read_tokens")
                : AgentUsage.count(usage, "cached_tokens");
        long write = AgentUsage.count(usage, "cache_write_tokens");
        long output = AgentUsage.count(usage, "output_tokens");
        if ((input | read | write | output) < 0) return null;
        return new AgentTokens.Counts(Math.max(0, input - read - write), write, read, output);
    }

    /** An approval review's usage, which names its cache reads {@code cached_input_tokens}. */
    private static AgentTokens.@Nullable Counts review(@Nullable Object usage) {
        if (!(usage instanceof Map<?, ?>)) return null;
        long input = AgentUsage.count(usage, "input_tokens");
        long read = AgentUsage.count(usage, "cached_input_tokens");
        long output = AgentUsage.count(usage, "output_tokens");
        if ((input | read | output) < 0) return null;
        return new AgentTokens.Counts(Math.max(0, input - read), 0, read, output);
    }
}

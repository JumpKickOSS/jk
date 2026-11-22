// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import cc.jumpkick.engine.savings.AgentUsage.Entry;
import cc.jumpkick.engine.savings.AgentUsage.Parsed;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Grok's {@code <home>/sessions/<checkout, percent-encoded>/<session>/usage.json}: each of its
 * {@code turns}, at its {@code endedAt}, counted per model from its {@code modelUsage}. Grok's
 * {@code inputTokens} include the tokens read from and written to the prompt cache.
 */
final class GrokUsage {

    private GrokUsage() {}

    static Parsed parse(Path file, long size, long modified) throws IOException {
        Object root = AgentUsage.json(file, 1, Files.readString(file, StandardCharsets.UTF_8));
        List<Entry> entries = new ArrayList<>();
        int n = 0;
        for (Object turn : MiniJson.list(root, "turns")) {
            n++;
            long at = AgentUsage.millis(MiniJson.get(turn, "endedAt"));
            if (!(MiniJson.get(turn, "modelUsage") instanceof Map<?, ?> models) || at < 0) {
                skipped(file, n);
                continue;
            }
            for (Map.Entry<?, ?> m : models.entrySet()) {
                AgentTokens.Counts counts = counts(m.getValue());
                if (counts == null) {
                    skipped(file, n);
                    continue;
                }
                entries.add(new Entry(at, AgentTokens.model(m.getKey()), counts));
            }
        }
        return new Parsed(size, modified, checkout(file), List.copyOf(entries));
    }

    private static AgentTokens.@Nullable Counts counts(@Nullable Object usage) {
        long input = AgentUsage.count(usage, "inputTokens");
        long read = AgentUsage.count(usage, "cachedReadTokens");
        long write = AgentUsage.count(usage, "cacheCreationTokens");
        long output = AgentUsage.count(usage, "outputTokens");
        if ((input | read | write | output) < 0) return null;
        return new AgentTokens.Counts(Math.max(0, input - read - write), write, read, output);
    }

    /** The checkout a session ran in, from the name of the directory above it. */
    static @Nullable String checkout(Path file) {
        Path session = file.getParent();
        Path dir = session == null ? null : session.getParent();
        if (dir == null || dir.getFileName() == null) return null;
        try {
            return URLDecoder.decode(dir.getFileName().toString().replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void skipped(Path file, int turn) {
        Log.debug("agent usage: " + file + ": turn " + turn + ": not a usage record, skipped");
    }
}

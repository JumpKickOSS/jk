// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import cc.jumpkick.engine.savings.AgentUsage.Entry;
import cc.jumpkick.engine.savings.AgentUsage.Parsed;
import cc.jumpkick.jsonl.MiniJson;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Copilot CLI's {@code <home>/session-state/<session>/events.jsonl}: its checkout from {@code
 * session.start}'s {@code context.cwd}, and each {@code session.shutdown}'s {@code modelMetrics},
 * the session's running total per model, taken as the growth since the shutdown before so a resumed
 * session counts each token once. Copilot keeps no count per call on disk, so a whole session's
 * count lands at its end. Its {@code inputTokens} include the tokens read from and written to the
 * prompt cache.
 */
final class CopilotUsage {

    private CopilotUsage() {}

    static Parsed parse(Path file, long size, long modified) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Map<String, AgentTokens.Counts> seen = new HashMap<>();
        String cwd = null;
        int n = 0;
        try (BufferedReader in = AgentUsage.reader(file)) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                n++;
                boolean start = line.contains("\"session.start\"");
                if (!start && !line.contains("\"session.shutdown\"")) continue;
                Object root = AgentUsage.json(file, n, line);
                String type = MiniJson.str(root, "type");
                Object data = MiniJson.get(root, "data");
                if ("session.start".equals(type)) {
                    if (cwd == null) cwd = MiniJson.str(MiniJson.get(data, "context"), "cwd");
                    continue;
                }
                if (!"session.shutdown".equals(type)) continue;
                long at = AgentUsage.millis(MiniJson.get(root, "timestamp"));
                if (!(MiniJson.get(data, "modelMetrics") instanceof Map<?, ?> models) || at < 0) {
                    AgentUsage.skipped(file, n);
                    continue;
                }
                for (Map.Entry<?, ?> m : models.entrySet()) {
                    AgentTokens.Counts total = total(MiniJson.get(m.getValue(), "usage"));
                    if (total == null) {
                        AgentUsage.skipped(file, n);
                        continue;
                    }
                    String model = AgentTokens.model(m.getKey());
                    AgentTokens.Counts grown = growth(seen.get(model), total);
                    seen.put(model, total);
                    if (grown.total() > 0) entries.add(new Entry(at, model, grown));
                }
            }
        }
        return new Parsed(size, modified, cwd, List.copyOf(entries));
    }

    /** A model's running total, fresh input apart from the cache's. */
    private static AgentTokens.@Nullable Counts total(@Nullable Object usage) {
        long input = AgentUsage.count(usage, "inputTokens");
        long read = AgentUsage.count(usage, "cacheReadTokens");
        long write = AgentUsage.count(usage, "cacheWriteTokens");
        long output = AgentUsage.count(usage, "outputTokens");
        if ((input | read | write | output) < 0) return null;
        return new AgentTokens.Counts(Math.max(0, input - read - write), write, read, output);
    }

    /** What {@code now} adds to {@code before}; all of {@code now} when it is not a later total of the same run. */
    private static AgentTokens.Counts growth(AgentTokens.@Nullable Counts before, AgentTokens.Counts now) {
        if (before == null
                || now.input() < before.input()
                || now.cacheWrite() < before.cacheWrite()
                || now.cacheRead() < before.cacheRead()
                || now.output() < before.output()) {
            return now;
        }
        return new AgentTokens.Counts(
                now.input() - before.input(),
                now.cacheWrite() - before.cacheWrite(),
                now.cacheRead() - before.cacheRead(),
                now.output() - before.output());
    }
}

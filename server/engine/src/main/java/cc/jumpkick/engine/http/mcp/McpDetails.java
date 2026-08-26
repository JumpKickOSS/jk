// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * MCP {@code jk_details}: a budgeted tail of a run's {@code details.jsonl} transcript. The default
 * shape (last-fail run, error + task-finish events, 80-event tail, hard byte budget) keeps a
 * failed compile under a few KiB; the absolute path rides along for hosts that want the whole
 * file. Paging: {@code next} counts matching events already consumed from the newest end.
 */
public final class McpDetails {

    /** Default event-type filter: what an agent acts on first. */
    public static final List<String> DEFAULT_TYPES = List.of("error", EngineProtocol.TASK_FINISH);

    public static final int DEFAULT_TAIL = 80;
    public static final int MAX_TAIL = 400;

    /** Hard serialized budget for the events array (raw JSONL chars). */
    static final int MAX_CHARS = 24_000;

    private McpDetails() {}

    /**
     * @param rec resolved journal record (from {@code McpDiagnostics.findRun})
     * @param detailsFile locator → transcript path resolver (journal-backed)
     * @param types event-type filter; empty → {@link #DEFAULT_TYPES}
     * @param tail max events returned (clamped to {@link #MAX_TAIL})
     * @param next matching events already consumed from the newest end (prior call's cursor)
     */
    public static Map<String, Object> tail(
            @Nullable Map<String, Object> rec,
            Function<String, Optional<Path>> detailsFile,
            List<String> types,
            int tail,
            int next) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (rec == null) {
            m.put("error", "no matching run (nothing failed, or pass run=<history id>)");
            return m;
        }
        String id = McpHistoryViews.str(rec, "id");
        m.put("run", id);
        long jid = McpHistoryViews.lng(rec, "requestId");
        if (jid > 0) m.put("jid", jid);
        Optional<Path> file = id == null ? Optional.empty() : detailsFile.apply(id);
        if (file.isEmpty() || !Files.isRegularFile(file.get())) {
            m.put("error", "no details.jsonl for run " + id + " (transcripts prune with history)");
            return m;
        }
        m.put("path", file.get().toString());
        Set<String> wanted = new LinkedHashSet<>(types == null || types.isEmpty() ? DEFAULT_TYPES : types);
        m.put("types", new ArrayList<>(wanted));

        List<String> matching = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file.get(), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String type = Jsonl.str(line, "type");
                if (type != null && wanted.contains(type)) matching.add(line);
            }
        } catch (IOException e) {
            m.put("error", "reading " + file.get() + ": " + e.getMessage());
            return m;
        }
        int total = matching.size();
        m.put("count", total);

        int want = Math.max(1, Math.min(tail <= 0 ? DEFAULT_TAIL : tail, MAX_TAIL));
        int skipNewest = Math.max(0, next);
        int end = Math.max(0, total - skipNewest);
        int start = Math.max(0, end - want);
        // Enforce the byte budget from the newest edge of the window backward.
        int chars = 0;
        List<Map<String, Object>> events = new ArrayList<>();
        int first = end;
        for (int i = end - 1; i >= start; i--) {
            String line = matching.get(i);
            if (chars + line.length() > MAX_CHARS && !events.isEmpty()) break;
            chars += line.length();
            first = i;
            Object parsed = MiniJson.parse(line);
            if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ev = (Map<String, Object>) map;
                events.add(0, ev);
            }
        }
        m.put("events", events);
        boolean truncated = first > 0;
        m.put("truncatedTail", truncated);
        if (truncated) m.put("nextCursor", skipNewest + events.size());
        return m;
    }
}

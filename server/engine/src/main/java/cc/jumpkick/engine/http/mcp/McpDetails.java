// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
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
 * MCP {@code details}: a budgeted tail of a run's {@code details.jsonl} transcript. The default
 * shape (last-fail run, error + task-finish events, 80-event tail, hard byte budget) keeps a
 * failed compile under a few KiB; the absolute path rides along for hosts that want the whole
 * file. Each event has the run's project secrets masked as it is read. Paging: {@code next} counts matching events already consumed from the newest end.
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
     * Matching lines read in one pass with a ring at most this long; a window reaching deeper is
     * read in two passes (count, then collect), so no cursor makes the tail hold the transcript.
     */
    static final int ONE_PASS_LINES = 2 * MAX_TAIL;

    /**
     * The matching lines of a window, oldest first, and how many lines of a transcript matched in
     * all. The window is the {@code want} matching lines that come before the newest {@code skip}.
     */
    record Matching(List<String> lines, int total) {}

    /** Read {@code file} for the window of {@code want} matching lines before the newest {@code skip}. */
    static Matching window(Path file, Set<String> wanted, int skip, int want) throws IOException {
        if ((long) skip + want <= ONE_PASS_LINES) {
            Matching newest = lastMatching(file, wanted, skip + want);
            List<String> lines = newest.lines();
            return new Matching(lines.subList(0, Math.max(0, lines.size() - skip)), newest.total());
        }
        int total = 0;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null; ) if (matches(line, wanted)) total++;
        }
        // A transcript still being written may grow between the passes: the window stays where the count put it.
        long end = Math.max(0L, (long) total - skip);
        long start = Math.max(0L, end - want);
        List<String> lines = new ArrayList<>();
        int index = 0;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; index < end && (line = in.readLine()) != null; ) {
                if (!matches(line, wanted)) continue;
                if (index >= start) lines.add(line);
                index++;
            }
        }
        return new Matching(List.copyOf(lines), total);
    }

    /** Read {@code file} once, holding at most {@code keep} matching lines: the newest. */
    static Matching lastMatching(Path file, Set<String> wanted, int keep) throws IOException {
        ArrayDeque<String> ring = new ArrayDeque<>(Math.min(keep, 1_024));
        int total = 0;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null; ) {
                if (!matches(line, wanted)) continue;
                total++;
                if (ring.size() == keep) ring.removeFirst();
                ring.addLast(line);
            }
        }
        return new Matching(List.copyOf(ring), total);
    }

    private static boolean matches(String line, Set<String> wanted) {
        if (line.isBlank()) return false;
        String type = Jsonl.str(line, "type");
        return type != null && wanted.contains(type);
    }

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

        int want = Math.max(1, Math.min(tail <= 0 ? DEFAULT_TAIL : tail, MAX_TAIL));
        int skipNewest = Math.max(0, next);
        Matching window;
        try {
            window = window(file.get(), wanted, skipNewest, want);
        } catch (IOException e) {
            m.put("error", "reading " + file.get() + ": " + e.getMessage());
            return m;
        }
        List<String> matching = window.lines();
        int total = window.total();
        m.put("count", total);

        // Matching events older than the window.
        long older = Math.max(0L, (long) total - skipNewest - matching.size());
        int end = matching.size();
        // Enforce the byte budget from the newest edge of the window backward.
        SecretRedactor redactor = redactorFor(McpHistoryViews.str(rec, "dir"));
        int chars = 0;
        List<Map<String, Object>> events = new ArrayList<>();
        int first = end;
        for (int i = end - 1; i >= 0; i--) {
            String line = redact(redactor, matching.get(i));
            if (chars + line.length() > MAX_CHARS && !events.isEmpty()) break;
            chars += line.length();
            first = i;
            Object parsed;
            try {
                parsed = MiniJson.parse(line);
            } catch (IllegalArgumentException e) {
                // A line cut short (a transcript still being written) or corrupt: skip it, keep the rest.
                continue;
            }
            if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ev = (Map<String, Object>) map;
                events.add(0, ev);
            }
        }
        m.put("events", events);
        boolean truncated = older + first > 0;
        m.put("truncatedTail", truncated);
        if (truncated) m.put("nextCursor", skipNewest + (end - first));
        return m;
    }

    /** The redactor for the run's project; fail-open, as every read-time redaction is. */
    private static SecretRedactor redactorFor(String dir) {
        try {
            return EventRedaction.redactorFor(dir).forEscapedJson();
        } catch (RuntimeException e) {
            // A record with no dir read outside a session is routine, not a broken redactor.
            if (!dir.isBlank()) EventRedaction.warnFailOpen(e);
            return SecretRedactor.none();
        }
    }

    /** {@code line}, a JSON document, with each secret masked in its escaped form too. */
    private static String redact(SecretRedactor redactor, String line) {
        try {
            return redactor.redact(line);
        } catch (RuntimeException e) {
            EventRedaction.warnFailOpen(e);
            return line;
        }
    }
}

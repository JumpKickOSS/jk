// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.diagnostic.CompilerLocus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Unique, budgeted diagnostic rows from a journal record. */
public final class McpDiagnostics {

    private McpDiagnostics() {}

    public static List<Map<String, Object>> unique(List<Map<String, Object>> raw, boolean unique) {
        if (!unique) {
            // Same normalized row shape as the deduped default — the flag only controls dedup.
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> d : raw) {
                Map<String, Object> row = normalize(d);
                row.put("count", 1);
                out.add(row);
            }
            return out;
        }
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> d : raw) {
            Map<String, Object> row = normalize(d);
            String key = key(row);
            Map<String, Object> prev = byKey.get(key);
            if (prev == null) {
                row.put("count", 1);
                byKey.put(key, row);
            } else {
                prev.put("count", ((Number) prev.getOrDefault("count", 1)).intValue() + 1);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> fromRecord(Map<String, Object> rec) {
        Object raw = rec.get("diagnostics");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    public static @Nullable Map<String, Object> findRun(
            List<String> historyRaw, @Nullable String run, @Nullable String dir) {
        if (historyRaw == null) return null;
        boolean lastFail = run == null || run.isBlank() || "last-fail".equalsIgnoreCase(run);
        for (String raw : historyRaw) {
            Map<String, Object> rec = McpHistoryViews.parseRecord(raw);
            if (rec == null) continue;
            if (McpHistoryViews.bool(rec, "running")) continue;
            if (lastFail) {
                if (!McpHistoryViews.matches(rec, dir, null, Boolean.FALSE, null)) continue;
            } else {
                // A specific run id names one record; the bound-dir filter must not hide it.
                if (!run.equals(McpHistoryViews.str(rec, "id"))) continue;
            }
            return rec;
        }
        return null;
    }

    /** Finished row whose {@code requestId} equals {@code jid}. */
    public static @Nullable Map<String, Object> findByRequestId(List<String> historyRaw, long jid) {
        if (historyRaw == null || jid <= 0) return null;
        for (String raw : historyRaw) {
            Map<String, Object> rec = McpHistoryViews.parseRecord(raw);
            if (rec == null) continue;
            if (McpHistoryViews.lng(rec, "requestId") != jid) continue;
            if (McpHistoryViews.bool(rec, "running")) continue;
            return rec;
        }
        return null;
    }

    /** Newest finished row for {@code dir} (any kind). */
    public static @Nullable Map<String, Object> findNewest(List<String> historyRaw, @Nullable String dir) {
        if (historyRaw == null) return null;
        for (String raw : historyRaw) {
            Map<String, Object> rec = McpHistoryViews.parseRecord(raw);
            if (rec == null) continue;
            if (McpHistoryViews.bool(rec, "running")) continue;
            if (!McpHistoryViews.matches(rec, dir, null, null, null)) continue;
            return rec;
        }
        return null;
    }

    public static Map<String, Object> normalize(Map<String, Object> d) {
        Map<String, Object> row = new LinkedHashMap<>();
        String message = McpHistoryViews.str(d, "message");
        String file = McpHistoryViews.str(d, "file");
        int line = (int) McpHistoryViews.lng(d, "line");
        int col = (int) McpHistoryViews.lng(d, "col");
        CompilerLocus loc = CompilerLocus.parse(message);
        if (loc != null) {
            // Same guard as BuildAccumulator.diagFromPlan: the parsed column belongs with the
            // parsed file/line, never with a locus the message merely quotes.
            boolean fromMessage = file.isEmpty() && line <= 0;
            if (file.isEmpty()) file = loc.file();
            if (line <= 0) line = loc.line();
            if (fromMessage && col <= 0) col = loc.col();
        }
        String first = firstLine(message);
        String detail = restAfterFirstLine(message);
        row.put("severity", McpHistoryViews.str(d, "severity"));
        row.put("code", McpHistoryViews.str(d, "code"));
        row.put("module", McpHistoryViews.str(d, "dir"));
        if (file.isEmpty()) row.put("file", "");
        else row.put("file", file);
        row.put("line", line);
        row.put("col", col);
        row.put("message", first);
        if (!detail.isEmpty()) row.put("detail", detail);
        Object ex = d.get("exceptionClass");
        if (ex != null && !String.valueOf(ex).isBlank()) row.put("exceptionClass", ex);
        return row;
    }

    private static String key(Map<String, Object> row) {
        return row.getOrDefault("file", "")
                + "|"
                + row.getOrDefault("line", 0)
                + "|"
                + row.getOrDefault("col", 0)
                + "|"
                + row.getOrDefault("message", "");
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }

    private static String restAfterFirstLine(String message) {
        if (message == null) return "";
        int nl = message.indexOf('\n');
        return nl < 0 ? "" : message.substring(nl + 1);
    }

    /**
     * Which diagnostics a caller wants. {@code jk_diagnostics} fills all seven; {@code jk_run
     * wait=true} attaching a failed job's errors uses {@link #Query(String, String)}.
     *
     * @param run {@code null} / {@code last-fail} for the newest failed run, else a history id
     * @param dir checkout filter, already defaulted to the bound dir
     * @param module substring match on the module dir or the file path
     * @param severity {@code error} / {@code warning}
     * @param unique collapse duplicate file:line:col + first message line
     * @param limit page size
     * @param skip rows to skip, from a prior page's {@code next}
     */
    public record Query(
            @Nullable String run,
            @Nullable String dir,
            @Nullable String module,
            @Nullable String severity,
            boolean unique,
            int limit,
            int skip) {

        /** The default page: unique rows, first 20, no severity or module narrowing. */
        public Query(@Nullable String run, @Nullable String dir) {
            this(run, dir, null, null, true, 20, 0);
        }
    }

    /** One page of {@link #page}: the rows plus the cursor a caller needs to ask for more. */
    public record Page(
            String run,
            List<Map<String, Object>> rows,
            int totalMatched,
            @Nullable Integer next) {

        public boolean truncated() {
            return next != null;
        }
    }

    /** The rows for one run, filtered and paged. {@code null} when no record matches. */
    public static @Nullable Page page(List<String> historyRaw, Query q) {
        Map<String, Object> rec = findRun(historyRaw, q.run(), q.dir());
        if (rec == null) return null;
        List<Map<String, Object>> rows = unique(fromRecord(rec), q.unique());
        if (q.severity() != null && !q.severity().isBlank()) {
            String sev = q.severity();
            rows = rows.stream()
                    .filter(r -> sev.equalsIgnoreCase(String.valueOf(r.getOrDefault("severity", ""))))
                    .toList();
        }
        if (q.module() != null && !q.module().isBlank()) {
            String needle = q.module().toLowerCase(Locale.ROOT);
            rows = rows.stream()
                    .filter(r -> String.valueOf(r.getOrDefault("module", ""))
                                    .toLowerCase(Locale.ROOT)
                                    .contains(needle)
                            || String.valueOf(r.getOrDefault("file", ""))
                                    .toLowerCase(Locale.ROOT)
                                    .contains(needle))
                    .toList();
        }
        int total = rows.size();
        int from = Math.min(q.skip(), total);
        int to = Math.min(from + q.limit(), total);
        List<Map<String, Object>> visible = new ArrayList<>(rows.subList(from, to));
        return new Page(McpHistoryViews.str(rec, "id"), visible, total, to < total ? Integer.valueOf(to) : null);
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.diagnostic.CompilerLocus;
import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
            Map<String, Object> rec = parse(raw);
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
            Map<String, Object> rec = parse(raw);
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
            Map<String, Object> rec = parse(raw);
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

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> parse(String raw) {
        try {
            Object o = MiniJson.parse(raw);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}

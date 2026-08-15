// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Compact history rows for MCP — no diagnostic blobs, no successful task lists. */
public final class McpHistoryViews {

    private McpHistoryViews() {}

    public static Map<String, Object> summarize(Map<String, Object> rec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(rec, "id"));
        m.put("buildNumber", lng(rec, "buildNumber"));
        m.put("kind", str(rec, "kind"));
        m.put("success", bool(rec, "success"));
        m.put("exitCode", lng(rec, "exitCode"));
        m.put("millis", lng(rec, "millis"));
        m.put("dir", str(rec, "dir"));
        m.put("projectId", str(rec, "projectId"));
        m.put("coord", str(rec, "coord"));
        m.put("failedModules", failedModules(rec));
        m.put("diagnosticCount", listSize(rec.get("diagnostics")));
        Map<String, Object> tests = map(rec.get("tests"));
        long failed = tests == null ? 0 : lng(tests, "failed");
        m.put("testFailed", failed);
        return m;
    }

    public static boolean matches(
            Map<String, Object> rec,
            @Nullable String dir,
            @Nullable String projectId,
            @Nullable Boolean success,
            @Nullable String kind) {
        if (projectId != null && !projectId.isBlank() && !projectId.equals(str(rec, "projectId"))) {
            return false;
        }
        if (kind != null && !kind.isBlank() && !kind.equalsIgnoreCase(str(rec, "kind"))) {
            return false;
        }
        if (success != null && success.booleanValue() != bool(rec, "success")) {
            return false;
        }
        if (dir != null && !dir.isBlank()) {
            String recDir = str(rec, "dir");
            if (recDir.isEmpty()) return false;
            String want = normalizeDir(dir);
            String have = normalizeDir(recDir);
            if (!have.equals(want) && !have.startsWith(want + "/")) return false;
        }
        return true;
    }

    public static String normalizeDir(String dir) {
        String s = dir.replace('\\', '/');
        while (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static List<String> failedModules(Map<String, Object> rec) {
        List<String> out = new ArrayList<>();
        Object raw = rec.get("modules");
        if (!(raw instanceof List<?> list)) return out;
        for (Object item : list) {
            Map<String, Object> mod = map(item);
            if (mod == null) continue;
            if (bool(mod, "success")) continue;
            String coord = str(mod, "coord");
            if (coord.isEmpty()) coord = str(mod, "dir");
            if (!coord.isEmpty()) out.add(coord);
        }
        return out;
    }

    private static int listSize(Object raw) {
        return raw instanceof List<?> list ? list.size() : 0;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> map(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    public static long lng(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.longValue();
        if (v == null) return 0;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean bool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Boolean b) return b;
        return v != null && "true".equalsIgnoreCase(String.valueOf(v));
    }

    public static @Nullable Boolean parseBool(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Boolean b) return b;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
        return null;
    }
}

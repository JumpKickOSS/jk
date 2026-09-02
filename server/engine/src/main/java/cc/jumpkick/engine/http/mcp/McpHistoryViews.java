// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.util.DirKeys;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        long rid = lng(rec, "requestId"); // the journal's stored field name
        if (rid > 0) m.put("jid", rid);
        m.put("success", bool(rec, "success"));
        m.put("exitCode", lng(rec, "exitCode"));
        m.put("millis", lng(rec, "millis"));
        m.put("dir", str(rec, "dir"));
        m.put("projectId", str(rec, "projectId"));
        m.put("coord", str(rec, "coord"));
        m.put("failedModules", failedModules(rec));
        m.put("diagnosticCount", listSize(rec.get("diagnostics")));
        // The same nested counts object the journal stores and the dashboard renders — MCP does not
        // get a private flattened spelling of "how many tests failed".
        m.put(TestSummary.WIRE_KEY, map(rec.get(TestSummary.WIRE_KEY)));
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
        String s = DirKeys.key(dir);
        while (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * The journal key for a caller-supplied directory. Journal and bind keys are strings, not host
     * paths: an already-absolute key keeps its shape, so {@code /ws} does not acquire a drive letter
     * on Windows ({@code resolveUserPath} would make it {@code C:\ws}), while {@code ~} and relative
     * input still resolve against the host. {@code ..} collapses either way — a key still reading
     * {@code /ws/../other} matches no journal row.
     *
     * <p>Every tool that looks a project up by directory keys it through here, or {@code jk_bind}
     * and {@code jk_project} answer differently for the same argument.
     */
    public static String dirKey(String dir) {
        return isAbsoluteDirKey(dir)
                ? normalizeDir(Path.of(dir.strip()).normalize().toString())
                : normalizeDir(PathUtil.resolveUserPath(dir).toString());
    }

    /** True for journal-style absolute keys: leading {@code /} or {@code C:\…} / {@code C:/…}. */
    private static boolean isAbsoluteDirKey(@Nullable String dir) {
        if (dir == null || dir.isBlank()) return false;
        String s = dir.strip();
        if (s.startsWith("/") || s.startsWith("\\")) return true;
        return s.length() >= 3
                && Character.isLetter(s.charAt(0))
                && s.charAt(1) == ':'
                && (s.charAt(2) == '/' || s.charAt(2) == '\\');
    }

    /**
     * The compact outcome row a job answer carries: enough to decide what to do next, never the
     * diagnostic blobs. {@code null} in, {@code null} out — no run, no summary.
     */
    public static @Nullable Map<String, Object> jobSummary(@Nullable Map<String, Object> rec) {
        if (rec == null) return null;
        Map<String, Object> sum = summarize(rec);
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("id", sum.get("id"));
        one.put("kind", sum.get("kind"));
        one.put("success", sum.get("success"));
        one.put("exitCode", sum.get("exitCode"));
        one.put("failedModules", sum.get("failedModules"));
        if (sum.get("jid") != null) one.put("jid", sum.get("jid"));
        return one;
    }

    /** One raw journal line as a map; a corrupt row is {@code null}, never an exception. */
    @SuppressWarnings("unchecked")
    public static @Nullable Map<String, Object> parseRecord(String raw) {
        try {
            Object o = MiniJson.parse(raw);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (RuntimeException e) {
            return null;
        }
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

    public static boolean bool(Map<String, Object> m, String k) {
        return Boolean.TRUE.equals(parseBool(m.get(k)));
    }

    /** A JSON tool argument as a boolean: a real JSON {@code true}, or a string in jk's truth set. */
    public static @Nullable Boolean parseBool(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Boolean b) return b;
        return EnvValues.parseBool(String.valueOf(raw)).orElse(null);
    }
}

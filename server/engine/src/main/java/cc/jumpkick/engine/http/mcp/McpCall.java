// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One {@code tools/call}: the engine context, the client's {@code arguments}, and the MCP progress
 * token bound from {@code params._meta}. The argument readers live here so every tool decodes
 * {@code dir} / {@code limit} / {@code apply} the same way — a tool that hand-rolls one is
 * inventing private semantics for a shared wire.
 */
public record McpCall(
        McpContext ctx, Map<String, Object> args, @Nullable String progressToken) {

    /** A string argument, or {@code null} when absent. */
    public @Nullable String str(String key) {
        Object raw = args.get(key);
        return raw == null ? null : String.valueOf(raw);
    }

    /** A numeric argument as a long, or {@code null} when absent or not a number. */
    public @Nullable Long num(String key) {
        return args.get(key) instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    /** A numeric argument as an int, or {@code null} when absent or not a number. */
    public @Nullable Integer intOrNull(String key) {
        return args.get(key) instanceof Number n ? Integer.valueOf(n.intValue()) : null;
    }

    /** A bounded integer argument: absent, unparseable or out of range all clamp to the band. */
    public int count(String key, int fallback, int min, int max) {
        Object raw = args.get(key);
        int n = fallback;
        if (raw instanceof Number num) n = num.intValue();
        else if (raw != null) {
            try {
                n = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
                n = fallback;
            }
        }
        if (n < min) n = min;
        if (n > max) n = max;
        return n;
    }

    /** A string-array argument; anything else is empty. */
    public List<String> strings(String key) {
        if (!(args.get(key) instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    /** Three-state: {@code null} when the client said nothing. */
    public @Nullable Boolean tri(String key) {
        return McpHistoryViews.parseBool(args.get(key));
    }

    /** An opt-in flag: absent means false. */
    public boolean flag(String key) {
        return flagOr(key, false);
    }

    /** A flag with a declared default — {@code wait} and {@code unique} default true. */
    public boolean flagOr(String key, boolean fallback) {
        Boolean b = tri(key);
        return b == null ? fallback : b.booleanValue();
    }

    /** {@code arguments.action}, or {@code fallback} when absent or blank. Case is not touched. */
    public String action(String fallback) {
        String action = str("action");
        return action == null || action.isBlank() ? fallback : action;
    }

    /** The target checkout: the explicit argument, else the bound dir, else {@code null}. */
    public @Nullable String dir() {
        String dir = str("dir");
        if (dir == null || dir.isBlank()) dir = ctx.session().dir();
        return dir;
    }

    /** {@link #dir()} for a tool that cannot run without one. */
    public String requiredDir() {
        String dir = dir();
        if (dir == null || dir.isBlank()) {
            throw new McpError(-32602, "requires arguments.dir (or jk_bind first)");
        }
        return dir;
    }

    /** Wrap an {@link McpEnvelope} as the {@code tools/call} result. */
    public Map<String, Object> ok(Map<String, Object> envelope, String summary) {
        return McpEnvelope.toolResult(envelope, summary);
    }
}

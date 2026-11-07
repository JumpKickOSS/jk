// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.api.BuildJobFingerprint;
import cc.jumpkick.engine.sessions.ActionRecord;
import cc.jumpkick.engine.sessions.Fact;
import cc.jumpkick.engine.sessions.SessionLog;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The MCP calls a session's timeline shows besides its runs, in compact display form: the code
 * graph's questions, {@code rename}, and the dependency changes {@code deps}, {@code update} and
 * {@code workspace}. Every other tool reads state or starts a run, which the run's own record shows.
 */
final class McpActions {

    /** The code-graph questions: inference reads a session that only asks these as exploring. */
    static final Set<String> GRAPH = Set.of("query", "callers", "read", "find");

    /** Every tool a session's timeline logs. */
    static final Set<String> LOGGED =
            Set.of("query", "callers", "read", "find", "rename", "deps", "update", "workspace");

    /** The tools that take {@link McpSchemas#GOAL}: the ones that start work. */
    static final Set<String> GOALS = Set.of("run", "deps", "rename", "update");

    /** Characters of a question or answer line a summary keeps. */
    static final int TEXT_CHARS = 120;

    private McpActions() {}

    /**
     * Log one finished call of tool {@code name} in its connection's session. {@code result} is the
     * tool's answer, or {@code null} when it threw; {@code at} and {@code millis} bound the call.
     */
    static void log(String name, McpCall call, @Nullable Map<String, Object> result, long at, long millis) {
        SessionLog log = call.ctx().sessions();
        if (log == null || !LOGGED.contains(name)) return;
        McpConnection connection = call.connection();
        boolean ok = result != null && !Boolean.TRUE.equals(result.get("isError"));
        String dir = call.dir();
        String project = dir == null || dir.isBlank() ? "" : BuildJobFingerprint.canonicalDir(dir);
        List<String> coords = call.strings("coords");
        Fact fact = Fact.action(
                at, millis, project, kind(name, call), ok, target(name, call, coords), count(name, result, coords));
        log.action(
                "mcp",
                connection == null ? null : connection.label(),
                connection == null ? null : connection.client(),
                fact,
                name,
                summary(name, call, result),
                result == null ? "failed" : headline(result));
    }

    /**
     * Before a call of tool {@code name} runs, let the goal it names title its session's episode: a
     * new one when the goal changed. A call with no goal changes nothing, so its work joins the
     * episode it would have anyway.
     */
    static void goal(String name, McpCall call) {
        SessionLog log = call.ctx().sessions();
        if (log == null || !GOALS.contains(name)) return;
        String goal = call.str(McpSchemas.GOAL);
        if (goal == null || goal.isBlank()) return;
        McpConnection connection = call.connection();
        String dir = call.dir();
        String project = dir == null || dir.isBlank() ? "" : BuildJobFingerprint.canonicalDir(dir);
        log.goal("mcp", connection == null ? null : connection.label(), project, goal);
    }

    static Fact.Kind kind(String name, McpCall call) {
        if (GRAPH.contains(name)) return Fact.Kind.GRAPH;
        return switch (name) {
            case "rename" -> Fact.Kind.RENAME;
            case "update" -> Fact.Kind.UPDATE;
            case "deps" ->
                switch (call.action("add").toLowerCase(Locale.ROOT)) {
                    case "remove" -> Fact.Kind.REMOVE;
                    case "pin" -> Fact.Kind.PIN;
                    default -> Fact.Kind.ADD;
                };
            default -> Fact.Kind.OTHER;
        };
    }

    private static String target(String name, McpCall call, List<String> coords) {
        return switch (name) {
            case "rename" -> orEmpty(call.str("address"));
            case "deps" -> coords.isEmpty() ? "" : coords.getFirst();
            default -> "";
        };
    }

    private static int count(String name, @Nullable Map<String, Object> result, List<String> coords) {
        return switch (name) {
            case "deps" -> coords.size();
            case "update" -> rewrites(result);
            default -> 0;
        };
    }

    /** One line saying what the call asked: {@code callers PricingRules.weekendUplift}. */
    static String summary(String name, McpCall call, @Nullable Map<String, Object> result) {
        String text =
                switch (name) {
                    case "query" -> "query \"" + orEmpty(call.str("question")) + "\"";
                    case "callers", "read" -> name + " " + orEmpty(call.str("address"));
                    case "find" -> "find " + orEmpty(call.str("name"));
                    case "rename" -> "rename " + orEmpty(call.str("address")) + " → " + orEmpty(call.str("to"));
                    case "deps" ->
                        "deps " + call.action("add").toLowerCase(Locale.ROOT) + " "
                                + String.join(", ", call.strings("coords"))
                                + (call.flag("preview") ? " (preview)" : "");
                    case "update" ->
                        "update " + rewrites(result) + (rewrites(result) == 1 ? " pin" : " pins")
                                + (call.flag("apply") ? "" : " (preview)");
                    case "workspace" ->
                        "workspace " + call.action("add_member") + " " + orEmpty(call.str("path"))
                                + (call.flag("apply") ? "" : " (preview)");
                    default -> name;
                };
        return clip(text.strip());
    }

    /** The first non-blank line of the answer's text, or {@code null} when it had none. */
    static @Nullable String headline(Map<String, Object> result) {
        if (!(result.get("content") instanceof List<?> content) || content.isEmpty()) return null;
        if (!(content.getFirst() instanceof Map<?, ?> first) || !(first.get("text") instanceof String text))
            return null;
        for (String line : text.lines().toList()) {
            if (!line.isBlank()) return clip(line.strip());
        }
        return null;
    }

    /** The pins an {@code update} moved, from its envelope's {@code rewrites}; 0 when it moved none. */
    private static int rewrites(@Nullable Map<String, Object> result) {
        if (result == null || !(result.get("structuredContent") instanceof Map<?, ?> envelope)) return 0;
        return envelope.get("rewrites") instanceof List<?> l ? l.size() : 0;
    }

    private static String clip(String s) {
        return s.length() > TEXT_CHARS ? s.substring(0, TEXT_CHARS - 1) + ActionRecord.CLIP : s;
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s.strip();
    }
}

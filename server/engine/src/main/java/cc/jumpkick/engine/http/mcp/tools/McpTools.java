// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.changes.ChangeActor;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.Request;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpConnection;
import cc.jumpkick.engine.http.mcp.McpContext;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpHistoryViews;
import cc.jumpkick.engine.http.mcp.McpOutbound;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.plugin.ProjectActivity;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Explicit list — adding a tool is one class in {@code mcp.tools} plus one line in {@link
 * #standard()}. Never {@code ServiceLoader}: a tool the agent can call must be visible in a grep,
 * and a native image must be able to see it at build time.
 *
 * <p>{@code tools/list} and {@code tools/call} both read this one map, so a name cannot be
 * advertised without a body or answered without being advertised; the one exception is an {@link
 * #ALIASES alias}, answered by the tool it names and never listed.
 *
 * <p>The default {@code tools/list} is {@link #LOOP}. A client asks for the rest by sending {@code
 * tools/list} with {@code extended: true}, or by setting {@code [mcp] tools = "all"} so every list
 * is the full registry. Both select {@link Surface}. Every registered tool stays callable by name.
 */
public final class McpTools {

    /** The wire name of the explicit bind; the one call that never binds implicitly. */
    public static final String BIND = "bind";

    /**
     * The default {@code tools/list}: what one fix-and-rerun loop needs, in reading order, {@code
     * usages}, the one graph question that answers in a call what grep and reads take turns to
     * approximate, and {@code rename}, which makes in one call the edits a rename takes a turn each
     * for. Every card is paid on every turn.
     */
    public static final List<String> LOOP = List.of("run", "diagnostics", "deps", "usages", "rename", "skill");

    /** Released tool names a call may still use, each answered by the tool it maps to; never listed. */
    public static final Map<String, String> ALIASES = Map.of("callers", "usages");

    /** Which rows {@code tools/list} answers. */
    public enum Surface {
        /** {@link #LOOP}. The default. */
        LOOP,
        /** Every registered tool. */
        ALL;

        /** The {@code [mcp] tools} value — {@code loop} or {@code all}; anything else is the default. */
        public static Surface of(@Nullable String value) {
            return value != null && "all".equals(value.trim().toLowerCase(Locale.ROOT)) ? ALL : LOOP;
        }
    }

    /**
     * The system prompt an MCP host shows the model. Every tool it names is on the default list —
     * a playbook pointing at a tool the default list does not serve is worse than no playbook.
     */
    public static final String INSTRUCTIONS = instructions(false);

    /** {@link #INSTRUCTIONS} for a {@link McpConnection#pinned() pinned} connection: no {@code dir}. */
    public static final String PINNED_INSTRUCTIONS = instructions(true);

    /**
     * Grok and Cursor put this text in the model's context and not the tool cards (Cursor shows the
     * names, Grok nothing), so it carries the two graph calls whole: a model that sees only this
     * still calls them first, with their arguments, and trusts the answer.
     */
    private static String instructions(boolean pinned) {
        return "This project builds with jk, not Maven or Gradle: never add pom.xml or Gradle files. "
                + "Tools: run, diagnostics, deps, usages, rename, skill. The calls below show every argument they "
                + "need: make them as written, without fetching a card, schema or skill page. "
                + (pinned
                        ? "Loop: run() returns the verdict → edit → run(). "
                        : "Loop: run(dir=<project>) returns the verdict and binds the connection → edit → run(). ")
                + "Who uses a method, field, type or column: usages(address=\"Type.method\"), once, before any "
                + "search; complete. "
                + "Rename or move a method, field (with its getter and setter), class or package: "
                + "rename(goal=\"<the task>\", address=\"Type.field\", to=\"newName\") first, with no usages call "
                + "before it; it edits every use, compiles and tests. Uses it lists but did not apply: the same "
                + "call with also=\"c1,c3\". "
                + "Both answers are compiled facts and final: do not grep, read or re-run to check them; when "
                + "rename says the task is done, it is. "
                + "skill(topic=…) has the rest; tools/list extended=true lists every tool.";
    }

    private final Map<String, McpTool> byName;

    /** The loop set in reading order; empty when this registry has no default surface of its own. */
    private final List<String> loop;

    /** A registry with no loop set: {@code tools/list} answers every tool. */
    public McpTools(List<McpTool> tools) {
        this(tools, List.of());
    }

    /**
     * A registry whose default listing is {@code loop}. Every loop name must be a registered tool —
     * a typo here would silently shrink the default list.
     */
    public McpTools(List<McpTool> tools, List<String> loop) {
        Map<String, McpTool> map = new LinkedHashMap<>();
        for (McpTool tool : tools) {
            String name = tool.spec().name();
            McpTool prev = map.put(name, tool);
            if (prev != null) throw new IllegalArgumentException("duplicate MCP tool " + name);
        }
        for (String name : loop) {
            if (!map.containsKey(name)) throw new IllegalArgumentException("loop names an unregistered tool " + name);
        }
        this.byName = map;
        this.loop = List.copyOf(loop);
        for (McpTool tool : tools) {
            if (tool instanceof SkillTool skill) {
                skill.tools(
                        name -> {
                            McpTool named = map.get(name);
                            return named == null || named == skill
                                    ? null
                                    : named.spec().description();
                        },
                        name -> this.loop.isEmpty() || this.loop.contains(name));
            }
        }
    }

    public static McpTools standard() {
        List<McpTool> tools = new ArrayList<>(List.of(
                new SkillTool(),
                new StatusTool(),
                new CancelTool(),
                new BindTool(),
                new ProjectTool(),
                new HistoryTool(),
                new DiagnosticsTool(),
                new DetailsTool(),
                new RunTool(),
                new WhyTool(),
                new ExplainTool(),
                new AffectedTestsTool(),
                new OutdatedTool(),
                new UpdateTool(),
                new DepsTool(),
                new WorkspaceTool(),
                new ManifestTool(),
                new ConfigTool(),
                new DiskTool(),
                new JdkTool(),
                new NodeTool(),
                new NewTool(),
                new RunAliasTool(
                        "publish",
                        "publish",
                        "Dry-run the publish bundle; upload=true sends it to the repository [publish] names "
                                + "(stored credentials, signing from [publish]). Central stays on the CLI.",
                        McpSchemas.BOUND_ROOT,
                        Map.of("upload", McpSchemas.bool("Upload to the [publish] repository (default: dry run)"))),
                new InstallTool(),
                new RunAliasTool(
                        "import",
                        "import",
                        "Import a Maven/Gradle build into jk.toml (auto-detects build.gradle.kts / "
                                + "build.gradle / pom.xml). Same importer as jk import.",
                        "Checkout with the foreign build (default: bound dir)"),
                new ExportTool(),
                new IdeTool(),
                new GraphTool(),
                new DoctorTool(),
                new MachineTool(),
                CodeGraphTool.usages(),
                CodeGraphTool.rename(),
                CodeGraphTool.query(),
                CodeGraphTool.read(),
                CodeGraphTool.find(),
                CodeGraphTool.delete(),
                CodeGraphTool.move(),
                CodeGraphTool.pullUp()));
        return new McpTools(tools, LOOP);
    }

    /** The tool registered as {@code name}, or the one it is an alias of; null for none. */
    public @Nullable McpTool tool(String name) {
        return byName.get(ALIASES.getOrDefault(name, name));
    }

    /** Every tool name, in registration order. */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    /** The names the default {@code tools/list} answers, in reading order. */
    public List<String> loopNames() {
        if (loop.isEmpty()) return names();
        return loop;
    }

    /** The {@code tools/list} result for one surface. */
    public Map<String, Object> listing(Surface surface) {
        return listing(surface, Map.of());
    }

    /**
     * As {@link #listing(Surface)}, widened to every tool when {@code params.extended} is true.
     * The configured surface still applies when the client does not ask.
     */
    public Map<String, Object> listing(Surface surface, @Nullable Map<String, Object> params) {
        return listing(surface, params, false);
    }

    /** As {@link #listing(Surface, Map)}; a {@code pinned} connection's cards carry no {@code dir}. */
    public Map<String, Object> listing(Surface surface, @Nullable Map<String, Object> params, boolean pinned) {
        boolean all = surface == Surface.ALL || extended(params) || loop.isEmpty();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : all ? names() : loop) {
            Map<String, Object> card =
                    Objects.requireNonNull(byName.get(name)).spec().listed();
            rows.add(pinned ? withoutDir(card) : card);
        }
        return Map.of("tools", rows);
    }

    /** {@code card} with {@code dir} gone from its input schema's properties and required list. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutDir(Map<String, Object> card) {
        if (!(card.get("inputSchema") instanceof Map<?, ?> raw)) return card;
        Map<String, Object> schema = new LinkedHashMap<>((Map<String, Object>) raw);
        if (schema.get("properties") instanceof Map<?, ?> props && props.containsKey("dir")) {
            Map<String, Object> kept = new LinkedHashMap<>((Map<String, Object>) props);
            kept.remove("dir");
            schema.put("properties", kept);
        }
        if (schema.get("required") instanceof List<?> req && req.contains("dir")) {
            List<Object> kept = new ArrayList<>(req);
            kept.remove("dir");
            schema.put("required", kept);
        }
        Map<String, Object> out = new LinkedHashMap<>(card);
        out.put("inputSchema", schema);
        return out;
    }

    /** {@code extended: true} on a {@code tools/list} request — the client asked for every tool. */
    static boolean extended(@Nullable Map<String, Object> params) {
        if (params == null) return false;
        Object value = params.get("extended");
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return EnvValues.parseBool(s).orElse(false);
        if (value instanceof Number n) return n.intValue() != 0;
        return false;
    }

    /** Dispatch one {@code tools/call} from an anonymous connection. */
    public Map<String, Object> call(McpContext ctx, Map<String, Object> params) {
        return call(ctx, params, null, null);
    }

    /**
     * Dispatch one {@code tools/call}; {@code connection} is the caller's, or null when it sent no
     * session id, and {@code outbound} the way back to it while the call is outstanding. An unbound
     * connection whose call carries {@code dir} is bound to it. The reply is the tool's own result.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(
            McpContext ctx,
            Map<String, Object> params,
            @Nullable McpConnection connection,
            @Nullable McpOutbound outbound) {
        Object rawName = params.get("name");
        String asked = rawName == null ? null : String.valueOf(rawName);
        if (asked == null || asked.isBlank()) throw new McpError(-32602, "tools/call requires name");
        String name = ALIASES.getOrDefault(asked, asked);
        McpTool tool = byName.get(name);
        if (tool == null) throw new McpError(-32602, "unknown tool: " + name);
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        McpCall call = new McpCall(ctx, args, progressTokenOf(params), connection, outbound);
        try {
            if (!BIND.equals(name)) implicitBind(connection, args.get("dir"));
        } catch (McpError e) {
            return refused(call, e);
        }
        // An agent's every call about a checkout is that project being worked on.
        ProjectActivity.touch(call.dir());
        noteGoal(name, call);
        // Whatever the tool changes outside a project is journaled as this agent's doing.
        ChangeActor actor = ChangeActor.agent(
                connection == null ? null : connection.id(),
                connection == null ? null : connection.client(),
                call.str(McpSchemas.GOAL),
                name);
        if (ctx.sessions() == null || !McpActions.LOGGED.contains(name))
            return Request.actingAs(actor, () -> invoke(tool, call));
        long at = Clock.SYSTEM.millis();
        Map<String, Object> result = null;
        try {
            result = Request.actingAs(actor, () -> invoke(tool, call));
            return result;
        } finally {
            logAction(name, call, result, at);
        }
    }

    /** The JSON-RPC code of an argument a tool refuses. */
    static final int INVALID_PARAMS = -32602;

    /** {@code tool}'s answer to {@code call}; an argument it refuses is a {@link #refused} result. */
    private static Map<String, Object> invoke(McpTool tool, McpCall call) {
        try {
            return tool.call(call);
        } catch (McpError e) {
            return refused(call, e);
        }
    }

    /**
     * An argument the tool refused, as a result flagged {@code isError} that the model reads and
     * corrects, not a protocol error; any other {@link McpError} is rethrown. An unknown tool and a
     * call with no name stay protocol errors.
     *
     * @see <a href="https://modelcontextprotocol.io/specification/2025-11-25/server/tools#error-handling">Tool error handling</a>
     */
    private static Map<String, Object> refused(McpCall call, McpError e) {
        if (e.code() != INVALID_PARAMS) throw e;
        String message = String.valueOf(e.getMessage());
        return call.error(message.endsWith("\n") ? message : message + "\n");
    }

    /** Let the call's goal title its session's episode; a goal that cannot be noted never costs the call. */
    private static void noteGoal(String name, McpCall call) {
        try {
            McpActions.goal(name, call);
        } catch (RuntimeException e) {
            Log.debug("goal not noted: " + name, e);
        }
    }

    /** Log a finished call in its session's timeline; a log that fails never costs the call its answer. */
    private static void logAction(String name, McpCall call, @Nullable Map<String, Object> result, long at) {
        try {
            McpActions.log(name, call, result, at, Clock.SYSTEM.millis() - at);
        } catch (RuntimeException e) {
            Log.debug("session action not logged: " + name, e);
        }
    }

    /** Bind an unbound connection to the call's {@code dir}. A connection that already has one is left alone. */
    private static void implicitBind(@Nullable McpConnection connection, @Nullable Object rawDir) {
        if (connection == null || connection.dir() != null) return;
        String dir = rawDir == null ? null : String.valueOf(rawDir);
        if (dir == null || dir.isBlank()) return;
        String abs;
        try {
            abs = McpHistoryViews.dirKey(dir);
        } catch (RuntimeException e) {
            throw new McpError(-32602, "invalid dir: " + e.getMessage());
        }
        connection.bind(abs);
    }

    /**
     * MCP progress token from {@code params._meta.progressToken}, a string or a number as the
     * client sent it: its {@code notifications/progress} must carry the same value back. Null when
     * absent or blank.
     */
    private static @Nullable Object progressTokenOf(Map<String, Object> params) {
        Object meta = params.get("_meta");
        if (!(meta instanceof Map<?, ?> m)) return null;
        Object tok = m.get("progressToken");
        if (tok instanceof Number) return tok;
        return tok instanceof String s && !s.isBlank() ? s : null;
    }
}

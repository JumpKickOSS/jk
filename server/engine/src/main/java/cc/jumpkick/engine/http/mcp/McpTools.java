// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.engine.http.ProgressTokenRegistry;
import cc.jumpkick.engine.http.mcp.tools.AffectedTestsTool;
import cc.jumpkick.engine.http.mcp.tools.BindTool;
import cc.jumpkick.engine.http.mcp.tools.CancelTool;
import cc.jumpkick.engine.http.mcp.tools.ConfigTool;
import cc.jumpkick.engine.http.mcp.tools.DepsTool;
import cc.jumpkick.engine.http.mcp.tools.DetailsTool;
import cc.jumpkick.engine.http.mcp.tools.DiagnosticsTool;
import cc.jumpkick.engine.http.mcp.tools.DiskTool;
import cc.jumpkick.engine.http.mcp.tools.DoctorTool;
import cc.jumpkick.engine.http.mcp.tools.ExplainTool;
import cc.jumpkick.engine.http.mcp.tools.ExportTool;
import cc.jumpkick.engine.http.mcp.tools.GraphTool;
import cc.jumpkick.engine.http.mcp.tools.HistoryTool;
import cc.jumpkick.engine.http.mcp.tools.IdeTool;
import cc.jumpkick.engine.http.mcp.tools.InstallTool;
import cc.jumpkick.engine.http.mcp.tools.JdkTool;
import cc.jumpkick.engine.http.mcp.tools.ManifestTool;
import cc.jumpkick.engine.http.mcp.tools.NewTool;
import cc.jumpkick.engine.http.mcp.tools.NodeTool;
import cc.jumpkick.engine.http.mcp.tools.OutdatedTool;
import cc.jumpkick.engine.http.mcp.tools.ProjectTool;
import cc.jumpkick.engine.http.mcp.tools.RunAliasTool;
import cc.jumpkick.engine.http.mcp.tools.RunTool;
import cc.jumpkick.engine.http.mcp.tools.SkillTool;
import cc.jumpkick.engine.http.mcp.tools.StatusTool;
import cc.jumpkick.engine.http.mcp.tools.TriggerTool;
import cc.jumpkick.engine.http.mcp.tools.UpdateTool;
import cc.jumpkick.engine.http.mcp.tools.WhyTool;
import cc.jumpkick.engine.http.mcp.tools.WorkspaceTool;
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
 * advertised without a body or answered without being advertised.
 *
 * <p>The default {@code tools/list} is {@link #LOOP}. A client asks for the rest by sending {@code
 * tools/list} with {@code extended: true}, or by setting {@code [mcp] tools = "all"} so every list
 * is the full registry. Both select {@link Surface}. Every registered tool stays callable by name.
 */
public final class McpTools {

    /** The wire name of the explicit bind; the one call that never binds implicitly. */
    public static final String BIND = "bind";

    /**
     * The default {@code tools/list}: what one fix-and-rerun loop needs, in reading order.
     * Everything else is an extended {@code tools/list}.
     */
    public static final List<String> LOOP = List.of("run", "diagnostics", "deps", "why", "skill");

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

    private static String instructions(boolean pinned) {
        return "JumpKick (jk) is not Maven or Gradle: the manifest is jk.toml, the lock is jk-lock.toml, "
                + "never add pom.xml or Gradle files. "
                + (pinned
                        ? "Loop: run() tests and returns the verdict → edit → run() again. "
                        : "Loop: run(dir=<project>) tests and returns the verdict → edit → run again. ")
                + "diagnostics is every problem past the cap (file= for one). deps adds, removes, or pins and relocks. "
                + "why(coord) is the path and the rule that picked the version. skill is the playbook. "
                + (pinned ? "" : "The first call that carries dir binds the connection. ")
                + "Other tools: tools/list with extended=true.";
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
    }

    public static McpTools standard() {
        return new McpTools(
                List.of(
                        new SkillTool(),
                        new StatusTool(),
                        new TriggerTool(
                                "build",
                                "build",
                                "Start a workspace/module build for dir (async). Returns jid; stream progress "
                                        + "via GET /mcp?jid=N (or ?progressToken=T with _meta.progressToken) "
                                        + "Accept: text/event-stream. Same as POST /api/build."),
                        new TriggerTool(
                                "test",
                                "test",
                                "Start a true test-only job for dir (async; compile + tests, no package — same as "
                                        + "jk test). Journal kind test. Progress: GET /mcp?jid=N. Returns jid."),
                        new TriggerTool(
                                "lock",
                                "lock",
                                "Resolve dependencies and write jk-lock.toml for dir (async). Progress: GET /mcp?jid=N."),
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
                                "Validate the publish bundle — ALWAYS a dry-run (same planner as jk publish; "
                                        + "credentials never enter the engine). Real uploads: jk publish CLI.",
                                McpSchemas.BOUND_ROOT),
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
                        new DoctorTool()),
                LOOP);
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
        return call(ctx, params, null);
    }

    /**
     * Dispatch one {@code tools/call}; {@code connection} is the caller's, or null when it sent no
     * session id. An unbound connection whose call carries {@code dir} is bound to it. The reply is
     * the tool's own result.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(McpContext ctx, Map<String, Object> params, @Nullable McpConnection connection) {
        Object rawName = params.get("name");
        String name = rawName == null ? null : String.valueOf(rawName);
        if (name == null || name.isBlank()) throw new McpError(-32602, "tools/call requires name");
        McpTool tool = byName.get(name);
        if (tool == null) throw new McpError(-32602, "unknown tool: " + name);
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        if (!BIND.equals(name)) implicitBind(connection, args.get("dir"));
        return tool.call(new McpCall(ctx, args, progressTokenOf(params), connection));
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
     * MCP progress token from {@code params._meta.progressToken} (string or number). Numeric
     * tokens are canonicalized to their integral form — MiniJson parses numbers as Double, and
     * {@code String.valueOf(5.0)} would never match the client's {@code ?progressToken=5} query.
     */
    private static @Nullable String progressTokenOf(Map<String, Object> params) {
        Object meta = params.get("_meta");
        if (!(meta instanceof Map<?, ?> m)) return null;
        Object tok = m.get("progressToken");
        if (tok == null) return null;
        String s = ProgressTokenRegistry.canonicalText(String.valueOf(tok));
        return s.isEmpty() || "null".equals(s) ? null : s;
    }
}

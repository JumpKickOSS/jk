// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

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
import cc.jumpkick.engine.http.mcp.tools.JobTool;
import cc.jumpkick.engine.http.mcp.tools.ManifestTool;
import cc.jumpkick.engine.http.mcp.tools.ManualTool;
import cc.jumpkick.engine.http.mcp.tools.NewTool;
import cc.jumpkick.engine.http.mcp.tools.OutdatedTool;
import cc.jumpkick.engine.http.mcp.tools.ProjectTool;
import cc.jumpkick.engine.http.mcp.tools.ResultsTool;
import cc.jumpkick.engine.http.mcp.tools.RunAliasTool;
import cc.jumpkick.engine.http.mcp.tools.RunTool;
import cc.jumpkick.engine.http.mcp.tools.StatusTool;
import cc.jumpkick.engine.http.mcp.tools.TriggerTool;
import cc.jumpkick.engine.http.mcp.tools.UpdateTool;
import cc.jumpkick.engine.http.mcp.tools.WhyTool;
import cc.jumpkick.engine.http.mcp.tools.WorkspaceTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Explicit list — adding {@code jk_quux} is one class in {@code mcp.tools} plus one line in
 * {@link #standard()}. Never {@code ServiceLoader}: a tool the agent can call must be visible in
 * a grep, and a native image must be able to see it at build time.
 *
 * <p>{@code tools/list} and {@code tools/call} both read this one map, so a name cannot be
 * advertised without a body or answered without being advertised.
 */
public final class McpTools {

    /**
     * The system prompt an MCP host shows the model. Every {@code jk_*} it names must exist in
     * {@link #standard()} — {@code McpToolRegistryTest} asserts that, because a playbook pointing
     * at a tool the server does not serve is worse than no playbook.
     */
    public static final String INSTRUCTIONS = "Playbook: jk_manual (CLI `jk manual`; resource jk://manual). "
            + "JumpKick is not Maven or Gradle — read the playbook before inventing pom.xml / Gradle. "
            + "Bind first: jk_bind {dir}. "
            + "What happened → jk_results (markdown; same as CLI `jk results` / target/jk-results.md) "
            + "or jk_diagnostics. Prefer reading target/jk-results.md with file tools when MCP is off. "
            + "Raw transcript → jk_details (budgeted; CLI `jk results --details` "
            + "dumps the full details.jsonl). "
            + "Why dep X → jk_why. Module/dep DAG → jk_graph. Slow / next-build ETA → jk_explain. "
            + "WIP tests → jk_affected_tests (advisory; writes target/jk-tests-affected.md). "
            + "Frozen / kill → jk_status then jk_job cancel. "
            + "Run / test / lock / format / publish (dry-run) / install / import → jk_run (wait defaults true). "
            + "House rules (jk-guards.toml) → jk_run kind=guard; a failure's code is a rule id, fix per Instead, "
            + "never edit the baseline — the playbook's Guards page. Read jk://guards before large edits; "
            + "jk://guards/<id> is one rule's card (= jk guard explain <id>). "
            + "Scaffold → jk_new (preview first). Export maven/gradle/bom → jk_export. "
            + "IDE files (.idea / .vscode / .bsp) → jk_ide (preview first). "
            + "Add/remove deps → jk_deps. Git/path as workspace member → jk_workspace. "
            + "java= → jk_manifest. Heap / nerd-font / CI → jk_config. "
            + "Disk → jk_disk. Host health → jk_doctor. "
            + "History is summaries only. Live progress: GET /mcp?jid=N "
            + "(Accept: text/event-stream).";

    private final Map<String, McpTool> byName;

    public McpTools(List<McpTool> tools) {
        Map<String, McpTool> map = new LinkedHashMap<>();
        for (McpTool tool : tools) {
            String name = tool.spec().name();
            McpTool prev = map.put(name, tool);
            if (prev != null) throw new IllegalArgumentException("duplicate MCP tool " + name);
        }
        this.byName = map;
    }

    public static McpTools standard() {
        return new McpTools(List.of(
                new ManualTool(),
                new StatusTool(),
                new TriggerTool(
                        "jk_build",
                        "build",
                        "Start a workspace/module build for dir (async). Returns jid; stream progress "
                                + "via GET /mcp?jid=N (or ?progressToken=T with _meta.progressToken) "
                                + "Accept: text/event-stream. Same as POST /api/build."),
                new TriggerTool(
                        "jk_test",
                        "test",
                        "Start a true test-only job for dir (async; compile + tests, no package — same as "
                                + "jk test). Journal kind test. Progress: GET /mcp?jid=N. Returns jid."),
                new TriggerTool(
                        "jk_lock",
                        "lock",
                        "Resolve dependencies and write jk-lock.toml for dir (async). Progress: GET /mcp?jid=N."),
                new CancelTool(),
                new BindTool(),
                new ProjectTool(),
                new HistoryTool(),
                new DiagnosticsTool(),
                new ResultsTool(),
                new DetailsTool(),
                new RunTool(),
                new JobTool(),
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
                new NewTool(),
                new RunAliasTool(
                        "jk_publish",
                        "publish",
                        "Validate the publish bundle — ALWAYS a dry-run (same planner as jk publish; "
                                + "credentials never enter the engine). Real uploads: jk publish CLI.",
                        McpSchemas.BOUND_ROOT),
                new InstallTool(),
                new RunAliasTool(
                        "jk_import",
                        "import",
                        "Import a Maven/Gradle build into jk.toml (auto-detects build.gradle.kts / "
                                + "build.gradle / pom.xml). Same importer as jk import.",
                        "Checkout with the foreign build (default: bound dir)"),
                new ExportTool(),
                new IdeTool(),
                new GraphTool(),
                new DoctorTool()));
    }

    /** Every tool name, in advertised order. */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    /** The {@code tools/list} result. */
    public Map<String, Object> listing() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (McpTool tool : byName.values()) rows.add(tool.spec().listed());
        return Map.of("tools", rows);
    }

    /** Dispatch one {@code tools/call}. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(McpContext ctx, Map<String, Object> params) {
        Object rawName = params.get("name");
        String name = rawName == null ? null : String.valueOf(rawName);
        if (name == null || name.isBlank()) throw new McpError(-32602, "tools/call requires name");
        McpTool tool = byName.get(name);
        if (tool == null) throw new McpError(-32602, "unknown tool: " + name);
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        return tool.call(new McpCall(ctx, args, progressTokenOf(params)));
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

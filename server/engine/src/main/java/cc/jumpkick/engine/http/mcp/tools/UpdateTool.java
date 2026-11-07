// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDecisions;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.http.mcp.McpUpdate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code update} — preview or apply the pin rewrite and relock {@code jk update} performs. An apply
 * that moves a pin across a minor or a major, or whose relock adds a package, is a trust-changing
 * decision the user makes ({@link McpDecisions}); nothing is written until they accept it.
 */
public final class UpdateTool implements McpTool {

    static final String KIND = "update";

    @Override
    public Spec spec() {
        return new Spec(
                "update",
                "Move declared exact pins in jk.toml to the newest stable on the same major (major=true"
                        + " crosses) and relock — same as jk update. apply=false (default) only previews"
                        + " the rewrites; apply=true writes jk.toml and jk-lock.toml and lists every lock"
                        + " package the relock added, removed or moved under lock.changes. A move across a"
                        + " minor or major, or a new package, asks the user first.",
                McpSchemas.withGoal(
                        Map.of(
                                "dir",
                                McpSchemas.string(McpSchemas.WORKSPACE_ROOT),
                                "deps",
                                McpSchemas.strings("Only these handles or group:artifact coordinates (default: all)"),
                                "major",
                                McpSchemas.bool("Allow a pin to cross its Maven major"),
                                "apply",
                                McpSchemas.bool()),
                        List.of()));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        boolean apply = in.flag("apply");
        String dir = in.requiredDir();
        List<String> deps = in.strings("deps");
        boolean major = in.flag("major");
        boolean continued = apply && in.str(McpDecisions.DECISION) != null;
        Map<String, Object> data = continued ? Map.of() : McpUpdate.run(dir, deps, major, apply, false);
        if (continued || (apply && data.get("held") instanceof List<?>)) {
            McpDecisions.Outcome outcome = McpDecisions.require(in, this, ask(dir, strings(data.get("held"))));
            if (outcome instanceof McpDecisions.Outcome.Stop stop) return stop.result();
            data = McpUpdate.run(dir, deps, major, true, true);
        }
        Object moved = data.get("rewrites") instanceof List<?> l ? Integer.valueOf(l.size()) : data.get("error");
        String relocked = data.get("lock") instanceof Map<?, ?> lock && lock.get("updated") != null
                ? ", " + lock.get("updated") + " lock packages changed"
                : "";
        return in.ok(
                McpEnvelope.of("update", data), (apply ? "update applied " : "update preview ") + moved + relocked);
    }

    private static McpDecisions.Ask ask(String dir, List<String> held) {
        String question = held.isEmpty()
                ? "Apply the dependency update in " + dir + "?"
                : "Apply " + held.size() + " dependency change" + (held.size() == 1 ? "" : "s")
                        + " that need a person in " + dir + "?";
        return new McpDecisions.Ask(KIND, question, held, "Yes, update and relock", true);
    }

    private static List<String> strings(@Nullable Object raw) {
        return raw instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpAppRuns;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code run} — start the project's application and wait until it answers, read its stdout and
 * stderr (and the file it logs to), stop it. The work is {@link McpAppRuns}'. Builds and tests are
 * {@link BuildTool}'s; a {@code kind} is refused with that pointer.
 */
public final class RunTool implements McpTool {

    static final String DESCRIPTION = "Run the app: action=start builds it, starts it and waits until its port"
            + " answers (READY, or FAIL with why); action=logs tails its stdout and stderr, and log=<path> or"
            + " [run] log as well (request=<text> or request=/regex/ keeps one request's lines); action=stop.";

    /** Every argument the tool accepts; any other is refused by name. A habitual {@code goal} is accepted and unused. */
    static final Set<String> ARGS =
            Set.of("action", "dir", "port", "args", "timeout_s", "request", "lines", "log", McpSchemas.GOAL);

    /** The one-line refusal of a build-shaped call. */
    static final String NOT_A_BUILD = "run starts the app; builds and tests are build(kind=…)";

    @Override
    public Spec spec() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", McpSchemas.oneOf("start", "logs", "stop"));
        props.put("dir", McpSchemas.string(McpSchemas.BOUND_ROOT));
        props.put(
                "port",
                McpSchemas.integer("start: port to wait for (default: [dev] ready, else the one the app logs)"));
        props.put("args", McpSchemas.strings("start: program arguments"));
        props.put("request", McpSchemas.string("logs: only lines with this text, or matching /regex/"));
        props.put("lines", McpSchemas.integer("logs: at most this many (default 60, max 400)"));
        props.put("log", McpSchemas.string("logs: a file the app writes, tailed after stdout and stderr"));
        return new Spec("run", DESCRIPTION, McpSchemas.object(props, List.of("action")));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        if (in.args().containsKey("kind")) throw new McpError(-32602, NOT_A_BUILD);
        Set<String> unknown = new TreeSet<>(in.args().keySet());
        unknown.removeAll(ARGS);
        if (!unknown.isEmpty()) throw new McpError(-32602, "run does not take " + String.join(", ", unknown));
        return switch (in.action("")) {
            case "start" -> McpAppRuns.start(in);
            case "logs" -> McpAppRuns.logs(in);
            case "stop" -> McpAppRuns.stop(in);
            case "" -> throw new McpError(-32602, "run needs action: start, logs or stop");
            default -> throw new McpError(-32602, "action is start, logs or stop");
        };
    }
}

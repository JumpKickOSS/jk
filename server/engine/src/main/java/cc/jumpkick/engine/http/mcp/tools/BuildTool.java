// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpAgentText;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDecisions;
import cc.jumpkick.engine.http.mcp.McpDiagnostics;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.http.mcp.McpVitals;
import cc.jumpkick.engine.jobs.JobRequests;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code build} — start a build job and wait, keep waiting on one, or read an earlier verdict.
 * {@code jid} waits on that job; {@code decision} continues the call a {@code NEEDS-YOU} answered;
 * {@code id} (a history id) with no {@code kind} is the read; otherwise it starts one of {@link
 * #KINDS}, {@code test} unless {@code kind} says otherwise. The job fields are {@link JobRequests}'.
 */
public final class BuildTool implements McpTool {

    static final String DESCRIPTION =
            "Build and wait; the reply is the verdict. kind=test (default)|build|gate (tests + guards, before done)|guard|format;"
                    + " jid=N waits; id=<id> rereads.";

    /** The kinds {@code build} starts: what {@code jk build}, {@code jk test} and their siblings run. */
    public static final List<String> KINDS =
            List.of("test", "build", "compile", "gate", "guard", "assemble", "native", "image", "clean", "format");

    /** A job kind another tool starts, and the call that starts it. */
    private static final Map<String, String> ELSEWHERE = Map.of(
            "lock", "deps(action=lock)",
            "update", "update(apply=true)",
            "publish", "publish",
            "upload", "publish(upload=true)",
            "install", "install",
            "import", "import");

    /**
     * The job fields {@code build}'s card names: the ones an agent's loop uses. The decoder accepts
     * the rest of {@link JobRequests#FIELDS} too ({@code docs/user/mcp.md} lists them); naming them
     * here would cost every turn the bytes of arguments few calls send.
     */
    static final List<String> CARD_FIELDS = List.of("kind", "dir", "modules", "classes", "suites");

    @Override
    public Spec spec() {
        Map<String, Object> all = JobRequests.schemaProperties();
        Map<String, Object> props = new LinkedHashMap<>();
        for (String f : CARD_FIELDS) props.put(f, all.get(f));
        props.put("jid", McpSchemas.integer());
        props.put("id", McpSchemas.string());
        props.put(McpDecisions.DECISION, McpSchemas.string());
        return new Spec("build", DESCRIPTION, McpSchemas.withGoal(props, List.of()));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String decision = in.str(McpDecisions.DECISION);
        if (decision != null && !decision.isBlank()) return McpDecisions.resume(in, decision);
        Long jid = in.num("jid");
        if (jid != null) return McpJobRuns.await(in, jid);
        String kind = in.str("kind");
        String id = in.str("id");
        if ((kind == null || kind.isBlank()) && id != null && !id.isBlank()) return earlier(in, id);
        if (kind != null && !kind.isBlank()) refuseOtherKinds(kind.strip().toLowerCase(Locale.ROOT));
        return McpJobRuns.run(in, null);
    }

    /** Refuse a kind {@code build} does not start, naming the tool that does. */
    static void refuseOtherKinds(String kind) {
        if (KINDS.contains(kind)) return;
        String elsewhere = ELSEWHERE.get(kind);
        throw new McpError(
                -32602,
                elsewhere != null
                        ? "kind=" + kind + " is not a build; call " + elsewhere
                        : "unknown kind " + kind + "; build takes " + String.join(", ", KINDS));
    }

    private static @Nullable Long jidOf(String id) {
        try {
            return Long.parseLong(id.strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** The agent rendering of a finished build. {@code latest} is the newest. */
    private static Map<String, Object> earlier(McpCall in, String id) {
        String dir = in.dir();
        boolean newest = "last".equalsIgnoreCase(id) || "latest".equalsIgnoreCase(id);
        Map<String, Object> rec = newest
                ? McpDiagnostics.findNewest(in.ctx().history(), dir)
                : McpDiagnostics.findRun(in.ctx().history(), id, dir);
        if (rec == null) {
            // An agent handed TIMEOUT ... jid=N often asks for id=N: when N is a live job, wait on it.
            Long live = newest ? null : jidOf(id);
            if (live != null && McpVitals.isLive(in.ctx(), live)) return McpJobRuns.await(in, live);
            return in.error("no matching build\n");
        }
        String text = McpAgentText.of(in.ctx(), rec);
        return text == null ? in.error("no matching build\n") : in.text(text);
    }
}

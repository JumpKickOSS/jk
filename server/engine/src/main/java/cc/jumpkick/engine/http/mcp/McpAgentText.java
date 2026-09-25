// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JkResultsAgent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The agent rendering of a finished run, shared by the job and results tools. */
public final class McpAgentText {

    private McpAgentText() {}

    /** A job that has not finished. The second line is how to collect the verdict. */
    public static String running(String kind, long jid) {
        return "RUNNING " + kind + " jid=" + jid + "\njob action=wait jid=" + jid + "\n";
    }

    /** A wait that ended before the job did. Same continuation as {@link #running}. */
    public static String timeout(String kind, long jid) {
        return "TIMEOUT " + kind + " jid=" + jid + "\njob action=wait jid=" + jid + "\n";
    }

    /**
     * The report written beside {@code jk-results.md} when that file is already on disk, otherwise
     * the same renderer over the journal row. {@code null} when {@code rec} is not a run.
     */
    public static @Nullable String of(McpContext ctx, @Nullable Map<String, Object> rec) {
        if (rec == null) return null;
        String filed = filed(ctx, rec);
        if (filed != null) return filed;
        BuildRecord record = JkResultsAgent.recordOf(rec);
        return record == null ? null : JkResultsAgent.render(record);
    }

    private static @Nullable String filed(McpContext ctx, Map<String, Object> rec) {
        String id = McpHistoryViews.str(rec, "id");
        Path markdown = McpResults.locate(rec, id.isEmpty() ? null : id, ctx.detailsFile());
        if (markdown == null) return null;
        Path agent = markdown.resolveSibling(JkResultsAgent.FILE_NAME);
        if (!Files.isRegularFile(agent)) return null;
        try {
            String text = Files.readString(agent, StandardCharsets.UTF_8);
            return text.endsWith("\n") ? text : text + "\n";
        } catch (IOException e) {
            return null;
        }
    }
}

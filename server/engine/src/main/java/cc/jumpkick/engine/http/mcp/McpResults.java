// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.journal.JkResultsMarkdown;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.util.MarkdownReports;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * MCP {@code run}: the high-level {@code jk-results.md} for a run. Sibling of {@code
 * details.jsonl} in the journal, with a latest copy at {@code target/jk-results.md}. A run with a
 * run before it from the same session also returns {@code delta}: files changed, diagnostics that
 * appeared or went away, tests that flipped, and the previous attempt's wall — each list a
 * {@code count} with a bounded {@code shown} head.
 */
public final class McpResults {

    private McpResults() {}

    /**
     * @param rec resolved journal record (from {@code McpDiagnostics.findRun})
     * @param detailsFile locator → transcript path; the report is the sibling {@code jk-results.md}
     */
    public static Map<String, Object> read(
            @Nullable Map<String, Object> rec, Function<String, Optional<Path>> detailsFile) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (rec == null) {
            m.put("error", "no matching run (nothing finished, or pass run=<history id>)");
            return m;
        }
        String id = McpHistoryViews.str(rec, "id");
        m.put("run", id);
        long jid = McpHistoryViews.lng(rec, "requestId");
        if (jid > 0) m.put("jid", jid);
        Path file = locate(rec, id, detailsFile);
        if (file == null || !Files.isRegularFile(file)) {
            m.put("error", "no jk-results.md for run " + id);
            return m;
        }
        m.put("path", file.toString());
        // What changed since the run before from the same origin — the record's own `delta`, the
        // facts behind the report's "Since the previous run" section, structured for an agent.
        if (rec.get("delta") instanceof Map<?, ?> delta) m.put("delta", delta);
        Optional<Path> details = id == null ? Optional.empty() : detailsFile.apply(id);
        if (details.isPresent() && Files.isRegularFile(details.get())) {
            m.put("details", details.get().toString());
        }
        try {
            m.put("markdown", MarkdownReports.strip(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            m.put("error", "reading " + file + ": " + e.getMessage());
        }
        return m;
    }

    /** Absolute path of the markdown for {@code rec}, or {@code null} when absent. */
    public static @Nullable Path locate(
            @Nullable Map<String, Object> rec, @Nullable String id, Function<String, Optional<Path>> detailsFile) {
        if (id != null && detailsFile != null) {
            Optional<Path> details = detailsFile.apply(id);
            if (details.isPresent()) {
                Path runDir = details.get().getParent();
                if (runDir == null) return null;
                Path sibling = runDir.resolve(JkResultsMarkdown.FILE_NAME);
                if (Files.isRegularFile(sibling)) return sibling;
            }
        }
        if (rec == null) return null;
        String dir = McpHistoryViews.str(rec, "dir");
        if (dir == null || dir.isBlank()) return null;
        try {
            Path latest = Path.of(dir).resolve(BuildLayout.TARGET).resolve(JkResultsMarkdown.FILE_NAME);
            return Files.isRegularFile(latest) ? latest : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.config.TestQuarantine;
import cc.jumpkick.config.TestQuarantineReport;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code flaky} — the project's flaky and quarantined test classes, as {@code jk flaky}:
 * list them, show one with its history, release one from {@value TestQuarantine#FILE}.
 */
public final class FlakyTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "flaky",
                "Flaky and quarantined test classes with their history; release takes one out of " + "quarantine.",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.string("list (default) | show | release"),
                        "class",
                        McpSchemas.string("show / release: the class, fully qualified or its simple name"),
                        "dir",
                        McpSchemas.string("The project (default: bound dir)"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String action = in.action("list");
        Path dir = Path.of(in.requiredDir());
        Path root = WorkspaceScan.owningRoot(dir).orElse(dir);
        Map<String, Object> data;
        try {
            data = switch (action) {
                case "list" -> list(JkDirs.state(), root);
                case "show" -> show(JkDirs.state(), root, in.str("class"));
                case "release" -> release(JkDirs.state(), root, in.str("class"));
                default -> Map.of("error", "unknown action `" + action + "`");
            };
        } catch (IOException | RuntimeException e) {
            data = Map.of("error", Errors.text(e));
        }
        return in.ok(McpEnvelope.of("flaky", data), MachineActions.summary(data, action));
    }

    static Map<String, Object> list(Path stateDir, Path root) throws IOException {
        TestQuarantineReport report = TestQuarantineReport.of(stateDir, root);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flakes", report.policy().flakes());
        out.put("runs", report.policy().runs());
        out.put("classes", rows(report.listed(), report.policy().runs()));
        return out;
    }

    static Map<String, Object> show(Path stateDir, Path root, @Nullable String name) throws IOException {
        if (name == null || name.isBlank()) return Map.of("error", "show requires class");
        TestQuarantineReport report = TestQuarantineReport.of(stateDir, root);
        List<TestQuarantineReport.Row> named = report.named(name);
        if (named.isEmpty()) return Map.of("error", "no history or quarantine entry: " + name);
        return Map.of("classes", rows(named, report.policy().runs()));
    }

    static Map<String, Object> release(Path stateDir, Path root, @Nullable String name) throws IOException {
        if (name == null || name.isBlank()) return Map.of("error", "release requires class");
        List<TestQuarantine.Entry> released = TestQuarantine.release(stateDir, root, name);
        if (released.isEmpty()) return Map.of("error", "not quarantined: " + name);
        List<Object> rows = new ArrayList<>();
        for (TestQuarantine.Entry e : released) rows.add(Map.of("class", e.className(), "module", e.module()));
        return Map.of("released", rows);
    }

    /**
     * Each row as {@code class}, {@code module}, {@code quarantined}, {@code flakes} in the {@code
     * runs} of its {@code history} (oldest first) a count looks at, and the entry's {@code since}, {@code
     * reason} and {@code issue} when it has them.
     */
    private static List<Object> rows(List<TestQuarantineReport.Row> rows, int window) {
        List<Object> out = new ArrayList<>();
        for (TestQuarantineReport.Row r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("class", r.className());
            row.put("module", r.module());
            row.put("quarantined", r.quarantined());
            row.put("flakes", r.flakes(window));
            row.put("runs", r.counted(window));
            row.put("history", r.history());
            TestQuarantine.Entry e = r.entry();
            if (e != null) {
                if (!e.since().isEmpty()) row.put("since", e.since());
                if (!e.reason().isEmpty()) row.put("reason", e.reason());
                if (e.issue() != null && !e.issue().isBlank()) row.put("issue", e.issue());
            }
            out.add(row);
        }
        return out;
    }
}

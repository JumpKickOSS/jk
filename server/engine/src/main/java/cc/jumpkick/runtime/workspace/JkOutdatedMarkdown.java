// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.util.MarkdownReports;
import cc.jumpkick.wire.protocol.OutdatedReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code target/jk-outdated-dependencies.md} — the whole picture a {@code jk outdated} run saw,
 * whatever filter the terminal applied: the coordinates that can move as a table, the rows behind
 * them by module, the rest as a list. Stateless renderer; a new run replaces the file.
 */
public final class JkOutdatedMarkdown {

    public static final String FILE_NAME = "jk-outdated-dependencies.md";

    private static final String NONE = "—";

    private JkOutdatedMarkdown() {}

    /** The file for the workspace (or standalone project) rooted at {@code root}. */
    public static Path latestPath(Path root) {
        return root.resolve(BuildLayout.TARGET).resolve(FILE_NAME);
    }

    public static void write(Path file, OutdatedReport report, boolean offline, LocalDate on) throws IOException {
        Files.createDirectories(file.getParent());
        MarkdownReports.write(file, render(report, offline, on));
    }

    /**
     * Render {@code report}: {@link OutdatedReport.Row#canMove} splits the rows; {@code offline}
     * adds the cache-only note so a reader does not mistake an unreachable remote for an up-to-date
     * one. The rollup comes first, one row per coordinate; the per-module rows follow in a
     * workspace.
     */
    public static String render(OutdatedReport report, boolean offline, LocalDate on) {
        List<OutdatedReport.Row> movable = report.movable();
        List<OutdatedReport.Rollup> rollups = report.rollup();
        List<OutdatedReport.Rollup> moving =
                rollups.stream().filter(OutdatedReport.Rollup::canMove).toList();
        List<OutdatedReport.Rollup> current =
                rollups.stream().filter(r -> !r.canMove()).toList();
        boolean tip = report.rows().stream().anyMatch(r -> !blank(r.tip()));
        boolean workspace = report.workspace();

        StringBuilder sb = new StringBuilder();
        sb.append("# jk outdated dependencies\n\n");
        int modules = moduleCount(report);
        sb.append(on)
                .append(" · ")
                .append(modules)
                .append(modules == 1 ? " module" : " modules")
                .append(" · ")
                .append(report.rows().size())
                .append(" checked · **")
                .append(movable.size())
                .append("** can move");
        if (workspace) {
            sb.append(" across **")
                    .append(moving.size())
                    .append(moving.size() == 1 ? "** coordinate" : "** coordinates");
        }
        sb.append("\n\n");
        if (offline) {
            sb.append("> Offline: Compatible / Latest come from the local cache and repos only;"
                    + " an unreachable remote looks up to date.\n\n");
        }

        sb.append("## Can move\n\n");
        if (moving.isEmpty()) {
            sb.append("_none_\n\n");
        } else {
            List<String> headers = new ArrayList<>();
            headers.add("Dependency");
            headers.add("Current");
            headers.add("Compatible");
            headers.add("Latest");
            if (tip) headers.add("Tip");
            if (workspace) headers.add("Modules");
            headers.add("Scope");
            tableHead(sb, headers);
            for (OutdatedReport.Rollup r : moving) {
                List<String> cells = new ArrayList<>();
                cells.add(dependency(r));
                cells.add(cell(OutdatedReport.spreadText(r.current())));
                cells.add(cell(OutdatedReport.spreadText(r.compatible())));
                cells.add(cell(r.latest()));
                if (tip) cells.add(cell(r.tip()));
                if (workspace) cells.add(Integer.toString(r.modules().size()));
                cells.add(cell(String.join(" · ", r.scopes())));
                tableRow(sb, cells);
            }
            sb.append('\n');
        }

        if (workspace && !movable.isEmpty()) {
            sb.append("## By module\n\n");
            List<String> headers = new ArrayList<>();
            headers.add("Module");
            headers.add("Dependency");
            headers.add("Current");
            headers.add("Compatible");
            headers.add("Latest");
            if (tip) headers.add("Tip");
            headers.add("Scope");
            tableHead(sb, headers);
            for (OutdatedReport.Row r : movable) {
                List<String> cells = new ArrayList<>();
                cells.add(code(r.moduleLabel()));
                cells.add(code(r.coordinate()));
                cells.add(cell(r.current()));
                cells.add(cell(r.compatible()));
                cells.add(cell(r.latest()));
                if (tip) cells.add(cell(r.tip()));
                cells.add(cell(r.scope()));
                tableRow(sb, cells);
            }
            sb.append('\n');
        }

        sb.append("## Up to date\n\n");
        if (current.isEmpty()) {
            sb.append("_none_\n");
        } else {
            for (OutdatedReport.Rollup r : current) {
                sb.append("- ")
                        .append(dependency(r))
                        .append(' ')
                        .append(cell(OutdatedReport.spreadText(r.current())))
                        .append(" (")
                        .append(cell(String.join(" · ", r.scopes())))
                        .append(')');
                if (workspace) {
                    int n = r.modules().size();
                    sb.append(" · ").append(n).append(n == 1 ? " module" : " modules");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** The coordinate in code, with the catalog short name after it when one exists. */
    private static String dependency(OutdatedReport.Rollup r) {
        String c = code(r.coordinate());
        return r.display().isEmpty() || r.display().equals(r.coordinate()) ? c : c + " (" + r.display() + ")";
    }

    private static void tableHead(StringBuilder sb, List<String> headers) {
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("|").append("---|".repeat(headers.size())).append('\n');
    }

    private static void tableRow(StringBuilder sb, List<String> cells) {
        sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
    }

    private static int moduleCount(OutdatedReport report) {
        if (!report.workspace()) return 1;
        Set<String> labels = new LinkedHashSet<>();
        for (OutdatedReport.Row r : report.rows()) {
            if (!blank(r.moduleLabel())) labels.add(r.moduleLabel());
        }
        return Math.max(1, labels.size());
    }

    private static String code(String text) {
        return blank(text) ? NONE : "`" + text + "`";
    }

    private static String cell(@Nullable String text) {
        return text == null || text.isBlank() ? NONE : text;
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}

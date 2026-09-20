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
 * whatever filter the terminal applied: the rows that can move as a table, the rest as a list.
 * Stateless renderer; a new run replaces the file.
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
     * one.
     */
    public static String render(OutdatedReport report, boolean offline, LocalDate on) {
        List<OutdatedReport.Row> movable = new ArrayList<>();
        List<OutdatedReport.Row> current = new ArrayList<>();
        for (OutdatedReport.Row r : report.rows()) {
            (r.canMove() ? movable : current).add(r);
        }
        boolean tip = report.rows().stream().anyMatch(r -> !blank(r.tip()));

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
                .append("** can move\n\n");
        if (offline) {
            sb.append("> Offline: Compatible / Latest come from the local cache and repos only;"
                    + " an unreachable remote looks up to date.\n\n");
        }

        sb.append("## Can move\n\n");
        if (movable.isEmpty()) {
            sb.append("_none_\n\n");
        } else {
            List<String> headers = new ArrayList<>();
            if (report.workspace()) headers.add("Module");
            headers.add("Dependency");
            headers.add("Current");
            headers.add("Compatible");
            headers.add("Latest");
            if (tip) headers.add("Tip");
            headers.add("Scope");
            sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
            sb.append("|").append("---|".repeat(headers.size())).append('\n');
            for (OutdatedReport.Row r : movable) {
                List<String> cells = new ArrayList<>();
                if (report.workspace()) cells.add(code(r.moduleLabel()));
                cells.add(code(r.coordinate()));
                cells.add(cell(r.current()));
                cells.add(cell(r.compatible()));
                cells.add(cell(r.latest()));
                if (tip) cells.add(cell(r.tip()));
                cells.add(cell(r.scope()));
                sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## Up to date\n\n");
        if (current.isEmpty()) {
            sb.append("_none_\n");
        } else {
            for (OutdatedReport.Row r : current) {
                sb.append("- ");
                if (report.workspace() && !blank(r.moduleLabel()))
                    sb.append(code(r.moduleLabel())).append(" · ");
                sb.append(code(r.coordinate()))
                        .append(' ')
                        .append(cell(r.current()))
                        .append(" (")
                        .append(cell(r.scope()))
                        .append(")\n");
            }
        }
        return sb.toString();
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

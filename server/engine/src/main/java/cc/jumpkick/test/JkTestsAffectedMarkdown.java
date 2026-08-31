// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/** {@code target/jk-tests-affected.md} — modules + ranked tests. Does not touch {@code jk-results.md}. */
public final class JkTestsAffectedMarkdown {

    public static final String FILE_NAME = "jk-tests-affected.md";

    private static final ConcurrentHashMap<Path, AffectedTests> BY_ROOT = new ConcurrentHashMap<>();

    private JkTestsAffectedMarkdown() {}

    public static Path latestPath(Path workspaceOrProjectRoot) {
        return workspaceOrProjectRoot.resolve(BuildLayout.TARGET).resolve(FILE_NAME);
    }

    public static void publish(Path workspaceOrProjectRoot, AffectedTests slice) throws IOException {
        Path root = workspaceOrProjectRoot.toAbsolutePath().normalize();
        AffectedTests merged = BY_ROOT.merge(root, slice, AffectedTests::merge);
        write(latestPath(root), merged);
    }

    public static void write(Path file, AffectedTests report) throws IOException {
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, render(report));
    }

    public static String render(AffectedTests report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# jk affected tests\n\n");
        if (report.refuse() != null) {
            sb.append("**REFUSED** · `")
                    .append(report.refuse().code())
                    .append("` — ")
                    .append(report.refuse().message())
                    .append(".\nThis run did **not** execute tests. Run `jk test`.\n\n");
        } else {
            sb.append("WIP · ")
                    .append(report.modules().size())
                    .append(" module")
                    .append(report.modules().size() == 1 ? "" : "s")
                    .append(" · ranked **")
                    .append(report.ranked().size())
                    .append("** of **")
                    .append(report.candidateCount())
                    .append("** candidates (cap ")
                    .append(report.cap())
                    .append(").\n\n");
        }
        sb.append("## Modules\n\n");
        if (report.modules().isEmpty()) {
            sb.append("_none_\n\n");
        } else {
            sb.append("| Module | Why |\n|--------|-----|\n");
            for (var m : report.modules()) {
                sb.append("| `").append(m.path()).append("` | ").append(m.why()).append(" |\n");
            }
            sb.append('\n');
        }
        if (report.refuse() == null) {
            sb.append("## Tests\n\n");
            if (report.ranked().isEmpty()) {
                sb.append("_nothing affected_\n");
            } else {
                sb.append("| Module | Class | Score | Reason |\n|--------|-------|------:|--------|\n");
                for (var r : report.ranked()) {
                    sb.append("| ")
                            .append(r.module())
                            .append(" | `")
                            .append(r.className())
                            .append("` | ")
                            .append(r.score())
                            .append(" | ")
                            .append(r.reason())
                            .append(" |\n");
                }
                if (report.ranked().size() < report.candidateCount()) {
                    sb.append("\n_Dropped ")
                            .append(report.candidateCount() - report.ranked().size())
                            .append(" candidates past cap ")
                            .append(report.cap())
                            .append(". This is not the full default suite — `jk test`._\n");
                }
            }
        }
        return sb.toString();
    }
}

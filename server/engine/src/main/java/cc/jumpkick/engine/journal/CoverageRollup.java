// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The workspace coverage page: one table over every module that measured coverage this run, each
 * row linking to that module's JaCoCo HTML, with the same figures and deltas as the {@code ##
 * Coverage} block of {@code jk-results.md}. Written at {@code target/reports/coverage/index.html}
 * under the invocation root; when the root is itself a module whose own report lives there, the
 * roll-up is {@code workspace.html} beside it. A single-module run has no roll-up — its module
 * page is the page.
 */
final class CoverageRollup {

    private CoverageRollup() {}

    /** The roll-up's path for {@code r}, or the one module's own page when there is one module. */
    static Path pageFor(BuildRecord r) {
        Path dir =
                Path.of(r.dir()).resolve(BuildLayout.TARGET).resolve("reports").resolve("coverage");
        Path index = dir.resolve("index.html");
        for (BuildRecord.Coverage c : r.coverage()) {
            if (samePath(c.html(), index)) return dir.resolve("workspace.html");
        }
        return index;
    }

    static void write(Path root, BuildRecord r, @Nullable BuildRecord previous) throws IOException {
        if (r.coverage().size() < 2) return;
        Path page = pageFor(r);
        AtomicWrites.replace(page, render(r, previous, page.resolveSibling("")));
    }

    static String render(BuildRecord r, @Nullable BuildRecord previous, Path pageDir) {
        List<BuildRecord.Coverage> before = previous == null ? List.of() : previous.coverage();
        boolean delta = !before.isEmpty();
        long previousRun = previous == null ? 0 : previous.buildNumber();
        StringBuilder sb = new StringBuilder(2_048);
        sb.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n<title>Coverage — ")
                .append(esc(r.coord() == null ? r.dir() : r.coord()))
                .append("</title>\n<style>body{font:14px system-ui,sans-serif;margin:2rem;color:#222}")
                .append(
                        "table{border-collapse:collapse}th,td{padding:.35rem .8rem;border-bottom:1px solid #ddd;text-align:right}")
                .append("th:first-child,td:first-child{text-align:left}tfoot td{font-weight:600}")
                .append(".up{color:#0a7f3f}.down{color:#b3261e}</style></head><body>\n<h1>Coverage · ")
                .append(esc(r.coord() == null ? r.dir() : r.coord()))
                .append(" · run #")
                .append(r.buildNumber())
                .append("</h1>\n");
        if (delta) sb.append("<p>Δ vs run #").append(previousRun).append("</p>\n");
        sb.append("<table><thead><tr><th>Module</th><th>Lines</th>");
        if (delta) sb.append("<th>Δ</th>");
        sb.append("<th>Branches</th>");
        if (delta) sb.append("<th>Δ</th>");
        sb.append("</tr></thead>\n<tbody>\n");
        long lc = 0, lm = 0, bc = 0, bm = 0, plc = 0, plm = 0, pbc = 0, pbm = 0;
        for (BuildRecord.Coverage c : r.coverage()) {
            BuildRecord.Coverage was = null;
            for (BuildRecord.Coverage p : before) if (p.dir().equals(c.dir())) was = p;
            sb.append("<tr><td><a href=\"")
                    .append(esc(href(pageDir, c.html())))
                    .append("\">")
                    .append(esc(c.label()))
                    .append("</a></td><td>")
                    .append(JkResultsCoverageSection.cell(c.linesCovered(), c.linesMissed()))
                    .append("</td>");
            if (delta) sb.append(deltaCell(was == null ? null : c.linePercent() - was.linePercent()));
            sb.append("<td>")
                    .append(JkResultsCoverageSection.cell(c.branchesCovered(), c.branchesMissed()))
                    .append("</td>");
            if (delta) sb.append(deltaCell(was == null ? null : c.branchPercent() - was.branchPercent()));
            sb.append("</tr>\n");
            lc += c.linesCovered();
            lm += c.linesMissed();
            bc += c.branchesCovered();
            bm += c.branchesMissed();
        }
        for (BuildRecord.Coverage p : before) {
            plc += p.linesCovered();
            plm += p.linesMissed();
            pbc += p.branchesCovered();
            pbm += p.branchesMissed();
        }
        sb.append("</tbody>\n<tfoot><tr><td>all</td><td>")
                .append(JkResultsCoverageSection.cell(lc, lm))
                .append("</td>");
        if (delta) sb.append(deltaCell(BuildRecord.Coverage.percent(lc, lm) - BuildRecord.Coverage.percent(plc, plm)));
        sb.append("<td>").append(JkResultsCoverageSection.cell(bc, bm)).append("</td>");
        if (delta) sb.append(deltaCell(BuildRecord.Coverage.percent(bc, bm) - BuildRecord.Coverage.percent(pbc, pbm)));
        sb.append("</tr></tfoot></table>\n<p>Same figures as <code>target/jk-results.md</code>.</p>\n</body></html>\n");
        return sb.toString();
    }

    private static String deltaCell(@Nullable Double points) {
        if (points == null) return "<td>new</td>";
        String text = JkResultsCoverageSection.signed(points);
        String cls = text.startsWith("+") ? " class=\"up\"" : text.startsWith("−") ? " class=\"down\"" : "";
        return "<td" + cls + ">" + text + "</td>";
    }

    private static String href(Path pageDir, String html) {
        try {
            Path target = Path.of(html).toAbsolutePath().normalize();
            return pageDir.toAbsolutePath()
                    .normalize()
                    .relativize(target)
                    .toString()
                    .replace('\\', '/');
        } catch (RuntimeException e) {
            return html;
        }
    }

    private static boolean samePath(String a, Path b) {
        try {
            return Path.of(a)
                    .toAbsolutePath()
                    .normalize()
                    .equals(b.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

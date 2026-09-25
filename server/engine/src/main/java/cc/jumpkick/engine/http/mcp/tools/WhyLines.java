// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.wire.protocol.WhyReport;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** At most five lines: the locked version, the rule that picked it, and one path. */
final class WhyLines {

    static final int MAX_LINES = 5;

    private WhyLines() {}

    static String of(WhyReport report, String query) {
        if (report.error() != null) return finish(report.error().strip());
        if (report.matchNames().isEmpty() && report.exclusions().isEmpty()) {
            return finish(query + " is not in jk-lock.toml");
        }
        List<String> lines = new ArrayList<>();
        int shown = 0;
        for (int i = 0; i < report.matchNames().size(); i++) {
            if (lines.size() >= MAX_LINES - 1 && i + 1 < report.matchNames().size()) break;
            if (lines.size() >= MAX_LINES) break;
            String version =
                    i < report.matchVersions().size() ? report.matchVersions().get(i) : "";
            String head = report.matchNames().get(i) + (version.isEmpty() ? "" : " " + version);
            String rule = rule(report, i);
            if (!rule.isEmpty()) head = head + " · " + rule;
            lines.add(head);
            shown++;
            if (lines.size() >= MAX_LINES) break;
            String path = firstPath(report, i);
            if (path != null) lines.add("  " + path.replace(">", " > "));
        }
        int rest = report.matchNames().size() - shown;
        if (rest > 0 && lines.size() < MAX_LINES) lines.add("+" + rest + " more");
        for (int i = 0; i < report.exclusions().size() && lines.size() < MAX_LINES; i++) {
            lines.add(exclusion(report.exclusionFields(i)));
        }
        if (lines.size() > MAX_LINES) lines = new ArrayList<>(lines.subList(0, MAX_LINES));
        return finish(String.join("\n", lines));
    }

    /** What selected the version: a pin when one exists, otherwise the selector on the path. */
    private static String rule(WhyReport report, int match) {
        String pin = report.pinnedByOf(match);
        if (pin != null) return "pinned by " + pin;
        String idx = Integer.toString(match);
        for (int p = 0; p < report.paths().size(); p++) {
            if (!idx.equals(report.pathOwners().get(p))) continue;
            List<String> selectors = report.selectorsOf(p);
            String[] steps = report.paths().get(p).split(">", -1);
            for (int k = steps.length - 1; k >= 0; k--) {
                String selector = k < selectors.size() ? selectors.get(k) : "";
                if (selector.isEmpty()) continue;
                String by = k > 0
                        ? moduleOf(steps[k - 1])
                        : report.rootOf(p).isEmpty() ? ManifestPaths.MANIFEST : report.rootOf(p);
                return "declared " + selector + " by " + by;
            }
        }
        return "";
    }

    private static @Nullable String firstPath(WhyReport report, int match) {
        String idx = Integer.toString(match);
        for (int p = 0; p < report.paths().size(); p++) {
            if (idx.equals(report.pathOwners().get(p))) return report.paths().get(p);
        }
        return null;
    }

    private static String exclusion(List<String> fields) {
        String child = fields.get(0);
        String origin = fields.size() > 1 ? fields.get(1) : "";
        String under = fields.size() > 2 ? fields.get(2) : "";
        String line = child + " excluded under " + under;
        if (!origin.isEmpty()) line = line + " (by " + origin + ")";
        return line;
    }

    private static String moduleOf(String step) {
        int at = step.lastIndexOf('@');
        return at > 0 ? step.substring(0, at) : step;
    }

    private static String finish(String text) {
        String stripped = text.strip();
        return stripped.isEmpty() ? "\n" : stripped + "\n";
    }
}

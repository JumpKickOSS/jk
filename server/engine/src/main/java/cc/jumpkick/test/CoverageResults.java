// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide store of the coverage each module's {@code run-tests} step measured, keyed by module
 * path; the journal drains it at request-finish into the run's record and {@code jk-results.md}.
 * Companion to {@link MarkdownTestReport}, which carries the per-test entries the same way.
 */
public final class CoverageResults {

    /**
     * One module's whole-report counters and where its HTML report starts.
     *
     * @param dir the module directory, absolute
     * @param label the module coordinate ({@code group:name})
     * @param html the report's {@code index.html}, absolute
     */
    public record Module(
            String dir,
            String label,
            long linesCovered,
            long linesMissed,
            long branchesCovered,
            long branchesMissed,
            String html) {}

    private static final ConcurrentHashMap<String, Module> PUBLISHED = new ConcurrentHashMap<>();

    private CoverageResults() {}

    /**
     * Covered as a percentage of covered + missed, one decimal, as every surface spells it:
     * {@code 85.2%}; {@code 100.0%} of nothing.
     */
    public static String pct(long covered, long missed) {
        long total = covered + missed;
        double percent = total == 0 ? 100.0 : covered * 100.0 / total;
        return String.format(Locale.ROOT, "%.1f%%", percent);
    }

    /** Record {@code moduleDir}'s coverage; a second publish for the same module replaces the first. */
    public static void publish(Path moduleDir, Module module) {
        PUBLISHED.put(moduleDir.toAbsolutePath().normalize().toString(), module);
    }

    /**
     * Drain every published module whose directory is {@code projectDir} or a path under it, in
     * module-path order, so a concurrent build of a different checkout is not stolen.
     */
    public static List<Module> takeUnder(Path projectDir) {
        Path root;
        try {
            root = projectDir.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(PUBLISHED.keySet());
        keys.sort(null);
        List<Module> out = new ArrayList<>();
        for (String key : keys) {
            Path p;
            try {
                p = Path.of(key);
            } catch (RuntimeException e) {
                continue;
            }
            if (!p.equals(root) && !p.startsWith(root)) continue;
            Module m = PUBLISHED.remove(key);
            if (m != null) out.add(m);
        }
        return out;
    }
}

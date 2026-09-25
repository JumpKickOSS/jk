// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What changed between two consecutive runs of one project from one origin — the supervisor's
 * "what did it change, and did that help": the files that differ between the runs, the
 * diagnostics that appeared or went away, the tests that flipped, and the wall of the run before.
 * Computed once at journal-write by {@link #compute} and carried on the {@link BuildRecord}, so
 * {@code jk-results.md}, the dashboard's run view and MCP {@code run} all read the same
 * facts.
 *
 * <p>Every list is a {@link Rows}: the exact count beside at most {@value #MAX_SHOWN} entries, so
 * the rendering stays small however large the change. {@code files} is {@code null} when either
 * run has no source snapshot; the test rows are {@code null} when either run recorded no per-test
 * outcomes (a {@code --skip-tests} build, a run that compiled nothing) — an absent comparison, not
 * an empty one.
 */
public record JobDelta(
        long previousBuildNumber,
        boolean previousSuccess,
        long previousMillis,
        @Nullable Rows files,
        Rows appeared,
        Rows gone,
        @Nullable Rows broke,
        @Nullable Rows fixed,
        @Nullable Rows added,
        @Nullable Rows dropped) {

    /** How many entries of a list the delta keeps beside its count. */
    public static final int MAX_SHOWN = 8;

    /** Longest label kept for a diagnostic or test row. */
    static final int MAX_LABEL_CHARS = 160;

    /** A list's exact size and its first {@value #MAX_SHOWN} entries. */
    public record Rows(int count, List<String> shown) {
        public Rows {
            shown = shown == null ? List.of() : List.copyOf(shown);
        }

        /** The rows for {@code all}: its size, and its head. */
        public static Rows of(List<String> all) {
            return new Rows(all.size(), all.subList(0, Math.min(all.size(), MAX_SHOWN)));
        }

        /** Entries beyond the shown ones. */
        public int more() {
            return Math.max(0, count - shown.size());
        }
    }

    /** True when nothing the delta tracks changed. */
    public boolean quiet() {
        return empty(files)
                && empty(appeared)
                && empty(gone)
                && empty(broke)
                && empty(fixed)
                && empty(added)
                && empty(dropped);
    }

    /** True when both runs recorded per-test outcomes, so the test rows are a comparison. */
    public boolean comparedTests() {
        return broke != null && fixed != null && added != null && dropped != null;
    }

    private static boolean empty(@Nullable Rows rows) {
        return rows == null || rows.count() == 0;
    }

    /**
     * The delta from {@code previous} to {@code current}. {@code previousTests} / {@code
     * currentTests} map {@code Class#display} to {@link RunSnapshots#PASS}, {@link
     * RunSnapshots#FAIL} or {@link RunSnapshots#SKIP}; {@code previousSources} / {@code
     * currentSources} map a project-relative path to its content hash. A {@code null} side leaves
     * that comparison out.
     */
    public static JobDelta compute(
            BuildRecord previous,
            BuildRecord current,
            @Nullable Map<String, Character> previousTests,
            @Nullable Map<String, Character> currentTests,
            @Nullable Map<String, String> previousSources,
            @Nullable Map<String, String> currentSources) {
        Rows files = previousSources == null || currentSources == null
                ? null
                : Rows.of(changedFiles(previousSources, currentSources));
        Set<String> before = diagKeys(previous);
        Set<String> after = diagKeys(current);
        Map<String, String> labelsBefore = diagLabels(previous);
        Map<String, String> labelsAfter = diagLabels(current);
        List<String> appeared = new ArrayList<>();
        for (String k : after) if (!before.contains(k)) appeared.add(labelsAfter.get(k));
        List<String> gone = new ArrayList<>();
        for (String k : before) if (!after.contains(k)) gone.add(labelsBefore.get(k));
        Rows broke = null;
        Rows fixed = null;
        Rows added = null;
        Rows dropped = null;
        if (previousTests != null && currentTests != null) {
            TestFlips flips = TestFlips.of(previousTests, currentTests);
            broke = Rows.of(flips.broke);
            fixed = Rows.of(flips.fixed);
            added = Rows.of(flips.added);
            dropped = Rows.of(flips.dropped);
        }
        return new JobDelta(
                previous.buildNumber(),
                previous.success(),
                previous.millis(),
                files,
                Rows.of(appeared),
                Rows.of(gone),
                broke,
                fixed,
                added,
                dropped);
    }

    /** Sorted project-relative paths whose hash differs, with {@code (new)} / {@code (gone)} marks. */
    static List<String> changedFiles(Map<String, String> before, Map<String, String> after) {
        Set<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        List<String> out = new ArrayList<>();
        for (String p : paths) {
            String was = before.get(p);
            String now = after.get(p);
            if (was == null) out.add(p + " (new)");
            else if (now == null) out.add(p + " (gone)");
            else if (!was.equals(now)) out.add(p);
        }
        return out;
    }

    /** The identity of a diagnostic across runs: everything but its stack and snippet. */
    static String diagKey(BuildRecord.Diag d) {
        return String.join(
                "\t",
                d.severity(),
                d.dir(),
                nz(d.step()),
                d.code(),
                d.file(),
                String.valueOf(d.line()),
                nz(d.test()),
                firstLine(d.message()));
    }

    private static Set<String> diagKeys(BuildRecord r) {
        Set<String> keys = new LinkedHashSet<>();
        for (BuildRecord.Diag d : r.diagnostics()) keys.add(diagKey(d));
        return keys;
    }

    private static Map<String, String> diagLabels(BuildRecord r) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (BuildRecord.Diag d : r.diagnostics()) labels.putIfAbsent(diagKey(d), diagLabel(d, r.dir()));
        return labels;
    }

    /** {@code error · compile-java · src/Foo.java:12 · cannot find symbol}, clipped. */
    static String diagLabel(BuildRecord.Diag d, @Nullable String projectDir) {
        StringBuilder b = new StringBuilder(d.severity());
        if (d.step() != null && !d.step().isBlank()) b.append(" · ").append(d.step());
        if (d.test() != null && !d.test().isBlank()) {
            b.append(" · ").append(d.test());
        } else if (!d.file().isBlank()) {
            b.append(" · ").append(relative(d.file(), projectDir));
            if (d.line() > 0) b.append(':').append(d.line());
        }
        String msg = firstLine(d.message()).strip();
        if (!msg.isEmpty()) b.append(" · ").append(msg);
        return clip(b.toString());
    }

    static String relative(String file, @Nullable String projectDir) {
        if (projectDir == null || projectDir.isBlank()) return file;
        try {
            Path f = Path.of(file);
            Path root = Path.of(projectDir);
            return f.isAbsolute() && f.startsWith(root) ? root.relativize(f).toString() : file;
        } catch (RuntimeException e) {
            return file;
        }
    }

    static String clip(String s) {
        return s.length() <= MAX_LABEL_CHARS ? s : s.substring(0, MAX_LABEL_CHARS - 1) + "…";
    }

    private static String firstLine(@Nullable String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String nz(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** The four ways a test moves between runs; a skip is present without a verdict. */
    private record TestFlips(List<String> broke, List<String> fixed, List<String> added, List<String> dropped) {
        static TestFlips of(Map<String, Character> before, Map<String, Character> after) {
            List<String> broke = new ArrayList<>();
            List<String> fixed = new ArrayList<>();
            List<String> added = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (Map.Entry<String, Character> e : after.entrySet()) {
                Character was = before.get(e.getKey());
                char now = e.getValue();
                if (was == null) added.add(e.getKey());
                else if (was == RunSnapshots.PASS && now == RunSnapshots.FAIL) broke.add(e.getKey());
                else if (was == RunSnapshots.FAIL && now == RunSnapshots.PASS) fixed.add(e.getKey());
            }
            for (String k : before.keySet()) if (!after.containsKey(k)) dropped.add(k);
            return new TestFlips(broke, fixed, added, dropped);
        }
    }
}

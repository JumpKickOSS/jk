// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.host.Log;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * What one project's builds have learned about how long their work takes, in one file under the
 * project's home ({@value #FILE}), a {@link SectionedFile} of four sections:
 *
 * <ul>
 *   <li>{@value #RUNS}: the step ledger's recent runs, one JSON line each (the runtime's {@code
 *       StepLedger} owns the grammar and the count kept);
 *   <li>{@value #MEANS}: one {@link RunningMean} per measure the estimates read, {@code key mean
 *       last count seen}: a module's step walls ({@code module.<dir>.task.<step>.wall-ms}, a suite's
 *       one-runner {@code wall1-ms}), a step kind's ({@code task.<step>.wall-ms}) and an
 *       invocation's ({@code invocation.<kind>.wall-ms}, {@code workspace.wall-ms}).
 *       Module keys are spelled relative to the project root ({@link ModuleKeys});
 *   <li>{@value #CLASSES}: one {@link RunningMean} per test class of a suite, {@code
 *       suiteDir<TAB>fqcn<TAB>last<TAB>mean<TAB>count<TAB>seen}, the suite as {@code
 *       <rel-dir><@selection>};
 *   <li>{@value #META}: one {@code <section><@selection> N} line per group of rows, the runs that
 *       folded a row of that group, which its rows' {@code seen} count in ({@link #groupOf}).
 * </ul>
 *
 * <p>A finished run is folded in once ({@link #foldRun}): one locked read and rewrite of the whole
 * file. A row's staleness counts only the runs that could have refreshed it: those that folded a
 * row of its section under its test selection. A row is dropped once {@link #STALE_RUNS} of them
 * passed without refreshing it, so a class gone from a suite that still runs goes, and a rarely run
 * tier keeps its rows however many runs of another tier come between. Past {@link #MAX_MEANS} or
 * {@link #MAX_CLASSES} rows the stalest within their own group go first, so the file follows the
 * project's current shape and stays bounded however long it lives.
 */
public final class ProjectStore {

    /** The store's file name under a project's home. */
    public static final String FILE = "estimates.txt";

    /** The step ledger's runs: one JSON line each. */
    public static final String RUNS = "runs";

    /** The scalar run means: {@code key mean last count seen}. */
    public static final String MEANS = "means";

    /** The test-class walls: {@code suiteDir fqcn last mean count seen}, tab separated. */
    public static final String CLASSES = "classes";

    /** {@code <section><@selection> N}: the runs folded per group of rows. */
    public static final String META = "meta";

    /** Runs of its own group a row may go unrefreshed before it is dropped. */
    static final long STALE_RUNS = 200;

    /** Scalar rows kept at most. */
    public static final int MAX_MEANS = 4_000;

    /**
     * Class rows kept at most: every tier of a project the size of jk's own (about 2,100 classes
     * across its default and integration tiers) with room to spare.
     */
    public static final int MAX_CLASSES = 10_000;

    private static final String HEADER = "what this project's builds learned about their walls (ProjectStore)";

    private static final Set<String> ESTIMATE_SECTIONS = Set.of(MEANS, CLASSES);

    private ProjectStore() {}

    /** The store under {@code projectHome}. */
    public static Path file(Path projectHome) {
        return projectHome.resolve(FILE);
    }

    /** A scalar row of {@link #MEANS}. */
    public interface MeanRow {
        void row(String key, RunningMean mean);
    }

    /** A class row of {@link #CLASSES}. */
    public interface ClassRow {
        void row(String suiteDir, String fqcn, RunningMean wall);
    }

    /**
     * Whether a run's {@code key} is one the estimates read, and so one the store keeps: a module's
     * step wall and a suite's one-runner wall, a step kind's wall, an invocation's wall.
     */
    public static boolean isKept(String key) {
        if (key.equals("workspace.wall-ms")) return true;
        if (key.startsWith("module.")) {
            return key.contains(".task.") && (key.endsWith(".wall-ms") || key.endsWith(".wall1-ms"));
        }
        return (key.startsWith("task.") || key.startsWith("invocation.")) && key.endsWith(".wall-ms");
    }

    /**
     * The kept scalars of a run's metrics text ({@link MetricsFile}), by key: a key the run measured
     * several times (a step kind's wall once per module) is the mean of its samples, one sample of
     * the run.
     */
    public static Map<String, Double> scalars(@Nullable String metricsText) {
        Map<String, double[]> sums = new LinkedHashMap<>();
        if (metricsText != null) {
            MetricsFile.scan(
                    metricsText,
                    (section, key, v) -> {
                        if (v < 0 || !isKept(key)) return;
                        double[] s = sums.computeIfAbsent(key, k -> new double[2]);
                        s[0] += v;
                        s[1]++;
                    },
                    (dir, fqcn, ms) -> {});
        }
        Map<String, Double> out = new LinkedHashMap<>();
        sums.forEach((k, s) -> out.put(k, s[0] / s[1]));
        return out;
    }

    /** {@link #foldRun(Path, String, UnaryOperator)} with no ledger line. */
    public static void foldRun(Path projectHome, @Nullable String metricsText) {
        foldRun(projectHome, metricsText, null);
    }

    /**
     * Folds a finished run into the store in one locked rewrite: its kept scalars ({@link
     * #scalars}) into {@link #MEANS} and its class walls into {@link #CLASSES}, each row at its
     * group's next run ({@link #groupOf}), and {@code runs}, when given, applied to {@link #RUNS}. Failures are logged: what a
     * project learns is never worth failing a build over.
     */
    public static void foldRun(
            Path projectHome, @Nullable String metricsText, @Nullable UnaryOperator<List<String>> runs) {
        Map<String, Double> scalars = scalars(metricsText);
        Map<String, Map<String, Double>> classes = new LinkedHashMap<>();
        if (metricsText != null) {
            MetricsFile.scan(metricsText, (section, key, v) -> {}, (dir, fqcn, ms) -> {
                if (ms > 0 && !fqcn.isBlank())
                    classes.computeIfAbsent(dir, d -> new LinkedHashMap<>()).put(fqcn, ms);
            });
        }
        boolean learns = !scalars.isEmpty() || !classes.isEmpty();
        if (!learns && runs == null) return;
        try {
            SectionedFile.updateAll(file(projectHome), HEADER, all -> {
                if (runs != null) all.put(RUNS, runs.apply(new ArrayList<>(all.getOrDefault(RUNS, List.of()))));
                if (!learns) return all;
                // Each group this run folds a row of moves on by one; the others stand still.
                Map<String, Long> seqs = sequences(all.getOrDefault(META, List.of()));
                Set<String> folded = new TreeSet<>();
                for (String key : scalars.keySet()) folded.add(groupOf(MEANS, key));
                for (String dir : classes.keySet()) folded.add(groupOf(CLASSES, dir));
                for (String g : folded) seqs.merge(g, 1L, Long::sum);
                Set<String> live = new TreeSet<>();
                all.put(MEANS, foldMeans(all.getOrDefault(MEANS, List.of()), scalars, seqs, live));
                all.put(CLASSES, foldClasses(all.getOrDefault(CLASSES, List.of()), classes, seqs, live));
                // A group none of whose rows is left is forgotten with them.
                List<String> meta = new ArrayList<>();
                seqs.forEach((g, n) -> {
                    if (live.contains(g)) meta.add(g + ' ' + n);
                });
                all.put(META, meta);
                return all;
            });
        } catch (IOException | RuntimeException e) {
            Log.debug("ProjectStore: run not folded into " + projectHome, e);
        }
    }

    /**
     * The group a row of {@code section} counts its staleness in: the section and the test
     * selection the row was measured under, {@code classes@integration} for a class of an
     * integration suite, {@code means} for a compile's wall. Only a run that folds a row of a group
     * moves that group on.
     */
    static String groupOf(String section, String key) {
        if (CLASSES.equals(section)) {
            int at = key.lastIndexOf(TaskNames.SUITE_SEPARATOR);
            return at < 0 ? section : section + key.substring(at);
        }
        int suite = key.indexOf(TaskNames.RUN_TESTS + TaskNames.SUITE_SEPARATOR);
        if (suite < 0) return section;
        int from = suite + TaskNames.RUN_TESTS.length();
        int end = key.indexOf(".wall", from);
        return section + key.substring(from, end < 0 ? key.length() : end);
    }

    /** Applies {@code edit} to {@link #RUNS} alone. */
    public static void updateRuns(Path projectHome, UnaryOperator<List<String>> edit) {
        foldRun(projectHome, null, edit);
    }

    /** The lines of {@link #RUNS}, oldest first. */
    public static List<String> runs(Path projectHome) {
        return SectionedFile.read(file(projectHome), Set.of(RUNS)).getOrDefault(RUNS, List.of());
    }

    /**
     * One streaming pass over {@link #MEANS} and {@link #CLASSES}, each well-formed row to its sink
     * (either may be null); the other sections are skipped unread.
     */
    public static void scan(Path projectHome, @Nullable MeanRow means, @Nullable ClassRow classes) {
        SectionedFile.scan(file(projectHome), ESTIMATE_SECTIONS, (section, line) -> {
            if (MEANS.equals(section)) {
                if (means == null) return;
                String[] cols = line.split(" ");
                RunningMean m = RunningMean.parse(cols, 1);
                if (m != null) means.row(cols[0], m);
            } else if (classes != null) {
                String[] cols = line.split("\t");
                RunningMean m = parseClass(cols);
                if (m != null) classes.row(cols[0], cols[1], m);
            }
        });
    }

    /** Each group's run count from {@link #META}, {@code <group> N} per line. */
    private static Map<String, Long> sequences(List<String> meta) {
        Map<String, Long> out = new TreeMap<>();
        for (String line : meta) {
            String[] cols = line.split(" ");
            if (cols.length != 2) continue;
            try {
                out.put(cols[0], Long.parseLong(cols[1]));
            } catch (NumberFormatException e) {
                // a line of another grammar: no group
            }
        }
        return out;
    }

    private static List<String> foldMeans(
            List<String> lines, Map<String, Double> scalars, Map<String, Long> seqs, Set<String> live) {
        Map<String, RunningMean> all = new TreeMap<>();
        for (String line : lines) {
            String[] cols = line.split(" ");
            RunningMean m = RunningMean.parse(cols, 1);
            if (m != null && isKept(cols[0])) all.put(cols[0], m);
        }
        scalars.forEach((k, v) -> all.put(k, RunningMean.fold(all.get(k), v, seqOf(seqs, groupOf(MEANS, k)))));
        List<String> out = new ArrayList<>(all.size());
        for (Map.Entry<String, RunningMean> e : kept(MEANS, all, seqs, MAX_MEANS, live)) {
            out.add(e.getKey() + ' ' + e.getValue().columns());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static List<String> foldClasses(
            List<String> lines, Map<String, Map<String, Double>> walls, Map<String, Long> seqs, Set<String> live) {
        Map<String, RunningMean> all = new TreeMap<>();
        for (String line : lines) {
            String[] cols = line.split("\t");
            RunningMean m = parseClass(cols);
            if (m != null) all.put(cols[0] + '\t' + cols[1], m);
        }
        walls.forEach((dir, byClass) -> byClass.forEach((fqcn, ms) -> {
            String key = dir + '\t' + fqcn;
            all.put(key, RunningMean.fold(all.get(key), ms, seqOf(seqs, groupOf(CLASSES, dir))));
        }));
        List<String> out = new ArrayList<>(all.size());
        for (Map.Entry<String, RunningMean> e : kept(CLASSES, all, seqs, MAX_CLASSES, live)) {
            RunningMean m = e.getValue();
            out.add(e.getKey()
                    + '\t'
                    + RunningMean.fmt(m.last())
                    + '\t'
                    + RunningMean.fmt(m.mean())
                    + '\t'
                    + m.count()
                    + '\t'
                    + m.seen());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** {@code suiteDir fqcn last mean count seen}; null when it does not parse. */
    private static @Nullable RunningMean parseClass(String[] cols) {
        if (cols.length < 6 || cols[0].isEmpty() || cols[1].isEmpty()) return null;
        return RunningMean.parse(new String[] {cols[3], cols[2], cols[4], cols[5]}, 0);
    }

    private static long seqOf(Map<String, Long> seqs, String group) {
        return seqs.getOrDefault(group, 0L);
    }

    /** A class row's group: its suite's, from the {@code suiteDir<TAB>fqcn} key. */
    private static String classGroup(String key) {
        int tab = key.indexOf('\t');
        return groupOf(CLASSES, tab < 0 ? key : key.substring(0, tab));
    }

    /**
     * The rows of {@code all} seen within {@link #STALE_RUNS} runs of their own group, at most
     * {@code max} of them: the least stale in their group's runs, ties by key, so the classes of a
     * suite folded in one run sort together and the cap cuts into at most one suite. A row seen
     * after its group's latest run belongs to no run this store counted and is dropped. The groups
     * of the rows kept are added to {@code live}.
     */
    private static List<Map.Entry<String, RunningMean>> kept(
            String section, Map<String, RunningMean> all, Map<String, Long> seqs, int max, Set<String> live) {
        List<Map.Entry<String, RunningMean>> rows = new ArrayList<>(all.size());
        Map<String, Long> staleness = new HashMap<>();
        for (Map.Entry<String, RunningMean> e : all.entrySet()) {
            String group = CLASSES.equals(section) ? classGroup(e.getKey()) : groupOf(section, e.getKey());
            long behind = seqOf(seqs, group) - e.getValue().seen();
            if (behind < 0 || behind > STALE_RUNS) continue;
            rows.add(e);
            staleness.put(e.getKey(), behind);
        }
        if (rows.size() > max) {
            rows.sort(Comparator.<Map.Entry<String, RunningMean>>comparingLong(
                            e -> staleness.getOrDefault(e.getKey(), 0L))
                    .thenComparing(Map.Entry::getKey));
            rows = rows.subList(0, max);
        }
        for (Map.Entry<String, RunningMean> e : rows) {
            live.add(CLASSES.equals(section) ? classGroup(e.getKey()) : groupOf(section, e.getKey()));
        }
        return rows;
    }
}

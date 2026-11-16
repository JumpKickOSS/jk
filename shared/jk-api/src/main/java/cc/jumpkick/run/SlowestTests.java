// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The slowest test classes and methods of one run, ranked from its {@link TestClassResult}s. Only
 * classes and methods that ran count: a suite replayed from its green stamp sends no class results,
 * so it never ranks. Safe across the modules and workers of one run.
 */
public final class SlowestTests {

    /** The wall a class or method must reach to rank; a suite with nothing this slow ranks nothing. */
    public static final long SLOW_MS = 1_000;

    /** Rows kept per list. */
    public static final int TOP = 5;

    /** The tier of a run that includes no tag: the untagged tests, the inner loop. */
    public static final String FAST_TIER = "fast";

    /** One ranked class ({@code method} empty) or method. */
    public record Row(String module, String className, String method, String tier, long millis) {}

    /** The ranked classes and methods, longest first. */
    public record Ranking(List<Row> classes, List<Row> methods) {
        public static final Ranking EMPTY = new Ranking(List.of(), List.of());

        public Ranking {
            classes = List.copyOf(classes);
            methods = List.copyOf(methods);
        }

        public boolean isEmpty() {
            return classes.isEmpty() && methods.isEmpty();
        }
    }

    private static final Comparator<Row> LONGEST_FIRST = Comparator.comparingLong(Row::millis)
            .reversed()
            .thenComparing(Row::className)
            .thenComparing(Row::method);

    private final List<Row> classes = new ArrayList<>();
    private final List<Row> methods = new ArrayList<>();

    /** Fold one finished class in. */
    public synchronized void add(TestClassResult r) {
        if (r.durationMs() >= SLOW_MS) {
            classes.add(new Row(r.module(), r.className(), "", r.tier(), r.durationMs()));
            trim(classes);
        }
        for (TestClassResult.SlowMethod m : r.slow()) {
            if (m.millis() < SLOW_MS) continue;
            methods.add(new Row(r.module(), r.className(), m.name(), r.tier(), m.millis()));
        }
        trim(methods);
    }

    public synchronized Ranking ranking() {
        return new Ranking(classes, methods);
    }

    private static void trim(List<Row> rows) {
        rows.sort(LONGEST_FIRST);
        while (rows.size() > TOP) rows.removeLast();
    }

    /**
     * The method part of a test's display: its name without the parameter list, any invocation
     * index kept — {@code build(java.nio.file.Path)[#2]} is {@code build[#2]}.
     */
    public static String methodName(String method) {
        int open = method.indexOf('(');
        if (open < 0) return method;
        int close = method.indexOf(')', open);
        return close < 0 ? method.substring(0, open) : method.substring(0, open) + method.substring(close + 1);
    }

    /** {@code 640ms}, {@code 1.4s}, {@code 2m 05s}: a test's wall as every slowest surface spells it. */
    public static String duration(long millis) {
        if (millis < 1000) return Math.max(0, millis) + "ms";
        if (millis < 60_000) return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
        long s = Math.round(millis / 1000.0);
        return s / 60 + "m " + String.format(Locale.ROOT, "%02ds", s % 60);
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestQuarantine;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Each test class's recent runs, and the quarantine they feed. Every run of a suite appends each
 * class it finished to that class's history; a class that passed on a re-run with nothing changed
 * as often as the workspace's {@link TestQuarantine.Policy} says goes into {@value
 * TestQuarantine#FILE}, and the run says so.
 *
 * <p>The history is one file per module under {@code <state>/test-history}, outside the checkout so
 * a clean keeps it: a {@code # <module dir>} line, then {@code <class>\t<outcomes>}, the outcomes
 * one character per run, oldest first ({@link Outcome#mark}), the last {@value #KEEP} kept.
 */
final class TestFlakes {

    /** The warning a class jk has just quarantined carries. */
    static final String QUARANTINED = "test-quarantined";

    /** The warning a quarantine file that cannot be read carries; its failures fail the run. */
    static final String UNREADABLE = "test-quarantine-unreadable";

    /** Runs a class's history keeps. */
    static final int KEEP = 100;

    /** How one class ended in one run. */
    enum Outcome {
        PASSED('.'),
        PASSED_ON_RERUN('R'),
        FAILED('F');

        final char mark;

        Outcome(char mark) {
            this.mark = mark;
        }

        /** The worse of two outcomes of one class: a nested class's failure fails its outer class. */
        static Outcome worse(Outcome a, Outcome b) {
            return a.compareTo(b) >= 0 ? a : b;
        }
    }

    /** Held while this engine reads and rewrites a history or the quarantine file. */
    private static final Object LOCK = new Object();

    private TestFlakes() {}

    /** The history file of the module at {@code moduleDir}, under {@code stateDir}. */
    static Path historyFile(Path stateDir, Path moduleDir) {
        String dir = moduleDir.toAbsolutePath().normalize().toString();
        return stateDir.resolve("test-history").resolve(Hashing.sha256Hex(dir).substring(0, 16) + ".tsv");
    }

    /**
     * Append this run's {@code outcomes} to the module's history, then quarantine each class that
     * just reached the workspace's threshold. A history or quarantine that cannot be read or written
     * costs the record, never the run.
     */
    static void record(TaskContext ctx, Path root, Path moduleDir, Map<String, Outcome> outcomes) {
        if (!outcomes.isEmpty()) record(ctx, JkDirs.state(), root, moduleDir, outcomes);
    }

    /** {@link #record} with the history under {@code stateDir}. */
    static void record(TaskContext ctx, Path stateDir, Path root, Path moduleDir, Map<String, Outcome> outcomes) {
        TestQuarantine.Policy policy = TestQuarantine.Policy.of(root);
        List<String> due;
        try {
            due = append(historyFile(stateDir, moduleDir), moduleDir, outcomes, policy);
        } catch (IOException | RuntimeException e) {
            Log.debug("test history not recorded", e);
            return;
        }
        if (!due.isEmpty()) quarantine(ctx, root, moduleDir, due, policy);
    }

    /**
     * Append {@code outcomes} to {@code file} and answer the classes that passed on a re-run in this
     * run and have now done so {@code policy.flakes()} times in their last {@code policy.runs()}
     * runs; none when the policy is off.
     */
    static List<String> append(Path file, Path moduleDir, Map<String, Outcome> outcomes, TestQuarantine.Policy policy)
            throws IOException {
        Map<String, String> rows;
        synchronized (LOCK) {
            rows = read(file);
            outcomes.forEach((cls, o) -> rows.merge(cls, String.valueOf(o.mark), (old, now) -> last(old + now, KEEP)));
            StringBuilder sb = new StringBuilder("# ")
                    .append(moduleDir.toAbsolutePath().normalize())
                    .append('\n');
            rows.forEach(
                    (cls, marks) -> sb.append(cls).append('\t').append(marks).append('\n'));
            Files.createDirectories(file.getParent());
            AtomicWrites.replace(file, sb.toString());
        }
        List<String> due = new ArrayList<>();
        if (policy.off()) return due;
        outcomes.forEach((cls, o) -> {
            if (o == Outcome.PASSED_ON_RERUN && flakes(rows.getOrDefault(cls, ""), policy.runs()) >= policy.flakes())
                due.add(cls);
        });
        return due;
    }

    /** Each class's outcomes in {@code file}, oldest first; empty when there is none. */
    static Map<String, String> read(Path file) throws IOException {
        Map<String, String> rows = new TreeMap<>();
        if (!Files.isRegularFile(file)) return rows;
        for (String line : Files.readAllLines(file)) {
            int tab = line.indexOf('\t');
            if (line.startsWith("#") || tab <= 0) continue;
            rows.put(line.substring(0, tab), line.substring(tab + 1).trim());
        }
        return rows;
    }

    /** How many of the last {@code runs} outcomes in {@code marks} passed on a re-run. */
    static int flakes(String marks, int runs) {
        int n = 0;
        for (char c : last(marks, runs).toCharArray()) if (c == Outcome.PASSED_ON_RERUN.mark) n++;
        return n;
    }

    private static String last(String marks, int n) {
        return marks.length() <= n ? marks : marks.substring(marks.length() - n);
    }

    /**
     * The quarantined top-level classes {@code result}'s failures are all in, sorted; empty when it
     * has none, when one is outside the quarantine or in no class (a test JVM that died), or when
     * {@value TestQuarantine#FILE} cannot be read, which is said.
     */
    static List<String> quarantinedFailures(TaskContext ctx, Path root, Path moduleDir, TestSummary result) {
        List<TestFailureInfo> failures = result.failures();
        if (failures.isEmpty() || failures.size() < result.failed()) return List.of();
        TestQuarantine q;
        try {
            q = TestQuarantine.read(root);
        } catch (IOException | RuntimeException e) {
            ctx.warn(UNREADABLE, TestQuarantine.FILE + " unreadable: " + e.getMessage());
            return List.of();
        }
        if (q.entries().isEmpty()) return List.of();
        String module = TestQuarantine.moduleKey(root, moduleDir);
        Set<String> held = new TreeSet<>();
        for (TestFailureInfo f : failures) {
            String cls = topLevel(f.className());
            if (cls.isBlank() || q.find(module, cls) == null) return List.of();
            held.add(cls);
        }
        return List.copyOf(held);
    }

    /** The top-level class of a binary class name: everything before its first {@code $}. */
    private static String topLevel(String className) {
        int dollar = className.indexOf('$');
        return dollar < 0 ? className : className.substring(0, dollar);
    }

    /** The one line a run red only in quarantined {@code classes} says in place of failing. */
    static String quarantinedFailedMessage(List<String> classes) {
        return "quarantined flaky test failed, not your change: " + String.join(", ", classes);
    }

    /** Add each of {@code classes} the quarantine does not hold yet, warning once for each. */
    private static void quarantine(
            TaskContext ctx, Path root, Path moduleDir, List<String> classes, TestQuarantine.Policy policy) {
        String module = TestQuarantine.moduleKey(root, moduleDir);
        String reason = "failed, then passed on a re-run with nothing changed, in " + policy.flakes() + " of its last "
                + policy.runs() + " runs";
        List<String> added = new ArrayList<>();
        synchronized (LOCK) {
            try {
                TestQuarantine q = TestQuarantine.read(root);
                for (String cls : classes) {
                    if (q.find(module, cls) != null) continue;
                    q = q.with(new TestQuarantine.Entry(
                            cls, module, reason, LocalDate.now(ZoneOffset.UTC).toString(), null));
                    added.add(cls);
                }
                if (!added.isEmpty()) q.write(root);
            } catch (IOException | RuntimeException e) {
                ctx.warn(QUARANTINED, TestQuarantine.FILE + " not updated: " + e.getMessage());
                return;
            }
        }
        for (String cls : added) {
            ctx.warn(
                    QUARANTINED,
                    "quarantined " + cls + ": passed on a re-run " + policy.flakes() + " of its last " + policy.runs()
                            + " runs");
        }
    }
}

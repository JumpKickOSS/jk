// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestHistory;
import cc.jumpkick.config.TestQuarantine;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Each test class's recent runs, and the quarantine they feed. Every run of a suite appends each
 * class it finished to that class's history; a class that passed on a re-run with nothing changed
 * as often as the workspace's {@link TestQuarantine.Policy} says goes into {@value
 * TestQuarantine#FILE}, and the run says so.
 *
 * <p>The history is {@link TestHistory}, the last {@value #KEEP} runs of each class kept.
 */
public final class TestFlakes {

    /** The warning a class jk has just quarantined carries, once; the CLI sends a desktop notification for it. */
    static final String QUARANTINED = "test-quarantined";

    /** The warning a quarantine file that cannot be written carries; its classes are not quarantined. */
    static final String UNWRITABLE = "test-quarantine-unwritable";

    /** The warning a quarantine file that cannot be read carries; its failures fail the run. */
    static final String UNREADABLE = "test-quarantine-unreadable";

    /** Runs a class's history keeps. */
    static final int KEEP = 100;

    /** How one class ended in one run. */
    enum Outcome {
        PASSED(TestHistory.PASSED),
        PASSED_ON_RERUN(TestHistory.PASSED_ON_RERUN),
        FAILED(TestHistory.FAILED);

        final char mark;

        Outcome(char mark) {
            this.mark = mark;
        }

        /** The worse of two outcomes of one class: a nested class's failure fails its outer class. */
        static Outcome worse(Outcome a, Outcome b) {
            return a.compareTo(b) >= 0 ? a : b;
        }
    }

    /** jk has just put {@code classes} of the module at {@code module} (a quarantine module key) under {@code root} in quarantine. */
    public record Quarantined(Path root, String module, List<String> classes) {}

    private static final Consumer<Quarantined> NONE = q -> {};

    private static volatile Consumer<Quarantined> listener = NONE;

    private TestFlakes() {}

    /** Tell {@code l} of each class jk quarantines from now on, in place of the listener set before. */
    public static synchronized void listen(Consumer<Quarantined> l) {
        listener = Objects.requireNonNull(l);
    }

    /** Detach {@code l} when it is still the listener; one another engine set since stays. */
    public static synchronized void detach(Consumer<Quarantined> l) {
        if (listener == l) listener = NONE;
    }

    /**
     * One class the dashboard lists: its last {@code history} outcomes, oldest first ({@link
     * Outcome#mark}), how many of them passed on a re-run, and its quarantine entry, if any.
     */
    public record Row(
            String className, String module, String history, int flakes, TestQuarantine.@Nullable Entry held) {}

    /**
     * A workspace's flaky and quarantined classes: the quarantined first, then by re-run passes.
     * {@code error} says why {@value TestQuarantine#FILE} could not be read, which lists no entry.
     */
    public record Report(
            TestQuarantine.Policy policy,
            List<Row> rows,
            @Nullable String error) {}

    /**
     * The classes of the modules under {@code root} that passed on a re-run in the policy's window,
     * and every class {@value TestQuarantine#FILE} holds, with the histories under {@code stateDir}.
     */
    public static Report report(Path stateDir, Path root) {
        TestQuarantine.Policy policy = TestQuarantine.Policy.of(root);
        TestQuarantine q = TestQuarantine.NONE;
        String error = null;
        try {
            q = TestQuarantine.read(root);
        } catch (IOException | RuntimeException e) {
            error = TestQuarantine.FILE + " unreadable: " + e.getMessage();
        }
        Map<String, Map<String, String>> modules = histories(stateDir, root);
        List<Row> rows = new ArrayList<>();
        Set<String> listed = new TreeSet<>();
        for (TestQuarantine.Entry e : q.entries()) {
            boolean seen = false;
            for (var m : modules.entrySet()) {
                String marks = m.getValue().get(e.className());
                if (marks == null || !e.holds(m.getKey(), e.className())) continue;
                rows.add(row(e.className(), m.getKey(), marks, policy, e));
                listed.add(m.getKey() + '\t' + e.className());
                seen = true;
            }
            if (!seen) rows.add(new Row(e.className(), e.module(), "", 0, e));
        }
        modules.forEach((module, classes) -> classes.forEach((cls, marks) -> {
            if (!listed.contains(module + '\t' + cls) && TestHistory.flakes(marks, policy.runs()) > 0)
                rows.add(row(cls, module, marks, policy, null));
        }));
        rows.sort(Comparator.comparing((Row r) -> r.held() == null)
                .thenComparing(Comparator.comparingInt(Row::flakes).reversed())
                .thenComparing(Row::module)
                .thenComparing(Row::className));
        return new Report(policy, List.copyOf(rows), error);
    }

    private static Row row(
            String cls,
            String module,
            String marks,
            TestQuarantine.Policy policy,
            TestQuarantine.@Nullable Entry held) {
        return new Row(
                cls, module, TestHistory.last(marks, policy.runs()), TestHistory.flakes(marks, policy.runs()), held);
    }

    /**
     * The histories under {@code stateDir} of the modules at or under {@code root}, by quarantine
     * module key; none when they cannot be read.
     */
    static Map<String, Map<String, String>> histories(Path stateDir, Path root) {
        Path top = root.toAbsolutePath().normalize();
        Map<String, Map<String, String>> out = new TreeMap<>();
        try {
            for (TestHistory h : TestHistory.under(stateDir, top))
                out.put(TestQuarantine.moduleKey(top, h.moduleDir()), h.classes());
        } catch (IOException | RuntimeException e) {
            Log.debug("test histories not read", e);
        }
        return out;
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
            due = append(TestHistory.file(stateDir, moduleDir), moduleDir, outcomes, policy);
        } catch (IOException | RuntimeException e) {
            Log.debug("test history not recorded", e);
            return;
        }
        if (!due.isEmpty()) quarantine(ctx, root, moduleDir, due, policy);
    }

    /**
     * Append {@code outcomes} to {@code file} and answer the classes that passed on a re-run in this
     * run and have now done so {@code policy.flakes()} times in their last {@code policy.runs()}
     * runs since their last release; none when the policy is off.
     */
    static List<String> append(Path file, Path moduleDir, Map<String, Outcome> outcomes, TestQuarantine.Policy policy)
            throws IOException {
        Map<String, String> rows = TestHistory.edit(file, moduleDir, old -> {
            outcomes.forEach((cls, o) ->
                    old.merge(cls, String.valueOf(o.mark), (was, now) -> TestHistory.last(was + now, KEEP)));
            return old;
        });
        List<String> due = new ArrayList<>();
        if (policy.off()) return due;
        outcomes.forEach((cls, o) -> {
            if (o == Outcome.PASSED_ON_RERUN
                    && TestHistory.flakes(rows.getOrDefault(cls, ""), policy.runs()) >= policy.flakes()) due.add(cls);
        });
        return due;
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
        try {
            TestQuarantine.edit(root, q -> {
                added.clear();
                for (String cls : classes) {
                    if (q.find(module, cls) != null) continue;
                    q = q.with(new TestQuarantine.Entry(
                            cls, module, reason, LocalDate.now(ZoneOffset.UTC).toString(), null));
                    added.add(cls);
                }
                return q;
            });
        } catch (IOException | RuntimeException e) {
            ctx.warn(UNWRITABLE, TestQuarantine.FILE + " not updated: " + e.getMessage());
            return;
        }
        for (String cls : added) {
            ctx.warn(
                    QUARANTINED,
                    "quarantined " + cls + ": passed on a re-run " + policy.flakes() + " of its last " + policy.runs()
                            + " runs");
        }
        if (added.isEmpty()) return;
        try {
            listener.accept(new Quarantined(root.toAbsolutePath().normalize(), module, List.copyOf(added)));
        } catch (RuntimeException e) {
            Log.debug("quarantine not announced", e);
        }
    }
}

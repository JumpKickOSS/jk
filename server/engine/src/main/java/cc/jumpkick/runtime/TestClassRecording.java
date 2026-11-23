// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.runtime.base.TestEnv;
import cc.jumpkick.task.JdkToolchain;
import cc.jumpkick.test.ClassCones;
import cc.jumpkick.test.ClassReads;
import cc.jumpkick.test.RecordingReader;
import cc.jumpkick.test.SuiteReads;
import cc.jumpkick.test.TestTelemetry;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;

/**
 * After a suite ran: what each class did, read from the suite's recordings and lookups, kept as its
 * class record ({@link TestClassRecords}), with each class's key and what per-class selection would
 * have done, in shadow ({@link ClassShadow}). Nothing here decides what runs.
 *
 * <p>Bounded: one suite at a time across the engine ({@link #ONE}) holds its classes' observations,
 * at most {@link ClassReads#PEAK_BYTES}, and walks its cones over an index of its workspace classes only while
 * it holds the recording reader's permit, so the index (at most {@link #INDEX_CLASSES} classes,
 * {@link ClassCones#BYTES_PER_CLASS} each) never stands beside a recording being read. Records are
 * rewritten a row at a time.
 */
final class TestClassRecording {

    private TestClassRecording() {}

    /** One suite records its classes at a time. */
    private static final Semaphore ONE = new Semaphore(1);

    /**
     * The most workspace classes an index holds; past it no cone is trusted and every class is keyed
     * by its suite's stamp. jk-cli's classpath, the widest of jk's own, holds some tens of thousands.
     */
    static final int INDEX_CLASSES = 40_000;

    static {
        if (INDEX_CLASSES * ClassCones.BYTES_PER_CLASS > RecordingReader.PEAK_BYTES) {
            throw new IllegalStateException("the class index must fit within the recording reader's peak");
        }
    }

    /**
     * A suite's classes to record once its run has settled: everything but the run's discovered
     * inputs, which settling decides.
     */
    record Pending(
            Path moduleDir,
            BuildLayout layout,
            Collection<Path> keyed,
            TestClassLedger ledger,
            String moduleLabel,
            Path testClasses,
            Path mainClasses,
            List<Path> runtimeCp,
            @Nullable Path javaHome,
            List<String> stampExtras,
            List<Path> resourceDirs,
            @Nullable String stampKey) {

        /** The suite's discovered inputs under {@code selection}, as one digest: what a class past its bound is keyed on. */
        String suiteReads(TestSelection selection) {
            if (!TestTelemetry.shadow()) return "";
            return SuiteReads.digest(moduleDir, selection, layout.workspaceRoot());
        }

        /** Record the classes, {@code readsBefore} and {@code readsAfter} the suite's discovered inputs before and after settling. */
        void record(String readsBefore, String readsAfter) {
            if (!TestTelemetry.on() || ledger.recorded().isEmpty()) return;
            try {
                ONE.acquire();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                recordHeld(readsBefore, readsAfter);
            } finally {
                ONE.release();
            }
        }

        private void recordHeld(String readsBefore, String readsAfter) {
            long started = Clock.SYSTEM.nanos();
            try {
                Path target = layout.moduleTargetDir();
                Path workspace = layout.workspaceRoot().toAbsolutePath().normalize();
                Roots roots = Roots.of(workspace, testClasses, mainClasses, runtimeCp);
                List<Path> coneRoots = roots.cone();
                List<Path> external = roots.external();
                List<Path> scratch = List.of(target, TestHomes.root());
                List<Path> temp = new ArrayList<>();
                for (Path t : TestEnv.tempRoots(moduleDir)) {
                    if (!t.toAbsolutePath().normalize().startsWith(workspace)) temp.add(t);
                }
                ClassReads.Attributed seen = ClassReads.attribute(
                        SuiteReads.recordingDir(target),
                        moduleDir,
                        new ClassReads.Layout(
                                roots.classTrees(),
                                List.copyOf(keyed),
                                roots.jars(),
                                workspace,
                                javaHome,
                                scratch,
                                temp));
                long attributedMs = (Clock.SYSTEM.nanos() - started) / 1_000_000;
                TestClassRecords.Lookups lookups = TestClassRecords.Lookups.read(EnvReadReport.recordDir(moduleDir));
                Map<String, TestClassRecords.Outcome> ran = TestClassRecords.outcomes(ledger.recorded());
                Path file = TestClassRecords.file(moduleDir);
                String previous = TestClassRecords.header(file);
                SuiteCodeFiles.Listed code =
                        SuiteCodeFiles.listed(previous, seen.code(), workspace, firstFinished(ran));
                String header = TestClassRecords.header(
                        moduleLabel,
                        seen,
                        lookups,
                        workspace,
                        javaHome == null ? null : JdkToolchain.token(javaHome),
                        code);
                if (!TestTelemetry.shadow()) {
                    TestClassRecords.merge(
                            moduleDir,
                            header,
                            ran,
                            (o, prev) -> TestClassRecords.row(o, observation(seen, o), lookups, workspace, null));
                    done(started, attributedMs, seen, null);
                    return;
                }
                String suitePart = ClassKeys.suitePart(stampExtras, external, resourceDirs);
                ClassKeys.Observed pooledBefore = ClassShadow.pooledOf(previous);
                ClassKeys.Observed pooledNow = ClassShadow.observed(seen.pooled(), workspace);
                ClassShadow shadow;
                // The index stands only while no recording is read anywhere in the engine.
                try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
                    ClassCones.Index index = ClassCones.index(coneRoots, INDEX_CLASSES);
                    shadow = new ClassShadow(
                            index,
                            suitePart,
                            workspace,
                            pooledBefore,
                            pooledNow,
                            ClassKeys.programs(stampExtras),
                            readsBefore,
                            readsAfter,
                            SuiteCodeFiles.since(previous, workspace),
                            code.more(),
                            stampKey);
                    ClassShadow s = shadow;
                    TestClassRecords.merge(moduleDir, header, ran, (o, prev) -> {
                        ClassReads.Observation obs = observation(seen, o);
                        return TestClassRecords.row(o, obs, lookups, workspace, s.of(o, obs, prev));
                    });
                }
                if (!shadow.unsound().isEmpty()) {
                    Log.debug(ClassShadow.UNSOUND + " " + moduleDir + ": per-class selection would have replayed "
                            + String.join(", ", shadow.unsound()));
                }
                shadow.log(moduleDir, stampKey, (Clock.SYSTEM.nanos() - started) / 1_000_000);
                done(started, attributedMs, seen, shadow);
            } catch (IOException | RuntimeException e) {
                Log.debug("test class records: " + moduleDir + " not kept", e);
            }
        }

        private void done(long started, long attributedMs, ClassReads.Attributed seen, @Nullable ClassShadow shadow) {
            Log.debug("test class records: " + moduleDir + " " + seen.classes().size() + " classes, " + seen.jvms()
                    + " JVMs, " + seen.events() + " events; attributed in " + attributedMs + " ms, "
                    + (Clock.SYSTEM.nanos() - started) / 1_000_000 + " ms in all"
                    + (shadow == null ? "" : "; " + shadow.wouldRun() + " would run"));
        }
    }

    /**
     * Where a suite's classes come from: the class trees they load from by file (with the class tree
     * of each sibling whose jar is on the classpath: a loader handed that tree loads the jar's classes
     * from it, and a load there is the class's by name, never a code file every later class read),
     * the roots its cones walk (its own trees and every sibling's jar), the jars on its classpath, and
     * the classpath entries outside the workspace, which the suite part keys whole.
     */
    record Roots(List<Path> classTrees, List<Path> cone, Set<Path> jars, List<Path> external) {
        static Roots of(Path workspace, Path testClasses, Path mainClasses, List<Path> runtimeCp) {
            List<Path> classTrees = new ArrayList<>(List.of(testClasses, mainClasses));
            List<Path> cone = new ArrayList<>(classTrees);
            Set<Path> jars = new HashSet<>();
            List<Path> external = new ArrayList<>();
            for (Path entry : runtimeCp) {
                Path abs = entry.toAbsolutePath().normalize();
                if (!abs.startsWith(workspace)) {
                    jars.add(abs);
                    external.add(abs);
                    continue;
                }
                cone.add(abs);
                if (Files.isDirectory(abs)) {
                    classTrees.add(abs);
                    continue;
                }
                // A sibling's jar is its classes, which the cone holds one by one.
                jars.add(abs);
                Path target = abs.getParent();
                if (target != null
                        && target.getParent() != null
                        && BuildLayout.TARGET.equals(String.valueOf(target.getFileName()))) {
                    Path tree = BuildLayout.classesDir(target.getParent());
                    if (Files.isDirectory(tree)) classTrees.add(tree);
                }
            }
            return new Roots(classTrees, cone, jars, external);
        }
    }

    /**
     * Before a suite runs under {@code [test] cache = "cone"}: each class whose last record passed and
     * whose key, taken now over its cone and what that record says it read, is still the key the
     * record was stored under, by its launch and class as the class ledger names it, with the counts
     * it replays. Nothing that class reaches changed since it passed. A class that reached past the
     * host, or whose suite did outside every class, is never replayed, nor a class no longer in the
     * workspace. Empty when the shadow is off ({@link TestTelemetry#shadow}): its keys are not kept.
     */
    static Map<String, TestClassLedger.Entry> unmoved(
            Path moduleDir,
            BuildLayout layout,
            TestSelection selection,
            Path testClasses,
            Path mainClasses,
            List<Path> runtimeCp,
            List<String> stampExtras,
            List<Path> resourceDirs,
            @Nullable String stampKey)
            throws IOException {
        if (!TestTelemetry.shadow()) return Map.of();
        Path file = TestClassRecords.file(moduleDir);
        String header = TestClassRecords.header(file);
        if (header == null || reachedPastHost(Jsonl.nested(header, "pooled"))) return Map.of();
        Path workspace = layout.workspaceRoot().toAbsolutePath().normalize();
        Roots roots = Roots.of(workspace, testClasses, mainClasses, runtimeCp);
        String suitePart = ClassKeys.suitePart(stampExtras, roots.external(), resourceDirs);
        String reads = SuiteReads.digest(moduleDir, selection, layout.workspaceRoot());
        Map<String, TestClassLedger.Entry> out = new TreeMap<>();
        try {
            ONE.acquire();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
        // The index stands only while no recording is read anywhere in the engine.
        try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
            ClassCones.Index index = ClassCones.index(roots.cone(), INDEX_CLASSES);
            ClassShadow keys = new ClassShadow(
                    index,
                    suitePart,
                    workspace,
                    ClassShadow.pooledOf(header),
                    ClassKeys.Observed.NONE,
                    ClassKeys.programs(stampExtras),
                    reads,
                    reads,
                    SuiteCodeFiles.since(header, workspace),
                    false,
                    stampKey);
            TestClassRecords.forEachRow(file, (key, row) -> {
                String launch = Jsonl.str(row, "launch");
                String cls = TestClassRecords.className(Jsonl.strArray(row, "container"));
                if (launch == null || reachedPastHost(row) || !index.contains(cls)) return;
                if (keys.whyRun(row, launch, index.cone(cls)) != null) return;
                out.put(
                        key,
                        new TestClassLedger.Entry(
                                Jsonl.longValue(row, "tests", 0),
                                0,
                                Jsonl.longValue(row, "skipped", 0),
                                Jsonl.longValue(row, "ranAt", 0),
                                Jsonl.longValue(row, "millis", 0)));
            });
        } finally {
            ONE.release();
        }
        return out;
    }

    /** When the first of {@code ran} finished: the start of the run, as its records date it. */
    private static long firstFinished(Map<String, TestClassRecords.Outcome> ran) {
        long first = Long.MAX_VALUE;
        for (TestClassRecords.Outcome o : ran.values()) {
            if (o.ranAt() > 0) first = Math.min(first, o.ranAt());
        }
        return first == Long.MAX_VALUE ? Clock.SYSTEM.millis() : first;
    }

    /** Whether a record row, or the pool of its header, says the network was reached. */
    private static boolean reachedPastHost(@Nullable String row) {
        return row != null && !Jsonl.strArray(row, "network").isEmpty();
    }

    /** The observation of the class {@code o} names, when its recording was read whole. */
    private static ClassReads.@Nullable Observation observation(
            ClassReads.Attributed seen, TestClassRecords.Outcome o) {
        return seen.complete() ? seen.classes().get(o.testClass()) : null;
    }
}

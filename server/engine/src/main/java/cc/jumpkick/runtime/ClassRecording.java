// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
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
final class ClassRecording {

    private ClassRecording() {}

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
                List<Path> classTrees = new ArrayList<>(List.of(testClasses, mainClasses));
                List<Path> coneRoots = new ArrayList<>(classTrees);
                Set<Path> classpathJars = new HashSet<>();
                List<Path> external = new ArrayList<>();
                for (Path entry : runtimeCp) {
                    Path abs = entry.toAbsolutePath().normalize();
                    if (!abs.startsWith(workspace)) {
                        classpathJars.add(abs);
                        external.add(abs);
                        continue;
                    }
                    coneRoots.add(abs);
                    if (Files.isDirectory(abs)) classTrees.add(abs);
                    // A sibling's jar is its classes, which the cone holds one by one.
                    else classpathJars.add(abs);
                }
                List<Path> scratch = List.of(target, TestHomes.root());
                ClassReads.Attributed seen = ClassReads.attribute(
                        SuiteReads.recordingDir(target),
                        moduleDir,
                        new ClassReads.Layout(
                                classTrees, List.copyOf(keyed), classpathJars, workspace, javaHome, scratch));
                long attributedMs = (Clock.SYSTEM.nanos() - started) / 1_000_000;
                TestClassRecords.Lookups lookups = TestClassRecords.Lookups.read(EnvReadReport.recordDir(moduleDir));
                Map<String, TestClassRecords.Outcome> ran = TestClassRecords.outcomes(ledger.recorded());
                Path file = TestClassRecords.file(moduleDir);
                String header = TestClassRecords.header(
                        moduleLabel, seen, lookups, workspace, javaHome == null ? null : JdkToolchain.token(javaHome));
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
                ClassKeys.Observed pooledBefore = ClassShadow.pooledOf(TestClassRecords.header(file));
                ClassKeys.Observed pooledNow = ClassShadow.observed(seen.pooled(), workspace);
                ClassShadow shadow;
                // The index stands only while no recording is read anywhere in the engine.
                try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
                    ClassCones.Index index = ClassCones.index(coneRoots, INDEX_CLASSES);
                    shadow = new ClassShadow(
                            index, suitePart, workspace, pooledBefore, pooledNow, readsBefore, readsAfter, stampKey);
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

    /** The observation of the class {@code o} names, when its recording was read whole. */
    private static ClassReads.@Nullable Observation observation(
            ClassReads.Attributed seen, TestClassRecords.Outcome o) {
        return seen.complete() ? seen.classes().get(o.testClass()) : null;
    }
}

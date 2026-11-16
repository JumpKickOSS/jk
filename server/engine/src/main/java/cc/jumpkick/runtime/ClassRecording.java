// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.test.ClassReads;
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
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * After a suite ran: what each class did, read from the suite's recordings and lookups, kept as its
 * class record ({@link TestClassRecords}). Read before the environment report empties the lookups.
 * Nothing here decides what runs.
 */
final class ClassRecording {

    private ClassRecording() {}

    /**
     * Record the classes {@code ledger} saw finish in the run of the module at {@code moduleDir}, laid
     * out as {@code layout}, whose stamp covers {@code keyed} whole, whose test classes are {@code
     * testClasses} and main classes {@code mainClasses}, on {@code runtimeCp} under the JDK at {@code
     * javaHome}.
     */
    static void record(
            Path moduleDir,
            BuildLayout layout,
            Collection<Path> keyed,
            TestClassLedger ledger,
            String moduleLabel,
            Path testClasses,
            Path mainClasses,
            List<Path> runtimeCp,
            @Nullable Path javaHome) {
        if (!TestTelemetry.on() || ledger.recorded().isEmpty()) return;
        long started = Clock.SYSTEM.nanos();
        try {
            Path target = layout.moduleTargetDir();
            Path workspace = layout.workspaceRoot().toAbsolutePath().normalize();
            List<Path> classTrees = new ArrayList<>(List.of(testClasses, mainClasses));
            Set<Path> externalJars = new HashSet<>();
            for (Path entry : runtimeCp) {
                Path abs = entry.toAbsolutePath().normalize();
                // A jar on the classpath, a library's or a sibling's, is read as the classpath, not by path.
                if (Files.isDirectory(abs) && abs.startsWith(workspace)) classTrees.add(abs);
                else externalJars.add(abs);
            }
            List<Path> covered = new ArrayList<>(keyed);
            covered.add(target);
            covered.add(TestHomes.root());
            ClassReads.Attributed seen = ClassReads.attribute(
                    SuiteReads.recordingDir(target),
                    moduleDir,
                    new ClassReads.Layout(classTrees, covered, externalJars, workspace, javaHome));
            long attributedMs = (Clock.SYSTEM.nanos() - started) / 1_000_000;
            TestClassRecords.write(
                    moduleDir,
                    moduleLabel,
                    ledger.recorded(),
                    seen,
                    TestClassRecords.Lookups.read(EnvReadReport.recordDir(moduleDir)),
                    workspace,
                    javaHome == null ? null : "toolchain:jdk:" + ActionKey.platformJdkToken(javaHome));
            Log.debug("test class records: " + moduleDir + " " + seen.classes().size() + " classes, " + seen.jvms()
                    + " JVMs, " + seen.events() + " events; attributed in " + attributedMs + " ms, "
                    + (Clock.SYSTEM.nanos() - started) / 1_000_000 + " ms in all");
        } catch (IOException | RuntimeException e) {
            Log.debug("test class records: " + moduleDir + " not kept", e);
        }
    }
}

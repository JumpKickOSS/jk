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
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * After a suite ran: what each class did, read from the suite's recordings and lookups, kept as its
 * class record ({@link TestClassRecords}), with each class's key and what per-class selection would
 * have done, in shadow ({@link ClassShadow}). Read before the environment report empties the
 * lookups. Nothing here decides what runs.
 */
final class ClassRecording {

    private ClassRecording() {}

    /**
     * Record the classes {@code ledger} saw finish in the run of the module at {@code moduleDir}, laid
     * out as {@code layout}, whose stamp covers {@code keyed} whole, whose test classes are {@code
     * testClasses} and main classes {@code mainClasses}, on {@code runtimeCp} under the JDK at {@code
     * javaHome}; {@code stampExtras}, {@code resourceDirs} and {@code stampKey} are the suite stamp's
     * tokens, resource roots and key, which the shadow's class keys take their suite part from.
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
            @Nullable Path javaHome,
            List<String> stampExtras,
            List<Path> resourceDirs,
            @Nullable String stampKey) {
        if (!TestTelemetry.on() || ledger.recorded().isEmpty()) return;
        long started = Clock.SYSTEM.nanos();
        try {
            Path target = layout.moduleTargetDir();
            Path workspace = layout.workspaceRoot().toAbsolutePath().normalize();
            List<Path> classTrees = new ArrayList<>(List.of(testClasses, mainClasses));
            List<Path> coneRoots = new ArrayList<>(classTrees);
            Set<Path> externalJars = new HashSet<>();
            List<Path> external = new ArrayList<>();
            for (Path entry : runtimeCp) {
                Path abs = entry.toAbsolutePath().normalize();
                if (!abs.startsWith(workspace)) {
                    externalJars.add(abs);
                    external.add(abs);
                    continue;
                }
                coneRoots.add(abs);
                if (Files.isDirectory(abs)) classTrees.add(abs);
                // A sibling's jar is its classes, which the cone holds one by one.
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
            ClassShadow.Result shadow = null;
            if (TestTelemetry.shadow()) {
                String suitePart = ClassKeys.suitePart(stampExtras, external, resourceDirs);
                shadow = ClassShadow.run(moduleDir, ledger.recorded(), seen, coneRoots, suitePart, stampKey, workspace);
                if (!shadow.unsound().isEmpty()) {
                    Log.debug(ClassShadow.UNSOUND + " " + moduleDir + ": per-class selection would have replayed "
                            + String.join(", ", shadow.unsound()));
                }
            }
            TestClassRecords.write(
                    moduleDir,
                    moduleLabel,
                    ledger.recorded(),
                    seen,
                    TestClassRecords.Lookups.read(EnvReadReport.recordDir(moduleDir)),
                    workspace,
                    javaHome == null ? null : "toolchain:jdk:" + ActionKey.platformJdkToken(javaHome),
                    shadow == null ? Map.of() : shadow.keys(),
                    shadow == null ? Map.of() : shadow.cones());
            Log.debug("test class records: " + moduleDir + " " + seen.classes().size() + " classes, " + seen.jvms()
                    + " JVMs, " + seen.events() + " events; attributed in " + attributedMs + " ms, "
                    + (Clock.SYSTEM.nanos() - started) / 1_000_000 + " ms in all");
        } catch (IOException | RuntimeException e) {
            Log.debug("test class records: " + moduleDir + " not kept", e);
        }
    }
}

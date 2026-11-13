// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.MODULE;
import static cc.jumpkick.runtime.BuildPlanner.PARSED_BUILD;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.KeyOwner;
import cc.jumpkick.task.TestStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The stamps compile-test leaves in {@code target/test-classes}, one per test compiler that wrote
 * into it, each naming its action key as compile-java's stamps name the main compiles: the code
 * graph keys the test set's compiled facts by them.
 */
final class TestClassStamps {

    private TestClassStamps() {}

    /**
     * Stamp {@code testClasses} for this run's Java, Kotlin and Groovy test compiles: each one's
     * action key in {@code keys}, the sources each read and {@code readClock}, the instant before
     * the first of them read a source. One
     * compile reads every selected suite's sources, so its key covers every suite the tree holds. A
     * stamp of a compiler that wrote nothing this run is removed.
     */
    static void write(
            TaskContext ctx,
            TestStamp.CompileTestKeys keys,
            List<Path> javaSources,
            List<Path> kotlinSources,
            List<Path> groovySources,
            List<Path> classpath,
            Path testClasses,
            long readClock)
            throws IOException {
        int release = ctx.require(PARSED_BUILD).release();
        write(
                testClasses,
                BuildStamps.JAVA,
                TaskNames.COMPILE_TEST,
                keys.javac(),
                javaSources,
                classpath,
                ctx.require(MODULE).project().javac().testRelease(release),
                readClock);
        write(
                testClasses,
                BuildStamps.KOTLIN,
                TaskNames.COMPILE_TEST_KOTLIN,
                keys.kotlin(),
                kotlinSources,
                classpath,
                release,
                readClock);
        write(
                testClasses,
                BuildStamps.GROOVY,
                TaskNames.COMPILE_TEST_GROOVY,
                keys.groovy(),
                groovySources,
                classpath,
                release,
                readClock);
    }

    @KeyOwner(
            step = TaskNames.COMPILE_TEST,
            unpaired =
                    "the test compiles' stamp is written after them; the forecast prices compile-test by its compile keys",
            forecastsStep = true)
    private static void write(
            Path testClasses,
            String stamp,
            String taskId,
            @Nullable String actionKey,
            List<Path> sources,
            List<Path> classpath,
            int release,
            long readClock)
            throws IOException {
        if (actionKey == null || sources.isEmpty()) {
            Files.deleteIfExists(testClasses.resolve(stamp));
            return;
        }
        FreshnessStamp.write(testClasses, stamp, taskId, actionKey, sources, classpath, release, "", readClock);
    }
}

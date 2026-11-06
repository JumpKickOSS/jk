// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.COMPILE_TEST_ACTION_KEY;
import static cc.jumpkick.runtime.BuildPlanner.COMPILE_TEST_GROOVY_ACTION_KEY;
import static cc.jumpkick.runtime.BuildPlanner.COMPILE_TEST_KOTLIN_ACTION_KEY;
import static cc.jumpkick.runtime.BuildPlanner.PROJECT;
import static cc.jumpkick.runtime.BuildPlanner.RELEASE;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.FreshnessStamp;
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
     * Stamp {@code testClasses} for this run's Java, Kotlin and Groovy test compiles, the sources
     * each read and {@code readClock}, the instant before the first of them read a source. One
     * compile reads every selected suite's sources, so its key covers every suite the tree holds. A
     * stamp of a compiler that wrote nothing this run is removed.
     */
    static void write(
            TaskContext ctx,
            List<Path> javaSources,
            List<Path> kotlinSources,
            List<Path> groovySources,
            List<Path> classpath,
            Path testClasses,
            long readClock)
            throws IOException {
        int release = ctx.require(RELEASE);
        write(
                testClasses,
                BuildStamps.JAVA,
                TaskNames.COMPILE_TEST,
                ctx.get(COMPILE_TEST_ACTION_KEY).orElse(null),
                javaSources,
                classpath,
                ctx.require(PROJECT).javac().testRelease(release),
                readClock);
        write(
                testClasses,
                BuildStamps.KOTLIN,
                TaskNames.COMPILE_TEST_KOTLIN,
                ctx.get(COMPILE_TEST_KOTLIN_ACTION_KEY).orElse(null),
                kotlinSources,
                classpath,
                release,
                readClock);
        write(
                testClasses,
                BuildStamps.GROOVY,
                TaskNames.COMPILE_TEST_GROOVY,
                ctx.get(COMPILE_TEST_GROOVY_ACTION_KEY).orElse(null),
                groovySources,
                classpath,
                release,
                readClock);
    }

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

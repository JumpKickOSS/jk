// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The compile-test processor path, derived one way for the build and the forecast so both hash
 * the same {@code -processorpath}: the shared table's javac half, then every entry the {@code
 * [test-processor-dependencies]} closure adds.
 */
final class ProcessorPaths {

    private ProcessorPaths() {}

    /** {@code javacHalf}, then each {@code test} entry the {@code shared} path did not already carry. */
    static List<Path> forTest(List<Path> shared, List<Path> javacHalf, List<Path> test) {
        List<Path> out = new ArrayList<>(javacHalf);
        for (Path p : test) {
            if (!shared.contains(p) && !out.contains(p)) out.add(p);
        }
        return out;
    }

    /**
     * The forecast's compile-test path for a module on disk: {@code shared} stands as its own javac
     * half, and the test closure is resolved from the lock and the workspace like the build's.
     */
    static List<Path> forecastTest(
            JkBuild project, Lockfile lock, ClasspathResolver resolver, Path dir, List<Path> shared)
            throws IOException {
        List<Path> test = PlannerSupport.processorClasspath(
                project,
                lock,
                resolver,
                WorkspaceClasspath.resolve(dir, project, ClasspathResolver.PROCESSOR_PATH),
                ClasspathResolver.PROCESSOR_PATH,
                false);
        return forTest(shared, shared, test);
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The compile-test processor path, derived one way for the build and the forecast so both hash
 * the same {@code -processorpath}: by default the shared table's javac half, then every entry the
 * {@code [test-processor-dependencies]} closure adds; under that table's {@code inherit = false}
 * the test table's closure alone, the way Maven's {@code testCompile} reads only its own {@code
 * annotationProcessorPaths}. An empty one leaves the request builder discovering processors on the
 * test classpath, as a module without any processor table does.
 */
final class ProcessorPaths {

    private ProcessorPaths() {}

    /** Whether compile-test runs {@code [processor-dependencies]} beside the test table. */
    static boolean inherits(JkBuild project) {
        return project.dependencies().testProcessorsInherit();
    }

    /** The lock scopes and workspace edges compile-test's declared processor path is read from. */
    static Set<Scope> testScopes(JkBuild project) {
        return inherits(project) ? ClasspathResolver.PROCESSOR_PATH : EnumSet.of(Scope.TEST_PROCESSOR);
    }

    /**
     * {@code javacHalf}, then each {@code test} entry the {@code shared} path did not already carry;
     * {@code test} alone when the module's test table does not inherit.
     */
    static List<Path> forTest(JkBuild project, List<Path> shared, List<Path> javacHalf, List<Path> test) {
        if (!inherits(project)) return List.copyOf(test);
        List<Path> out = new ArrayList<>(javacHalf);
        for (Path p : test) {
            if (!shared.contains(p) && !out.contains(p)) out.add(p);
        }
        return out;
    }

    /** compile-test's declared processor closure for a module, from the lock and the workspace. */
    static List<Path> testClosure(
            JkBuild project, Lockfile lock, ClasspathResolver resolver, Path dir, boolean requirePresent)
            throws IOException {
        Set<Scope> scopes = testScopes(project);
        return PlannerSupport.processorClasspath(
                dir, project, lock, resolver, WorkspaceClasspath.resolve(dir, project, scopes), scopes, requirePresent);
    }

    /**
     * The forecast's compile-test path for a module on disk: {@code shared} stands as its own javac
     * half, and the test closure is resolved from the lock and the workspace like the build's.
     */
    static List<Path> forecastTest(
            JkBuild project, Lockfile lock, ClasspathResolver resolver, Path dir, List<Path> shared)
            throws IOException {
        return forTest(project, shared, shared, testClosure(project, lock, resolver, dir, false));
    }
}

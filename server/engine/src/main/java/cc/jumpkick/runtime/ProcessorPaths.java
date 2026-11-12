// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.WorkspaceMerge;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The compile-test processor path ({@link ModuleClasspaths} derives it for the build and the
 * forecast alike): by default the shared table's javac half, then every entry the
 * {@code [test-processor-dependencies]} closure adds; under that table's {@code inherit = false}
 * the test table's closure alone, the way Maven's {@code testCompile} reads only its own {@code
 * annotationProcessorPaths}. An empty one runs no processor ({@link #discovers}), as Maven's
 * testCompile does on JDK 23 and later; an inheriting compile-test that declares none discovers
 * them on the test classpath, as a module without any processor table does.
 */
final class ProcessorPaths {

    private ProcessorPaths() {}

    /** Whether compile-test runs {@code [processor-dependencies]} beside the test table. */
    static boolean inherits(JkBuild project) {
        return project.dependencies().testProcessorsInherit();
    }

    /** Whether compile-test may discover classpath processors when its path declares none. */
    static boolean discovers(JkBuild project) {
        return inherits(project);
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

    /**
     * Declared {@code [processor-dependencies]} and {@code [test-processor-dependencies]} entries
     * that resolve to nothing — not a workspace sibling and absent from the lock — each as {@code
     * <table> <module>}. Silently skipping code generation is the worst failure mode for an
     * annotation-driven project, so callers turn this into a build error.
     *
     * <p>After {@link WorkspaceMerge#resolveSiblingCoordinates}, workspace
     * processors are real {@code group:name} coords (so {@link
     * Dependency#isWorkspace()} is false) and still never appear in the lock —
     * pass the {@link WorkspaceClasspath} result so rewritten siblings stay covered.
     */
    static List<String> unresolved(JkBuild project, Lockfile lock) {
        return unresolved(project, lock, null);
    }

    static List<String> unresolved(
            JkBuild project, Lockfile lock, WorkspaceClasspath.@Nullable Result processorSiblings) {
        Set<String> locked = lock.classpathModules();
        Set<String> siblings = new HashSet<>();
        if (processorSiblings != null) {
            siblings.addAll(processorSiblings.siblingCoords());
        }
        List<String> missing = new ArrayList<>();
        for (Scope scope : ClasspathResolver.PROCESSOR_PATH) {
            for (Dependency dep : project.dependencies().of(scope)) {
                if (dep.isWorkspace()) continue; // covered by the missing-sibling guard
                if (siblings.contains(dep.module())) continue;
                if (!locked.contains(dep.module())) missing.add("[" + scope.tomlSection() + "] " + dep.module());
            }
        }
        return missing;
    }
}

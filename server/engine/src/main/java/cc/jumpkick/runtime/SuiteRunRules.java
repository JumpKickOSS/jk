// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The rules that decide a module's suite runs before any key is looked at, asked by the run and
 * its forecast ({@link TestSteps}) alike, so the two cannot disagree on them.
 */
final class SuiteRunRules {

    private SuiteRunRules() {}

    /**
     * Why {@code project}'s suite must run whatever its last green marker says, or {@code null}: a
     * rebuild bypasses the caches, a debug request needs a JVM to attach to, and a coverage run
     * needs a report a replayed marker does not have.
     */
    static @Nullable String forcedRunReason(Session session, JkBuild project) {
        if (session.config().rebuildOr(false)) return "rebuild";
        if (session.debugJvm() != null) return "debug";
        if (session.coverage() || project.build().testCoverage()) return "coverage";
        return null;
    }

    /** Whether {@code moduleDir}'s suite runs: always, unless {@code --exclusive} left it out of the selection. */
    static boolean selectedForTests(TestSelection selection, Path moduleDir) {
        if (!selection.exclusive()) return true;
        Path dir = BuildGraph.canonicalPath(moduleDir);
        return selection.onlyModules().stream()
                .map(Path::of)
                .map(BuildGraph::canonicalPath)
                .anyMatch(dir::equals);
    }
}

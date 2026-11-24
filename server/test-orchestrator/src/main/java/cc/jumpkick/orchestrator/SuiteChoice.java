// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.DeclaredSuite;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which suites a module's run runs, whether another run's green result already covers them, and
 * whether its launch is the walled default tier. The engine plans, keys and launches around these
 * answers; a change to one moves every run-tests stamp through this package's classes.
 */
public final class SuiteChoice {

    private SuiteChoice() {}

    /**
     * The suites a run of {@code moduleDir} runs under {@code selection}, resolved against the
     * module's suites, the built-in {@code integration} suite always among them ({@link
     * DeclaredSuite#withConvention}, so a caller handing the raw tables cannot lose it).
     *
     * @param declared the module's {@link cc.jumpkick.model.BuildBlock#runSuites}
     */
    public static TestSelection.Resolved resolve(
            TestSelection selection, Path moduleDir, boolean compact, Map<String, DeclaredSuite> declared) {
        return TestSuites.resolve(selection, moduleDir, compact, DeclaredSuite.withConvention(declared));
    }

    /**
     * The selection {@code jk test -g} runs in the workspace of {@code dir}, when its run covers
     * {@code selection}'s: a plain selection (no tags, classes, scripts or {@code --all}) whose suites
     * are all guard suites; null otherwise, and for the guard's own selection.
     */
    public static @Nullable TestSelection guardCovering(TestSelection selection, Path dir) {
        if (selection.guard()
                || selection.allSuites()
                || selection.scriptsOnly()
                || !selection.includeTags().isEmpty()
                || !selection.excludeTags().isEmpty()
                || !selection.classes().isEmpty()) return null;
        TestSelection guard;
        try {
            Path root = WorkspaceScan.owningRoot(dir).orElse(dir);
            guard = TestSelection.of(TestSelection.guardSuites(root), false, List.of(), List.of(), true, false, false);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (guard.identityToken().equals(selection.identityToken())) return null;
        return guard.wantedSuites().containsAll(selection.wantedSuites())
                ? guard.withOnlyModules(selection.onlyModules())
                : null;
    }

    /**
     * Whether the launch that runs {@code suites} is the default tier, which may reach no remote host
     * and start no engine: {@code project} sorts its tests into tiers, the run includes no tag, and
     * the launch runs the default suite beside no suite that carries tags.
     */
    public static boolean fastTier(Set<String> suites, TestSelection selection, JkBuild project) {
        if (!project.build().routesTiers() || !selection.includeTags().isEmpty()) return false;
        return suites.contains(TestSuites.DEFAULT)
                && suites.stream()
                        .map(project.build().runSuites()::get)
                        .allMatch(s -> s == null || s.tags().isEmpty());
    }
}

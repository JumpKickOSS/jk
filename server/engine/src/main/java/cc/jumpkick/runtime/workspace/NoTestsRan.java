// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.Session;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The verdict a workspace {@code jk test} earns when every module finished and none ran a test: the
 * sibling of {@link NothingToBuild} for the test verb. A green test run that ran nothing reads as a
 * passing suite to a person and to an agent alike; a workspace whose modules have no test sources
 * fails instead, naming them. A plain project (no {@code [workspace]} block) is never judged here,
 * and neither is a run that asked for no tests ({@code --skip-tests}, scripts only) or one whose
 * {@code --class} patterns are judged by {@link cc.jumpkick.runtime.TestClassMatch}.
 */
@NullMarked
final class NoTestsRan {

    private static final int NAMED = 5;

    private NoTestsRan() {}

    /**
     * The one-line reason, or {@code null} when the run is not a workspace test run or some module's
     * {@link BuildPlanner#TEST_RESULT} holds a test — run now, or replayed from a green stamp.
     */
    static @Nullable String verdict(
            WorkspaceRequest request,
            JkBuild entry,
            Session session,
            Collection<BuildPlan> plans,
            List<ModuleOutcome> outcomes) {
        if (!request.testOnly() || request.skipTests() || entry.workspace() == null) return null;
        if (session.testSelection().scriptsOnly()
                || !session.testSelection().classes().isEmpty()) return null;
        for (BuildPlan plan : plans) {
            TestSummary result = plan.get(BuildPlanner.TEST_RESULT).orElse(null);
            if (result != null && result.total() > 0) return null;
        }
        List<String> modules = new ArrayList<>();
        for (ModuleOutcome outcome : outcomes) modules.add(outcome.coord());
        int n = modules.size();
        String named = String.join(", ", modules.subList(0, Math.min(n, NAMED))) + (n > NAMED ? ", …" : "");
        return "no tests ran: none of the " + n + " module" + (n == 1 ? "" : "s") + " has a test suite (" + named + ")";
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildLogicScripts;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.base.BuildLogicAnchor;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Prices a project's {@code .jk/} build-logic scripts for the ETA.
 *
 * <p>Build logic is the one part of a build jk cannot reason about: it is arbitrary user code, and a
 * root script that walks the whole tree can be the largest single step of a small build. {@link
 * TaskForecaster} classifies build-logic steps as bookkeeping and never lists them, so they never
 * reach the step pricer; this class prices them from their own history instead. (The {@code
 * BUILD_LOGIC_* -> TOKEN} arm in {@link EffortWeights} is unreachable for that reason.)
 *
 * <p><strong>Three rules.</strong>
 *
 * <ol>
 *   <li><strong>Measured, never invented.</strong> A script's price is its own recorded wall, for
 *       its own task, in its own module. One warm run replaces everything below it. A script with no
 *       history is read statically by {@link BuildLogicReading}, which falls back to {@link
 *       EffortWeights#TOKEN} when the text carries no signal at all — enough to say "something runs
 *       here", not enough to claim a duration nothing has observed.
 *   <li><strong>Only what exists.</strong> Discovery goes through {@link BuildLogicScripts#discover},
 *       the engine's own, so pricing cannot drift from what runs — including suffixed stems like
 *       {@code before-compile-collections.kts}. A project with no {@code .jk/} adds nothing, and
 *       deleting a script stops the charge immediately instead of waiting out a recency-weighted
 *       mean.
 *   <li><strong>Only what will run.</strong> An {@code after-compile} script is priced only when the
 *       module actually compiles; on a cache hit the anchor is never reached, so charging for it
 *       would forecast work the build has already decided to skip.
 * </ol>
 *
 * <p><strong>Adding an anchor.</strong> {@link BuildLogicAnchor} is the extension point for a
 * before/after cut on any stage — sync, generate, test, native, image, whatever comes next — and
 * {@link #taskOf} switches over it exhaustively with no {@code default}, so a new constant is a
 * <em>compile</em> error here rather than a script silently priced at zero. {@link BuildLogicScripts}
 * already enforces the matching half (a stem with no anchor fails at class load).
 */
public final class BuildLogicEffort {

    private BuildLogicEffort() {}

    /** Anchors reached only when the module really compiles. */
    private static final Set<BuildLogicAnchor> COMPILE_ANCHORS =
            EnumSet.of(BuildLogicAnchor.BEFORE_COMPILE, BuildLogicAnchor.AFTER_COMPILE);

    private static final Set<String> COMPILE_STEPS =
            Set.of(TaskNames.COMPILE_JAVA, TaskNames.COMPILE_KOTLIN, TaskNames.COMPILE_GROOVY);

    /**
     * The task name an anchor's wall is recorded under, or empty when it has none of its own.
     *
     * <p>{@code AFTER_RESOURCES} is the empty case, on purpose: it runs <em>inside</em>
     * {@code copy-resources}, so its cost is already inside that step's recorded wall and pricing it
     * here would count it twice.
     *
     * <p>No {@code default} branch — see the class doc.
     */
    private static String taskOf(BuildLogicAnchor anchor) {
        return switch (anchor) {
            case BEFORE_COMPILE -> TaskNames.BUILD_LOGIC_BEFORE_COMPILE;
            case AFTER_COMPILE -> TaskNames.BUILD_LOGIC_AFTER_COMPILE;
            case BEFORE_PACKAGE -> TaskNames.BUILD_LOGIC_BEFORE_PACKAGE;
            case AFTER_BUILD -> TaskNames.BUILD_LOGIC_AFTER_BUILD;
            case GUARD -> TaskNames.BUILD_LOGIC_GUARD;
            case AFTER_RESOURCES -> "";
        };
    }

    /**
     * A module's own build-logic cost in ms, counting only the anchors this build will reach.
     *
     * <p>{@code 0} for the overwhelmingly common case of a module with no {@code .jk/}, which costs
     * one failed directory probe to establish.
     */
    public static long moduleMillis(Path moduleDir, TaskForecast.Module m, BuildMetrics metrics) {
        if (moduleDir == null || m == null) return 0;
        Map<BuildLogicAnchor, List<BuildLogicScripts.ScriptTask>> present = scriptsIn(moduleDir);
        if (present.isEmpty()) return 0;
        boolean compiles = runsAnyOf(m, COMPILE_STEPS);
        boolean packages = runsAnyOf(m, Set.of(TaskNames.PACKAGE_JAR));
        long total = 0;
        for (var e : present.entrySet()) {
            BuildLogicAnchor anchor = e.getKey();
            if (anchor.workspaceScoped()) continue; // priced by rootMillis, once, not per module
            boolean reached =
                    COMPILE_ANCHORS.contains(anchor) ? compiles : anchor == BuildLogicAnchor.BEFORE_PACKAGE && packages;
            if (reached) total += millisFor(moduleDir, anchor, e.getValue(), metrics);
        }
        return total;
    }

    /**
     * The invocation root's build-logic cost in ms. {@code after-build} runs whenever the build does
     * anything at all — the caller only asks when there is scheduled work — and {@code guard} runs
     * only when it was asked for.
     */
    public static long rootMillis(Path entryDir, BuildMetrics metrics, boolean guardRequested) {
        if (entryDir == null) return 0;
        long total = 0;
        for (var e : scriptsIn(entryDir).entrySet()) {
            BuildLogicAnchor anchor = e.getKey();
            if (!anchor.workspaceScoped()) continue;
            if (anchor == BuildLogicAnchor.GUARD && !guardRequested) continue;
            total += millisFor(entryDir, anchor, e.getValue(), metrics);
        }
        return total;
    }

    /**
     * Recorded wall for one anchor's scripts in {@code dir}, or the sum of their cold readings when
     * nothing has timed the anchor on this host.
     */
    private static long millisFor(
            Path dir, BuildLogicAnchor anchor, List<BuildLogicScripts.ScriptTask> scripts, BuildMetrics metrics) {
        String task = taskOf(anchor);
        if (task.isEmpty()) return 0;
        long own = metrics == null
                ? 0
                : EffortWeights.stepOkAvgMillisOwn(metrics, BuildMetrics.slashKey(dir.toString()), task);
        if (own > 0) return own;
        long cold = 0;
        for (BuildLogicScripts.ScriptTask s : scripts) cold += BuildLogicReading.millis(s.file(), s.kind());
        return cold;
    }

    /** Scripts under {@code projectDir}'s resolved logic dir, grouped by the anchor they run at. */
    private static Map<BuildLogicAnchor, List<BuildLogicScripts.ScriptTask>> scriptsIn(Path projectDir) {
        Optional<BuildLogicToml.Logic> logic;
        try {
            logic = BuildLogicToml.resolve(projectDir);
        } catch (RuntimeException e) {
            return Map.of(); // a misconfigured logic dir is the build's error to report, not the ETA's
        }
        if (logic.isEmpty()) return Map.of();
        try {
            Map<BuildLogicAnchor, List<BuildLogicScripts.ScriptTask>> out = new EnumMap<>(BuildLogicAnchor.class);
            for (BuildLogicScripts.ScriptTask s :
                    BuildLogicScripts.discover(logic.get().dir())) {
                out.computeIfAbsent(s.anchor(), a -> new ArrayList<>()).add(s);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    /** True when the forecast says one of {@code steps} will really run for this module. */
    private static boolean runsAnyOf(TaskForecast.Module m, Set<String> steps) {
        List<TaskForecast.Task> tasks = m.steps();
        if (tasks == null) return false;
        for (TaskForecast.Task s : tasks) {
            if (!s.cached() && steps.contains(s.name())) return true;
        }
        return false;
    }
}

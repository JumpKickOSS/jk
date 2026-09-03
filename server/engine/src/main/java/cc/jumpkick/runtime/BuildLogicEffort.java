// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Prices a project's {@code .jk/} build-logic scripts for the ETA.
 *
 * <p>Build logic is the one part of a build jk cannot reason about: it is arbitrary user code, and
 * on this repo it is also the <em>largest single step</em> of a small build — the root
 * {@code after-build.kts} runs 44 house-rule scans and measures around 5 s, against a 7.8 s
 * leaf-module build. It was priced at nothing, because {@link TaskForecaster} classifies build-logic
 * steps as bookkeeping and so never lists them, which means they never reach the step pricer at all.
 * (The {@code BUILD_LOGIC_* -> TOKEN} arm in {@link EffortWeights} is unreachable for that reason.)
 *
 * <p><strong>Three rules.</strong>
 *
 * <ol>
 *   <li><strong>Measured, never invented.</strong> A script's price is its own recorded wall, for
 *       its own task, in its own module. A script with no history is worth {@link
 *       EffortWeights#TOKEN} — enough to say "something runs here", not enough to claim a duration
 *       nothing has observed. A better cold reading is worth having and does not exist yet.
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
final class BuildLogicEffort {

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
            case GATE -> TaskNames.BUILD_LOGIC_GATE;
            case AFTER_RESOURCES -> "";
        };
    }

    /**
     * A module's own build-logic cost in ms, counting only the anchors this build will reach.
     *
     * <p>{@code 0} for the overwhelmingly common case of a module with no {@code .jk/}, which costs
     * one failed directory probe to establish.
     */
    static long moduleMillis(Path moduleDir, TaskForecast.Module m, BuildMetrics metrics) {
        if (moduleDir == null || m == null) return 0;
        Set<BuildLogicAnchor> present = anchorsIn(moduleDir);
        if (present.isEmpty()) return 0;
        boolean compiles = runsAnyOf(m, COMPILE_STEPS);
        boolean packages = runsAnyOf(m, Set.of(TaskNames.PACKAGE_JAR));
        long total = 0;
        for (BuildLogicAnchor anchor : present) {
            if (anchor.workspaceScoped()) continue; // priced by rootMillis, once, not per module
            boolean reached =
                    COMPILE_ANCHORS.contains(anchor) ? compiles : anchor == BuildLogicAnchor.BEFORE_PACKAGE && packages;
            if (reached) total += millisFor(moduleDir, anchor, metrics);
        }
        return total;
    }

    /**
     * The invocation root's build-logic cost in ms. {@code after-build} runs whenever the build does
     * anything at all — the caller only asks when there is scheduled work — and {@code gate} runs
     * only when it was asked for.
     */
    static long rootMillis(Path entryDir, BuildMetrics metrics, boolean gateRequested) {
        if (entryDir == null) return 0;
        long total = 0;
        for (BuildLogicAnchor anchor : anchorsIn(entryDir)) {
            if (!anchor.workspaceScoped()) continue;
            if (anchor == BuildLogicAnchor.GATE && !gateRequested) continue;
            total += millisFor(entryDir, anchor, metrics);
        }
        return total;
    }

    /** Recorded wall for one anchor's scripts in {@code dir}, or TOKEN-worth when it is cold. */
    private static long millisFor(Path dir, BuildLogicAnchor anchor, BuildMetrics metrics) {
        String task = taskOf(anchor);
        if (task.isEmpty()) return 0;
        long own = metrics == null
                ? 0
                : EffortWeights.stepOkAvgMillisOwn(metrics, BuildMetrics.slashKey(dir.toString()), task);
        // Cold: something runs here and nothing has ever timed it. TOKEN is a placeholder rather
        // than an estimate — a real cold reading wants the script's own shape.
        return own > 0 ? own : (long) EffortWeights.TOKEN * EffortWeights.MS_PER_WEIGHT;
    }

    /** Anchors with at least one script under {@code projectDir}'s resolved logic dir. */
    private static Set<BuildLogicAnchor> anchorsIn(Path projectDir) {
        Optional<BuildLogicToml.Logic> logic;
        try {
            logic = BuildLogicToml.resolve(projectDir);
        } catch (RuntimeException e) {
            return Set.of(); // a misconfigured logic dir is the build's error to report, not the ETA's
        }
        if (logic.isEmpty()) return Set.of();
        try {
            Set<BuildLogicAnchor> out = EnumSet.noneOf(BuildLogicAnchor.class);
            for (BuildLogicScripts.ScriptTask s :
                    BuildLogicScripts.discover(logic.get().dir())) {
                out.add(s.anchor());
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return Set.of();
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

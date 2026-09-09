// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.command.Invocation;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The plan-affecting options {@code jk build} and {@code jk explain} must marshal identically.
 *
 * <p><b>Why this exists.</b> The ETA <em>math</em> cannot diverge — {@code
 * BuildEta.estimateEtaMillis} is one function and {@code ExplainBuildEtaParityTest} pins it. What
 * used to diverge is what each command <em>fed</em> that function: the two derived the same eight
 * values from the same {@link Invocation} in two places, and drifted. {@code jk build} floored a
 * negative {@code --workers=-N} to {@code 0} and {@code jk explain} passed it through; {@code jk build}
 * turned {@code --scripts-only} into {@code skipTests} and {@code jk explain} did not, so {@code
 * jk explain --scripts-only} priced test suites the build would never run.
 *
 * <p>One derivation, one place. See {@code docs/contributors/progress-contract.md} and
 * {@code BuildExplainPlanOptionsParityTest}, which parses one argv against both commands' option
 * sets and asserts the two results are equal.
 */
public record PlanOptions(
        /** Within-module test JVMs; {@code 0} = auto. Never negative. */
        int workers,
        boolean skipTests,
        @Nullable String profile,
        @Nullable Path jdksDir,
        boolean parallelTests,
        /** Concurrent module budget from {@code -j} / {@code JK_JOBS} / {@code [engine] jobs}. */
        int jobs,
        boolean verbose,
        boolean rebuild) {

    /**
     * Derive the set from one parsed invocation. {@code selection} is the resolved test selection
     * ({@code TestCommand.resolveTestSelection}) — it belongs here because {@code --scripts-only}
     * means "guard scripts, no JUnit", which is {@code skipTests} to everything downstream.
     */
    public static PlanOptions from(Invocation in, GlobalOptions global, TestSelection selection) {
        return new PlanOptions(
                // A negative -w is not "auto in the other direction"; both commands floor it.
                in.value("workers").map(Integer::parseInt).map(w -> Math.max(0, w)).orElse(0),
                in.isSet("skip-tests") || selection.scriptsOnly(),
                in.value("profile").orElse(null),
                CommonOpts.jdksDirValue(in),
                // C2: cross-module tests parallel by default; --serial-tests opts out (TEST_GATE).
                ParallelTestsOpts.enabled(in),
                global.jobsEffective(),
                global.verbose,
                // Global --redo / --force: forecast full work + rebuild ETA priors.
                global.rebuild || global.force);
    }

    /** {@code -j1}: strict serial, one module at a time. */
    public boolean serial() {
        return jobs == 1;
    }
}

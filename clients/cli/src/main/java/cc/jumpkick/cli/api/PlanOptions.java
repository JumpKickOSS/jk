// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The plan-affecting options {@code jk build} and {@code jk explain} must marshal identically: one
 * option list ({@link #options()}) and one derivation ({@link #from}), so a forecast prices exactly
 * what the build would run. {@code BuildExplainPlanOptionsParityTest} parses every option here
 * against both commands and asserts the two records are equal.
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

    /** The options both commands declare; each {@code options()} adds these, then its own. */
    public static List<Opt> options() {
        List<Opt> opts = new ArrayList<>();
        opts.add(Opt.value("<name>", "Build profile (default auto)", "--profile"));
        opts.add(Opt.value("<N>", "Test JVMs per module (0=auto)", "-w", "--workers"));
        opts.add(CommonOpts.skipTests());
        // Suite/tag widening, the same vocabulary and resolver as `jk test`: the resolved selection
        // is an input to every module's run-tests key, so a forecast has to be able to state it.
        opts.add(Opt.value("<name>", "Test suite directory (repeatable)", "-s", "--suite")
                .repeat());
        opts.add(Opt.flag("Run every test suite (tags included)", "--all"));
        opts.add(CommonOpts.guard());
        opts.add(Opt.flag("Guard scripts, no JUnit", "--scripts-only"));
        opts.add(Opt.flag("Skip guard scripts", "--no-scripts"));
        opts.add(Opt.value("<tags>", "JUnit tags to include (CSV)", "--include-tags")
                .splitOn(","));
        opts.add(Opt.value("<tags>", "JUnit tags to exclude (CSV)", "--exclude-tags")
                .splitOn(","));
        opts.add(Opt.flag("Skip profile tag filters", "--no-profile"));
        // Module concurrency is the global -j/--jobs; cross-module tests default on.
        opts.addAll(ParallelTestsOpts.options());
        opts.add(CommonOpts.jdksDir());
        return opts;
    }

    /**
     * Derive the set from one parsed invocation. {@code selection} is the resolved test selection
     * ({@code TestCommand.resolveTestSelection}) — it belongs here because {@code --scripts-only}
     * means "guard scripts, no JUnit", which is {@code skipTests} to everything downstream.
     */
    public static PlanOptions from(Invocation in, GlobalOptions global, TestSelection selection) {
        return new PlanOptions(
                // A negative -w is not "auto in the other direction"; both commands floor it.
                in.value("workers")
                        .map(Integer::parseInt)
                        .map(w -> Math.max(0, w))
                        .orElse(0),
                in.isSet("skip-tests") || selection.scriptsOnly(),
                in.value("profile").orElse(null),
                CommonOpts.jdksDirValue(in),
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

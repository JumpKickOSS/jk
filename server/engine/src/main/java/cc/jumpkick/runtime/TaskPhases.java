// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.BuildStage;

/**
 * Closed stage taxonomy for ETA rollup — facade over {@link BuildStage}.
 *
 * <p>Prefer {@link BuildStage} at new call sites. Wire strings ({@link #COMPILE}, …) remain for
 * metrics and tests that compare string keys.
 *
 * <p>Stages are a calibration / UI dimension — not a lifecycle scheduler (ordering is the task
 * DAG). Orthogonal to request-level {@link cc.jumpkick.plugin.build.InvocationPhase}.
 */
public final class TaskPhases {

    public static final String RESOLVE = BuildStage.RESOLVE.wireName();
    public static final String GENERATE = BuildStage.GENERATE.wireName();
    public static final String COMPILE = BuildStage.COMPILE.wireName();
    public static final String TEST = BuildStage.TEST.wireName();
    public static final String PACKAGE = BuildStage.PACKAGE.wireName();
    public static final String NATIVE = BuildStage.NATIVE.wireName();
    public static final String IMAGE = BuildStage.IMAGE.wireName();
    public static final String OTHER = BuildStage.OTHER.wireName();

    private TaskPhases() {}

    /** Wire stage key for a task name (empty/null → {@link #OTHER}). */
    public static String of(String taskName) {
        return BuildStage.ofTaskName(taskName).wireName();
    }

    /** Typed stage for a task name. */
    public static BuildStage stageOf(String taskName) {
        return BuildStage.ofTaskName(taskName);
    }
}

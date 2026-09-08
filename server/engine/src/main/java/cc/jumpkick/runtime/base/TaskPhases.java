// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.run.BuildStage;

/**
 * Wire stage keys for the journal's old-record fallback and for tests that compare string keys.
 * {@link BuildStage} is the type; new call sites use it directly.
 *
 * <p>Stages are a calibration / UI dimension — not a lifecycle scheduler (ordering is the task
 * DAG). Orthogonal to request-level {@link cc.jumpkick.plugin.build.InvocationPhase}.
 */
public final class TaskPhases {

    public static final String RESOLVE = BuildStage.RESOLVE.wireName();
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
}

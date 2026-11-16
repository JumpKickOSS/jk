// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.run.StepCache;
import org.jspecify.annotations.Nullable;

/** Edits to a finished step's row that arrive after the row was written. */
final class TaskRows {

    private TaskRows() {}

    /** {@code row} with what the action cache did for its step. */
    static BuildRecord.Task withCache(BuildRecord.Task row, @Nullable StepCache cache) {
        return new BuildRecord.Task(
                row.name(),
                row.stage(),
                row.status(),
                row.millis(),
                row.waitMillis(),
                row.detail(),
                row.suspendedMillis(),
                cache);
    }
}

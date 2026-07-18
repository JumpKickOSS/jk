// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

/** Step lifecycle: PENDING → RUNNING → terminal (SUCCESS/FAIL/CANCELLED/SKIPPED). */
public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAIL,
    CANCELLED,
    SKIPPED;

    public boolean isTerminal() {
        return this != PENDING && this != RUNNING;
    }
}

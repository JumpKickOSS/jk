// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

/**
 * How a worker process ended: its {@code pid}, which {@link WorkerContainment#memoryKill} needs to
 * tell its kill from another worker's, and its exit {@code code}. {@code pid} is {@link #NO_PID}
 * when the process never started.
 */
public record WorkerExit(long pid, int code) {

    /** The pid of a worker that never started; no memory kill is ever attributed to it. */
    public static final long NO_PID = -1L;

    /** An exit with no worker process to attribute it to: one that never started, or a parent-side end. */
    public static WorkerExit noWorker(int code) {
        return new WorkerExit(NO_PID, code);
    }

    /** {@link WorkerContainment#failure} for this exit. */
    public String failure(String otherwise) {
        return WorkerContainment.failure(pid, code, otherwise);
    }
}

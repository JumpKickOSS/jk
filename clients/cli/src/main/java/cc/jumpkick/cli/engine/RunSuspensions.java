// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

/**
 * The times this invocation's engine jobs were not running ({@code suspended} frames): the machine
 * slept or the engine was stopped. One process is one invocation, so the totals are process-wide.
 * A duration this client measured on {@code System.nanoTime} grew by {@link #monoMs} across them,
 * and a summary names {@link #wallMs} as the time suspended.
 */
public final class RunSuspensions {

    private static long wallMs;
    private static long monoMs;

    private RunSuspensions() {}

    /** One {@code suspended} frame: {@code wallMs} away, {@code monoMs} on the monotonic clock. */
    public static synchronized void note(long wall, long mono) {
        wallMs += Math.max(0, wall);
        monoMs += Math.max(0, mono);
    }

    /** How long this invocation's jobs were suspended in all. */
    public static synchronized long wallMs() {
        return wallMs;
    }

    /** How far the monotonic clock moved while they were. */
    public static synchronized long monoMs() {
        return monoMs;
    }

    /** Test seam: forget what was noted. */
    public static synchronized void reset() {
        wallMs = 0;
        monoMs = 0;
    }
}

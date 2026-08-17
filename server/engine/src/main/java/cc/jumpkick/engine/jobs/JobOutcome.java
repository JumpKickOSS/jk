// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

/**
 * A job body's terminal verdict, stamped on the accumulator by the envelope — the one success
 * law. A body that returns {@code null} declined to rule (sync reads, cancelled unwinds); the
 * journal then derives from the accumulated facts ({@code !anyFailure} and the cancel stamps).
 */
public record JobOutcome(boolean success, int exitCode) {

    public static JobOutcome ok() {
        return new JobOutcome(true, 0);
    }

    public static JobOutcome failed(int exitCode) {
        return new JobOutcome(false, exitCode == 0 ? 1 : exitCode);
    }

    public static JobOutcome of(boolean success, int exitCode) {
        return new JobOutcome(success, exitCode);
    }
}

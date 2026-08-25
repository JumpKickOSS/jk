// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.model.command.Exit;

/**
 * A job body's terminal verdict, stamped on the accumulator by the envelope — the one success law.
 *
 * <p>Four arms, and {@link Declined} is not the absence of the other three. It says the body has no
 * verdict of its own, so the journal derives one from the facts the run accumulated. That is honest
 * for an inline read, which journals nothing at all, and for a body whose recorded module/plan rows
 * already <em>are</em> the verdict. It is a lie for a body that stopped before recording any: a run
 * with no failure rows derives green. So a journaled request that ends declined having recorded
 * nothing is written as a failure, not as a success — see {@code BuildAccumulator}.
 *
 * <p>A verdict pairs no boolean with an integer, which is what keeps "failed with exit 0" and
 * "succeeded with exit 3" out of the journal: success carries no code, failure carries a non-zero
 * one, and cancellation carries the engine's cancel code rather than the exit of work that never
 * ran. Whether the row is <em>labelled</em> cancelled is not decided here — that belongs to the
 * accumulator's cancel stamps, which know whether the user, a deadline or a socket race stopped the
 * run.
 */
public sealed interface JobOutcome {

    /** The body ran to completion and everything it drove succeeded. */
    record Succeeded() implements JobOutcome {}

    /**
     * The body ruled against the run. {@code exitCode} is what the process exits with, never
     * {@code 0}: a zero here is the incoherent pair this type exists to prevent, so it is mapped to
     * {@link Exit#FAILURE}.
     */
    record Failed(int exitCode) implements JobOutcome {
        public Failed {
            if (exitCode == 0) exitCode = Exit.FAILURE;
        }
    }

    /**
     * The body stopped because the request was cancelled. Stamps a plain failure — {@code false}
     * and {@link Exit#FAILURE} — and deliberately does not reach for the interrupt code: a body
     * knows only that it was told to stop, not whether a user, a wall deadline or a socket race
     * told it. Naming the cancel is the accumulator's cancel stamps' job, and a row they label
     * cancelled is journaled as {@link Exit#INTERRUPTED} whatever this arm stamped. The
     * {@link Exit#FAILURE} here is what survives when they do <em>not</em> — a cooperative
     * fail-fast, or the benign end-of-request EOF — which is a failed build and should read as one.
     */
    record Cancelled() implements JobOutcome {}

    /** No verdict of its own: the journal derives from the accumulated facts. */
    record Declined() implements JobOutcome {}

    JobOutcome SUCCEEDED = new Succeeded();
    JobOutcome CANCELLED = new Cancelled();
    JobOutcome DECLINED = new Declined();

    static JobOutcome ok() {
        return SUCCEEDED;
    }

    static JobOutcome failed(int exitCode) {
        return new Failed(exitCode);
    }

    static JobOutcome cancelled() {
        return CANCELLED;
    }

    static JobOutcome declined() {
        return DECLINED;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import org.jspecify.annotations.NullMarked;

/**
 * The header's remaining-work clock: the seed {@code R0} the engine reports up front, the live
 * residual it re-anchors mid-run, and the whole-second face the header actually paints.
 *
 * <p>Invariant: <b>one anchor, one face, sampled once per second</b>. Remaining is never stored as a
 * number that decays on its own — it is an {@code (amount, taken-at)} anchor that {@link #face}
 * projects onto now, so between engine samples the countdown open-loop-decays instead of freezing at
 * {@code R0}. The face is then held for a whole second at a time: residual can re-anchor many times
 * inside one second, and repainting each time makes the digits jitter. The one deliberate asymmetry
 * is that a re-anchor which <em>raises</em> the target after a zero was committed repaints at once —
 * holding the 0s would manufacture a 0s → Ns bounce.
 *
 * <p>Anchoring and sampling are one rule, owned here rather than split across {@link JkManager}
 * and {@link JkManagerView#planHeader}. Everything here is called under the live region's lock;
 * it takes none of its own.
 */
@NullMarked
final class Countdown {

    /**
     * Frozen seed remaining {@code R0} at seed time ({@code -1} = unknown / count-up only), paired
     * with {@link #r0SetAtElapsedMs}. Explain-identical; the seed path freezes once execute starts.
     */
    private long r0 = -1;

    /** Elapsed ms when the {@link #r0} seed was taken. */
    private long r0SetAtElapsedMs;

    /**
     * Live residual remaining from engine RemainingWork ({@code -1} unknown). Drives the adaptive
     * clock bar ({@code elapsed/(elapsed+residual)}) and the painted countdown.
     */
    private long residualMs = -1;

    /** Elapsed ms when {@link #residualMs} was last applied. */
    private long residualSetAtElapsedMs;

    /** Last painted remaining seconds — the held face. */
    private long faceRemainingSec;

    /** Whole-second elapsed the face was sampled for ({@code -1} = never painted). */
    private long faceElapsedSec = -1;

    /** Run-wide total for notifications: elapsed-at-seed + R0. 0 when never seeded. */
    private long etaEstimateMs;

    /**
     * True once execute has begun — the explain seed path does not replace {@code R0}. Residual
     * re-anchors for display still apply.
     */
    private boolean seedLocked;

    /** The three numbers the header paints. {@code remainingSec}/{@code overrunSec} are exclusive. */
    record Face(boolean seeded, long remainingSec, long overrunSec) {}

    /** Freeze the seed path: a provisional lock-window figure must not thrash mid-run. */
    void lockSeed() {
        if (r0 >= 0) seedLocked = true;
    }

    boolean seeded() {
        return r0 >= 0;
    }

    long r0() {
        return r0;
    }

    long r0SetAtElapsedMs() {
        return r0SetAtElapsedMs;
    }

    long residual() {
        return residualMs;
    }

    long etaEstimateMs() {
        return etaEstimateMs;
    }

    /**
     * Apply a seed remaining estimate (the {@code R0} path). A bare {@code 0} is the engine saying
     * "no estimate" and never clears a seed. Returns true when a positive seed was stored — the
     * caller then drops the pre-solve label and announces the ETA in plain mode.
     */
    boolean seed(long remainingMillis, long elapsedMillis) {
        if (seedLocked) return false;
        long rem = Math.max(0, remainingMillis);
        if (rem == 0) return false; // unknown → unknown, or seeded → do not clear R0
        r0 = rem;
        r0SetAtElapsedMs = elapsedMillis;
        etaEstimateMs = elapsedMillis + rem;
        // A pre-execute re-seed also refreshes residual so the countdown tracks the refined R0
        // until live RemainingWork updates arrive (provisional → post-forecast).
        residualMs = rem;
        residualSetAtElapsedMs = elapsedMillis;
        faceElapsedSec = -1; // a seed is intentional, not jitter: re-sample on the next paint
        return true;
    }

    /**
     * Apply live residual remaining from engine RemainingWork; {@code -1} clears it (the countdown
     * falls back to {@code R0 − elapsed}). Returns true when a positive residual was stored.
     *
     * <p>An identical re-emit carries no new information and is dropped, so the promised open-loop
     * decay between samples actually happens. Re-anchoring on an unchanged R0 every ~500 ms would
     * freeze the face at R0 for the whole prepare window and push the real finish out to
     * {@code executeStart + R0}.
     */
    boolean residual(long residualMillis, long elapsedMillis) {
        if (residualMillis < 0) {
            residualMs = -1;
            return false;
        }
        long rem = residualMillis;
        if (r0 < 0 && rem == 0) return false; // no dual clock invented from a bare "done"
        if (rem == residualMs) return false;
        residualMs = rem;
        residualSetAtElapsedMs = elapsedMillis;
        if (r0 < 0 && rem > 0) { // reconnect / residual-before-seed
            r0 = rem;
            r0SetAtElapsedMs = elapsedMillis;
        }
        if (etaEstimateMs == 0 && rem > 0) etaEstimateMs = elapsedMillis + rem;
        return rem > 0;
    }

    /**
     * Remaining ms projected onto {@code elapsedMillis} by open-loop decay from the live anchor;
     * {@code -1} when nothing has ever been seeded. This is the raw figure — plain mode prints it,
     * the header paints {@link #face} instead.
     */
    long remainingMs(long elapsedMillis) {
        if (residualMs >= 0 && (r0 >= 0 || residualMs > 0)) {
            return Math.max(0L, residualMs - Math.max(0L, elapsedMillis - residualSetAtElapsedMs));
        }
        if (r0 >= 0) {
            return Math.max(0L, r0 - Math.max(0L, elapsedMillis - r0SetAtElapsedMs));
        }
        return -1L;
    }

    /**
     * The whole-second face for this frame. Deadline is {@code anchorAt + anchor} so remaining and
     * elapsed share second boundaries; the sample is held until elapsed advances a second (see the
     * class invariant for the one exception).
     */
    Face face(long elapsedMillis) {
        long elapsedSec = Math.max(0L, elapsedMillis) / 1000L;
        long anchorRem;
        long anchorAt;
        // Dual clock once any remaining-work seed has arrived (including residual 0 = done).
        // Prefer the residual re-anchor — it eases into R(t) and ends on time; else frozen R0.
        if (residualMs >= 0 && (r0 >= 0 || residualMs > 0)) {
            anchorRem = residualMs;
            anchorAt = residualSetAtElapsedMs;
        } else if (r0 >= 0) {
            anchorRem = r0;
            anchorAt = r0SetAtElapsedMs;
        } else {
            faceElapsedSec = -1;
            return new Face(false, 0, 0);
        }
        long deadlineSec = (anchorAt + anchorRem) / 1000L;
        long targetSec = Math.max(0L, deadlineSec - elapsedSec);
        if (faceElapsedSec < 0 || elapsedSec != faceElapsedSec || targetSec == 0 || faceRemainingSec <= 0) {
            faceRemainingSec = targetSec;
            faceElapsedSec = elapsedSec;
        }
        long overrunSec = faceRemainingSec <= 0 ? Math.max(0L, elapsedSec - deadlineSec) : 0;
        return new Face(true, faceRemainingSec, overrunSec);
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.progress;

/**
 * Monotonic displayed-fraction floor shared by a clock/weighted strategy pair.
 *
 * <p>Each strategy held a private peak, so the AUTO takeover (weighted preflight climbs to ~10%,
 * R0 seed switches to clock starting near 0) visibly repainted the bar backwards on every seeded
 * build — violating the contract's "bar never goes backwards". The pair shares this floor: the
 * strategy about to paint raises it with its own fraction and never paints below it.
 */
public final class SharedPeak {

    private double fraction;

    /** Current floor (0..1). */
    public synchronized double get() {
        return fraction;
    }

    /** Raise the floor to {@code f} if higher; returns the (possibly raised) floor. */
    public synchronized double raise(double f) {
        if (f > fraction) fraction = f;
        return fraction;
    }

    public synchronized void reset() {
        fraction = 0;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

/**
 * How a metric's baselined entry is read. A cap-shaped metric (a count, a size) is worse when the
 * value rises and tightens when it falls; a floor-shaped one (coverage) is the mirror image. The
 * {@code band} is how far a unit may move either side of its entry and still hold it: within the
 * band nothing changes, beyond it the entry tightens or the unit is red.
 */
public record Tolerance(boolean floor, double band) {

    /** A cap with no band: red above the entry, tightened below it. Every count metric today. */
    public static final Tolerance CAP = new Tolerance(false, 0);

    public Tolerance {
        if (band < 0) throw new IllegalArgumentException("band must not be negative: " + band);
    }

    /** True when {@code observed} has moved past {@code entry} in the bad direction by more than the band. */
    boolean worse(double observed, double entry) {
        return floor ? observed < entry - band : observed > entry + band;
    }

    /** True when {@code observed} has moved past {@code entry} in the good direction by more than the band. */
    boolean better(double observed, double entry) {
        return floor ? observed > entry + band : observed < entry - band;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime.progress;

/** Aggregate progress fill strategy (clock vs weighted). */
public interface HeaderProgressStrategy {

    /** {@code [numerator, denominator]} for percent paint; den ≤ 0 means no bar yet. */
    long[] display(HeaderProgressState state);

    /**
     * Weight-slice update. Weighted strategy updates peak; clock may ignore weights.
     *
     * @return display pair after the update
     */
    long[] onWeightProgress(HeaderProgressState state, long numerator, long denominator);

    String id();
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

/**
 * Where a guard reports. Each site is fingerprinted for the baseline; {@code why} and {@code instead}
 * come from the {@link Guard} annotation, so a guard says only what is wrong at the site.
 */
public interface Violations {

    /** A violation at {@code site}. */
    void add(Site site, String detail);

    /** A measured value over a cap (or under a floor); the baseline ratchets it. */
    void metric(MetricSite site, String detail);

    /**
     * How much the guard examined, when it is not the size of what it queried. The engine treats a
     * run that examined far less than the baseline did as {@code scope-shrunk}, never clean.
     */
    void population(long examined);
}

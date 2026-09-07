// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import org.jspecify.annotations.Nullable;

/**
 * A measured unit: the fingerprint is the unit, and the baseline keeps the value it may not exceed.
 *
 * @param unit a file, class, method or module name
 * @param value what was measured
 * @param file the unit's file, when it has one
 */
public record MetricSite(
        String unit, double value, @Nullable String file) implements Site {

    @Override
    public String fingerprint() {
        return unit;
    }

    @Override
    public int line() {
        return 0;
    }
}

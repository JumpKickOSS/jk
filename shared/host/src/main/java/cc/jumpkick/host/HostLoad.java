// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.lang.management.ManagementFactory;
import java.time.Duration;

/**
 * A wall bound stretched by the load the host is under. A forked compiler that boots in ten seconds
 * on an idle machine takes minutes when the load average is a hundred, and a bound that guards
 * against a hang must not fail a run that is merely slow: it grows with the load per online
 * processor, and stays the bound it was on a quiet machine.
 */
public final class HostLoad {

    /** The most a bound is stretched: a machine this oversubscribed is not one to judge a hang on. */
    public static final int MAX_FACTOR = 10;

    private HostLoad() {}

    /** The host's one-minute load average, or a negative number where the platform reports none. */
    public static double loadAverage() {
        return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    }

    /** {@code base} multiplied by the load average per online processor — at least once, at most {@link #MAX_FACTOR} times. */
    public static Duration stretch(Duration base) {
        return base.multipliedBy(factor(loadAverage(), HostProcessors.count()));
    }

    /** The stretch for a load average over a processor count: load per processor, rounded up, within [1, {@link #MAX_FACTOR}]. */
    public static int factor(double loadAverage, int processors) {
        if (loadAverage <= 0 || processors <= 0) return 1;
        long perProcessor = (long) Math.ceil(loadAverage / processors);
        return (int) Math.max(1, Math.min(MAX_FACTOR, perProcessor));
    }
}

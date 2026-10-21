// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.time.Duration;

/**
 * How long a warm worker stays up after its last request: the Java compiler's lanes, the
 * build-script host and every {@link WarmWorkers served plugin}. {@link #DEFAULT}, or {@value #ENV}
 * in milliseconds, where {@code 0} keeps none up. A warm worker still exits at once when a fork is
 * waiting for its memory, whatever the linger.
 */
public final class WarmLinger {

    private WarmLinger() {}

    public static final String ENV = "JK_WORKER_LINGER_MS";

    public static final Duration DEFAULT = Duration.ofMinutes(30);

    private static volatile long forTests = -1L;

    /** The linger in milliseconds; {@code 0} means a worker exits after its request. */
    public static long millis() {
        long pinned = forTests;
        if (pinned >= 0) return pinned;
        String v = System.getenv(ENV);
        if (v != null && !v.isBlank()) {
            try {
                return Math.max(0L, Long.parseLong(v.trim()));
            } catch (NumberFormatException e) {
                // an unreadable pin is no pin
            }
        }
        return DEFAULT.toMillis();
    }

    /** Test seam: pin the linger; {@code -1} reads the environment again. */
    public static void forTests(long millis) {
        forTests = millis;
    }
}

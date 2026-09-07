// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host.time;

import java.time.Instant;

/**
 * The two clocks jk reads: epoch milliseconds for stamps, records and ages, and a monotonic
 * nanosecond ticker for durations. Production passes {@link #SYSTEM}; a test passes a fake it
 * advances by hand, so "let time pass" is a method call, not a sleep. The {@code clock-owner} guard
 * holds the JVM reads to {@code SystemClock}.
 */
public interface Clock {
    /** The JVM's clocks. */
    Clock SYSTEM = new SystemClock();

    /** Milliseconds since the epoch, as {@code System.currentTimeMillis()} counts them. */
    long millis();

    /** A monotonic nanosecond reading with no epoch; only differences mean anything. */
    long nanos();

    /** {@link #millis()} as an instant. */
    default Instant instant() {
        return Instant.ofEpochMilli(millis());
    }
}

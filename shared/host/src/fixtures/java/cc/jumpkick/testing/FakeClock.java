// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import cc.jumpkick.host.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock that moves only when the test says so. Starts at a fixed instant (2026-01-01T00:00Z) and
 * ticker zero, so a duration a test asserts is a number the test chose, on every machine.
 */
public final class FakeClock implements Clock {
    public static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final AtomicLong millis = new AtomicLong(START.toEpochMilli());
    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long millis() {
        return millis.get();
    }

    @Override
    public long nanos() {
        return nanos.get();
    }

    /** Move both clocks forward by {@code d}. */
    public FakeClock advance(Duration d) {
        millis.addAndGet(d.toMillis());
        nanos.addAndGet(d.toNanos());
        return this;
    }

    /** Set the epoch clock; the ticker keeps counting from where it was. */
    public FakeClock set(Instant at) {
        millis.set(at.toEpochMilli());
        return this;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import cc.jumpkick.host.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Whether a file was last written long enough ago for its {@link StatIdentity} to vouch for its
 * bytes. Two writes inside one timestamp tick can leave the same identity over different bytes, so
 * a memo keyed on identity re-reads a file written within {@link #WINDOW_MS} of now and records
 * nothing for it.
 */
public final class Settled {

    /** How long after its last write a file's identity is distrusted. */
    public static final long WINDOW_MS = 2_000;

    private static volatile Clock clock = Clock.SYSTEM;

    private Settled() {}

    /** Whether {@code identity}'s last write is at least {@link #WINDOW_MS} before now. */
    public static boolean is(StatIdentity identity) {
        return at(identity, clock.millis());
    }

    /** As {@link #is}, judged at {@code nowMillis}. */
    public static boolean at(StatIdentity identity, long nowMillis) {
        return nowMillis - identity.lastTouchedMillis() >= WINDOW_MS;
    }

    /** Test seam: judge as if {@code ahead} had passed since now; null restores the system clock. */
    public static void aheadForTests(@Nullable Duration ahead) {
        if (ahead == null) {
            clock = Clock.SYSTEM;
            return;
        }
        long by = ahead.toMillis();
        clock = new Clock() {
            @Override
            public long millis() {
                return Clock.SYSTEM.millis() + by;
            }

            @Override
            public long nanos() {
                return Clock.SYSTEM.nanos();
            }
        };
    }
}

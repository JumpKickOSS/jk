// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.ProcessEnv;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * How long a warm worker stays up after its last use: the Java compiler's workers, the build-script
 * host and every {@link WarmWorkers served plugin}. {@link #DEFAULT}, or {@value #ENV} in whole
 * minutes, where {@code 0} keeps none up. A warm worker still exits at once when a fork is waiting
 * for its memory, or when the host is short of memory, whatever the linger.
 *
 * <p>The window slides: for the Java compiler, any request about a project a worker compiled for —
 * a build that compiled nothing, a test run, an MCP call — and any edit to its sources moves the
 * deadline ({@link ProjectActivity}). So the window can be short: a project being worked on keeps its
 * worker, an abandoned one gives the memory back after ten minutes.
 *
 * <p>On CI ({@code CI} set to a true value) the window is {@link #CI_FACTOR} times as long: an agent
 * runs job after job, but a JDK it builds once a month should not keep its worker for the agent's
 * life. A pinned {@value #ENV} is taken as given, CI or not.
 */
public final class WarmLinger {

    private WarmLinger() {}

    public static final String ENV = "JK_WORKER_LINGER_MIN";

    public static final Duration DEFAULT = Duration.ofMinutes(10);

    /** How many default windows a warm worker lingers on CI. */
    public static final int CI_FACTOR = 3;

    private static volatile long forTests = -1L;

    private static volatile @Nullable Boolean ciForTests;

    /** The linger in milliseconds; {@code 0} means a worker exits after its request. */
    public static long millis() {
        long pinned = forTests;
        if (pinned >= 0) return pinned;
        String v = ProcessEnv.get(ENV);
        if (v != null && !v.isBlank()) {
            try {
                return Duration.ofMinutes(Math.max(0L, Long.parseLong(v.trim())))
                        .toMillis();
            } catch (NumberFormatException e) {
                // an unreadable pin is no pin
            }
        }
        return DEFAULT.toMillis() * (ci() ? CI_FACTOR : 1);
    }

    private static boolean ci() {
        Boolean pinned = ciForTests;
        return pinned != null ? pinned : EnvValues.isCi(ProcessEnv::get);
    }

    /** Test seam: pin whether this is CI; {@code null} reads the environment again. */
    public static void ciForTests(@Nullable Boolean ci) {
        ciForTests = ci;
    }

    /** Test seam: pin the linger; {@code -1} reads the environment again. */
    public static void forTests(long millis) {
        forTests = millis;
    }
}

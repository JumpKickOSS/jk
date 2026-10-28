// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.ProcessEnv;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * How long a warm worker stays up after its last request: the Java compiler's lanes, the
 * build-script host and every {@link WarmWorkers served plugin}. {@link #DEFAULT}, or {@value #ENV}
 * in whole minutes, where {@code 0} keeps none up. A warm worker still exits at once when a fork is
 * waiting for its memory, whatever the linger.
 *
 * <p>On CI ({@code CI} set to a true value) the most recently used worker of the Java compiler
 * and of kotlinc ignores the linger: one of each stays up for the life of the engine, which on CI
 * is the job or the agent. Every other warm worker keeps the linger.
 */
public final class WarmLinger {

    private WarmLinger() {}

    public static final String ENV = "JK_WORKER_LINGER_MIN";

    public static final Duration DEFAULT = Duration.ofMinutes(30);

    /** The Java compiler's warm lanes, as {@link #keepsLatest} names them. */
    public static final String JAVA_COMPILER = "java compiler";

    /** kotlinc's served workers, as {@link #keepsLatest} names them. */
    public static final String KOTLIN_COMPILER = "kotlinc";

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
        return DEFAULT.toMillis();
    }

    /** Whether the most recently used warm worker of {@code tool} ignores the linger. */
    public static boolean keepsLatest(String tool) {
        return ci() && (JAVA_COMPILER.equals(tool) || KOTLIN_COMPILER.equals(tool));
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

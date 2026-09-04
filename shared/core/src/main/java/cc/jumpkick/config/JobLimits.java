// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Wall-clock limits on one engine job: the heartbeat cadence, an optional wall deadline, the join
 * grace after a deadline cancel, and the shared grace forked workers get between SIGTERM and SIGKILL
 * on any cancel, all in milliseconds. {@code 0} disables the heartbeat or the deadline. Resolved from the environment once, at engine start, as part of
 * {@link JkEngineConfig#resolve()}; nothing in job handling reads the environment itself.
 */
public record JobLimits(long heartbeatMs, long deadlineMs, long deadlineGraceMs, long cancelGraceMs) {

    /** {@code JK_ENGINE_HEARTBEAT_MS} default: one keep-alive line every 30 s. */
    public static final long DEFAULT_HEARTBEAT_MS = 30_000L;

    /** {@code JK_ENGINE_JOB_DEADLINE_MS} default: no wall deadline. */
    public static final long DEFAULT_DEADLINE_MS = 0L;

    /** {@code JK_ENGINE_JOB_DEADLINE_GRACE_MS} default: 30 s for the runner to unwind after the kill. */
    public static final long DEFAULT_DEADLINE_GRACE_MS = 30_000L;

    /**
     * {@code JK_CANCEL_GRACE_MS} default: one shared 500 ms window for the whole worker set — not
     * per worker and not additive.
     */
    public static final long DEFAULT_CANCEL_GRACE_MS = 500L;

    /**
     * Ceiling for {@code JK_CANCEL_GRACE_MS}: a misconfiguration clamp so a typo cannot bring back
     * multi-second wedged cancels, not a statement that workers may take 5 s each.
     */
    public static final long MAX_CANCEL_GRACE_MS = 5_000L;

    public static final JobLimits DEFAULTS = new JobLimits(
            DEFAULT_HEARTBEAT_MS, DEFAULT_DEADLINE_MS, DEFAULT_DEADLINE_GRACE_MS, DEFAULT_CANCEL_GRACE_MS);

    private static final MachineConfig<Long> HEARTBEAT_MS =
            MachineConfig.of(DEFAULT_HEARTBEAT_MS, JobLimits::nonNegative);
    private static final MachineConfig<Long> DEADLINE_MS =
            MachineConfig.of(DEFAULT_DEADLINE_MS, JobLimits::nonNegative);
    private static final MachineConfig<Long> DEADLINE_GRACE_MS =
            MachineConfig.of(DEFAULT_DEADLINE_GRACE_MS, JobLimits::nonNegative);
    private static final MachineConfig<Long> CANCEL_GRACE_MS =
            MachineConfig.of(DEFAULT_CANCEL_GRACE_MS, JobLimits::nonNegative);

    public JobLimits {
        if (heartbeatMs < 0 || deadlineMs < 0 || deadlineGraceMs < 0 || cancelGraceMs < 0) {
            throw new IllegalArgumentException("job limits are durations in ms and cannot be negative");
        }
    }

    /** The four env knobs, each falling back to its default when unset, malformed or negative; the cancel grace is clamped to its ceiling. */
    public static JobLimits resolve(Function<String, @Nullable String> env) {
        return new JobLimits(
                HEARTBEAT_MS.layer(
                        EnvValues.longValue(env, "JK_ENGINE_HEARTBEAT_MS").orElse(null)),
                DEADLINE_MS.layer(
                        EnvValues.longValue(env, "JK_ENGINE_JOB_DEADLINE_MS").orElse(null)),
                DEADLINE_GRACE_MS.layer(EnvValues.longValue(env, "JK_ENGINE_JOB_DEADLINE_GRACE_MS")
                        .orElse(null)),
                Math.min(
                        MAX_CANCEL_GRACE_MS,
                        CANCEL_GRACE_MS.layer(
                                EnvValues.longValue(env, "JK_CANCEL_GRACE_MS").orElse(null))));
    }

    private static boolean nonNegative(long ms) {
        return ms >= 0;
    }
}

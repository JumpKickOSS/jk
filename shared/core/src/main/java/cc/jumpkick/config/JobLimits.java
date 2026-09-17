// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Wall-clock limits on one engine job: the heartbeat cadence, an optional wall deadline for a job a
 * client owns over its socket, the wall deadline a detached (HTTP/MCP) job runs under, the join
 * grace after a deadline cancel, the shared grace forked workers get between SIGTERM and SIGKILL
 * on any cancel, and how long a job waits for engine memory before it gives up, all in
 * milliseconds. {@code 0} disables the heartbeat, either deadline, or the queue wait's bound.
 *
 * <p>The two deadlines differ because the jobs differ. A socket job ends when its client hangs up,
 * so EOF is its deadline and the wall cap is off unless a CI sets one. A detached job has no
 * connection to end it: a body that never finishes and is never cancelled would hold its project
 * fingerprint and journal row forever, so it gets a wall cap by default, and a submission may
 * carry its own. Resolved from the environment and {@code ~/.jk/config.toml} once, at engine start,
 * as part of {@link JkEngineConfig#resolve()}; nothing in job handling reads the environment itself.
 */
public record JobLimits(
        long heartbeatMs,
        long deadlineMs,
        long detachedDeadlineMs,
        long deadlineGraceMs,
        long cancelGraceMs,
        long queueWaitMs) {

    /** {@code JK_ENGINE_HEARTBEAT_MS} default: one keep-alive line every 30 s. */
    public static final long DEFAULT_HEARTBEAT_MS = 30_000L;

    /** {@code JK_ENGINE_JOB_DEADLINE_MS} default: no wall deadline on a socket job. */
    public static final long DEFAULT_DEADLINE_MS = 0L;

    /**
     * {@code [engine] detached-deadline-ms} / {@code JK_ENGINE_DETACHED_DEADLINE_MS} default: one
     * hour. Long enough for any build or suite an agent or dashboard would start and poll, short
     * enough that a job nobody is watching cannot pin its project past a working session.
     */
    public static final long DEFAULT_DETACHED_DEADLINE_MS = 3_600_000L;

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

    /**
     * {@code [engine] queue-wait-ms} / {@code JK_ENGINE_QUEUE_WAIT_MS} default: one hour. A job
     * that has waited this long for engine memory ends with an error naming the jobs ahead of it
     * and the live job holding the heap, instead of waiting on; {@code 0} waits without bound.
     */
    public static final long DEFAULT_QUEUE_WAIT_MS = 3_600_000L;

    public static final JobLimits DEFAULTS = new JobLimits(
            DEFAULT_HEARTBEAT_MS,
            DEFAULT_DEADLINE_MS,
            DEFAULT_DETACHED_DEADLINE_MS,
            DEFAULT_DEADLINE_GRACE_MS,
            DEFAULT_CANCEL_GRACE_MS,
            DEFAULT_QUEUE_WAIT_MS);

    private static final MachineConfig<Long> HEARTBEAT_MS =
            MachineConfig.of(DEFAULT_HEARTBEAT_MS, JobLimits::nonNegative);
    private static final MachineConfig<Long> DEADLINE_MS =
            MachineConfig.of(DEFAULT_DEADLINE_MS, JobLimits::nonNegative);
    private static final MachineConfig<Long> DETACHED_DEADLINE_MS =
            MachineConfig.of(DEFAULT_DETACHED_DEADLINE_MS, JobLimits::nonNegative);
    private static final MachineConfig<Long> DEADLINE_GRACE_MS =
            MachineConfig.of(DEFAULT_DEADLINE_GRACE_MS, JobLimits::nonNegative);
    private static final MachineConfig<Long> CANCEL_GRACE_MS =
            MachineConfig.of(DEFAULT_CANCEL_GRACE_MS, JobLimits::nonNegative);
    private static final MachineConfig<Long> QUEUE_WAIT_MS =
            MachineConfig.of(DEFAULT_QUEUE_WAIT_MS, JobLimits::nonNegative);

    public JobLimits {
        if (heartbeatMs < 0
                || deadlineMs < 0
                || detachedDeadlineMs < 0
                || deadlineGraceMs < 0
                || cancelGraceMs < 0
                || queueWaitMs < 0) {
            throw new IllegalArgumentException("job limits are durations in ms and cannot be negative");
        }
    }

    /** The env knobs alone, each falling back to its default when unset, malformed or negative. */
    public static JobLimits resolve(Function<String, @Nullable String> env) {
        return resolve(env, null, null);
    }

    /**
     * The env knobs plus the two {@code [engine]} keys, {@code detached-deadline-ms} and {@code
     * queue-wait-ms}, as their file layer — env over file over default, the precedence every
     * machine setting follows. Unset, malformed or negative values fall through; the cancel grace
     * is clamped to its ceiling.
     */
    public static JobLimits resolve(
            Function<String, @Nullable String> env,
            @Nullable Long fileDetachedDeadlineMs,
            @Nullable Long fileQueueWaitMs) {
        return new JobLimits(
                HEARTBEAT_MS.layer(
                        EnvValues.longValue(env, "JK_ENGINE_HEARTBEAT_MS").orElse(null)),
                DEADLINE_MS.layer(
                        EnvValues.longValue(env, "JK_ENGINE_JOB_DEADLINE_MS").orElse(null)),
                DETACHED_DEADLINE_MS.layer(
                        EnvValues.longValue(env, "JK_ENGINE_DETACHED_DEADLINE_MS")
                                .orElse(null),
                        fileDetachedDeadlineMs),
                DEADLINE_GRACE_MS.layer(EnvValues.longValue(env, "JK_ENGINE_JOB_DEADLINE_GRACE_MS")
                        .orElse(null)),
                Math.min(
                        MAX_CANCEL_GRACE_MS,
                        CANCEL_GRACE_MS.layer(
                                EnvValues.longValue(env, "JK_CANCEL_GRACE_MS").orElse(null))),
                QUEUE_WAIT_MS.layer(
                        EnvValues.longValue(env, "JK_ENGINE_QUEUE_WAIT_MS").orElse(null), fileQueueWaitMs));
    }

    /** The file layer alone: every env-only knob at its default, the two file keys from the file. */
    public static JobLimits fromFile(@Nullable Long fileDetachedDeadlineMs, @Nullable Long fileQueueWaitMs) {
        return DEFAULTS.withDetachedDeadlineMs(DETACHED_DEADLINE_MS.layer(fileDetachedDeadlineMs))
                .withQueueWaitMs(QUEUE_WAIT_MS.layer(fileQueueWaitMs));
    }

    /** These limits with another detached deadline — how a test gives a detached job a millisecond cap. */
    public JobLimits withDetachedDeadlineMs(long ms) {
        return new JobLimits(heartbeatMs, deadlineMs, ms, deadlineGraceMs, cancelGraceMs, queueWaitMs);
    }

    /** These limits with another queue wait — how a test makes a queued job give up in milliseconds. */
    public JobLimits withQueueWaitMs(long ms) {
        return new JobLimits(heartbeatMs, deadlineMs, detachedDeadlineMs, deadlineGraceMs, cancelGraceMs, ms);
    }

    private static boolean nonNegative(long ms) {
        return ms >= 0;
    }
}

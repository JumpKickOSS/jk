// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

/**
 * The dashboard/MCP event surface an engine event sink publishes through — the part of the SSE
 * publisher the {@code listen} package needs, so it depends on this leaf and not on the publisher's
 * home in the engine root.
 */
public interface SseEvents {
    /**
     * A plan's progress — its start, a tick, a tick's re-weighting — as the dashboard's {@code
     * progress} frame: {@code record} is the event's wire record; the counts feed a single-plan job's
     * own percent.
     */
    void publishPlanProgress(long requestId, String dir, long numerator, long denominator, String record);

    /** A step starts: the journal's accumulator hears of it, and the frame is its wire {@code record}. */
    void publishStepStart(long requestId, String dir, String step, String phase, String record);

    /** Any other event SSE carries as the engine sent it: its wire {@code record}, under its own type. */
    void publishRecord(long requestId, String record);

    void publishPlan(long requestId, long totalWeight);

    /** Whether anyone listens: with no one, a sink need not encode a frame's record. */
    boolean eventsWanted();
}

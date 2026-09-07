// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import org.jspecify.annotations.Nullable;

/**
 * The dashboard/MCP event surface an engine event sink publishes through — the part of the SSE
 * publisher the {@code listen} package needs, so it depends on this leaf and not on the publisher's
 * home in the engine root.
 */
public interface SseEvents {
    void publishPlanProgress(long requestId, String dir, long numerator, long denominator);

    void publishStepStart(long requestId, String dir, String step, String phase);

    void publishStepFinish(long requestId, String dir, String step, String phase, String status, long millis);

    void publishLabel(long requestId, String dir, String step, String label);

    void publishOutput(long requestId, String dir, String step, String line);

    void publishModuleStart(long requestId, String dir, @Nullable String coord);

    void publishModuleFinish(
            long requestId, String dir, String coord, boolean success, long millis, boolean didWork, boolean cancelled);

    void publishEta(long requestId, long millis);

    void publishPlan(long requestId, long totalWeight);

    void publishBuildPlanFinish(long requestId, String dir, boolean success);
}

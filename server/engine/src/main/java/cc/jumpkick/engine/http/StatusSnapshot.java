// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

/**
 * The engine vitals {@code GET /api/status} reports — supplied per request by {@code EngineServer}
 * (the same numbers its socket {@code status-ack} carries), so the dashboard and {@code jk engine
 * status} can never drift apart. Memory fields are best-effort; {@code -1} = unobservable.
 * {@code aotTrainingPid} is the sidecar AOT trainer's pid while one runs, {@code -1} otherwise.
 * {@code cores} is the JVM's available processor count; {@code totalMemoryBytes} is the OS's total
 * physical memory ({@code -1} if the platform bean can't report it).
 */
public record StatusSnapshot(
        String version,
        long pid,
        long startedAtMillis,
        int activeRequests,
        int activePipelines,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long rssBytes,
        long aotTrainingPid,
        int cores,
        long totalMemoryBytes,
        /** High-water mark of concurrent client connections since engine start. */
        int peakActiveRequests,
        /** High-water mark of concurrent pipelines since engine start. */
        int peakActivePipelines) {

    /** Back-compat constructor without peak counters (tests). */
    public StatusSnapshot(
            String version,
            long pid,
            long startedAtMillis,
            int activeRequests,
            int activePipelines,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long rssBytes,
            long aotTrainingPid,
            int cores,
            long totalMemoryBytes) {
        this(
                version,
                pid,
                startedAtMillis,
                activeRequests,
                activePipelines,
                heapUsedBytes,
                heapCommittedBytes,
                heapMaxBytes,
                rssBytes,
                aotTrainingPid,
                cores,
                totalMemoryBytes,
                activeRequests,
                activePipelines);
    }
}

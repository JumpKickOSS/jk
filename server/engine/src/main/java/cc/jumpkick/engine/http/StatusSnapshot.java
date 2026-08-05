// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

/**
 * The engine vitals {@code GET /api/status} reports — supplied per request by {@code EngineServer}
 * (the same numbers its socket {@code status-ack} carries), so the dashboard and {@code jk engine
 * status} can never drift apart. Memory fields are best-effort; {@code -1} = unobservable.
 * {@code aotTrainingPid} is the sidecar AOT trainer's pid while one runs, {@code -1} otherwise.
 * {@code cores} is the JVM's available processor count; {@code totalMemoryBytes} /
 * {@code freeMemoryBytes} are host total RAM and <em>available</em> headroom from
 * {@link cc.jumpkick.engine.plugin.MemoryProbe} (Linux {@code MemAvailable}, macOS reclaimable
 * pages, else MXBean free — not raw idle free on Linux). Wire name stays {@code freeMemoryBytes}
 * for schema stability; UI labels it available. {@code systemCpuLoad} is recent whole-host CPU
 * utilisation in {@code [0, 1]} ({@code -1} until the first sample or when unavailable).
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
        long freeMemoryBytes,
        double systemCpuLoad,
        /** High-water mark of concurrent client connections since engine start. */
        int peakActiveRequests,
        /** High-water mark of concurrent pipelines since engine start. */
        int peakActivePipelines) {

    /** Back-compat constructor without free/load/peak counters (tests). */
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
                /* freeMemoryBytes */ -1L,
                /* systemCpuLoad */ -1d,
                activeRequests,
                activePipelines);
    }
}

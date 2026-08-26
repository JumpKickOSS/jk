// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.JsonOut;

/**
 * The engine vitals {@code GET /api/status} reports — supplied per request by {@code EngineServer}
 * (the same numbers its socket {@code status-ack} carries), so the dashboard and {@code jk engine
 * status} can never drift apart. Memory fields are best-effort; {@code -1} = unobservable.
 * {@code aotTrainingPid} is the sidecar AOT trainer's pid while one runs, {@code -1} otherwise.
 * {@code cores} is the JVM's available processor count; {@code totalMemoryBytes} /
 * {@code availableMemoryBytes} are host total RAM and available headroom from
 * {@link cc.jumpkick.engine.plugin.MemoryProbe} (Linux {@code MemAvailable}, macOS reclaimable
 * pages, else MXBean free — not raw idle free on Linux). {@code systemCpuLoad} is recent
 * whole-host CPU utilisation in {@code [0, 1]} ({@code -1} until the first sample or when
 * unavailable). {@code systemLoadAverage} is the OS 1-minute load average ({@code -1} when
 * unsupported). {@code engineEpoch} is a process-scoped generation id (version + build identity +
 * start time) so the dashboard can hard-refresh when the engine is replaced.
 */
public record StatusSnapshot(
        String version,
        long pid,
        long startedAtMillis,
        /**
         * Live client attachments right now: engine-protocol sockets (CLI over UDS/TCP) plus
         * long-lived HTTP SSE (dashboard and MCP). Wire name stays {@code activeRequests} for
         * schema freeze; the Admin tile labels this "connections".
         */
        int activeRequests,
        /**
         * Currently executing build/test/lock jobs (in-flight concurrency), not lifetime build
         * count. Zero when the engine is idle.
         */
        int activeBuildPlans,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long rssBytes,
        long aotTrainingPid,
        int cores,
        long totalMemoryBytes,
        long availableMemoryBytes,
        double systemCpuLoad,
        double systemLoadAverage,
        String engineEpoch,
        /**
         * High-water mark of {@link #activeRequests} (combined UDS + SSE surfaces) since engine
         * start — bumped at every admission point (UDS accept, SSE gate acquire) and on each
         * status snapshot, so spikes between snapshots are counted.
         */
        int peakActiveRequests,
        /** High-water mark of concurrent in-flight plans since engine start. */
        int peakActiveBuildPlans) {

    /** Compact constructor for tests that omit memory headroom / load / epoch / peaks. */
    public StatusSnapshot(
            String version,
            long pid,
            long startedAtMillis,
            int activeRequests,
            int activeBuildPlans,
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
                activeBuildPlans,
                heapUsedBytes,
                heapCommittedBytes,
                heapMaxBytes,
                rssBytes,
                aotTrainingPid,
                cores,
                totalMemoryBytes,
                /* availableMemoryBytes */ -1L,
                /* systemCpuLoad */ -1d,
                /* systemLoadAverage */ -1d,
                /* engineEpoch */ version + "@" + startedAtMillis,
                activeRequests,
                activeBuildPlans);
    }

    /**
     * These vitals as JSON — <strong>the</strong> serializer, so a field added to the record
     * cannot reach one surface and miss another. {@code GET /api/status} chains its REST-only
     * knobs (URLs, config limits, web root) onto the object this returns; the dashboard's SSE
     * {@code status} frame publishes it unchanged. Field order is part of the answer: the two
     * surfaces were byte-identical before this became one method and stay so after.
     *
     * <p>{@code uptimeSeconds} is derived rather than stored, because it is a function of the
     * snapshot's {@code startedAtMillis} and the clock at render time, and a reader that computed
     * it itself would be reading a second clock.
     */
    public JsonOut toJson() {
        return JsonOut.object()
                .put("version", version)
                .put("pid", pid)
                .put("startedAt", startedAtMillis)
                .put("uptimeSeconds", Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000))
                .put("activeRequests", activeRequests)
                .put("activeBuildPlans", activeBuildPlans)
                .put("peakActiveRequests", peakActiveRequests)
                .put("peakActiveBuildPlans", peakActiveBuildPlans)
                .put("heapUsedBytes", heapUsedBytes)
                .put("heapCommittedBytes", heapCommittedBytes)
                .put("heapMaxBytes", heapMaxBytes)
                .put("rssBytes", rssBytes)
                .put("aotTrainingPid", aotTrainingPid)
                .put("cores", cores)
                .put("totalMemoryBytes", totalMemoryBytes)
                .put("availableMemoryBytes", availableMemoryBytes)
                .put("systemCpuLoad", systemCpuLoad)
                .put("systemLoadAverage", systemLoadAverage)
                .put("engineEpoch", engineEpoch);
    }
}

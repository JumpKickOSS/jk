// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.JsonOut;
import java.util.LinkedHashMap;
import java.util.Map;

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
        int peakActiveBuildPlans,
        /**
         * Connections the engine closed because they went idle since start: a peer that never sent
         * a request inside the hello window, or one that stayed quiet past the stream-idle bound.
         */
        long idleDropped,
        /** Size of the engine log on disk in bytes; {@code -1} when unobservable. */
        long logBytes,
        /** Epoch millis of the log's last in-process roll to {@code .1}; {@code -1} when it has not rolled. */
        long logRolledAt,
        /**
         * Signals the engine process still ignores, as {@code "HUP, INT"} — an ignore inherited from
         * the spawning shell that the startup reset could not undo, which every forked JVM inherits
         * in turn. {@code ""} when none, or when the platform does not expose the mask.
         */
        String ignoredSignals,
        /** Jobs waiting for coordinator memory before they may run; not counted in {@link #activeBuildPlans}. */
        int queuedBuildPlans) {

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
                activeBuildPlans,
                /* idleDropped */ 0L,
                /* logBytes */ -1L,
                /* logRolledAt */ -1L,
                /* ignoredSignals */ "",
                /* queuedBuildPlans */ 0);
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
        return JsonOut.rawObject(vitals());
    }

    /**
     * The vitals as one ordered map — the single enumeration every surface renders from:
     * {@link #toJson} for REST and SSE, the MCP status payload, and the socket status ack. A
     * component added to the record is added here once and reaches all of them.
     */
    public Map<String, Object> vitals() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("pid", pid);
        m.put("startedAt", startedAtMillis);
        m.put("uptimeSeconds", Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000));
        m.put("activeRequests", activeRequests);
        m.put("activeBuildPlans", activeBuildPlans);
        m.put("queuedBuildPlans", queuedBuildPlans);
        m.put("peakActiveRequests", peakActiveRequests);
        m.put("peakActiveBuildPlans", peakActiveBuildPlans);
        m.put("idleDropped", idleDropped);
        m.put("heapUsedBytes", heapUsedBytes);
        m.put("heapCommittedBytes", heapCommittedBytes);
        m.put("heapMaxBytes", heapMaxBytes);
        m.put("rssBytes", rssBytes);
        m.put("aotTrainingPid", aotTrainingPid);
        m.put("cores", cores);
        m.put("totalMemoryBytes", totalMemoryBytes);
        m.put("availableMemoryBytes", availableMemoryBytes);
        m.put("systemCpuLoad", systemCpuLoad);
        m.put("systemLoadAverage", systemLoadAverage);
        m.put("engineEpoch", engineEpoch);
        m.put("logBytes", logBytes);
        m.put("logRolledAt", logRolledAt);
        m.put("ignoredSignals", ignoredSignals);
        return m;
    }
}

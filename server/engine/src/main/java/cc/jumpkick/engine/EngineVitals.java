// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.plugin.MemoryProbe;
import com.sun.management.OperatingSystemMXBean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Status snapshot for the socket {@code status-ack} and {@code /api/status}. */
public final class EngineVitals {

    private final String version;
    private final long pid;
    private final long startedAtMillis;
    private final String engineEpoch;
    private final AtomicInteger peakActiveConnections;
    private final AtomicInteger peakActiveBuildPlans;
    private final AtomicInteger activeConnections;
    private final AtomicInteger activeBuildPlans;
    private final Supplier<HttpEngineServer> httpServer;
    private final LongSupplier aotTrainingPid;

    public EngineVitals(
            String version,
            long pid,
            long startedAtMillis,
            String engineEpoch,
            AtomicInteger peakActiveConnections,
            AtomicInteger peakActiveBuildPlans,
            AtomicInteger activeConnections,
            AtomicInteger activeBuildPlans,
            Supplier<HttpEngineServer> httpServer,
            LongSupplier aotTrainingPid) {
        this.version = version;
        this.pid = pid;
        this.startedAtMillis = startedAtMillis;
        this.engineEpoch = engineEpoch;
        this.peakActiveConnections = peakActiveConnections;
        this.peakActiveBuildPlans = peakActiveBuildPlans;
        this.activeConnections = activeConnections;
        this.activeBuildPlans = activeBuildPlans;
        this.httpServer = httpServer;
        this.aotTrainingPid = aotTrainingPid;
    }

    public StatusSnapshot snapshot() {
        Runtime rt = Runtime.getRuntime();
        long heapCommitted = rt.totalMemory();
        MemoryProbe.Memory host = MemoryProbe.current();
        int connections = liveConnectionCount();
        peakActiveConnections.accumulateAndGet(connections, Math::max);
        return new StatusSnapshot(
                version,
                pid,
                startedAtMillis,
                connections,
                activeBuildPlans.get(),
                heapCommitted - rt.freeMemory(),
                heapCommitted,
                rt.maxMemory(),
                MemoryProbe.ownRssBytes(),
                aotTrainingPid.getAsLong(),
                rt.availableProcessors(),
                host.totalBytes(),
                host.availableBytes(),
                systemCpuLoad(),
                systemLoadAverage(),
                engineEpoch,
                peakActiveConnections.get(),
                peakActiveBuildPlans.get());
    }

    public int liveConnectionCount() {
        int n = activeConnections.get();
        HttpEngineServer h = httpServer.get();
        if (h != null) n += h.liveEventStreams();
        return n;
    }

    static double systemCpuLoad() {
        try {
            var os = (OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            double load = os.getCpuLoad();
            return load >= 0 && load <= 1 ? load : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static double systemLoadAverage() {
        try {
            double avg = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    .getSystemLoadAverage();
            return avg >= 0 ? avg : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}

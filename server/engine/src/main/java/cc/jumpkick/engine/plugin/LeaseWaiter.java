// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.run.TaskContext;
import java.nio.file.Path;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** One lease of a {@link WorkerLeases.Ledger}, waiting or held; guarded by that ledger's lock. */
final class LeaseWaiter {
    /** What is leased; only {@code Ledger.resize} replaces it, under the ledger's lock. */
    LeaseDemand demand;

    final @Nullable Long requestId;
    final @Nullable LeaseResident resident;
    /** The resident has been asked to exit for the head of the queue. */
    boolean yieldAsked;

    boolean cancelled;
    boolean granted;
    boolean released;
    /** Counted as a running fork of its build and step, until its grant closes. */
    boolean counted;

    /** Why its last {@code admit} refused it; null before the first refusal. */
    @Nullable
    WaitReason reason;

    /** The step that asked for it, outside a resident; null outside a step. */
    @Nullable
    TaskContext scope;

    boolean overbooked;
    /** The host sample an overbooked grant was allowed on. */
    OverbookSignals.Reading sample = OverbookSignals.Reading.closed();

    /** The forked process, once {@link WorkerLeases.Grant#watch} names it; {@code -1} before. */
    long pid = -1;

    /** When {@link #pid} started, in epoch milliseconds; {@code 0} when the OS did not say. */
    long startedAtMillis;

    String what = "worker";

    @Nullable
    TaskContext step;
    /** Last resident set read, {@code -1} before the first. */
    long rss = -1;

    long peakRss;
    /** The last reading was {@linkplain WorkerRss#farOverLease far over} the lease. */
    boolean overLease;

    /** Named in the engine log, and on its step, for going far over its lease. */
    boolean named;

    /** The startup caches its process was started with ({@link WorkerLeases.Grant#maps}). */
    volatile Set<Path> maps = Set.of();

    LeaseWaiter(LeaseDemand demand, @Nullable Long requestId, @Nullable LeaseResident resident) {
        this.demand = demand;
        this.requestId = requestId;
        this.resident = resident;
    }

    /** Resident bytes above the lease, charged on top of it. */
    long excess() {
        return Math.max(0, rss - demand.bytes());
    }

    /** {@code test JVM pid 4242 (job #7)}. */
    String label() {
        String owner = owner();
        String base = resident != null ? owner : what;
        StringBuilder out = new StringBuilder(base);
        if (pid > 0) out.append(" pid ").append(pid);
        if (resident == null && requestId != null)
            out.append(" (").append(owner).append(')');
        return out.toString();
    }

    /** {@code job #7}, the resident's name, or {@code the engine} for a fork outside any job. */
    String owner() {
        if (resident != null) return resident.name();
        if (requestId != null) return "job #" + requestId;
        return "the engine";
    }
}

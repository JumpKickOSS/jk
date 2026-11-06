// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.AvailableCpus;
import cc.jumpkick.host.HostProcessors;
import cc.jumpkick.host.time.Clock;
import java.util.function.LongSupplier;

/** This engine process's own worker budget and JVM cap, before the other engines of the host are counted. */
final class EngineLimits {

    private EngineLimits() {}

    /**
     * How many forked JVMs may run at once: the host's processors, narrowed to a finite cgroup CPU
     * quota. {@link Runtime#availableProcessors()} is the wrong reading. An engine in a JVM started
     * with {@code -XX:ActiveProcessorCount} (a batch worker's share, or a user's {@code jvm-args})
     * would read that pin, and with no quota {@link AvailableCpus#count()} reports it. A cap of one lets a resident script host or
     * compiler hold the only slot until its idle timeout, and the next fork waits out that timeout.
     */
    static int forkCpuCap() {
        return forkCpuCap(HostProcessors.count(), AvailableCpus.quota());
    }

    /** {@link #forkCpuCap()} for a known host size and quota. {@code quota <= 0} means unlimited. */
    static int forkCpuCap(int hostProcessors, int cgroupQuota) {
        int host = Math.max(1, hostProcessors);
        if (cgroupQuota > 0) return Math.min(host, cgroupQuota);
        return host;
    }

    /** The engine ledger's capacity: {@link WorkerContainment#budgetBytes()}, cached for two seconds. */
    static final class Budget implements LongSupplier {
        private volatile long bytes;
        private volatile long atNanos;

        @Override
        public long getAsLong() {
            long now = Clock.SYSTEM.nanos();
            long cached = bytes;
            if (cached > 0 && now - atNanos < 2_000_000_000L) return cached;
            long measured = WorkerContainment.budgetBytes();
            bytes = measured;
            atNanos = now;
            return measured;
        }
    }
}

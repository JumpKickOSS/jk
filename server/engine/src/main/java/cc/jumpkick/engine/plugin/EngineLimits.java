// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.AvailableCpus;
import cc.jumpkick.host.HostProcessors;
import cc.jumpkick.host.time.Clock;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

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

    /**
     * The engine ledger's capacity: {@link WorkerContainment#budgetBytes()}, cached for two seconds
     * and read again at once when the containment {@link WorkerContainment#install} or a test armed
     * is no longer the one the cached number came from.
     */
    static final class Budget implements LongSupplier {
        private volatile @Nullable Sample last;

        @Override
        public long getAsLong() {
            long now = Clock.SYSTEM.nanos();
            Object containment = WorkerContainment.stateIdentity();
            Sample cached = last;
            if (cached != null
                    && cached.containment == containment
                    && cached.bytes > 0
                    && now - cached.atNanos < 2_000_000_000L) return cached.bytes;
            long measured = WorkerContainment.budgetBytes();
            last = new Sample(measured, now, containment);
            return measured;
        }

        private record Sample(
                long bytes, long atNanos, @Nullable Object containment) {}
    }
}

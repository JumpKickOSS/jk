// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.nio.file.Path;
import java.time.Duration;

/** Ledgers of made-up engines that share one state directory's {@link HostLeases} file, each counted alive. */
public final class HostEngines {

    private HostEngines() {}

    /** Engine {@code pid} of {@code engineDir} with its own budget and JVM cap, overbooking off. */
    public static WorkerLeases.Ledger engine(Path engineDir, long pid, long budgetBytes, int cpuCap) {
        return new WorkerLeases.Ledger(() -> budgetBytes, () -> cpuCap, id -> false, OverbookSignals.off())
                .waitAtMost(Duration.ofSeconds(10))
                .shareHost(new HostLeases(engineDir, new HostLeases.Owner(pid, 0), owner -> true, workers -> 0));
    }
}

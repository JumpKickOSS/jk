// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.JkThreads;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * A dirty prepare's over-reserve flag reaches every pool and every thread its weight estimate
 * starts, and nothing outside it.
 */
class OverReserveTailsTest {

    @Test
    void the_flag_rides_every_pool_and_started_thread() {
        List<Boolean> seen = EffortWeights.withOverReserveTails(OverReserveTailsTest::sampleEverywhere);

        assertThat(seen).hasSize(5).containsOnly(true);
    }

    @Test
    void work_started_outside_a_dirty_prepare_does_not_over_reserve() throws Exception {
        assertThat(sampleEverywhere()).hasSize(5).containsOnly(false);
    }

    private static List<Boolean> sampleEverywhere() throws Exception {
        List<Boolean> seen = new ArrayList<>();
        for (ExecutorService pool : List.of(JkThreads.cpu(), JkThreads.io(), JkThreads.platform())) {
            seen.add(pool.submit(EffortWeights::overReserveTails).get());
        }
        AtomicBoolean onVirtual = new AtomicBoolean();
        SessionContext.startVirtual("over-reserve-probe", () -> onVirtual.set(EffortWeights.overReserveTails()))
                .join();
        seen.add(onVirtual.get());
        AtomicBoolean onPlatform = new AtomicBoolean();
        SessionContext.startPlatform("over-reserve-probe", () -> onPlatform.set(EffortWeights.overReserveTails()))
                .join();
        seen.add(onPlatform.get());
        return seen;
    }
}

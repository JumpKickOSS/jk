// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guard lanes share one byte budget for the facts indexes they decode. */
class GuardFactsBudgetTest {

    private static final long MIB = 1L << 20;

    private static Path index(Path dir, String name, long bytes) throws Exception {
        Path f = dir.resolve(name);
        Files.write(f, new byte[(int) bytes]);
        return f;
    }

    @Test
    void a_share_is_the_decoded_size_of_its_indexes_clamped_to_the_whole_budget(@TempDir Path dir) throws Exception {
        GuardFactsBudget budget = new GuardFactsBudget(10 * MIB);
        Path small = index(dir, "small.idx", MIB / 2);
        Path huge = index(dir, "huge.idx", 4 * MIB);

        assertThat(budget.shareKib(Files.size(small)))
                .isEqualTo((int) (MIB / 2 * GuardFactsBudget.DECODED_BYTES_PER_FILE_BYTE / 1024));
        assertThat(budget.shareKib(Files.size(huge))).as("runs alone").isEqualTo(10 * 1024);
        try (GuardFactsBudget.Held none = budget.hold(List.of(dir.resolve("absent.idx")))) {
            assertThat(budget.availableKib()).as("no index, no share").isEqualTo(10 * 1024);
        }
    }

    @Test
    void a_lane_past_the_budget_waits_until_the_lane_ahead_gives_its_share_back(@TempDir Path dir) throws Exception {
        GuardFactsBudget budget = new GuardFactsBudget(10 * MIB);
        Path idx = index(dir, "main-guard.idx", 3 * MIB / 2);
        CountDownLatch secondHeld = new CountDownLatch(1);
        Thread second;

        try (GuardFactsBudget.Held first = budget.hold(List.of(idx))) {
            assertThat(budget.availableKib()).isEqualTo(10 * 1024 - 7680);
            second = Thread.ofPlatform().start(() -> {
                try (GuardFactsBudget.Held held = budget.hold(List.of(idx))) {
                    secondHeld.countDown();
                }
            });
            assertThat(secondHeld.await(300, TimeUnit.MILLISECONDS))
                    .as("the second lane does not fit beside the first")
                    .isFalse();
        }

        assertThat(secondHeld.await(10, TimeUnit.SECONDS)).isTrue();
        second.join(10_000);
        assertThat(budget.availableKib()).isEqualTo(10 * 1024);
    }
}

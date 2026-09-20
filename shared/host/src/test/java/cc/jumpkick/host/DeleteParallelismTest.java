// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DeleteParallelismTest {

    private static Function<String, @Nullable String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    void windows_takes_its_small_fixed_width_whatever_the_cpu_count() {
        assertThat(DeleteParallelism.width(env(Map.of()), true, 2)).isEqualTo(DeleteParallelism.WINDOWS);
        assertThat(DeleteParallelism.width(env(Map.of()), true, 64)).isEqualTo(DeleteParallelism.WINDOWS);
    }

    @Test
    void posix_scales_with_the_processors_up_to_the_cap() {
        assertThat(DeleteParallelism.width(env(Map.of()), false, 4)).isEqualTo(4 * DeleteParallelism.POSIX_PER_CPU);
        assertThat(DeleteParallelism.width(env(Map.of()), false, 1000)).isEqualTo(DeleteParallelism.POSIX_CAP);
        assertThat(DeleteParallelism.width(env(Map.of()), false, 0)).isEqualTo(1);
    }

    @Test
    void the_override_wins_on_every_os_and_is_capped() {
        var one = env(Map.of(DeleteParallelism.ENV, "1"));
        assertThat(DeleteParallelism.width(one, true, 8)).isEqualTo(1);
        assertThat(DeleteParallelism.width(one, false, 8)).isEqualTo(1);
        assertThat(DeleteParallelism.width(env(Map.of(DeleteParallelism.ENV, "99999")), false, 8))
                .isEqualTo(DeleteParallelism.MAX);
    }

    @Test
    void a_measurement_pool_has_exactly_the_width_asked_for_and_daemon_workers() throws Exception {
        var pool = DeleteParallelism.pool(1);
        try {
            assertThat(pool.getParallelism()).isEqualTo(1);
            boolean[] daemon = {false};
            pool.submit(() -> daemon[0] = Thread.currentThread().isDaemon()).get();
            assertThat(daemon[0])
                    .as("an idle pool must never hold the process open")
                    .isTrue();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void a_blank_zero_or_unparsable_override_falls_back_to_the_default() {
        assertThat(DeleteParallelism.width(env(Map.of(DeleteParallelism.ENV, "")), true, 8))
                .isEqualTo(DeleteParallelism.WINDOWS);
        assertThat(DeleteParallelism.width(env(Map.of(DeleteParallelism.ENV, "0")), true, 8))
                .isEqualTo(DeleteParallelism.WINDOWS);
        assertThat(DeleteParallelism.width(env(Map.of(DeleteParallelism.ENV, "many")), true, 8))
                .isEqualTo(DeleteParallelism.WINDOWS);
    }
}

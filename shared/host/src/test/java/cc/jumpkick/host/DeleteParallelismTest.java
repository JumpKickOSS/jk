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
    void each_os_takes_its_measured_width() {
        assertThat(DeleteParallelism.width(env(Map.of()), true)).isEqualTo(DeleteParallelism.WINDOWS);
        assertThat(DeleteParallelism.width(env(Map.of()), false)).isEqualTo(DeleteParallelism.POSIX);
        assertThat(DeleteParallelism.WINDOWS).isLessThan(DeleteParallelism.POSIX);
    }

    @Test
    void the_override_wins_on_every_os_and_is_capped() {
        var one = env(Map.of(DeleteParallelism.ENV, "1"));
        assertThat(DeleteParallelism.width(one, true)).isEqualTo(1);
        assertThat(DeleteParallelism.width(one, false)).isEqualTo(1);
        assertThat(DeleteParallelism.width(env(Map.of(DeleteParallelism.ENV, "99999")), false))
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
        assertThat(DeleteParallelism.width(env(Map.of(DeleteParallelism.ENV, "")), true))
                .isEqualTo(DeleteParallelism.WINDOWS);
        assertThat(DeleteParallelism.width(env(Map.of(DeleteParallelism.ENV, "0")), true))
                .isEqualTo(DeleteParallelism.WINDOWS);
        assertThat(DeleteParallelism.width(env(Map.of(DeleteParallelism.ENV, "many")), true))
                .isEqualTo(DeleteParallelism.WINDOWS);
    }
}

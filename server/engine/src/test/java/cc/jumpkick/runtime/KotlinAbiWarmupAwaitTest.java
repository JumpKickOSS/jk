// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** A producer whose classes never finished started no warm-up, so its module completes at once. */
class KotlinAbiWarmupAwaitTest {

    @Test
    void a_warmup_whose_signal_never_fired_does_not_hold_the_module() {
        KotlinAbiWarmup.Warmup warmup = new KotlinAbiWarmup.Warmup(() -> {});
        assertThat(CompletableFuture.runAsync(warmup::await))
                .as("a failed producer compile must not delay the verdict by the warm-up's timeout")
                .succeedsWithin(Duration.ofSeconds(5));
    }
}

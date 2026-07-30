// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildNumberAllocatorTest {

    @Test
    void allocates_monotonic_numbers(@TempDir Path dir) {
        Path counters = dir.resolve("run-numbers.json");
        Path metrics = dir.resolve("metrics.json");
        long a = BuildNumberAllocator.allocate(counters, metrics, "/proj");
        long b = BuildNumberAllocator.allocate(counters, metrics, "/proj");
        long c = BuildNumberAllocator.allocate(counters, metrics, "/other");
        assertThat(a).isEqualTo(1);
        assertThat(b).isEqualTo(2);
        assertThat(c).isEqualTo(1);
    }

    @Test
    void continues_past_metrics_history(@TempDir Path dir) {
        Path counters = dir.resolve("run-numbers.json");
        Path metrics = dir.resolve("metrics.json");
        BuildMetrics.clearMemo();
        BuildMetrics.record(
                metrics,
                new BuildMetrics.Outcome("build", "/proj", "g:n", true, false, 100, List.of()),
                1_000L);
        BuildMetrics.clearMemo();
        long n = BuildNumberAllocator.allocate(counters, metrics, "/proj");
        assertThat(n).isEqualTo(2);
    }
}

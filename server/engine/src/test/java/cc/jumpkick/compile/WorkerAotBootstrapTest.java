// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerAotBootstrapTest {

    @Test
    void trainCommonWorkers_never_throws_on_missing_workers(@TempDir Path dir) {
        // Best-effort path: may train or skip depending on host HotSpot + jars; must not throw.
        var result = WorkerAotBootstrap.trainCommonWorkers(1_000L, false);
        assertThat(result).isNotNull();
        assertThat(result.trained()).isNotNull();
        assertThat(result.skipped()).isNotNull();
        // Only java-compiler is pre-trained; language workers stay on-demand.
        assertThat(result.skipped().stream().anyMatch(s -> s.startsWith("kotlinc")))
                .isTrue();
        assertThat(result.skipped().stream().anyMatch(s -> s.startsWith("groovy")))
                .isTrue();
        assertThat(result.skipped().stream().anyMatch(s -> s.contains("test-runner")))
                .isTrue();
        assertThat(result.trained().stream().noneMatch(s -> s.startsWith("kotlinc")))
                .isTrue();
    }
}

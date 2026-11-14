// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PendingRunsTest {

    @Test
    void a_full_unwritten_set_drops_only_its_oldest_run() {
        PendingRuns runs = new PendingRuns((record, header) -> false);
        int total = PendingRuns.MAX_UNWRITTEN + 1;
        for (long id = 1; id <= total; id++) {
            BuildRecord running =
                    BuildRecord.running(id, "build", "/p", "g:a", null, 1_700_000_000_000L, "9.9", "cli", null, id);
            runs.defer(id, running, Path.of("/builds/runs/" + id), 3_600_000L);
            assertThat(runs.settle(id)).isNotNull();
        }

        assertThat(runs.settle(1)).as("the oldest goes first").isNull();
        assertThat(runs.settle(2))
                .as("every other run still knows it has no directory")
                .isNotNull();
        assertThat(runs.settle(total)).isNotNull();
    }
}

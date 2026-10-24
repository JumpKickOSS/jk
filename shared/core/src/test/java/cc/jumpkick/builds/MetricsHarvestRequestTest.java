// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A requested harvest folds the builds root it was asked for, whatever the state dir is when it runs. */
class MetricsHarvestRequestTest {

    private static final String STATE_DIR = "jk.env.JK_STATE_DIR";

    @AfterEach
    void restore() {
        System.clearProperty(STATE_DIR);
    }

    @Test
    void each_requested_root_is_harvested_and_the_current_state_dir_is_not(@TempDir Path tmp) throws Exception {
        Path first = run(tmp.resolve("first"));
        Path second = run(tmp.resolve("second"));
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        System.setProperty(STATE_DIR, elsewhere.toString());

        MetricsHarvest.get().request(first);
        MetricsHarvest.get().request(second);
        MetricsHarvest.get().request(first);
        MetricsHarvest.get().awaitIdle(30_000L);

        assertThat(MetricsHarvest.get().busy()).isFalse();
        assertThat(ledger(first)).content().contains("module.server/io.task.compile-java.wall-ms = 800");
        assertThat(ledger(second)).content().contains("module.server/io.task.compile-java.wall-ms = 800");
        try (var listed = Files.list(elsewhere)) {
            assertThat(listed)
                    .as("nothing written under the state dir set after the request")
                    .isEmpty();
        }
    }

    /** A builds root holding one finished run of project {@code demo}. */
    private static Path run(Path builds) throws Exception {
        Path dir = Files.createDirectories(
                ProjectHome.of(builds).resolve(ProjectBuilds.RUNS).resolve("1"));
        Files.writeString(dir.resolve(ProjectBuilds.METRICS), """
                [mean]
                module.server/io.task.compile-java.wall-ms = 800
                """, StandardCharsets.UTF_8);
        return builds;
    }

    private static Path ledger(Path builds) {
        return ProjectHome.of(builds).resolve(ProjectBuilds.PROJECT_METRICS);
    }

    private static final class ProjectHome {
        static Path of(Path builds) {
            return builds.resolve("projects").resolve("demo");
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each project keeps its newest runs, for every builds root asked for. */
class RunReaperTest {

    @AfterEach
    void defaults() {
        RunReaper.get().configure(RunReaper.DEFAULT_MAX_RUNS, RunReaper.DEFAULT_MAX_AGE_DAYS);
    }

    @Test
    void retention_keeps_the_newest_runs(@TempDir Path root) throws Exception {
        Path home = runs(root, 5);
        RunReaper.get().configure(2, 90);
        RunReaper.get().runOnce(root);
        assertThat(ProjectBuilds.listRuns(home)).hasSize(2);
        assertThat(ProjectBuilds.listRuns(home).getFirst().getFileName().toString())
                .isEqualTo("5");
    }

    @Test
    void each_requested_root_is_reaped(@TempDir Path tmp) throws Exception {
        Path first = tmp.resolve("first");
        Path second = tmp.resolve("second");
        Path a = runs(first, 4);
        Path b = runs(second, 4);
        RunReaper.get().configure(1, 90);
        RunReaper.get().request(first);
        RunReaper.get().request(second);
        RunReaper.get().request(first);
        RunReaper.get().awaitIdle(30_000L);

        assertThat(RunReaper.get().busy()).isFalse();
        assertThat(ProjectBuilds.listRuns(a)).hasSize(1);
        assertThat(ProjectBuilds.listRuns(b)).hasSize(1);
    }

    /** A builds root holding {@code n} runs of project {@code demo}; its home. */
    private static Path runs(Path builds, int n) throws Exception {
        Path home = builds.resolve("projects").resolve("demo");
        for (int i = 1; i <= n; i++)
            Files.createDirectories(home.resolve(ProjectBuilds.RUNS).resolve(Integer.toString(i)));
        return home;
    }
}

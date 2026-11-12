// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.runtime.base.RunMeasures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A run's test walls and runner counts go with the run, whether or not history keeps them. */
class JournalWriterMeasuresTest {

    @TempDir
    Path tmp;

    @Test
    void a_run_journaled_with_history_off_leaves_no_measures_behind() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        JobSessions sessions = new JobSessions(() -> 0L);
        JournalWriter writer = new JournalWriter(
                sessions,
                new BuildJournal(tmp.resolve("builds")),
                new JkHistoryConfig(false, 30, 512),
                () -> tmp.resolve("metrics.json"),
                () -> 1_700_000_000_100L,
                "9.9",
                s -> {});
        writer.register(9, "build", project.toString(), "cli", null, true, false, 1, null);
        RunMeasures measures = requireNonNull(sessions.accumulator(9)).measures();
        measures.classWalls(project.toString(), Map.of("demo.SlowTest", 1_200L));
        measures.runners(project.toString(), 8);

        writer.write(9, false, 100, null);

        assertThat(measures.isEmpty())
                .as("no harvest ran, and nothing keeps them for one")
                .isTrue();
    }
}

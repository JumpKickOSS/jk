// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.jobs.JobSessions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A run admitted into a session episode is written with it, and the session log hears the finished record. */
class JournalWriterEpisodeTest {

    @TempDir
    Path tmp;

    @Test
    void the_record_carries_its_episode_and_is_handed_to_the_session_log() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"));
        String locator = requireNonNull(journal.begin(BuildRecord.running(
                        3, "build", project.toString(), "g:a", null, 1_700_000_000_000L, "9.9", "mcp", "c 3f9a", 7)
                .withEpisode("mcp-3f9a-x.1")));
        JournalWriter writer = new JournalWriter(
                new JobSessions(() -> 0L),
                journal,
                new JkHistoryConfig(true, 30, 512),
                () -> tmp.resolve("metrics.json"),
                () -> 1_700_000_000_100L,
                "9.9",
                s -> {});
        List<BuildRecord> heard = new ArrayList<>();
        writer.onFinished(heard::add);
        writer.episode(7, "mcp-3f9a-x.1");
        writer.register(7, "build", project.toString(), "mcp", "c 3f9a", true, false, 3, locator);
        writer.write(7, false, 100, null);

        assertThat(heard).singleElement().satisfies(r -> assertThat(r.episode()).isEqualTo("mcp-3f9a-x.1"));
        assertThat(journal.get(locator).orElseThrow().episode()).isEqualTo("mcp-3f9a-x.1");
        assertThat(journal.rawRecord(locator).orElseThrow()).contains("\"episode\": \"mcp-3f9a-x.1\"");
    }
}

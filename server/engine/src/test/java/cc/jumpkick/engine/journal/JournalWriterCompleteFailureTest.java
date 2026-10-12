// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.jobs.JobSessions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run directory {@code begin} opened is the one the client writes its transcript into. A
 * finished record that cannot be swapped in leaves that directory as it is: it is never replaced
 * by a fresh append, which would take the transcript with it.
 */
class JournalWriterCompleteFailureTest {

    @TempDir
    Path tmp;

    @Test
    void a_record_that_cannot_be_rewritten_leaves_the_run_dir_and_its_transcript_in_place() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"));
        String locator = requireNonNull(journal.begin(
                BuildRecord.running(27, "build", project.toString(), "g:a", null, 1_700_000_000_000L, "9.9", "cli")));
        Path runDir = journal.runDir(locator).orElseThrow();
        Path details = runDir.resolve(ProjectBuilds.DETAILS);
        Files.writeString(details, "{\"type\":\"stepStart\"}\n");
        // record.json that no rename can replace: a directory with something in it.
        Path record = runDir.resolve("record.json");
        Files.delete(record);
        Files.writeString(Files.createDirectory(record).resolve("pin"), "");

        List<String> log = new ArrayList<>();
        JournalWriter writer = new JournalWriter(
                new JobSessions(() -> 0L),
                journal,
                new JkHistoryConfig(true, 30, 512),
                () -> tmp.resolve("metrics.json"),
                () -> 1_700_000_000_100L,
                "9.9",
                log::add);
        writer.register(7, "build", project.toString(), "cli", null, true, false, 27, locator);
        writer.write(7, false, 100, null);

        assertThat(runDir).isDirectory();
        assertThat(details).hasContent("{\"type\":\"stepStart\"}\n");
        assertThat(runDir.resolve("engine-owner.txt")).exists();
        assertThat(runDir.resolve(JkResultsMarkdown.FILE_NAME))
                .as("the reports still land beside the transcript")
                .exists();
        try (Stream<Path> siblings = Files.list(requireNonNull(runDir.getParent()))) {
            assertThat(siblings.map(p -> p.getFileName().toString())).containsExactly("27");
        }
        assertThat(log).anyMatch(l -> l.contains("could not be rewritten"));
    }
}

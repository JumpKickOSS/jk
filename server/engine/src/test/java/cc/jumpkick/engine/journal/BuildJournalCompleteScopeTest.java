// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.testing.Symlinks;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A numbered run completes only under its own project: build numbers repeat across project homes. */
class BuildJournalCompleteScopeTest {

    @TempDir
    Path tmp;

    private static BuildRecord running(long n, long requestId, Path project) {
        return BuildRecord.running(
                n, "build", project.toString(), "g:a", null, 1_700_000_000_000L, "9.9", "cli", null, requestId);
    }

    private static BuildRecord finished(long n, long requestId, Path project) {
        BuildAccumulator a = new BuildAccumulator(
                "build", project.toString(), "g:a", "cli", null, null, false, n, Long.toString(n), requestId);
        a.addTask("", "compile-java", "compile", "SKIPPED", 4, 0);
        a.stamp(JobOutcome.SUCCEEDED);
        return a.toRecord(1_700_000_000_090L, false, 90, "9.9", null).withBuildNumber(n);
    }

    @Test
    void completing_a_run_its_project_never_began_leaves_another_projects_run_of_that_number_alone() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"), 0);
        requireNonNull(journal.begin(running(5, 0, b)));
        Path theirs = journal.runDir("g:a", b.toString(), 5).orElseThrow();
        byte[] before = Files.readAllBytes(theirs.resolve(ProjectBuilds.RECORD));

        assertThat(journal.complete("5", finished(5, 0, a), BuildJournal.Snapshot.NONE))
                .as("project a has no runs/5 to complete")
                .isFalse();

        assertThat(Files.readAllBytes(theirs.resolve(ProjectBuilds.RECORD))).isEqualTo(before);
        assertThat(journal.runDir("g:a", a.toString(), 5)).isEmpty();
    }

    @Test
    void a_run_admitted_by_one_spelling_and_finished_by_a_symlink_completes_the_admission_entry() throws Exception {
        Path real = Files.createDirectories(tmp.resolve("real"));
        Path link = Symlinks.create(tmp.resolve("link"), real);
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"), 3_600_000L);
        String locator = requireNonNull(journal.beginLater(running(3, 21, real)));

        assertThat(journal.complete(locator, finished(3, 21, link), BuildJournal.Snapshot.NONE))
                .isTrue();

        Path dir = journal.runDir("g:a", real.toString(), 3).orElseThrow();
        assertThat(journal.get(locator).orElseThrow().running()).isFalse();
        assertThat(Jsonl.str(Files.readString(dir.resolve(ProjectBuilds.RECORD)), "running"))
                .isNotEqualTo("true");
    }

    @Test
    void a_run_begun_by_one_spelling_and_finished_by_a_symlink_completes_its_entry() throws Exception {
        Path real = Files.createDirectories(tmp.resolve("real"));
        Path link = Symlinks.create(tmp.resolve("link"), real);
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"), 0);
        String locator = requireNonNull(journal.begin(running(4, 0, real)));

        assertThat(journal.complete(locator, finished(4, 0, link), BuildJournal.Snapshot.NONE))
                .isTrue();

        assertThat(journal.get(locator).orElseThrow().running()).isFalse();
    }
}

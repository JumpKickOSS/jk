// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSessions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A no-op is one line of the project's run log: no run directory, no record, and the reports the
 * last run of its kind wrote left as they are. Anything else is journaled whole, as it always was.
 */
class NoopJournalTest {

    @TempDir
    Path tmp;

    private static final long HOUR = 3_600_000L;

    private BuildRecord running(long n, long requestId, Path project) {
        return BuildRecord.running(
                n, "build", project.toString(), "g:a", null, 1_700_000_000_000L, "9.9", "cli", null, requestId);
    }

    private BuildRecord finished(long n, long requestId, Path project) {
        BuildAccumulator a = new BuildAccumulator(
                "build", project.toString(), "g:a", "cli", null, null, false, n, Long.toString(n), requestId);
        a.addTask("", "compile-java", "compile", "SKIPPED", 4, 0);
        a.stamp(JobOutcome.SUCCEEDED);
        return a.toRecord(1_700_000_000_090L, false, 90, "9.9", null).withBuildNumber(n);
    }

    @Test
    void a_run_confirmed_before_the_stub_delay_is_one_log_line_and_no_directory() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"), HOUR);
        String locator = requireNonNull(journal.beginLater(running(3, 11, project)));
        Path planned = journal.runDir("g:a", project.toString(), 3).orElseThrow();
        assertThat(planned).as("the client is told where the run would live").doesNotExist();

        assertThat(journal.confirm(locator, finished(3, 11, project))).isTrue();

        assertThat(planned).doesNotExist();
        Path log =
                requireNonNull(requireNonNull(planned.getParent()).getParent()).resolve(BuildJournal.RUN_LOG);
        assertThat(Files.readAllLines(log))
                .singleElement()
                .satisfies(l -> assertThat(l).startsWith("3\t").contains("\tbuild\t90\tcli"));
        assertThat(journal.runDir("g:a", project.toString(), 3)).isEmpty();
    }

    @Test
    void a_run_completed_before_the_stub_delay_is_written_whole_at_once() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"), HOUR);
        String locator = requireNonNull(journal.beginLater(running(4, 12, project)));

        assertThat(journal.complete(locator, finished(4, 12, project), BuildJournal.Snapshot.NONE))
                .isTrue();

        Path dir = journal.runDir("g:a", project.toString(), 4).orElseThrow();
        assertThat(dir.resolve(ProjectBuilds.RECORD)).exists();
        assertThat(dir.resolve(BuildJournal.ENGINE_OWNER)).doesNotExist();
        assertThat(journal.get(locator).orElseThrow().running()).isFalse();
    }

    @Test
    void a_run_past_the_stub_delay_has_its_entry_and_is_never_a_log_line() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"), 0);
        String locator = requireNonNull(journal.beginLater(running(5, 13, project)));
        Path dir = ProjectBuilds.runDir(ProjectBuilds.projectHome(tmp.resolve("builds"), null, project), 5);
        for (int i = 0; i < 200 && !Files.isRegularFile(dir.resolve(ProjectBuilds.RECORD)); i++) Thread.sleep(10);
        assertThat(dir.resolve(ProjectBuilds.RECORD)).exists();

        assertThat(journal.confirm(locator, finished(5, 13, project))).isFalse();
        assertThat(journal.complete(locator, finished(5, 13, project), BuildJournal.Snapshot.NONE))
                .isTrue();
        assertThat(journal.get(locator).orElseThrow().running()).isFalse();
    }

    @Test
    void a_run_deleted_before_the_stub_delay_never_begins() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"), 30);
        String locator = requireNonNull(journal.beginLater(running(6, 14, project)));
        Path planned = journal.runDir("g:a", project.toString(), 6).orElseThrow();

        journal.delete(locator, "g:a", project.toString());
        Thread.sleep(150);

        assertThat(planned).doesNotExist();
    }

    @Test
    void a_numbered_run_with_no_directory_is_never_completed_into_another_projects_run() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"), HOUR);
        String theirs = requireNonNull(journal.begin(running(7, 0, other)));
        Path theirDir = journal.runDir("g:a", other.toString(), 7).orElseThrow();
        String ours = requireNonNull(journal.beginLater(running(7, 15, project)));

        assertThat(journal.complete(ours, finished(7, 15, project), BuildJournal.Snapshot.NONE))
                .isTrue();

        assertThat(theirs).isEqualTo(ours);
        assertThat(BuildJournal.scanString(Files.readString(theirDir.resolve(ProjectBuilds.RECORD)), "dir"))
                .isEqualTo(other.toString());
        assertThat(journal.runDir("g:a", project.toString(), 7)).isPresent();
    }

    @Test
    void a_held_transcript_header_lands_with_the_entry_and_goes_with_a_no_op() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"), HOUR);
        String whole = requireNonNull(journal.beginLater(running(8, 16, project)));
        String noop = requireNonNull(journal.beginLater(running(9, 17, project)));

        assertThat(journal.holdTranscriptHeader(16, "{\"type\":\"session-start\"}"))
                .isTrue();
        assertThat(journal.holdTranscriptHeader(17, "{\"type\":\"session-start\"}"))
                .isTrue();
        assertThat(journal.complete(whole, finished(8, 16, project), BuildJournal.Snapshot.NONE))
                .isTrue();
        assertThat(journal.confirm(noop, finished(9, 17, project))).isTrue();

        Path details = journal.detailsFile("g:a", project.toString(), 8).orElseThrow();
        assertThat(Files.readAllLines(details)).containsExactly("{\"type\":\"session-start\"}");
        assertThat(journal.runDir("g:a", project.toString(), 9)).isEmpty();
        assertThat(journal.holdTranscriptHeader(17, "late"))
                .as("a settled run holds nothing")
                .isFalse();
    }

    // --- JournalWriter: what counts as a no-op

    private JournalWriter writer(BuildJournal journal, JobSessions sessions, List<String> log) {
        return new JournalWriter(
                sessions,
                journal,
                new JkHistoryConfig(true, 30, 512),
                () -> tmp.resolve("metrics.json"),
                () -> 1_700_000_000_100L,
                "9.9",
                log::add);
    }

    /** One run of {@code project}: admitted, its steps reported, its verdict stamped, journaled. */
    private @Nullable String run(
            JournalWriter writer,
            JobSessions sessions,
            BuildJournal journal,
            Path project,
            long id,
            long n,
            String compileStatus,
            JobOutcome outcome) {
        String locator = requireNonNull(journal.beginLater(running(n, id, project)));
        writer.register(id, "build", project.toString(), "cli", null, true, false, n, locator);
        BuildAccumulator a = requireNonNull(sessions.accumulator(id));
        a.addTask("", "parse-build", "resolve", "SUCCESS", 1, 0);
        a.addTask("", "compile-java", "compile", compileStatus, 4, 0);
        a.addTask("", "package-jar", "package", "SKIPPED", 2, 0);
        a.stamp(outcome);
        return writer.write(id, false, 90, null);
    }

    @Test
    void an_up_to_date_rebuild_of_a_green_tree_is_a_log_line_and_leaves_the_reports_alone() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"), HOUR);
        JobSessions sessions = new JobSessions(() -> 0L);
        List<String> log = new ArrayList<>();
        JournalWriter writer = writer(journal, sessions, log);

        assertThat(run(writer, sessions, journal, project, 21, 1, "SUCCESS", JobOutcome.SUCCEEDED))
                .as("a compile is work")
                .isNull();
        assertThat(run(writer, sessions, journal, project, 22, 2, "SKIPPED", JobOutcome.SUCCEEDED))
                .as("the compile's reports stand for an up-to-date run of the same kind")
                .isNotNull();
        Path report = project.resolve("target").resolve(JkResultsMarkdown.FILE_NAME);
        FileTime written = Files.getLastModifiedTime(report);
        String text = Files.readString(report);

        String verdict = run(writer, sessions, journal, project, 23, 3, "SKIPPED", JobOutcome.SUCCEEDED);

        assertThat(verdict).startsWith("OK build").endsWith(" · up to date\n");
        assertThat(journal.runDir("g:a", project.toString(), 1)).isPresent();
        assertThat(journal.runDir("g:a", project.toString(), 3)).isEmpty();
        assertThat(Files.getLastModifiedTime(report)).isEqualTo(written);
        assertThat(Files.readString(report)).isEqualTo(text);
        assertThat(log).isEmpty();
    }

    @Test
    void an_up_to_date_run_after_a_failure_is_journaled_whole() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"), HOUR);
        JobSessions sessions = new JobSessions(() -> 0L);
        JournalWriter writer = writer(journal, sessions, new ArrayList<>());

        run(writer, sessions, journal, project, 31, 1, "SUCCESS", JobOutcome.SUCCEEDED);
        run(writer, sessions, journal, project, 32, 2, "FAIL", new JobOutcome.Failed(1));
        String verdict = run(writer, sessions, journal, project, 33, 3, "SKIPPED", JobOutcome.SUCCEEDED);

        assertThat(verdict).as("the failure's report must not stand").isNull();
        assertThat(journal.runDir("g:a", project.toString(), 3)).isPresent();
    }

    @Test
    void the_first_run_an_engine_sees_is_journaled_whole() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        BuildJournal journal = new BuildJournal(tmp.resolve("builds"), HOUR);
        JobSessions sessions = new JobSessions(() -> 0L);
        JournalWriter writer = writer(journal, sessions, new ArrayList<>());

        assertThat(run(writer, sessions, journal, project, 41, 1, "SKIPPED", JobOutcome.SUCCEEDED))
                .isNull();
        assertThat(journal.runDir("g:a", project.toString(), 1)).isPresent();
    }
}

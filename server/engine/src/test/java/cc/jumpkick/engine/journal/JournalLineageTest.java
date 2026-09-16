// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which run a delta compares against: the run before from the same origin, stepping over other
 * origins' runs and anything still running, with the snapshots it left beside its record.
 */
class JournalLineageTest {

    @TempDir
    Path dir;

    private static BuildRecord run(
            long finishedAt, String kind, String trigger, @Nullable String session, boolean running) {
        return new BuildRecord(
                null,
                0L,
                BuildRecord.SCHEMA,
                kind,
                "/proj",
                "g:a",
                null,
                finishedAt - 100,
                finishedAt,
                100,
                true,
                false,
                0,
                "9.9-test",
                null,
                List.of(),
                List.of(),
                List.of(),
                trigger,
                session,
                null,
                null,
                running,
                null,
                0L,
                null,
                List.of());
    }

    @Test
    void the_previous_run_is_the_newest_finished_one_from_the_same_origin() {
        BuildJournal journal = new BuildJournal(dir.resolve("builds"));
        String agentA = "claude-code 3f9a";
        journal.append(run(1_000, "build", "mcp", agentA, false), snapshot("P\tT#a()\n"));
        journal.append(run(2_000, "build", "cli", null, false), BuildJournal.Snapshot.NONE);
        journal.append(run(3_000, "build", "mcp", "claude-code 77aa", false), BuildJournal.Snapshot.NONE);
        journal.append(run(4_000, "format", "mcp", agentA, false), BuildJournal.Snapshot.NONE);
        journal.begin(run(5_000, "build", "mcp", agentA, true));

        BuildRecord current = run(9_000, "build", "mcp", agentA, false);
        Optional<JournalLineage.Previous> previous = journal.previousInSession(current);

        assertThat(previous).isPresent();
        assertThat(previous.get().record().finishedAt())
                .as("the other agent's, the shell's, a format run and a running stub are stepped over")
                .isEqualTo(1_000);
        assertThat(Files.isRegularFile(previous.get().dir().resolve(BuildJournal.TEST_OUTCOMES_TSV)))
                .as("its test outcomes ride beside it")
                .isTrue();

        assertThat(journal.previousInSession(run(9_000, "build", "cli", null, false)))
                .map(p -> p.record().finishedAt())
                .as("a shell run without a session matches the shell runs before it")
                .contains(2_000L);
        assertThat(journal.previousInSession(run(9_000, "build", "bsp", "IntelliJ-BSP 7b2c", false)))
                .as("an origin with no earlier run has no previous")
                .isEmpty();
    }

    @Test
    void a_run_that_ended_after_this_one_started_is_not_its_previous() {
        BuildJournal journal = new BuildJournal(dir.resolve("builds"));
        journal.append(run(5_000, "build", "cli", null, false), BuildJournal.Snapshot.NONE);
        BuildRecord current = run(5_050, "build", "cli", null, false); // started at 4_950
        assertThat(journal.previousInSession(current)).isEmpty();
    }

    @Test
    void the_snapshots_survive_complete_and_are_readable_artifacts() throws Exception {
        BuildJournal journal = new BuildJournal(dir.resolve("builds"));
        String locator = journal.begin(run(1_000, "build", "cli", null, true));
        assertThat(locator).isNotNull();
        assertThat(journal.complete(locator, run(1_000, "build", "cli", null, false), snapshot("F\tT#a()\n")))
                .isTrue();
        Path tests = journal.artifact(locator, BuildJournal.TEST_OUTCOMES_TSV).orElseThrow();
        assertThat(Files.readString(tests, StandardCharsets.UTF_8)).isEqualTo("F\tT#a()\n");
        assertThat(journal.artifact(locator, BuildJournal.SOURCES_TSV)).isPresent();
    }

    private static BuildJournal.Snapshot snapshot(String tests) {
        return new BuildJournal.Snapshot(null, null, null, tests, "h\t1\t2\tjk.toml\n");
    }
}

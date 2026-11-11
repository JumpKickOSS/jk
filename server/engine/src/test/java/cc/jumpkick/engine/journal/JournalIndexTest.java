// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The journal answers its reads off an index of record heads: a repeat lookup reads no record, a
 * run another engine writes on the same root is seen, and so is one it finishes.
 */
class JournalIndexTest {

    @TempDir
    Path dir;

    private static BuildRecord finished(String projectDir, long finishedAt) {
        return new BuildRecord(
                null,
                0L,
                BuildRecord.SCHEMA,
                "build",
                projectDir,
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
                "cli",
                null,
                null,
                null,
                false,
                null,
                0L,
                null,
                List.of());
    }

    @Test
    void repeat_reads_cost_no_record_head_and_an_unknown_id_costs_none_either() {
        BuildJournal j = new BuildJournal(dir);
        for (int i = 0; i < 20; i++)
            j.append(finished("/p" + i, 1_700_000_000_000L + i * 1000), BuildJournal.Snapshot.NONE);
        String newestId = requireNonNull(j.heads(1).getFirst().record().id());
        long afterFirst = j.indexHeadReads();

        assertThat(j.heads(5)).hasSize(5);
        assertThat(j.get(newestId)).isPresent();
        assertThat(j.get("20990101T000000000")).isEmpty();
        assertThat(j.rawFinishedRecordByRequestId(424242)).isEmpty();
        assertThat(j.indexHeadReads())
                .as("nothing on disk moved, so nothing is read again")
                .isEqualTo(afterFirst);
    }

    @Test
    void another_engines_new_run_and_its_finish_are_seen() {
        BuildJournal mine = new BuildJournal(dir);
        BuildJournal theirs = new BuildJournal(dir);
        mine.append(finished("/a", 1_700_000_000_000L), BuildJournal.Snapshot.NONE);
        assertThat(mine.heads(10)).hasSize(1);

        String locator = requireNonNull(
                theirs.begin(BuildRecord.running(7, "build", "/b", "g:a", null, 1_700_000_001_000L, "9.9", "cli")));
        assertThat(mine.heads(10))
                .as("a run written by another journal on the root")
                .hasSize(2);
        assertThat(mine.list()).anyMatch(BuildRecord::running);

        theirs.complete(locator, finished("/b", 1_700_000_002_000L).withBuildNumber(7), BuildJournal.Snapshot.NONE);
        assertThat(mine.list()).as("its finish rewrote the record").noneMatch(BuildRecord::running);
        assertThat(mine.heads(1).getFirst().record().dir()).isEqualTo("/b");
    }

    @Test
    void a_deleted_run_leaves_the_index() {
        BuildJournal j = new BuildJournal(dir);
        String a = requireNonNull(j.append(finished("/a", 1_700_000_000_000L), BuildJournal.Snapshot.NONE));
        j.append(finished("/b", 1_700_000_001_000L), BuildJournal.Snapshot.NONE);
        String id = requireNonNull(j.get(a).orElseThrow().id());
        assertThat(j.deleteFinished(id)).isTrue();
        assertThat(j.get(id)).isEmpty();
        assertThat(j.heads(10)).hasSize(1);
    }

    @Test
    void a_lazy_list_reads_a_record_only_when_it_is_reached() {
        BuildJournal j = new BuildJournal(dir);
        for (int i = 0; i < 10; i++)
            j.append(finished("/p" + i, 1_700_000_000_000L + i * 1000), BuildJournal.Snapshot.NONE);
        j.heads(1); // the index is built
        long before = j.recordReads();
        List<String> records = j.lazyRawRecords(200, raw -> raw);
        assertThat(records).hasSize(10);
        assertThat(records.getFirst()).contains("\"/p9\"");
        records.getFirst();
        assertThat(j.recordReads() - before).as("one record read, once").isEqualTo(1);
    }
}

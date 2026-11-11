// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.HistoryDeleteRequest;
import cc.jumpkick.wire.protocol.HistoryListRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryVerbsTest {

    private static final long T0 = 1_700_000_000_000L;

    @TempDir
    Path dir;

    private static BuildRecord finished(long number, String kind, String project, String coord, long at) {
        return BuildRecord.running(number, kind, project, coord, null, at, "9.9", "cli")
                .abandoned(at + 10, "9.9");
    }

    @Test
    void history_list_fills_its_limit_with_builds_past_many_newer_non_build_runs() {
        BuildJournal journal = new BuildJournal(dir);
        journal.append(finished(1, "build", "/proj", "g:a", T0), BuildJournal.Snapshot.NONE);
        journal.append(finished(2, "build", "/proj", "g:a", T0 + 1_000), BuildJournal.Snapshot.NONE);
        for (int i = 0; i < 12; i++) {
            journal.append(finished(0, "lock", "/proj", "g:a", T0 + 10_000 + i * 1_000L), BuildJournal.Snapshot.NONE);
        }
        RecordingHost host = new RecordingHost(journal);

        new HistoryListVerb(host).run(new HistoryListRequest(2).encode(), Session.CancelToken.live(), null);

        assertThat(host.ofType(EngineProtocol.HISTORY_ENTRY))
                .as("both builds listed although twelve newer lock runs sit above them")
                .hasSize(2);
    }

    @Test
    void history_delete_refuses_a_bare_build_number_that_every_project_home_has() {
        BuildJournal journal = new BuildJournal(dir);
        journal.append(finished(8, "build", "/projA", "g:a", T0), BuildJournal.Snapshot.NONE);
        journal.append(finished(8, "build", "/projB", "g:b", T0), BuildJournal.Snapshot.NONE);
        RecordingHost host = new RecordingHost(journal);

        new HistoryDeleteVerb(host).run(new HistoryDeleteRequest("8").encode(), Session.CancelToken.live(), null);

        assertThat(Jsonl.bool(host.ofType(EngineProtocol.HISTORY_DELETED).getFirst(), "deleted", true))
                .isFalse();
        assertThat(journal.runDir("g:a", "/projA", 8)).isPresent();
        assertThat(journal.runDir("g:b", "/projB", 8)).isPresent();
    }

    @Test
    void history_delete_refuses_a_run_still_in_flight_and_removes_a_finished_one_by_id() {
        BuildJournal journal = new BuildJournal(dir);
        journal.begin(BuildRecord.running(3, "build", "/proj", "g:a", null, T0, "9.9", "cli"));
        String runningId = requireNonNull(journal.heads(1).getFirst().record().id());
        RecordingHost host = new RecordingHost(journal);

        new HistoryDeleteVerb(host).run(new HistoryDeleteRequest(runningId).encode(), Session.CancelToken.live(), null);

        assertThat(journal.runDir("g:a", "/proj", 3))
                .as("the in-flight run survives")
                .isPresent();

        journal.append(finished(4, "build", "/proj", "g:a", T0 + 1_000), BuildJournal.Snapshot.NONE);
        String finishedId = requireNonNull(journal.heads(1).getFirst().record().id());
        new HistoryDeleteVerb(host)
                .run(new HistoryDeleteRequest(finishedId).encode(), Session.CancelToken.live(), null);

        assertThat(journal.runDir("g:a", "/proj", 4))
                .as("the finished run is removed")
                .isEmpty();
    }

    private static final class RecordingHost extends InertVerbHost {
        private final BuildJournal journal;
        private final List<String> sent = new ArrayList<>();

        RecordingHost(BuildJournal journal) {
            this.journal = journal;
        }

        @Override
        public BuildJournal journal() {
            return journal;
        }

        @Override
        public void send(@Nullable BufferedWriter w, String line) {
            sent.add(line);
        }

        List<String> ofType(String type) {
            return sent.stream()
                    .filter(l -> type.equals(EngineProtocol.typeOf(l)))
                    .toList();
        }
    }
}

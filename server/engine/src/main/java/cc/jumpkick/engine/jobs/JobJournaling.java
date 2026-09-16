// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import java.io.BufferedWriter;
import org.jspecify.annotations.Nullable;

/** The build journal: the accumulator a job stamps its verdict on, and the record it becomes. */
public interface JobJournaling {
    void registerAccumulator(
            long id,
            String kind,
            String dir,
            String trigger,
            @Nullable String session,
            boolean noTimeline,
            boolean rebuild,
            long buildNumber,
            @Nullable String journalId);

    @Nullable
    BuildAccumulator accumulatorOf(long id);

    void writeJournal(long id, boolean cancelled, long millis, @Nullable BufferedWriter writer);

    BuildJournal journal();

    JkHistoryConfig historyConfig();

    /** The project coordinate for {@code dir}, for journal rows. */
    @Nullable
    String coordOf(String dir);
}

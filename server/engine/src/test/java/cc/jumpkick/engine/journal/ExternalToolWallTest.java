// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A run another tool performed is journaled with that tool's wall as its headline, and is not
 * priced for a cache jk never ran: the journaling job's own few hundred milliseconds are not the
 * Maven run, and a saving figure for it would be a figure for nothing.
 */
class ExternalToolWallTest {

    private static final long FINISHED = 100_000L;

    @Test
    void the_tools_wall_is_the_records_headline_and_its_start() {
        BuildAccumulator a = new BuildAccumulator("mvn", "/proj", "demo:app", "cli");
        a.addModule(new ModuleOutcome("demo:app", Path.of("/proj"), true, 0, 3_190L, true, false, null));
        a.addTask("/proj", "compiler:compile", "", "SUCCESS", 2_800L, 0L);
        a.stamp(JobOutcome.ok());
        a.noteToolWall(3_200L);

        BuildRecord r = a.toRecord(FINISHED, false, 426L, "9.9", null);

        assertThat(r.millis()).isEqualTo(3_200L);
        assertThat(r.startedAt()).isEqualTo(FINISHED - 3_200L);
    }

    @Test
    void without_a_tool_wall_the_jobs_own_elapsed_is_the_headline() {
        BuildAccumulator a = new BuildAccumulator("build", "/proj", "demo:app", "cli");
        a.stamp(JobOutcome.ok());
        BuildRecord r = a.toRecord(FINISHED, false, 426L, "9.9", null);
        assertThat(r.millis()).isEqualTo(426L);
    }

    @Test
    void an_external_tools_run_is_not_priced_for_the_cache() {
        assertThat(BuildHistoryKinds.isExternalTool("mvn")).isTrue();
        assertThat(BuildHistoryKinds.isExternalTool("build")).isFalse();
        assertThat(BuildHistoryKinds.isExternalTool("test")).isFalse();
        assertThat(JournalWriter.pricesCacheBenefit("mvn")).isFalse();
        assertThat(JournalWriter.pricesCacheBenefit("build")).isTrue();
    }
}

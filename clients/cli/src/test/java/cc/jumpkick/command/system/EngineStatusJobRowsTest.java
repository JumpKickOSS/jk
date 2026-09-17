// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineProbe;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The job rows under {@code Live Jobs} and {@code Queued}, and their {@code --output json} array. */
class EngineStatusJobRowsTest {

    private static final long NOW = 1_700_007_500_000L;

    @Test
    void a_live_row_names_the_job_its_age_workers_and_silence() {
        EngineProbe.Job live =
                new EngineProbe.Job(739, "test", "/home/me/app", true, NOW - 7_500_000L, 1, NOW - 180_000L, -1);
        assertThat(EngineStatusCommand.jobLine(live, NOW))
                .startsWith("#739 test /home/me/app · since ")
                .endsWith(" (2h 05m) · 1 worker · last event 3m ago");
        EngineProbe.Job silentSinceStart =
                new EngineProbe.Job(740, "build", "/home/me/lib", true, NOW - 40_000L, 2, 0L, -1);
        assertThat(EngineStatusCommand.jobLine(silentSinceStart, NOW)).endsWith(" (40s) · 2 workers");
    }

    @Test
    void a_queued_row_names_its_position_and_wait() {
        EngineProbe.Job queued = new EngineProbe.Job(741, "format", "/home/me/tool", false, NOW - 720_000L, -1, -1, 1);
        assertThat(EngineStatusCommand.jobLine(queued, NOW))
                .isEqualTo("#741 format /home/me/tool · behind 1 · waiting 12m");
    }

    @Test
    void the_json_array_carries_the_engines_own_fields() {
        String json = EngineStatusCommand.jobsJson(List.of(
                new EngineProbe.Job(739, "test", "/home/me/app", true, 1_000L, 1, 1_500L, -1),
                new EngineProbe.Job(741, "format", "/home/me/tool", false, 2_000L, -1, -1, 0)));
        assertThat(json)
                .isEqualTo("[{\"jid\":739,\"kind\":\"test\",\"dir\":\"/home/me/app\",\"state\":\"live\",\"since\":1000,"
                        + "\"workers\":1,\"lastEventAt\":1500,\"ahead\":-1},"
                        + "{\"jid\":741,\"kind\":\"format\",\"dir\":\"/home/me/tool\",\"state\":\"queued\",\"since\":2000,"
                        + "\"workers\":-1,\"lastEventAt\":-1,\"ahead\":0}]");
    }
}

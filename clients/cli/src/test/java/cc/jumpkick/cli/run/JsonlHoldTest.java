// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A build on hold for memory in the jsonl stream (JK-VYJ4X). */
class JsonlHoldTest {

    @Test
    void the_hold_is_a_start_and_an_end_event_and_progress_while_held_says_on_hold() {
        var snap = new WorkspaceProgressTracker.Snapshot(150, 200, 75.0, "execute", 1, 2, 25_000, 100_000);

        String start = JsonlShape.memoryHold("/ws", true, 512L << 20, 128L << 20, 0);
        String end = JsonlShape.memoryHold("/ws", false, 0, 0, 19_000);
        String held = JsonlShape.workspaceProgress("/ws", snap, 3_000);
        String running = JsonlShape.workspaceProgress("/ws", snap);

        assertThat(start).contains("\"type\":\"hold-start\"").contains("\"freeBytes\":134217728");
        assertThat(end).contains("\"type\":\"hold-end\"").contains("\"heldMs\":19000");
        assertThat(held).contains("\"state\":\"on-hold\"").contains("\"heldMs\":3000");
        assertThat(running).doesNotContain("on-hold");
    }

    @Test
    void a_suspend_is_one_event_with_the_time_away_and_what_the_monotonic_clock_moved() {
        String line = JsonlShape.suspended("/ws", 274_000, 0);

        assertThat(line).contains("\"type\":\"suspended\"").contains("\"suspendedMs\":274000");
        assertThat(line).doesNotContain("monoMs");
    }

    @Test
    void the_summary_names_the_time_suspended_beside_the_time_the_work_took() {
        assertThat(ConsoleSpec.took(Duration.ofSeconds(217), 274_000)).contains("took 3m 37s, suspended 4m 34s");
        assertThat(ConsoleSpec.took(Duration.ofSeconds(217))).doesNotContain("suspended");
    }
}

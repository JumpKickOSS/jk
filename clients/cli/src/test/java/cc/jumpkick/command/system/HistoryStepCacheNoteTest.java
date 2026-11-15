// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code jk history show} says why a step ran, and which steps came from a remote cache. */
class HistoryStepCacheNoteTest {

    @Test
    void a_missed_step_names_its_reason_and_the_inputs_that_moved() {
        String row = "{\"type\":\"history-task\",\"name\":\"compile-main\",\"status\":\"SUCCESS\",\"millis\":40,"
                + "\"cache\":\"miss\",\"missReason\":\"inputs-changed\",\"changed\":\"src/A.java\"}";

        assertThat(HistoryCommand.HistoryShowCommand.cacheNote(row)).isEqualTo("  · ran: inputs changed (src/A.java)");
    }

    @Test
    void a_remote_hit_says_so_and_a_local_hit_or_a_step_without_a_cache_says_nothing() {
        assertThat(HistoryCommand.HistoryShowCommand.cacheNote("{\"cache\":\"remote-hit\"}"))
                .isEqualTo("  · remote hit");
        assertThat(HistoryCommand.HistoryShowCommand.cacheNote("{\"cache\":\"local-hit\"}"))
                .isEmpty();
        assertThat(HistoryCommand.HistoryShowCommand.cacheNote("{\"name\":\"parse-build\"}"))
                .isEmpty();
    }
}

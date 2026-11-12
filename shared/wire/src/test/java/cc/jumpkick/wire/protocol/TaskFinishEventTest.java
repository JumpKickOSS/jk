// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** task-finish carries what the estimate priced the task at, and which layer priced it (JK-626GS). */
class TaskFinishEventTest {

    @Test
    void a_priced_task_round_trips_its_price_and_rung() {
        String line =
                ProtoEvents.stepFinish("/ws/lib", "compile-java", "compile", "SUCCESS", 2_400, 100, 3_000, "MODULE");
        TaskFinishEvent e = TaskFinishEvent.decode(line);
        assertThat(e.predictedMs()).isEqualTo(3_000);
        assertThat(e.rung()).isEqualTo("MODULE");
        assertThat(e.millis()).isEqualTo(2_400);
    }

    @Test
    void an_unpriced_task_writes_neither_field() {
        String line = ProtoEvents.stepFinish("/ws/lib", "compile-java", "compile", "SUCCESS", 2_400, 100);
        assertThat(line).doesNotContain("predictedMs").doesNotContain("rung");
        assertThat(TaskFinishEvent.decode(line).rung()).isEmpty();
    }
}

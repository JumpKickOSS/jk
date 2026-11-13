// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A step's wait for a worker lease, on the wire and back. */
class TaskWaitEventTest {

    @Test
    void a_wait_carries_its_reason_and_its_end_an_empty_one() {
        String slot = ProtoEvents.taskWait("/ws/a", "run-tests", "slot");
        String done = ProtoEvents.taskWait("/ws/a", "run-tests", "");

        assertThat(slot).contains("\"type\":\"task-wait\"").contains("\"reason\":\"slot\"");
        assertThat(TaskWaitEvent.decode(slot)).isEqualTo(new TaskWaitEvent("/ws/a", "run-tests", "slot"));
        assertThat(TaskWaitEvent.decode(done).reason()).isEmpty();
    }
}

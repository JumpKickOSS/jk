// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The hold events and a held progress event, on the wire and back. */
class MemoryHoldEventTest {

    @Test
    void a_hold_start_carries_the_memory_waited_for_and_a_hold_end_the_time_held() {
        String start = MemoryHoldEvent.start("/ws", 512L << 20, 128L << 20).encode();
        String end = MemoryHoldEvent.end("/ws", 19_000).encode();

        assertThat(start).contains("\"type\":\"hold-start\"").contains("\"needBytes\":536870912");
        assertThat(MemoryHoldEvent.decode(start))
                .isEqualTo(new MemoryHoldEvent(true, "/ws", 512L << 20, 128L << 20, 0));
        assertThat(end).contains("\"type\":\"hold-end\"").contains("\"heldMs\":19000");
        assertThat(MemoryHoldEvent.decode(end)).isEqualTo(new MemoryHoldEvent(false, "/ws", 0, 0, 19_000));
    }

    @Test
    void a_held_progress_event_says_on_hold_with_the_time_held_and_an_ordinary_one_says_neither() {
        var held = new WorkspaceProgressEvent("/ws", 10, 100, 10.0, "execute", 1, 4, 30_000, 90_000, 4_000);
        var plain = new WorkspaceProgressEvent("/ws", 10, 100, 10.0, "execute", 1, 4, 30_000, 90_000);

        assertThat(held.encode()).contains("\"state\":\"on-hold\"").contains("\"heldMs\":4000");
        assertThat(WorkspaceProgressEvent.decode(held.encode()).heldMs()).isEqualTo(4_000);
        assertThat(plain.encode()).doesNotContain("on-hold").doesNotContain("heldMs");
        assertThat(WorkspaceProgressEvent.decode(plain.encode()).onHold()).isFalse();
    }
}

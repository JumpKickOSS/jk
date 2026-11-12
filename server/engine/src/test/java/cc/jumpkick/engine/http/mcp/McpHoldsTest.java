// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.MemoryHoldsSeam;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** An agent parked on a job learns of its hold for memory without polling (JK-VYJ4X). */
class McpHoldsTest {

    private static final long JID = 9_200_001L;

    @AfterEach
    void release() {
        MemoryHoldsSeam.clear(JID);
    }

    @Test
    void a_parked_call_is_told_when_the_hold_starts_while_it_lasts_and_when_it_resumes() {
        List<String> messages = new ArrayList<>();
        McpOutbound out = note -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) Objects.requireNonNull(note.get("params"));
            assertThat(note).containsEntry("method", "notifications/progress");
            assertThat(params).containsEntry("progressToken", "t1");
            messages.add(String.valueOf(params.get("message")));
        };
        var notifier = new McpHolds.Notifier("t1", out, JID);

        notifier.tick(0);
        MemoryHoldsSeam.hold(JID, 512L << 20, 128L << 20);
        notifier.tick(100);
        notifier.tick(2_000);
        notifier.tick(5_200);
        MemoryHoldsSeam.clear(JID);
        notifier.tick(6_000);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).startsWith(McpHolds.WAITING).contains("need 512 MiB");
        assertThat(messages.get(1)).startsWith("On hold ");
        assertThat(messages.get(2)).startsWith("Resumed");
    }

    @Test
    void a_call_without_a_progress_token_sends_nothing() {
        List<Object> sent = new ArrayList<>();
        var notifier = new McpHolds.Notifier(null, sent::add, JID);
        MemoryHoldsSeam.hold(JID, 1, 1);
        notifier.tick(0);
        assertThat(sent).isEmpty();
    }
}

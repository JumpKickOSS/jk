// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.plugin.MemoryHoldsSeam;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** An agent parked on a job hears how it goes, and of its hold for memory, without polling. */
class McpCallProgressTest {

    private static final long JID = 9_200_001L;

    private final List<Map<String, Object>> notes = new ArrayList<>();
    private final Map<Long, HttpLive.Run> live = new HashMap<>();

    @AfterEach
    void release() {
        MemoryHoldsSeam.clear(JID);
    }

    @SuppressWarnings("unchecked")
    private final McpOutbound out = note -> {
        assertThat(note).containsEntry("method", "notifications/progress");
        notes.add((Map<String, Object>) Objects.requireNonNull(note.get("params")));
    };

    private McpCallProgress progress(Object token) {
        return new McpCallProgress(token, out, JID, live::get);
    }

    private static HttpLive.Run run(long jid, double percent, HttpLive.Module... modules) {
        return new HttpLive.Run(
                jid,
                1,
                "test",
                "/ws",
                null,
                null,
                null,
                0,
                0,
                percent,
                null,
                -1,
                -1,
                0,
                0,
                List.of(modules),
                List.of());
    }

    private static HttpLive.Module module(String dir, String task) {
        return new HttpLive.Module(
                dir, null, false, false, 0, true, List.of(new HttpLive.Task(task, "test", "RUN", 0)));
    }

    private List<String> messages() {
        return notes.stream().map(n -> String.valueOf(n.get("message"))).toList();
    }

    @Test
    void a_parked_call_is_told_when_the_hold_starts_while_it_lasts_and_when_it_resumes() {
        McpCallProgress progress = progress("t1");

        progress.tick(0);
        MemoryHoldsSeam.hold(JID, 512L << 20, 128L << 20);
        progress.tick(100);
        progress.tick(2_000);
        progress.tick(5_200);
        MemoryHoldsSeam.clear(JID);
        progress.tick(6_000);

        assertThat(notes).allSatisfy(n -> assertThat(n).containsEntry("progressToken", "t1"));
        assertThat(messages()).hasSize(3);
        assertThat(messages().get(0)).startsWith(McpHolds.WAITING).contains("need 512 MiB");
        assertThat(messages().get(1)).startsWith("On hold ");
        assertThat(messages().get(2)).startsWith("Resumed");
    }

    @Test
    void a_running_job_reports_its_step_and_percent_on_change_and_a_heartbeat_when_quiet() {
        McpCallProgress progress = progress("t1");
        live.put(JID, run(JID, 40, module("/ws/api", "compileJava")));

        progress.tick(0);
        progress.tick(500);
        assertThat(notes).as("a quick call sends nothing").isEmpty();
        progress.tick(1_000);
        progress.tick(1_500);
        live.put(JID, run(JID, 55.5, module("/ws/api", "test")));
        progress.tick(2_000);
        progress.tick(3_000);
        progress.tick(6_900);
        progress.tick(8_000);

        assertThat(messages()).containsExactly("api: compileJava · 40%", "api: test · 56%", "api: test · 56%");
        assertThat(notes).allSatisfy(n -> assertThat(n).containsEntry("total", 100));
    }

    @Test
    void progress_rises_on_every_note_across_a_hold_and_from_one_job_to_the_next() {
        McpCallProgress progress = progress("t1");
        live.put(JID, run(JID, 70));
        progress.tick(0);
        progress.tick(1_000);
        MemoryHoldsSeam.hold(JID, 1, 1);
        progress.tick(1_100);
        MemoryHoldsSeam.clear(JID);
        progress.tick(1_200);
        long next = JID + 1;
        live.put(next, run(next, 10));
        progress.follow(next);
        progress.tick(2_200);

        assertThat(notes).hasSize(4);
        double last = -1;
        for (Map<String, Object> n : notes) {
            double p = ((Number) Objects.requireNonNull(n.get("progress"))).doubleValue();
            assertThat(p).as("progress increases on every notification").isGreaterThan(last);
            last = p;
        }
    }

    @Test
    void a_numeric_token_goes_back_as_the_number_the_client_sent() {
        McpCallProgress progress = new McpCallProgress(7L, out, JID, live::get);
        live.put(JID, run(JID, 10));
        progress.tick(0);
        progress.tick(1_000);
        assertThat(notes).singleElement().satisfies(n -> assertThat(n).containsEntry("progressToken", 7L));
    }

    @Test
    void a_call_without_a_progress_token_sends_nothing() {
        McpCallProgress progress = new McpCallProgress(null, out, JID, live::get);
        live.put(JID, run(JID, 10));
        MemoryHoldsSeam.hold(JID, 1, 1);
        progress.tick(0);
        progress.tick(5_000);
        assertThat(notes).isEmpty();
    }
}

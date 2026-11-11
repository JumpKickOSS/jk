// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.JsonOut;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpEventsTest {

    @Test
    void no_subscribers_means_no_work_signal() {
        HttpEvents hub = new HttpEvents();
        assertThat(hub.hasSubscribers()).isFalse();
        try (HttpEvents.Subscription s = hub.subscribe()) {
            assertThat(hub.hasSubscribers()).isTrue();
        }
        assertThat(hub.hasSubscribers()).isFalse(); // close unregisters
    }

    @Test
    void detached_subscription_misses_broadcasts_until_attach_and_keeps_hydrate_order() throws Exception {
        HttpEvents hub = new HttpEvents();
        HttpEvents.Subscription s = hub.subscribeDetached(HttpEvents.FrameStyle.DASHBOARD, null);
        try {
            assertThat(hub.hasSubscribers()).isFalse();
            hub.publish("task-finish", JsonOut.object().put("jid", 1)); // pre-attach broadcast: not queued
            hub.deliverTo(s, "run-snapshot", JsonOut.object().put("jid", 1)); // connect hydrate
            hub.attach(s);
            assertThat(hub.hasSubscribers()).isTrue();
            hub.publish("task-start", JsonOut.object().put("jid", 1));
            assertThat(s.next(1000)).contains("event: run-snapshot");
            assertThat(s.next(1000)).contains("event: task-start");
        } finally {
            s.close();
        }
    }

    @Test
    void full_queue_keeps_the_freshest_low_priority_frame() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription s = hub.subscribe()) {
            // Flood with low-priority output far past capacity; the SURVIVORS must be the newest
            // frames (evict-oldest), not an hours-stale prefix.
            int flood = 600; // > QUEUE_CAPACITY
            for (int i = 0; i < flood; i++) {
                hub.publish("output", JsonOut.object().put("jid", 1).put("line", "l-" + i));
            }
            String last = null;
            for (String f; (f = s.next(10)) != null; ) last = f;
            assertThat(last).contains("l-" + (flood - 1));
        }
    }

    @Test
    void attach_after_close_never_registers() {
        HttpEvents hub = new HttpEvents();
        HttpEvents.Subscription s = hub.subscribeDetached(HttpEvents.FrameStyle.DASHBOARD, null);
        s.close();
        hub.attach(s);
        assertThat(hub.hasSubscribers()).isFalse(); // no zombie keeps the sampler alive
    }

    @Test
    void frames_carry_monotonic_ids_and_sse_framing() throws Exception {
        HttpEvents hub = new HttpEvents(0);
        try (HttpEvents.Subscription s = hub.subscribe()) {
            hub.publish("request-start", JsonOut.object().put("jid", 1));
            hub.publish("request-finish", JsonOut.object().put("jid", 1));
            assertThat(s.next(1000)).isEqualTo("id: 1\nevent: request-start\ndata: {\"jid\":1}\n\n");
            assertThat(s.next(1000)).isEqualTo("id: 2\nevent: request-finish\ndata: {\"jid\":1}\n\n");
        }
    }

    @Test
    void every_subscriber_gets_every_frame() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription a = hub.subscribe();
                HttpEvents.Subscription b = hub.subscribe()) {
            hub.publish("module-start", JsonOut.object().put("dir", "/w/m1"));
            assertThat(a.next(1000)).contains("event: module-start");
            assertThat(b.next(1000)).contains("event: module-start");
        }
    }

    @Test
    void slow_subscriber_sheds_oldest_frames_never_blocks_the_publisher() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription s = hub.subscribe()) {
            // Critical frames fill the queue first so later low-priority output is dropped rather
            // than evicting progress (new priority policy). Use critical types for the capacity test.
            int published = HttpEvents.QUEUE_CAPACITY + 10;
            for (int i = 1; i <= published; i++) {
                hub.publish("workspace-progress", JsonOut.object().put("n", i)); // never blocks
            }
            List<String> received = new ArrayList<>();
            String frame;
            while ((frame = s.next(10)) != null) received.add(frame);
            assertThat(received).hasSize(HttpEvents.QUEUE_CAPACITY); // the oldest 10 were shed
            assertThat(received.getLast()).contains("\"n\":" + published); // newest survives
            assertThat(received.getFirst()).doesNotContain("\"n\":1}"); // oldest didn't
        }
    }

    @Test
    void output_flood_does_not_evict_workspace_progress() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription s = hub.subscribe()) {
            hub.publish("workspace-progress", JsonOut.object().put("jid", 1).put("progress", 12.0));
            for (int i = 0; i < HttpEvents.QUEUE_CAPACITY * 2; i++) {
                hub.publish("output", JsonOut.object().put("jid", 1).put("line", "noise-" + i));
            }
            // Progress must still be the first frame; later output may be truncated.
            String first = s.next(1000);
            assertThat(first).contains("event: workspace-progress").contains("\"progress\":12");
            // Drain the rest — queue held progress + at most CAP-1 output (rest shed).
            int n = 0;
            while (s.next(10) != null) n++;
            assertThat(n).isBetween(1, HttpEvents.QUEUE_CAPACITY - 1);
        }
    }

    @Test
    void drainTo_batches_without_blocking() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription s = hub.subscribe()) {
            for (int i = 0; i < 5; i++) {
                hub.publish("task-start", JsonOut.object().put("n", i));
            }
            List<String> batch = new ArrayList<>();
            // first next() then drain rest
            batch.add(s.next(1000));
            int more = s.drainTo(batch, 10);
            assertThat(more).isEqualTo(4);
            assertThat(batch).hasSize(5);
            assertThat(s.next(10)).isNull();
        }
    }

    @Test
    void frames_published_after_close_are_dropped() throws Exception {
        HttpEvents hub = new HttpEvents();
        HttpEvents.Subscription s = hub.subscribe();
        s.close();
        hub.publish("request-start", JsonOut.object().put("jid", 1));
        assertThat(s.next(10)).isNull();
    }

    @Test
    void mcp_style_frames_are_jsonrpc_notifications() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription s = hub.subscribe(HttpEvents.FrameStyle.MCP)) {
            hub.publish(
                    "task-start",
                    JsonOut.object()
                            .put("schema", 1)
                            .put("type", "task-start")
                            .put("step", "compile")
                            .put("progress", 42.5));
            String frame = s.next(1000);
            assertThat(frame).contains("event: message");
            assertThat(frame).contains("notifications/jk/event");
            assertThat(frame).contains("\"event\":\"task-start\"");
            assertThat(frame).contains("\"step\":\"compile\"");
            // agents read aggregate percent without parsing TTY bars.
            assertThat(frame).contains("\"progress\":42.5");
            assertThat(frame).doesNotContain("progress_num");
            assertThat(frame).doesNotContain("progress_den");
        }
    }

    @Test
    void dashboard_and_mcp_subscribers_coexist() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription dash = hub.subscribe(HttpEvents.FrameStyle.DASHBOARD);
                HttpEvents.Subscription mcp = hub.subscribe(HttpEvents.FrameStyle.MCP)) {
            hub.publish("module-finish", JsonOut.object().put("coord", "a:b").put("success", true));
            assertThat(dash.next(1000)).contains("event: module-finish");
            assertThat(mcp.next(1000)).contains("notifications/jk/event");
        }
    }

    @Test
    void request_id_filter_drops_other_jobs() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription only2 = hub.subscribe(HttpEvents.FrameStyle.MCP, 2L);
                HttpEvents.Subscription all = hub.subscribe(HttpEvents.FrameStyle.MCP, null)) {
            hub.publish("task-start", JsonOut.object().put("jid", 1).put("step", "a"));
            hub.publish("task-start", JsonOut.object().put("jid", 2).put("step", "b"));
            assertThat(only2.next(1000)).contains("\"jid\":2");
            assertThat(only2.next(50)).isNull(); // job 1 never arrives
            assertThat(all.next(1000)).contains("\"jid\":1");
            assertThat(all.next(1000)).contains("\"jid\":2");
        }
    }

    @Test
    void extract_request_id_from_payload() {
        assertThat(HttpEvents.extractRequestId("{\"jid\":42,\"kind\":\"test\"}"))
                .isEqualTo(42L);
        assertThat(HttpEvents.extractRequestId("{\"step\":\"x\"}")).isNull();
        assertThat(HttpEvents.extractRequestId(null)).isNull();
    }

    /**
     * An agent's build tool call streams its job's events, filtered by jid: the run's downloads are
     * among them, so the agent sees a cold lock move, and another job's are not.
     */
    @Test
    void an_mcp_stream_for_one_job_carries_its_downloads_and_no_one_elses() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription mcp = hub.subscribe(HttpEvents.FrameStyle.MCP, 7L)) {
            hub.publish("downloads", JsonOut.object().put("jid", 8).put("files", 1));
            hub.publish(
                    "downloads", JsonOut.object().put("jid", 7).put("files", 3).put("planned", 9));

            String frame = mcp.next(1000);
            assertThat(frame)
                    .contains("notifications/jk/event")
                    .contains("downloads")
                    .contains("\"files\":3");
            assertThat(mcp.next(10)).isNull();
        }
    }

    /** A dashboard reconnecting with the last id it saw gets the frames after it, then live ones. */
    @Test
    void a_reconnect_naming_its_last_id_replays_what_it_missed() throws Exception {
        HttpEvents hub = new HttpEvents(0);
        hub.publish("request-start", JsonOut.object().put("jid", 1));
        hub.publish("error", JsonOut.object().put("jid", 1).put("message", "boom"));
        hub.publish("output", JsonOut.object().put("jid", 1).put("line", "x"));
        try (HttpEvents.Subscription s = hub.subscribeDetached(HttpEvents.FrameStyle.DASHBOARD, null)) {
            hub.replayAfter(s, 1);
            assertThat(hub.attachReplaying(s)).isTrue();
            assertThat(s.next(1000)).startsWith("id: 2\nevent: error\n").contains("boom");
            assertThat(s.next(1000)).startsWith("id: 3\nevent: output\n");
            hub.publish("request-finish", JsonOut.object().put("jid", 1));
            assertThat(s.next(1000)).startsWith("id: 4\nevent: request-finish\n");
            assertThat(s.next(10)).isNull();
        }
    }

    /** More missed frames than a live queue holds are all replayed: none of them is shed. */
    @Test
    void a_replay_longer_than_the_queue_loses_nothing() throws Exception {
        HttpEvents hub = new HttpEvents(0);
        int n = HttpEvents.QUEUE_CAPACITY + 50;
        for (int i = 0; i < n; i++)
            hub.publish("error", JsonOut.object().put("jid", 1).put("i", i));
        try (HttpEvents.Subscription s = hub.subscribeDetached(HttpEvents.FrameStyle.DASHBOARD, null)) {
            hub.replayAfter(s, 0);
            assertThat(hub.attachReplaying(s)).isTrue();
            List<String> got = new ArrayList<>();
            s.drainTo(got, n + 10);
            assertThat(got).hasSize(n);
        }
    }

    /** An id older than the oldest kept frame, or from an earlier engine, gets no replay and stays detached. */
    @Test
    void a_reconnect_past_what_is_kept_or_from_another_engine_is_not_replayed() throws Exception {
        HttpEvents hub = new HttpEvents(0);
        for (int i = 0; i < HttpEvents.REPLAY_FRAMES + 5; i++)
            hub.publish("output", JsonOut.object().put("i", i));
        try (HttpEvents.Subscription s = hub.subscribeDetached(HttpEvents.FrameStyle.DASHBOARD, null)) {
            hub.replayAfter(s, 2);
            assertThat(hub.attachReplaying(s)).isFalse();
            assertThat(hub.hasSubscribers()).isFalse();
        }
        HttpEvents later = new HttpEvents(1_000_000);
        later.publish("output", JsonOut.object().put("i", 0));
        try (HttpEvents.Subscription s = later.subscribeDetached(HttpEvents.FrameStyle.DASHBOARD, null)) {
            later.replayAfter(s, 5);
            assertThat(later.attachReplaying(s)).isFalse();
        }
    }
}

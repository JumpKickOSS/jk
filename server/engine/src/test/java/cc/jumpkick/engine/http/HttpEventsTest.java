// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
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
    void frames_carry_monotonic_ids_and_sse_framing() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription s = hub.subscribe()) {
            hub.publish("request-start", JsonOut.object().put("requestId", 1));
            hub.publish("request-finish", JsonOut.object().put("requestId", 1));
            assertThat(s.next(1000)).isEqualTo("id: 1\nevent: request-start\ndata: {\"requestId\":1}\n\n");
            assertThat(s.next(1000)).isEqualTo("id: 2\nevent: request-finish\ndata: {\"requestId\":1}\n\n");
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
            int published = HttpEvents.QUEUE_CAPACITY + 10;
            for (int i = 1; i <= published; i++) {
                hub.publish("output", JsonOut.object().put("n", i)); // never blocks
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
    void frames_published_after_close_are_dropped() throws Exception {
        HttpEvents hub = new HttpEvents();
        HttpEvents.Subscription s = hub.subscribe();
        s.close();
        hub.publish("request-start", JsonOut.object().put("requestId", 1));
        assertThat(s.next(10)).isNull();
    }

    @Test
    void mcp_style_frames_are_jsonrpc_notifications() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription s = hub.subscribe(HttpEvents.FrameStyle.MCP)) {
            hub.publish(
                    "step-start",
                    JsonOut.object()
                            .put("schema", 1)
                            .put("type", "step-start")
                            .put("step", "compile")
                            .put("progress", 42.5));
            String frame = s.next(1000);
            assertThat(frame).contains("event: message");
            assertThat(frame).contains("notifications/jk/event");
            assertThat(frame).contains("\"event\":\"step-start\"");
            assertThat(frame).contains("\"step\":\"compile\"");
            // JK-1119: agents read aggregate percent without parsing TTY bars.
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
            hub.publish("step-start", JsonOut.object().put("requestId", 1).put("step", "a"));
            hub.publish("step-start", JsonOut.object().put("requestId", 2).put("step", "b"));
            assertThat(only2.next(1000)).contains("\"requestId\":2");
            assertThat(only2.next(50)).isNull(); // job 1 never arrives
            assertThat(all.next(1000)).contains("\"requestId\":1");
            assertThat(all.next(1000)).contains("\"requestId\":2");
        }
    }

    @Test
    void extract_request_id_from_payload() {
        assertThat(HttpEvents.extractRequestId("{\"requestId\":42,\"kind\":\"test\"}"))
                .isEqualTo(42L);
        assertThat(HttpEvents.extractRequestId("{\"step\":\"x\"}")).isNull();
        assertThat(HttpEvents.extractRequestId(null)).isNull();
    }
}

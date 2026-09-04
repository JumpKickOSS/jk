// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.JsonOut;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The event surfaces over real HTTP: the SSE stream's frames and heartbeats, {@code POST
 * /api/build}, and the live-stream count that vetoes an orphaned engine exiting under an open
 * dashboard tab. Fixture in {@link HttpEngineServerHarness}.
 */
@Tag("integration")
class HttpEventStreamTest extends HttpEngineServerHarness {

    // ---- /api/events (SSE) ----------------------------------------------------------------------

    @Test
    void events_stream_delivers_published_frames_in_sse_format() throws Exception {
        var lines = openEvents("");
        assertThat(nextLine(lines)).isEqualTo(": connected");
        assertThat(nextLine(lines)).isEqualTo(""); // blank line terminating the connected comment
        // Connect hydrate may publish status/cache before our frame.
        events.publish("request-start", JsonOut.object().put("jid", 1).put("kind", "build"));
        assertThat(awaitSseEvent(lines, "request-start")).isEqualTo("data: {\"jid\":1,\"kind\":\"build\"}");
    }

    @Test
    void quiet_events_stream_heartbeats() throws Exception {
        server.sse().heartbeatMillis(50);
        var lines = openEvents("");
        assertThat(nextLine(lines)).isEqualTo(": connected");
        assertThat(nextLine(lines)).isEqualTo("");
        // Hydrate status/cache frames may precede the first quiet-stream heartbeat.
        assertThat(awaitSseComment(lines, ": heartbeat")).isEqualTo(": heartbeat");
    }

    @Test
    void events_accepts_access_token_query_param_on_non_loopback_binds() throws Exception {
        JkHttpConfig config = httpConfig("0.0.0.0", 0, 16);
        HttpEngineServer lan = new HttpEngineServer(
                config,
                webRoot,
                stateDir.resolve("sse.http-token"),
                stateDir.resolve("sse.log"),
                "9.9.9-test",
                () -> SNAPSHOT,
                new HttpEvents(),
                stubJobs,
                testJournal(),
                List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            lan.start();
            String lanToken =
                    Files.readString(stateDir.resolve("sse.http-token")).trim();
            String lanUrl = lan.url();

            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "api/events")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401); // EventSource can't send headers...

            HttpResponse<Stream<String>> authorized = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "api/events?access_token=" + lanToken))
                            .build(),
                    HttpResponse.BodyHandlers.ofLines()); // ...so the query param is its way in
            assertThat(authorized.statusCode()).isEqualTo(200);
            authorized.body().close();
        } finally {
            lan.close();
        }
    }

    // ---- POST /api/build ------------------------------------------------------------------------

    @Test
    void build_trigger_acknowledges_with_request_id() throws Exception {
        HttpResponse<String> resp = postBuild("{\"dir\":\"/some/workspace\"}");
        assertThat(resp.statusCode()).isEqualTo(202);
        assertThat(resp.body()).contains("\"jid\":7").contains("\"events\":\"/api/events\"");
        assertThat(triggeredDirs).containsExactly("/some/workspace");
    }

    @Test
    void build_without_dir_is_400() throws Exception {
        assertThat(postBuild("{}").statusCode()).isEqualTo(400);
        assertThat(triggeredDirs).isEmpty();
    }

    @Test
    void build_of_an_unbuildable_dir_relays_the_trigger_error_as_400() throws Exception {
        HttpResponse<String> resp = postBuild("{\"dir\":\"/reject/me\"}");
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("no jk.toml in /reject/me");
    }

    // ---- live stream count: the veto on an orphaned engine exiting------------------

    @Test
    void no_attached_streams_means_none_are_counted() {
        assertThat(server.liveEventStreams()).isZero();
    }

    @Test
    void an_attached_dashboard_stream_is_counted() throws Exception {
        // Derived from the admission budget rather than a separate counter, so it cannot drift from what
        // actually holds a slot. Draining one permit stands in for one attached tab.
        server.admission().webSse().acquire(1);
        try {
            assertThat(server.liveEventStreams()).isEqualTo(1);
        } finally {
            server.admission().webSse().release(1);
        }
        assertThat(server.liveEventStreams()).isZero();
    }

    @Test
    void dashboard_and_mcp_streams_both_count() throws Exception {
        server.admission().webSse().acquire(2);
        server.admission().mcpSse().acquire(3);
        try {
            assertThat(server.liveEventStreams()).isEqualTo(5);
        } finally {
            server.admission().webSse().release(2);
            server.admission().mcpSse().release(3);
        }
    }

    @Test
    void a_fully_drained_budget_counts_every_slot_and_never_goes_negative() throws Exception {
        int web = server.admission().webSse().drainPermits();
        try {
            assertThat(server.liveEventStreams()).isGreaterThanOrEqualTo(web);
            assertThat(server.liveEventStreams()).isNotNegative();
        } finally {
            server.admission().webSse().release(web);
        }
    }

    @Test
    void a_real_open_stream_is_visible_as_attached() throws Exception {
        // The end-to-end version: an actual EventSource-style connection, not a drained permit. This is
        // the signal that stops an orphaned engine exiting under a developer's open dashboard tab.
        assertThat(server.liveEventStreams()).isZero();

        Iterator<String> lines = openEvents("");

        assertThat(nextLine(lines)).isNotNull(); // connected
        assertThat(server.liveEventStreams()).isEqualTo(1);
    }

    @Test
    void matchLiveRun_dir_fallback_applies_only_without_a_buildNumber() {
        // a stale running record with a real buildNumber that fails the strict match is a
        // DIFFERENT run (crashed-engine stub) — it must not rebind to the current run's stream.
        var run = new HttpLive.Run(42, 6, "build", "/w", "g:w", 0, Double.NaN, "j6");
        server.setLiveRunSupport(() -> List.of(run), null);

        assertThat(server.matchLiveRun(Map.of("dir", "/w", "buildNumber", 6L)))
                .isEqualTo(run); // strict (dir, buildNumber)
        assertThat(server.matchLiveRun(Map.of("id", "j6"))).isEqualTo(run); // journal id
        assertThat(server.matchLiveRun(Map.of("dir", "/w"))).isEqualTo(run); // legacy stub
        assertThat(server.matchLiveRun(Map.of("dir", "/w", "buildNumber", 5L)))
                .isNull(); // stale record, wrong build — no dir-only rebind
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.jsonl.Jsonl;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The {@code /api/*} admission contract: what {@code /api/status} reports, the token every route
 * requires, the engine-epoch header non-bootstrap routes require, the 404 for a disabled MCP or an
 * unknown endpoint, and the token file's own lifecycle. Fixture in {@link HttpEngineServerHarness}.
 */
@Tag("integration")
class HttpApiAuthTest extends HttpEngineServerHarness {

    @Test
    void api_status_reports_engine_vitals_with_token() throws Exception {
        HttpResponse<String> resp = get("/api/status");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("application/json; charset=utf-8");
        assertThat(resp.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(resp.body())
                .contains("\"version\":\"9.9.9-test\"")
                .contains("\"pid\":42")
                .contains("\"heapMaxBytes\":3000")
                .contains("\"rssBytes\":-1")
                .contains("\"cores\":8")
                .contains("\"totalMemoryBytes\":16000000000")
                .contains("\"availableMemoryBytes\":8000000000")
                .contains("\"systemCpuLoad\":0.18")
                .contains("\"systemLoadAverage\":1.2")
                .contains("\"engineEpoch\":\"9.9.9-test@1000\"")
                .contains("\"httpUrl\":\"" + baseUrl + "\"");
    }

    @Test
    void api_status_carries_config_and_pipeline_fields() throws Exception {
        String body = get("/api/status").body();
        assertThat(body)
                .contains("\"activeBuildPlans\":0")
                .contains("\"maxConcurrentRequests\":16")
                // Jsonl.quote, not hand-built quotes: a Windows path's backslashes are escaped in
                // the JSON the engine emits, so a raw path here only ever matches on POSIX.
                .contains("\"webRoot\":" + Jsonl.quote(String.valueOf(webRoot)));
    }

    @Test
    void api_status_reports_stream_budgets_and_mcp_state() throws Exception {
        String body = get("/api/status").body();
        assertThat(body)
                .contains("\"maxEventStreams\":16")
                .contains("\"mcpEnabled\":true")
                .contains("\"mcpMaxEventStreams\":16")
                .contains("\"mcpUrl\":\"" + baseUrl.replaceAll("/+$", "") + "/mcp\"");
    }

    @Test
    void disabled_mcp_is_404_while_web_surfaces_still_serve() throws Exception {
        Path noMcpToken = stateDir.resolve("nomcp.http-token");
        HttpEngineServer noMcp = new HttpEngineServer(
                httpConfig("127.0.0.1", 0, 16, new JkHttpConfig.Mcp(false, 16)),
                webRoot,
                noMcpToken,
                stateDir.resolve("nomcp.log"),
                "9.9.9-test",
                () -> SNAPSHOT,
                new HttpEvents(),
                stubJobs,
                testJournal(),
                List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            noMcp.start();
            String url = noMcp.url();
            String tok = Files.readString(noMcpToken).trim();

            // Every /mcp shape is 404, valid token or not: discovery GET, SSE GET, JSON-RPC POST.
            HttpResponse<String> discovery = client.send(
                    HttpRequest.newBuilder(URI.create(url + "mcp"))
                            .header("Authorization", "Bearer " + tok)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(discovery.statusCode()).isEqualTo(404);
            HttpResponse<String> sse = client.send(
                    HttpRequest.newBuilder(URI.create(url + "mcp"))
                            .header("Authorization", "Bearer " + tok)
                            .header("Accept", "text/event-stream")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(sse.statusCode()).isEqualTo(404);
            HttpResponse<String> post = client.send(
                    HttpRequest.newBuilder(URI.create(url + "mcp"))
                            .header("Authorization", "Bearer " + tok)
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(post.statusCode()).isEqualTo(404);

            // The web surfaces are unaffected; status reports the disable with a null mcpUrl.
            HttpResponse<String> status = client.send(
                    HttpRequest.newBuilder(URI.create(url + "api/status"))
                            .header("Authorization", "Bearer " + tok)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(status.statusCode()).isEqualTo(200);
            assertThat(status.body()).contains("\"mcpEnabled\":false").contains("\"mcpUrl\":null");
            HttpResponse<Stream<String>> events = client.send(
                    HttpRequest.newBuilder(URI.create(url + "api/events?access_token=" + tok))
                            .build(),
                    HttpResponse.BodyHandlers.ofLines());
            try {
                assertThat(events.statusCode()).isEqualTo(200);
                assertThat(nextLine(events.body().iterator())).isEqualTo(": connected");
            } finally {
                events.body().close();
            }
        } finally {
            noMcp.close();
        }
    }

    @Test
    void api_metrics_requires_the_token_even_on_loopback() throws Exception {
        // Rows carry every project dir and coordinate ever built — same class as /api/fs.
        HttpResponse<String> noToken = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/metrics")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noToken.statusCode()).isEqualTo(401);
        assertThat(get("/api/metrics").statusCode()).isEqualTo(200);
    }

    @Test
    void api_requires_matching_engine_epoch_except_status_and_events() throws Exception {
        // Bootstrap status has no epoch header requirement (token still required).
        assertThat(get("/api/status").statusCode()).isEqualTo(200);
        // Token without epoch on other /api/* → 409 (fail-closed).
        HttpResponse<String> missing = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/history"))
                        .header("Authorization", "Bearer " + token())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(409);
        assertThat(missing.body()).contains("engine-epoch-mismatch").contains(SNAPSHOT.engineEpoch());
        // Wrong epoch → 409 with current generation.
        HttpResponse<String> wrong = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/history"))
                        .header("Authorization", "Bearer " + token())
                        .header("X-Jk-Engine-Epoch", "stale-generation")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(wrong.statusCode()).isEqualTo(409);
        assertThat(wrong.body()).contains("\"engineEpoch\":\"" + SNAPSHOT.engineEpoch() + "\"");
        // Matching epoch + token succeeds.
        assertThat(get("/api/history").statusCode()).isEqualTo(200);
    }

    @Test
    void api_config_requires_the_token_even_on_loopback() throws Exception {
        // Payload names the owner's config path and verbatim values (templates.official can embed
        // credentials) — same class as /api/projects/defaults.
        HttpResponse<String> noToken = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/config")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noToken.statusCode()).isEqualTo(401);
        assertThat(get("/api/config").statusCode()).isEqualTo(200);
    }

    @Test
    void api_all_routes_require_token_including_history_status_and_cache() throws Exception {
        // Fail closed: bare browser / curl without a bearer must not see engine data.
        for (String path : List.of("api/status", "api/cache", "api/history", "api/events")) {
            HttpResponse<String> noToken = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(noToken.statusCode()).as(path).isEqualTo(401);
        }
        assertThat(get("/api/history").statusCode()).isEqualTo(200);
        assertThat(get("/api/status").statusCode()).isEqualTo(200);
    }

    @Test
    void unknown_api_endpoint_is_404() throws Exception {
        assertThat(get("/api/no-such-thing").statusCode()).isEqualTo(404);
    }

    @Test
    void mutation_without_token_is_401_even_on_loopback() throws Exception {
        // The CSRF defense: a hostile page can fire a no-preflight POST at 127.0.0.1, but can't
        // attach an Authorization header cross-origin.
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/status"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(resp.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void mutation_with_wrong_token_is_401() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/status"))
                        .header("Authorization", "Bearer not-the-token")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void mutation_with_valid_token_reaches_the_router() throws Exception {
        // No mutating routes exist yet, so a correctly authorized POST to a GET-only path is the
        // router's 405 — proving the token (and epoch) were accepted (401/409 would mean not).
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/status"))
                        .header("Authorization", "Bearer " + token())
                        .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(405);
        assertThat(resp.headers().firstValue("Allow")).contains("GET");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC}) // Windows ACLs: getPosixFilePermissions is unsupported
    void token_file_is_owner_only() throws IOException {
        assertThat(token()).isNotEmpty();
        assertThat(Files.getPosixFilePermissions(tokenFile))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void adopts_an_existing_token_file_across_restarts() throws Exception {
        // The token minted by the @BeforeEach engine must survive that engine stopping and a fresh
        // one starting against the same token file — that's what keeps an open dashboard tab valid.
        String original = token();
        assertThat(original).isNotEmpty();
        server.close();

        HttpEngineServer restarted = new HttpEngineServer(
                httpConfig("127.0.0.1", 0, 16),
                webRoot,
                tokenFile,
                logFile,
                "9.9.9-test",
                () -> SNAPSHOT,
                events,
                stubJobs,
                testJournal(),
                List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            restarted.start();
            assertThat(token()).isEqualTo(original); // file unchanged
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(restarted.url() + "api/fs?dir=" + encDir(stateDir)))
                            .header("Authorization", "Bearer " + original)
                            .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200); // the pre-restart token is still accepted
        } finally {
            restarted.close();
        }
    }

    @Test
    void mints_a_fresh_token_when_the_file_is_blank() throws Exception {
        // A truncated/blank token file (e.g. after `jk engine rotate-token` deleted it, or a
        // half-written file) is not usable — start must mint rather than serve with an empty secret.
        String original = token();
        server.close();
        Files.writeString(tokenFile, "   \n");

        HttpEngineServer restarted = new HttpEngineServer(
                httpConfig("127.0.0.1", 0, 16),
                webRoot,
                tokenFile,
                logFile,
                "9.9.9-test",
                () -> SNAPSHOT,
                events,
                stubJobs,
                testJournal(),
                List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            restarted.start();
            assertThat(token()).isNotEmpty().isNotEqualTo(original);
        } finally {
            restarted.close();
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import cc.jumpkick.config.JkHttpConfig;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives a real {@link HttpEngineServer} bound to an OS-assigned loopback port with the JDK's
 * {@link HttpClient} — plus a raw socket where the client won't cooperate (forged {@code Host}
 * headers, literal {@code..} request targets).
 */
@Tag("integration")
class HttpEngineServerTest {

    private static final StatusSnapshot SNAPSHOT = new StatusSnapshot(
            "9.9.9-test",
            42,
            1_000,
            1,
            0,
            1_000,
            2_000,
            3_000,
            -1,
            -1,
            8,
            16_000_000_000L,
            8_000_000_000L,
            0.18,
            1,
            0);

    @TempDir
    Path webRoot;

    @TempDir
    Path stateDir;

    private Path tokenFile;
    private Path logFile;
    private HttpEvents events;
    private final java.util.List<String> triggeredDirs = new java.util.ArrayList<>();

    /** Rows served by {@code GET /api/metrics} — tests seed this list directly. */
    private final java.util.List<cc.jumpkick.runtime.BuildMetrics.Entry> metricsRows = new java.util.ArrayList<>();

    /** The snapshot served by {@code GET /api/cache} — tests reassign the field directly. */
    private static final CacheSnapshot EMPTY_CACHE =
            new CacheSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    private CacheSnapshot cacheSnapshot = EMPTY_CACHE;
    private HttpEngineServer server;
    private HttpClient client;
    private String baseUrl;
    private int port;

    /** Config with the default stream budgets and MCP on — most tests only vary host/port/RPC cap. */
    private JkHttpConfig httpConfig(String host, int port, int maxConcurrentRequests) {
        return httpConfig(host, port, maxConcurrentRequests, JkHttpConfig.Mcp.DEFAULTS);
    }

    private JkHttpConfig httpConfig(String host, int port, int maxConcurrentRequests, JkHttpConfig.Mcp mcp) {
        return new JkHttpConfig(host, port, maxConcurrentRequests, 16, webRoot.toString(), mcp);
    }

    /** A journal rooted under the test's temp state dir — endpoints exist; content is per-test. */
    private cc.jumpkick.engine.journal.BuildJournal testJournal() {
        return new cc.jumpkick.engine.journal.BuildJournal(
                stateDir.resolve("builds").resolve("journal"));
    }

    /** Stub {@link EngineHttpJobs}: records the dir, returns a fixed id, rejects "reject me". */
    private final EngineHttpJobs stubJobs = new EngineHttpJobs() {
        @Override
        public long triggerBuild(String dir) {
            if (dir.contains("reject")) throw new IllegalArgumentException("no jk.toml in " + dir);
            triggeredDirs.add(dir);
            return 7;
        }

        @Override
        public long triggerTest(String dir) {
            return triggerBuild(dir);
        }

        @Override
        public long triggerLock(String dir) {
            return triggerBuild(dir);
        }

        @Override
        public boolean cancel(long requestId) {
            return requestId == 7L;
        }
    };

    @BeforeEach
    void start() throws IOException {
        Files.writeString(webRoot.resolve("hello.txt"), "hi from disk");
        Files.writeString(webRoot.resolve("index.html"), "<html>dash</html>");
        Files.writeString(webRoot.resolve("shared.txt"), "from disk");
        // The token file lives OUTSIDE web-root (a token inside it would be served as content).
        tokenFile = stateDir.resolve("e2e.http-token");
        logFile = stateDir.resolve("e2e.log");
        Files.writeString(logFile, "jk engine: listening\njk engine: http listening\nline three\n");
        events = new HttpEvents();
        JkHttpConfig config = httpConfig("127.0.0.1", 0, 16);
        server = new HttpEngineServer(
                config,
                webRoot,
                tokenFile,
                logFile,
                "9.9.9-test",
                () -> SNAPSHOT,
                events,
                stubJobs,
                testJournal(),
                () -> metricsRows,
                () -> cacheSnapshot,
                null);
        server.start();
        baseUrl = server.url();
        port = Integer.parseInt(baseUrl.replaceAll(".*:(\\d+)/$", "$1"));
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private String token() throws IOException {
        return Files.readString(tokenFile).trim();
    }

    private HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path.substring(1)));
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A raw HTTP/1.1 exchange, for requests {@link HttpClient} refuses to send. */
    private String raw(String target, String hostHeader) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String request = "GET " + target + " HTTP/1.1\r\n"
                    + (hostHeader != null ? "Host: " + hostHeader + "\r\n" : "")
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(UTF_8));
            return new String(socket.getInputStream().readAllBytes(), UTF_8);
        }
    }

    @Test
    void url_reports_the_bound_loopback_address() {
        assertThat(baseUrl).startsWith("http://127.0.0.1:").endsWith("/");
        assertThat(port).isGreaterThan(0);
    }

    @Test
    void a_symlink_under_web_root_is_not_served() throws Exception {
        // Static content is deliberately never token-gated, so a link planted in web-root (builds
        // may write there) must not become an unauthenticated read of anything outside it (JK-1487).
        Path secret = stateDir.resolve("outside-secret.txt");
        Files.writeString(secret, "TOP SECRET");
        try {
            Files.createSymbolicLink(webRoot.resolve("leak.txt"), secret);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            return; // filesystem without symlink support — nothing to prove here
        }
        HttpResponse<String> resp = get("/leak.txt");
        assertThat(resp.statusCode()).isNotEqualTo(200);
        assertThat(resp.body()).doesNotContain("TOP SECRET");
    }

    @Test
    void serves_disk_content_with_revalidation_headers() throws Exception {
        HttpResponse<String> resp = get("/hello.txt");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("hi from disk");
        assertThat(resp.headers().firstValue("Content-Type")).contains("text/plain; charset=utf-8");
        assertThat(resp.headers().firstValue("Cache-Control")).contains("no-cache");
        assertThat(resp.headers().firstValue("Last-Modified")).isPresent();
    }

    @Test
    void serves_index_html_for_directory_requests() throws Exception {
        HttpResponse<String> resp = get("/");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("<html>dash</html>");
        assertThat(resp.headers().firstValue("Content-Type")).contains("text/html; charset=utf-8");
    }

    @Test
    void disk_overrides_classpath() throws Exception {
        // shared.txt exists in both this test's web-root and the test classpath's /web.
        assertThat(get("/shared.txt").body()).isEqualTo("from disk");
    }

    @Test
    void ships_the_dashboard_spa_on_the_classpath() throws Exception {
        // The real SPA (clients/web/src/main/resources/web) rides the same classpath fallback the
        // test resources exercise — a bare [http] table gives a working dashboard with no file copying.
        assertThat(get("/app.js").body()).contains("Vue.createApp");
        assertThat(get("/fold.js").body()).contains("export function foldEvent");
        assertThat(get("/api.js").body()).contains("bootstrapToken");
        assertThat(get("/jk-logo.svg").headers().firstValue("Content-Type")).contains("image/svg+xml");
        assertThat(get("/jumpkick-logo.webp").headers().firstValue("Content-Type"))
                .contains("image/webp");
        HttpResponse<String> css = get("/style.css");
        assertThat(css.headers().firstValue("Content-Type")).contains("text/css; charset=utf-8");
        // Vue rides the CDN, version-pinned and integrity-locked (docs/webclient.md) — the shell
        // must carry the pin + SRI, and the CSP must allow exactly that one external origin.
        // (Read from the classpath: this test's web-root shadows /index.html with its own.)
        String shell;
        try (var in = getClass().getResourceAsStream("/web/index.html")) {
            shell = new String(in.readAllBytes(), UTF_8);
        }
        assertThat(shell).contains("https://unpkg.com/vue@3.5.39/dist/vue.global.prod.js");
        assertThat(shell).contains("integrity=\"sha384-");
        assertThat(shell).contains("crossorigin=\"anonymous\"");
        HttpResponse<String> js = get("/app.js");
        assertThat(js.headers().firstValue("Content-Security-Policy"))
                .contains("default-src 'self'; script-src 'self' 'unsafe-eval' https://unpkg.com; "
                        + "style-src 'self' https://fonts.googleapis.com; "
                        + "font-src https://fonts.gstatic.com");
    }

    @Test
    void falls_back_to_classpath_with_version_etag() throws Exception {
        HttpResponse<String> resp = get("/classpath-only.txt");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("from classpath\n");
        assertThat(resp.headers().firstValue("ETag")).contains("\"jk-9.9.9-test\"");
        assertThat(resp.headers().firstValue("Cache-Control")).contains("max-age=3600");
    }

    @Test
    void snapshot_versions_revalidate_classpath_assets_every_load() throws Exception {
        // A -SNAPSHOT jar swap doesn't move the version-derived ETag, so snapshot builds must not
        // let the browser cache classpath assets — otherwise an upgraded engine serves last jar's
        // dashboard for up to an hour.
        // Version string must end with -SNAPSHOT so StaticContent sets no-cache (release pins use
        // max-age + ETag). The status supplier's own version field is unrelated.
        HttpEngineServer snapshot = new HttpEngineServer(
                httpConfig("127.0.0.1", 0, 16),
                webRoot,
                stateDir.resolve("snap.http-token"),
                stateDir.resolve("snap.log"),
                "0.11.0-SNAPSHOT",
                () -> SNAPSHOT,
                new HttpEvents(),
                stubJobs,
                testJournal(),
                java.util.List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            snapshot.start();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(snapshot.url() + "classpath-only.txt"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Cache-Control")).contains("no-cache");
            assertThat(resp.headers().firstValue("ETag")).isEmpty();
        } finally {
            snapshot.close();
        }
    }

    @Test
    void matching_etag_yields_304() throws Exception {
        HttpResponse<String> resp = get("/classpath-only.txt", "If-None-Match", "\"jk-9.9.9-test\"");
        assertThat(resp.statusCode()).isEqualTo(304);
        assertThat(resp.body()).isEmpty();
    }

    @Test
    void if_modified_since_yields_304_for_unchanged_disk_file() throws Exception {
        String lastModified =
                get("/hello.txt").headers().firstValue("Last-Modified").orElseThrow();
        HttpResponse<String> resp = get("/hello.txt", "If-Modified-Since", lastModified);
        assertThat(resp.statusCode()).isEqualTo(304);
        assertThat(resp.body()).isEmpty();
    }

    @Test
    void missing_content_is_404() throws Exception {
        assertThat(get("/no-such-file.txt").statusCode()).isEqualTo(404);
    }

    @Test
    void empty_disk_file_serves_as_empty_200() throws Exception {
        Files.writeString(webRoot.resolve("empty.json"), "");
        HttpResponse<String> resp = get("/empty.json");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEmpty();
    }

    @Test
    void growing_file_serves_current_bytes_on_each_fetch() throws Exception {
        Path log = webRoot.resolve("build-log.json");
        Files.writeString(log, "{\"lines\":1}");
        assertThat(get("/build-log.json").body()).isEqualTo("{\"lines\":1}");
        Files.writeString(log, "{\"lines\":1}{\"lines\":2}");
        assertThat(get("/build-log.json").body()).isEqualTo("{\"lines\":1}{\"lines\":2}");
    }

    @Test
    void head_reports_length_without_body() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "hello.txt"))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Length")).contains(String.valueOf("hi from disk".length()));
        assertThat(resp.body()).isEmpty();
    }

    @Test
    void mutating_methods_are_405_on_static_content() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "hello.txt"))
                        .POST(HttpRequest.BodyPublishers.ofString("x"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(405);
        assertThat(resp.headers().firstValue("Allow")).contains("GET, HEAD");
    }

    @Test
    void traversal_attempts_cannot_escape_the_root() throws Exception {
        // A secret outside web-root that a traversal would reach if the gate leaked.
        Files.writeString(webRoot.resolveSibling("secret.txt"), "top secret");
        // Encoded dots arrive decoded as ".." and are rejected by the segment gate.
        assertThat(get("/%2e%2e/secret.txt").statusCode()).isEqualTo(404);
        assertThat(get("/sub/%2e%2e/%2e%2e/secret.txt").statusCode()).isEqualTo(404);
        // A literal ".." request target (HttpClient normalizes these away; send raw). The JDK
        // server may reject it before our handler runs — any non-200 without the payload is a pass.
        String rawResponse = raw("/../secret.txt", "127.0.0.1:" + port);
        assertThat(rawResponse).doesNotContain("top secret");
        assertThat(rawResponse).doesNotContain("HTTP/1.1 200");
    }

    @Test
    void forged_host_header_is_421() throws Exception {
        String response = raw("/hello.txt", "evil.example.com");
        assertThat(response).startsWith("HTTP/1.1 421");
        assertThat(response).doesNotContain("hi from disk");
    }

    @Test
    void legitimate_host_forms_are_accepted() throws Exception {
        assertThat(raw("/hello.txt", "127.0.0.1:" + port)).contains("hi from disk");
        assertThat(raw("/hello.txt", "localhost:" + port)).contains("hi from disk");
    }

    @Test
    void host_with_wrong_port_is_421() throws Exception {
        assertThat(raw("/hello.txt", "127.0.0.1:1")).startsWith("HTTP/1.1 421");
    }

    @Test
    void api_status_reports_engine_vitals_without_a_token_on_loopback() throws Exception {
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
                .contains("\"freeMemoryBytes\":8000000000")
                .contains("\"systemCpuLoad\":0.18")
                .contains("\"httpUrl\":\"" + baseUrl + "\"");
    }

    @Test
    void api_status_carries_config_and_pipeline_fields() throws Exception {
        String body = get("/api/status").body();
        assertThat(body)
                .contains("\"activePipelines\":0")
                .contains("\"maxConcurrentRequests\":16")
                .contains("\"webRoot\":\"" + webRoot + "\"");
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
                java.util.List::of,
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
                    HttpRequest.newBuilder(URI.create(url + "api/status")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(status.statusCode()).isEqualTo(200);
            assertThat(status.body()).contains("\"mcpEnabled\":false").contains("\"mcpUrl\":null");
            HttpResponse<java.util.stream.Stream<String>> events = client.send(
                    HttpRequest.newBuilder(URI.create(url + "api/events")).build(),
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
        // Rows carry every project dir and coordinate ever built — same class as /api/fs (JK-1466).
        assertThat(get("/api/metrics").statusCode()).isEqualTo(401);
    }

    @Test
    void api_history_and_project_require_the_token_even_on_loopback() throws Exception {
        // Diagnostics carry source excerpts and absolute paths; /api/project is a path oracle.
        assertThat(get("/api/history").statusCode()).isEqualTo(401);
        assertThat(get("/api/history/artifact?id=1&name=diagnostics.txt").statusCode())
                .isEqualTo(401);
        assertThat(get("/api/project?dir=" + stateDir).statusCode()).isEqualTo(401);
    }

    @Test
    void api_metrics_reports_aggregate_rows_with_the_token() throws Exception {
        var ok = new cc.jumpkick.runtime.BuildMetrics.Stats(3, 6000, 1000, 3000);
        var empty = cc.jumpkick.runtime.BuildMetrics.Stats.EMPTY;
        metricsRows.add(new cc.jumpkick.runtime.BuildMetrics.Entry("build", "", null, null, ok, empty, empty, 5L));
        metricsRows.add(new cc.jumpkick.runtime.BuildMetrics.Entry("build", "/p", "g:n", null, ok, empty, empty, 5L));
        metricsRows.add(
                new cc.jumpkick.runtime.BuildMetrics.Entry(null, "/other", null, "compile-java", ok, empty, empty, 5L));

        HttpResponse<String> resp = get("/api/metrics", "Authorization", "Bearer " + token());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("application/json; charset=utf-8");
        assertThat(resp.body())
                .contains("\"scope\":\"global\"")
                .contains("\"scope\":\"project\"")
                .contains("\"scope\":\"project/step\"")
                .contains("\"okCount\":3")
                .contains("\"okAvgMillis\":2000")
                .contains("\"coord\":\"g:n\"");

        // ?dir= keeps the global tiers but drops other projects' rows.
        String filtered =
                get("/api/metrics?dir=/p", "Authorization", "Bearer " + token()).body();
        assertThat(filtered).contains("\"scope\":\"global\"").contains("\"dir\":\"/p\"");
        assertThat(filtered).doesNotContain("/other");
    }

    @Test
    void api_metrics_is_an_empty_array_when_nothing_has_been_recorded() throws Exception {
        assertThat(get("/api/metrics", "Authorization", "Bearer " + token()).body())
                .isEqualTo("[]");
    }

    @Test
    void api_cache_reports_the_cache_breakdown_without_a_token_on_loopback() throws Exception {
        // maxBytes = store/artifact budget; actionMaxBytes = action-cache budget (CLI parity).
        cacheSnapshot = new CacheSnapshot(
                100,
                5_000_000,
                40,
                200_000,
                0,
                0,
                3,
                30_000_000,
                7,
                9_000,
                2,
                100,
                4_294_967_296L,
                1_073_741_824L,
                1_700_000_000_000L);
        HttpResponse<String> resp = get("/api/cache");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("application/json; charset=utf-8");
        assertThat(resp.body())
                .contains("\"casCount\":100")
                .contains("\"casBytes\":5000000")
                .contains("\"actionsCount\":40")
                .contains("\"workerJarsBytes\":30000000")
                .contains("\"totalCount\":152")
                .contains("\"totalBytes\":35209100")
                .contains("\"actionCacheBytes\":200100")
                .contains("\"actionMaxBytes\":1073741824") // max-cache-size-mb default 1024
                .contains("\"artifactStorageBytes\":35009000")
                .contains("\"maxBytes\":4294967296")
                .contains("\"lastPrunedMillis\":1700000000000");
    }

    @Test
    void api_log_tails_the_engine_log() throws Exception {
        HttpResponse<String> resp = get("/api/log", "Authorization", "Bearer " + token());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("text/plain; charset=utf-8");
        assertThat(resp.body()).contains("jk engine: listening").contains("line three");
    }

    @Test
    void api_log_requires_the_token_even_on_loopback() throws Exception {
        // The log can carry build diagnostics — another local user must not read it.
        assertThat(get("/api/log").statusCode()).isEqualTo(401);
    }

    @Test
    void api_log_respects_the_lines_parameter() throws Exception {
        assertThat(get("/api/log?lines=1", "Authorization", "Bearer " + token()).body())
                .isEqualTo("line three");
        assertThat(get("/api/log?lines=garbage", "Authorization", "Bearer " + token())
                        .statusCode())
                .isEqualTo(200); // default kicks in
    }

    @Test
    void api_log_of_a_missing_file_is_empty_200() throws Exception {
        Files.delete(logFile);
        HttpResponse<String> resp = get("/api/log", "Authorization", "Bearer " + token());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEmpty();
    }

    @Test
    void fs_listing_requires_the_token_even_on_loopback() throws Exception {
        // It lists the filesystem with the engine owner's permissions — never token-exempt.
        assertThat(get("/api/fs").statusCode()).isEqualTo(401);
    }

    @Test
    void fs_lists_subdirectories_and_flags_jk_toml() throws Exception {
        Files.createDirectories(stateDir.resolve("workspace/module-a"));
        Files.createDirectories(stateDir.resolve("workspace/module-b"));
        Files.createDirectories(stateDir.resolve("workspace/.git")); // hidden: skipped
        Files.writeString(stateDir.resolve("workspace/jk.toml"), "[project]");
        Files.writeString(stateDir.resolve("workspace/README.md"), "not a dir");
        HttpResponse<String> resp =
                get("/api/fs?dir=" + stateDir.resolve("workspace"), "Authorization", "Bearer " + token());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body())
                .contains("\"dirs\":[\"module-a\",\"module-b\"]")
                .contains("\"hasJkToml\":true")
                .contains("\"parent\":\"" + stateDir + "\"");
    }

    @Test
    void fs_rejects_relative_and_unreadable_paths() throws Exception {
        assertThat(get("/api/fs?dir=relative/path", "Authorization", "Bearer " + token())
                        .statusCode())
                .isEqualTo(400);
        assertThat(get("/api/fs?dir=" + stateDir.resolve("no-such-dir"), "Authorization", "Bearer " + token())
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void project_defaults_require_the_token_even_on_loopback() throws Exception {
        // Derived from the owner's git identity + home layout — same class as /api/fs.
        assertThat(get("/api/projects/defaults").statusCode()).isEqualTo(401);
    }

    @Test
    void project_defaults_return_group_and_parent_dir_with_token() throws Exception {
        HttpResponse<String> resp = get("/api/projects/defaults", "Authorization", "Bearer " + token());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"group\":").contains("\"parentDir\":");
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
        // router's 405 — proving the token was accepted (401 would mean it wasn't).
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/status"))
                        .header("Authorization", "Bearer " + token())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(405);
        assertThat(resp.headers().firstValue("Allow")).contains("GET");
    }

    @Test
    void token_file_is_owner_only() throws IOException {
        assertThat(token()).isNotEmpty();
        assertThat(java.nio.file.Files.getPosixFilePermissions(tokenFile))
                .containsExactlyInAnyOrder(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
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
                java.util.List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            restarted.start();
            assertThat(token()).isEqualTo(original); // file unchanged
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(restarted.url() + "api/fs?dir=" + stateDir))
                            .header("Authorization", "Bearer " + original)
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
                java.util.List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            restarted.start();
            assertThat(token()).isNotEmpty().isNotEqualTo(original);
        } finally {
            restarted.close();
        }
    }

    @Test
    void bind_on_a_held_port_fails_without_hanging() {
        // `server` (from @BeforeEach) already holds an OS-assigned loopback port. A second server
        // configured for that exact port must lose the bind — and, thanks to the bounded retry that
        // rides out a draining predecessor, give up promptly (a handful of attempts) rather than
        // spin forever. The generous timeout only guards against a regression to an unbounded loop.
        HttpEngineServer collider = new HttpEngineServer(
                httpConfig("127.0.0.1", port, 16),
                webRoot,
                tokenFile,
                logFile,
                "9.9.9-test",
                () -> SNAPSHOT,
                events,
                stubJobs,
                testJournal(),
                java.util.List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> assertThatThrownBy(collider::start)
                    .isInstanceOf(java.net.BindException.class));
        } finally {
            collider.close();
        }
    }

    @Test
    void non_loopback_bind_gates_api_reads_but_not_static() throws Exception {
        JkHttpConfig config = httpConfig("0.0.0.0", 0, 16);
        Path lanTokenFile = stateDir.resolve("lan.http-token");
        HttpEngineServer lan = new HttpEngineServer(
                config,
                webRoot,
                lanTokenFile,
                stateDir.resolve("lan.log"),
                "9.9.9-test",
                () -> SNAPSHOT,
                new HttpEvents(),
                stubJobs,
                testJournal(),
                java.util.List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            lan.start();
            String lanUrl = lan.url(); // advertises the always-valid loopback form for a wildcard bind
            String lanToken = Files.readString(lanTokenFile).trim();

            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "api/status")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401);

            HttpResponse<String> authorized = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "api/status"))
                            .header("Authorization", "Bearer " + lanToken)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(authorized.statusCode()).isEqualTo(200);

            HttpResponse<String> staticContent = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "hello.txt")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(staticContent.statusCode()).isEqualTo(200); // the shell must load by plain navigation
        } finally {
            lan.close();
        }
    }

    @Test
    void saturated_admission_gate_yields_503_with_retry_after() throws Exception {
        int permits = server.admission().drainPermits();
        try {
            HttpResponse<String> resp = get("/hello.txt");
            assertThat(resp.statusCode()).isEqualTo(503);
            assertThat(resp.headers().firstValue("Retry-After")).contains("1");
        } finally {
            server.admission().release(permits);
        }
        assertThat(get("/hello.txt").statusCode()).isEqualTo(200);
    }

    @Test
    void open_sse_streams_do_not_starve_rpc_admission() throws Exception {
        // A tiny RPC budget: if streams drew from it, three open streams would 503 everything else.
        HttpEngineServer tiny = new HttpEngineServer(
                httpConfig("127.0.0.1", 0, 2),
                webRoot,
                stateDir.resolve("tiny.http-token"),
                stateDir.resolve("tiny.log"),
                "9.9.9-test",
                () -> SNAPSHOT,
                new HttpEvents(),
                stubJobs,
                testJournal(),
                java.util.List::of,
                () -> EMPTY_CACHE,
                null);
        var streams = new java.util.ArrayList<HttpResponse<java.util.stream.Stream<String>>>();
        try {
            tiny.start();
            String url = tiny.url();
            for (int i = 0; i < 3; i++) { // more streams than the whole RPC budget
                HttpResponse<java.util.stream.Stream<String>> resp = client.send(
                        HttpRequest.newBuilder(URI.create(url + "api/events")).build(),
                        HttpResponse.BodyHandlers.ofLines());
                assertThat(resp.statusCode()).isEqualTo(200);
                var lines = resp.body().iterator();
                assertThat(nextLine(lines)).isEqualTo(": connected"); // handler is inside its stream loop
                streams.add(resp);
            }
            HttpResponse<String> rpc = client.send(
                    HttpRequest.newBuilder(URI.create(url + "api/status")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(rpc.statusCode()).isEqualTo(200); // RPC admission untouched by the streams
        } finally {
            streams.forEach(r -> r.body().close());
            tiny.close();
        }
    }

    @Test
    void sse_beyond_its_own_cap_is_503_without_touching_rpc_admission() throws Exception {
        int drained = server.webSseAdmission().drainPermits();
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                HttpResponse<String> resp = get("/api/events");
                assertThat(resp.statusCode()).isEqualTo(503);
                assertThat(resp.body()).contains("too many event streams");
                assertThat(resp.headers().firstValue("Retry-After")).contains("1");
            });
            assertThat(get("/api/status").statusCode()).isEqualTo(200); // RPC budget unaffected
        } finally {
            server.webSseAdmission().release(drained);
        }
    }

    @Test
    void exhausted_web_sse_budget_leaves_mcp_streams_connectable() throws Exception {
        int drained = server.webSseAdmission().drainPermits();
        try {
            assertThat(get("/api/events").statusCode()).isEqualTo(503);
            HttpResponse<java.util.stream.Stream<String>> mcpStream = openMcpEvents();
            try {
                assertThat(mcpStream.statusCode()).isEqualTo(200); // separate budget
                assertThat(nextLine(mcpStream.body().iterator())).isEqualTo(": mcp-events connected");
            } finally {
                mcpStream.body().close();
            }
        } finally {
            server.webSseAdmission().release(drained);
        }
    }

    @Test
    void exhausted_mcp_sse_budget_leaves_web_streams_connectable() throws Exception {
        int drained = server.mcpSseAdmission().drainPermits();
        try {
            HttpResponse<java.util.stream.Stream<String>> rejected = openMcpEvents();
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(String.join("\n", rejected.body().toList())).contains("too many MCP event streams");

            var lines = openEvents(""); // web budget untouched
            assertThat(nextLine(lines)).isEqualTo(": connected");
        } finally {
            server.mcpSseAdmission().release(drained);
        }
    }

    @Test
    void rpc_saturation_does_not_block_event_streams() throws Exception {
        int permits = server.admission().drainPermits();
        try {
            var lines = openEvents("");
            assertThat(nextLine(lines)).isEqualTo(": connected");
        } finally {
            server.admission().release(permits);
        }
    }

    // ---- /api/events (SSE) ----------------------------------------------------------------------

    /** Open the SSE stream and return a line iterator (the JDK client de-chunks for us). */
    private java.util.Iterator<String> openEvents(String query) throws Exception {
        HttpResponse<java.util.stream.Stream<String>> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/events" + query))
                        .build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("text/event-stream; charset=utf-8");
        return resp.body().iterator();
    }

    /** Open the MCP progress stream (token + event-stream Accept) without asserting the status. */
    private HttpResponse<java.util.stream.Stream<String>> openMcpEvents() throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "mcp"))
                        .header("Authorization", "Bearer " + token())
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofLines());
    }

    /** Read the next line with a timeout — a hung stream must fail the test, not the build. */
    private static String nextLine(java.util.Iterator<String> lines) throws Exception {
        return java.util.concurrent.CompletableFuture.supplyAsync(lines::next)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void events_stream_delivers_published_frames_in_sse_format() throws Exception {
        var lines = openEvents("");
        assertThat(nextLine(lines)).isEqualTo(": connected");
        assertThat(nextLine(lines)).isEqualTo(""); // blank line terminating the connected comment
        // Connect hydrate may publish status/cache before our frame (JK-1495 LiveVitals).
        events.publish("request-start", JsonOut.object().put("requestId", 1).put("kind", "build"));
        assertThat(awaitSseEvent(lines, "request-start"))
                .isEqualTo("data: {\"requestId\":1,\"kind\":\"build\"}");
    }

    @Test
    void quiet_events_stream_heartbeats() throws Exception {
        server.heartbeatMillis(50);
        var lines = openEvents("");
        assertThat(nextLine(lines)).isEqualTo(": connected");
        assertThat(nextLine(lines)).isEqualTo("");
        // Hydrate status/cache frames may precede the first quiet-stream heartbeat.
        assertThat(awaitSseComment(lines, ": heartbeat")).isEqualTo(": heartbeat");
    }

    /**
     * Read SSE lines until {@code event: <type>}, then return the following {@code data:} line.
     * Skips connect-hydrate vitals and other interleaved frames.
     */
    private static String awaitSseEvent(java.util.Iterator<String> lines, String type) throws Exception {
        String want = "event: " + type;
        for (int i = 0; i < 200; i++) {
            String line = nextLine(lines);
            if (want.equals(line)) {
                String data = nextLine(lines);
                assertThat(data).startsWith("data: ");
                return data;
            }
        }
        throw new AssertionError("did not see event: " + type + " within 200 lines");
    }

    /** Read until a comment line equals {@code comment} (e.g. {@code : heartbeat}). */
    private static String awaitSseComment(java.util.Iterator<String> lines, String comment) throws Exception {
        for (int i = 0; i < 200; i++) {
            String line = nextLine(lines);
            if (comment.equals(line)) return line;
        }
        throw new AssertionError("did not see " + comment + " within 200 lines");
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
                java.util.List::of,
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

            HttpResponse<java.util.stream.Stream<String>> authorized = client.send(
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

    private HttpResponse<String> postBuild(String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/build"))
                        .header("Authorization", "Bearer " + token())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void build_trigger_acknowledges_with_request_id() throws Exception {
        HttpResponse<String> resp = postBuild("{\"dir\":\"/some/workspace\"}");
        assertThat(resp.statusCode()).isEqualTo(202);
        assertThat(resp.body()).contains("\"requestId\":7").contains("\"events\":\"/api/events\"");
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
        server.webSseAdmission().acquire(1);
        try {
            assertThat(server.liveEventStreams()).isEqualTo(1);
        } finally {
            server.webSseAdmission().release(1);
        }
        assertThat(server.liveEventStreams()).isZero();
    }

    @Test
    void dashboard_and_mcp_streams_both_count() throws Exception {
        server.webSseAdmission().acquire(2);
        server.mcpSseAdmission().acquire(3);
        try {
            assertThat(server.liveEventStreams()).isEqualTo(5);
        } finally {
            server.webSseAdmission().release(2);
            server.mcpSseAdmission().release(3);
        }
    }

    @Test
    void a_fully_drained_budget_counts_every_slot_and_never_goes_negative() throws Exception {
        int web = server.webSseAdmission().drainPermits();
        try {
            assertThat(server.liveEventStreams()).isGreaterThanOrEqualTo(web);
            assertThat(server.liveEventStreams()).isNotNegative();
        } finally {
            server.webSseAdmission().release(web);
        }
    }

    @Test
    void a_real_open_stream_is_visible_as_attached() throws Exception {
        // The end-to-end version: an actual EventSource-style connection, not a drained permit. This is
        // the signal that stops an orphaned engine exiting under a developer's open dashboard tab.
        assertThat(server.liveEventStreams()).isZero();

        java.util.Iterator<String> lines = openEvents("");

        assertThat(nextLine(lines)).isNotNull(); // connected
        assertThat(server.liveEventStreams()).isEqualTo(1);
    }
}

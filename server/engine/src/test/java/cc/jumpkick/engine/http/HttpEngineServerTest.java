// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.JsonOut;
import cc.jumpkick.engine.jobs.JobSpec;
import java.io.IOException;
import java.net.BindException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives a real {@link HttpEngineServer} bound to an OS-assigned loopback port with the JDK's
 * {@link HttpClient} — plus a raw socket where the client won't cooperate (forged {@code Host}
 * headers, literal {@code..} request targets).
 *
 * <p>Runs under {@code :engine:integrationTest} — the unit-tier {@code test} task excludes
 * {@code @Tag("integration")}, so a {@code test --tests} filter naming this class matches nothing.
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
            1.2,
            "9.9.9-test@1000",
            1,
            0);

    @TempDir
    Path webRoot;

    @TempDir
    Path stateDir;

    private Path tokenFile;
    private Path logFile;
    private HttpEvents events;
    private final List<String> triggeredDirs = new ArrayList<>();

    /** Rows served by {@code GET /api/metrics} — tests seed this list directly. */
    private final List<cc.jumpkick.runtime.BuildMetrics.Entry> metricsRows = new ArrayList<>();

    /** The snapshot served by {@code GET /api/cache} — tests reassign the field directly. */
    private static final CacheSnapshot EMPTY_CACHE = new CacheSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

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
        public long trigger(JobSpec spec) {
            if (spec.dir().contains("reject")) throw new IllegalArgumentException("no jk.toml in " + spec.dir());
            triggeredDirs.add(spec.dir());
            return 7;
        }

        @Override
        public boolean cancel(long requestId) {
            return requestId == 7L;
        }

        public int cancelDir(String dir) {
            return 0;
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
        // Fail-closed: every /api/* needs a bearer; non-bootstrap also need the generation header.
        // Explicit Authorization / X-Jk-Engine-Epoch in headers win (tests can force 401/409).
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        boolean bootstrap = pathOnly.equals("/api/status") || pathOnly.equals("/api/events");
        boolean hasEpoch = false;
        boolean hasAuth = false;
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
            if ("X-Jk-Engine-Epoch".equalsIgnoreCase(headers[i])) hasEpoch = true;
            if ("Authorization".equalsIgnoreCase(headers[i])) hasAuth = true;
        }
        if (path.startsWith("/api/") && !hasAuth) {
            builder.header("Authorization", "Bearer " + token());
        }
        if (path.startsWith("/api/") && !bootstrap && !hasEpoch) {
            builder.header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch());
        }
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
        // may write there) must not become an unauthenticated read of anything outside it.
        Path secret = stateDir.resolve("outside-secret.txt");
        Files.writeString(secret, "TOP SECRET");
        try {
            Files.createSymbolicLink(webRoot.resolve("leak.txt"), secret);
        } catch (UnsupportedOperationException | IOException unsupported) {
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
    void disk_content_is_sandboxed_while_the_classpath_shell_is_not() throws Exception {
        // Disk web-root is tokenless AND build-writable, so any HTML that lands there must not be
        // able to script the dashboard origin and read localStorage['jk-http-token'].
        // A bare `sandbox` CSP forces a unique opaque origin with scripts/forms disabled.
        Files.writeString(webRoot.resolve("report.html"), "<html><script>alert(1)</script></html>");
        HttpResponse<String> disk = get("/report.html");
        assertThat(disk.statusCode()).isEqualTo(200);
        assertThat(disk.headers().firstValue("Content-Security-Policy")).contains("sandbox");
        // The shipped shell keeps its own (non-sandbox) CSP — the SPA must still run scripts.
        HttpResponse<String> shell = get("/app.js");
        assertThat(shell.headers().firstValue("Content-Security-Policy")).isPresent();
        assertThat(shell.headers().firstValue("Content-Security-Policy").orElseThrow())
                .doesNotContain("sandbox")
                .startsWith("default-src 'self'");
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
        assertThat(get("/code.js").body()).contains("export function routeFromHash");
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
        // OptionalAssert#contains is equality, not substring — unwrap and check the directives
        // (the full header also carries img-src and form-action; ).
        String csp = js.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(csp)
                .startsWith("default-src 'self'; script-src 'self' 'unsafe-eval' blob: https://unpkg.com; ")
                .contains("style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://unpkg.com; ")
                .contains("font-src https://fonts.gstatic.com data:; worker-src blob:")
                .contains("form-action 'none'");
    }

    @Test
    void falls_back_to_classpath_with_version_etag() throws Exception {
        HttpResponse<String> resp = get("/classpath-only.txt");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("from classpath\n");
        // Revalidate every load so a new engine jar is not hidden by a still-fresh max-age; the
        // ETag carries a content stamp after the version so an unchanged jar is a 304.
        assertThat(resp.headers().firstValue("ETag").orElseThrow())
                .startsWith("\"jk-9.9.9-test")
                .endsWith("\"");
        assertThat(resp.headers().firstValue("Cache-Control")).contains("no-cache");
    }

    @Test
    void snapshot_versions_use_the_same_revalidation_headers() throws Exception {
        // -SNAPSHOT is not a distinct cache policy. The version string is only an ETag prefix;
        // no-cache + stamp still revalidates after installLocal of the same snapshot line.
        HttpEngineServer snapshot = new HttpEngineServer(
                httpConfig("127.0.0.1", 0, 16),
                webRoot,
                stateDir.resolve("snap.http-token"),
                stateDir.resolve("snap.log"),
                "0.12.0-SNAPSHOT",
                () -> SNAPSHOT,
                new HttpEvents(),
                stubJobs,
                testJournal(),
                List::of,
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
            assertThat(resp.headers().firstValue("ETag").orElseThrow())
                    .startsWith("\"jk-0.12.0-SNAPSHOT-")
                    .endsWith("\"");
        } finally {
            snapshot.close();
        }
    }

    @Test
    void matching_etag_yields_304() throws Exception {
        HttpResponse<String> first = get("/classpath-only.txt");
        String etag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).startsWith("\"jk-9.9.9-test-");
        HttpResponse<String> resp = get("/classpath-only.txt", "If-None-Match", etag);
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
    void api_project_graph_returns_module_dag_with_token() throws Exception {
        Path ws = stateDir.resolve("graph-ws");
        Files.createDirectories(ws.resolve("lib"));
        Files.createDirectories(ws.resolve("app"));
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["lib", "app"]
                """);
        Files.writeString(ws.resolve("lib").resolve("jk.toml"), """
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                """);
        Files.writeString(ws.resolve("app").resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                lib = { group = "com.example", name = "lib", version = "1.0.0" }
                """);

        HttpResponse<String> missing = get("/api/project/graph", "Authorization", "Bearer " + token());
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("missing");

        HttpResponse<String> resp = get("/api/project/graph?dir=" + ws, "Authorization", "Bearer " + token());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("application/json; charset=utf-8");
        assertThat(resp.body())
                .contains("\"workspace\":true")
                .contains("\"label\":\"com.example:lib\"")
                .contains("\"label\":\"com.example:app\"")
                .contains("\"kind\":\"module\"")
                .contains("\"scopes\":")
                .contains("\"availableScopes\":")
                .contains("\"transitive\":false")
                .contains("\"from\":")
                .contains("\"to\":")
                .contains("\"nodes\":")
                .contains("\"edges\":");

        // Standalone project with an external direct dep (no lock → declared node, no transitive).
        Path solo = stateDir.resolve("solo");
        Files.createDirectories(solo);
        Files.writeString(solo.resolve("jk.toml"), """
                group = "g"
                name = "n"
                version = "1"

                [dependencies]
                leaf = { group = "com.foo", name = "leaf", version = "1.0" }
                """);
        String soloBody = get("/api/project/graph?dir=" + solo, "Authorization", "Bearer " + token())
                .body();
        assertThat(soloBody)
                .contains("\"workspace\":false")
                .contains("\"label\":\"g:n\"")
                .contains("\"label\":\"com.foo:leaf\"")
                .contains("\"kind\":\"declared\"");
    }

    /**
     * The SPA sends encodeURIComponent, which spells `,` as %2C. Reading the parameter raw turned
     * every multi-scope selection into one unknown token and silently fell back to main, with both
     * checkboxes still ticked. Single-scope requests worked, which is why this was never
     * caught.
     */
    @Test
    void api_project_graph_decodes_a_multi_scope_selection(@TempDir Path stateDir) throws Exception {
        Path solo = stateDir.resolve("solo");
        Files.createDirectories(solo);
        Files.writeString(solo.resolve("jk.toml"), """
                group = "g"
                name = "n"
                version = "1"

                [dependencies]
                leaf = { group = "com.foo", name = "leaf", version = "1.0" }

                [test-dependencies]
                harness = { group = "com.foo", name = "harness", version = "1.0" }
                """);

        String body = get(
                        "/api/project/graph?dir=" + solo + "&scopes="
                                + URLEncoder.encode("main,test", StandardCharsets.UTF_8),
                        "Authorization",
                        "Bearer " + token())
                .body();

        // Both scopes are echoed, and the test-scope dependency is actually in the graph.
        assertThat(body).contains("\"main\"").contains("\"test\"");
        assertThat(body).contains("\"label\":\"com.foo:leaf\"");
        assertThat(body).contains("\"label\":\"com.foo:harness\"");
    }

    /**
     * a project that exists but cannot be loaded (workspace member missing its jk.toml,
     * malformed toml) is a 422 naming what is broken — never a 200 with empty nodes, which the SPA
     * renders as "No dependencies for the selected scopes".
     */
    @Test
    void api_project_graph_surfaces_a_broken_project_as_422_with_the_message() throws Exception {
        Path ws = stateDir.resolve("broken-ws");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["gone"]
                """);
        HttpResponse<String> broken = get("/api/project/graph?dir=" + ws, "Authorization", "Bearer " + token());
        assertThat(broken.statusCode()).isEqualTo(422);
        assertThat(broken.body()).contains("error").contains("gone");

        Path bad = stateDir.resolve("bad-toml");
        Files.createDirectories(bad);
        Files.writeString(bad.resolve("jk.toml"), "not [ valid toml ===");
        HttpResponse<String> malformed = get("/api/project/graph?dir=" + bad, "Authorization", "Bearer " + token());
        assertThat(malformed.statusCode()).isEqualTo(422);
        assertThat(malformed.body()).contains("error");

        // An ABSENT jk.toml stays a 200 empty graph — a deleted checkout is not an error.
        Path empty = stateDir.resolve("no-toml");
        Files.createDirectories(empty);
        HttpResponse<String> absent = get("/api/project/graph?dir=" + empty, "Authorization", "Bearer " + token());
        assertThat(absent.statusCode()).isEqualTo(200);
        assertThat(absent.body()).contains("\"nodes\":[]");
    }

    /** a malformed dir (NUL byte) is a client-error 400, not a logged 500. */
    @Test
    void api_project_graph_rejects_a_malformed_dir_with_400_not_500() throws Exception {
        var resp = get("/api/project/graph?dir=%00x", "Authorization", "Bearer " + token());
        assertThat(resp.statusCode()).isEqualTo(400);
    }

    @Test
    void api_project_graph_rejects_an_unknown_scope_instead_of_falling_back_to_main() throws Exception {
        var resp = get("/api/project/graph?dir=/tmp&scopes=bogus", "Authorization", "Bearer " + token());

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("bogus").contains("valid:");
    }

    @Test
    void api_project_graph_rejects_malformed_percent_encoding_with_400_not_500() throws Exception {
        // URLDecoder.decode throws IllegalArgumentException on a bad escape (e.g. "%zz") — that
        // must land on the same 400 path as an unknown scope, not an uncaught 500. java.net.URI
        // (and so HttpClient) refuses to even send a request with an invalid escape, so this goes
        // over a raw socket like HttpEngineServerTest#raw, with the bearer token added by hand.
        String request = "GET /api/project/graph?dir=%zz HTTP/1.1\r\n"
                + "Authorization: Bearer " + token() + "\r\n"
                + "X-Jk-Engine-Epoch: " + SNAPSHOT.engineEpoch() + "\r\n"
                + "Connection: close\r\n\r\n";
        String response;
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(request.getBytes(UTF_8));
            response = new String(socket.getInputStream().readAllBytes(), UTF_8);
        }

        assertThat(response).startsWith("HTTP/1.1 400");
    }

    @Test
    void api_project_and_events_token_survive_malformed_percent_encoding() throws Exception {
        // /api/project?project=%zz previously escaped the handler as a 500; a garbage
        // access_token on /api/events must read as "no token" (401), never a 500.
        String project = "GET /api/project?project=%zz HTTP/1.1\r\n"
                + "Authorization: Bearer " + token() + "\r\n"
                + "X-Jk-Engine-Epoch: " + SNAPSHOT.engineEpoch() + "\r\n"
                + "Connection: close\r\n\r\n";
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(project.getBytes(UTF_8));
            assertThat(new String(socket.getInputStream().readAllBytes(), UTF_8))
                    .startsWith("HTTP/1.1 400");
        }
        String events = "GET /api/events?access_token=%zz HTTP/1.1\r\n" + "Connection: close\r\n\r\n";
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(events.getBytes(UTF_8));
            // jdk.httpserver may reject the malformed URI itself (400) before dispatch; when it
            // does dispatch, the garbage token reads as "no token" (401). Either way: never 5xx.
            assertThat(new String(socket.getInputStream().readAllBytes(), UTF_8))
                    .startsWith("HTTP/1.1 4");
        }
    }

    @Test
    void api_project_files_and_file_are_identity_scoped() throws Exception {
        Path buildsDir = stateDir.resolve("file-builds");
        System.setProperty("jk.env.JK_BUILDS_DIR", buildsDir.toString());
        try {
            Path checkout = stateDir.resolve("src-app");
            Files.createDirectories(checkout.resolve("src"));
            Files.writeString(checkout.resolve("jk.toml"), """
                    group = "g"
                    name = "n"
                    version = "1"
                    """);
            Files.writeString(checkout.resolve("src/Main.java"), "class Main {}\n");
            Files.createDirectories(checkout.resolve("target"));
            Files.createDirectories(checkout.resolve("build"));
            Files.writeString(checkout.resolve("target/Gen.java"), "class Gen {}");
            Files.writeString(checkout.resolve("target/report.md"), "# report\n");
            Files.writeString(checkout.resolve("build/Skip.java"), "class Skip {}");
            Files.writeString(checkout.resolve(".env"), "SECRET=1");
            var identity = cc.jumpkick.builds.ProjectIdentity.resolve(checkout);
            cc.jumpkick.builds.ProjectIdentity.IdentityFile.write(
                    cc.jumpkick.builds.ProjectBuilds.projectHome(identity.id()), identity);
            String id = identity.id();

            HttpResponse<String> noToken = client.send(
                    HttpRequest.newBuilder(
                                    URI.create(baseUrl + "api/project/file?project=" + id + "&path=src/Main.java"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(noToken.statusCode()).isEqualTo(401);

            HttpResponse<String> missing = get("/api/project/files");
            assertThat(missing.statusCode()).isEqualTo(400);
            assertThat(missing.body()).contains("missing");

            HttpResponse<String> dirOnly = get("/api/project/file?dir=" + checkout + "&path=src/Main.java");
            assertThat(dirOnly.statusCode()).isEqualTo(400);
            assertThat(dirOnly.body()).contains("missing");

            HttpResponse<String> unknown = get("/api/project/files?project=zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz");
            assertThat(unknown.statusCode()).isEqualTo(404);

            HttpResponse<String> listed = get("/api/project/files?project=" + id);
            assertThat(listed.statusCode()).isEqualTo(200);
            assertThat(listed.body())
                    .contains("\"path\":\"src/Main.java\"")
                    .contains("\"lang\":\"java\"")
                    .contains("\"path\":\"jk.toml\"")
                    .contains("target/Gen.java")
                    .contains("target/report.md")
                    .doesNotContain("build/Skip.java")
                    .doesNotContain(".env");

            HttpResponse<String> file = get("/api/project/file?project=" + id + "&path=src%2FMain.java");
            assertThat(file.statusCode()).isEqualTo(200);
            assertThat(file.body()).contains("class Main").contains("\"lang\":\"java\"");

            assertThat(get("/api/project/file?project=" + id + "&path=target%2FGen.java")
                            .statusCode())
                    .isEqualTo(200);
            assertThat(get("/api/project/file?project=" + id + "&path=build%2FSkip.java")
                            .statusCode())
                    .isEqualTo(404);
            assertThat(get("/api/project/file?project=" + id + "&path=.env").statusCode())
                    .isEqualTo(404);
            assertThat(get("/api/project/file?project=" + id + "&path=../jk.toml")
                            .statusCode())
                    .isEqualTo(400);

            Files.write(checkout.resolve("src/Big.java"), new byte[WorkspaceFileAccess.MAX_FILE_BYTES + 1]);
            HttpResponse<String> huge = get("/api/project/file?project=" + id + "&path=src%2FBig.java");
            assertThat(huge.statusCode()).isEqualTo(413);
            assertThat(huge.body()).contains("file too large");

            Files.write(checkout.resolve("src/Bin.java"), new byte[] {'x', 0, 'y'});
            HttpResponse<String> bin = get("/api/project/file?project=" + id + "&path=src%2FBin.java");
            assertThat(bin.statusCode()).isEqualTo(415);
            assertThat(bin.body()).contains("binary");

            Files.createDirectories(checkout.resolve("docs"));
            Files.write(checkout.resolve("docs/logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0});
            Files.writeString(checkout.resolve("docs/flow.mmd"), "graph TD; A-->B\n");
            assertThat(get("/api/project/files?project=" + id).body())
                    .contains("docs/logo.png")
                    .contains("docs/flow.mmd");
            HttpResponse<String> imgJson = get("/api/project/file?project=" + id + "&path=docs%2Flogo.png");
            assertThat(imgJson.statusCode()).isEqualTo(415);
            HttpResponse<byte[]> imgRaw = client.send(
                    HttpRequest.newBuilder(URI.create(
                                    baseUrl + "api/project/file/raw?project=" + id + "&path=docs%2Flogo.png"))
                            .header("Authorization", "Bearer " + token())
                            .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(imgRaw.statusCode()).isEqualTo(200);
            assertThat(imgRaw.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/png");
            assertThat(imgRaw.body()).startsWith((byte) 0x89, (byte) 'P');

            assertThat(file.body()).contains("\"etag\":");
            String etag = file.body().replaceAll("(?s).*\"etag\":\"([0-9a-f]+)\".*", "$1");
            assertThat(etag).hasSize(64);

            HttpResponse<String> put = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "api/project/file"))
                            .header("Authorization", "Bearer " + token())
                            .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(
                                    "{\"project\":\"" + id + "\",\"path\":\"src/Main.java\","
                                            + "\"content\":\"class Main { int y; }\\n\","
                                            + "\"etag\":\"" + etag + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(put.statusCode()).isEqualTo(200);
            assertThat(put.body()).contains("\"path\":\"src/Main.java\"").contains("\"etag\":");
            assertThat(put.body()).doesNotContain("lockStale"); // sources do not stale the lock
            assertThat(Files.readString(checkout.resolve("src/Main.java"))).isEqualTo("class Main { int y; }\n");

            // A manifest save flags the now-stale lock stamp.
            HttpResponse<String> putManifest = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "api/project/file"))
                            .header("Authorization", "Bearer " + token())
                            .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString("{\"project\":\"" + id
                                    + "\",\"path\":\"jk.toml\",\"content\":\"group = \\\"g\\\"\\n"
                                    + "name = \\\"n\\\"\\nversion = \\\"2\\\"\\n\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(putManifest.statusCode()).isEqualTo(200);
            assertThat(putManifest.body()).contains("\"lockStale\":true");

            HttpResponse<String> stale = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "api/project/file"))
                            .header("Authorization", "Bearer " + token())
                            .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(
                                    "{\"project\":\"" + id + "\",\"path\":\"src/Main.java\","
                                            + "\"content\":\"class Main { stale; }\\n\","
                                            + "\"etag\":\"" + etag + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(stale.body()).contains("file changed on disk");

            HttpResponse<String> putImg = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "api/project/file"))
                            .header("Authorization", "Bearer " + token())
                            .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString("{\"project\":\"" + id
                                    + "\",\"path\":\"docs/logo.png\"," + "\"content\":\"nope\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(putImg.statusCode()).isEqualTo(415);

            // control chars JSON-escape to six bytes each; a legal file well under the
            // 1 MiB write cap must not 413 on the request-body cap (old factor 3 rejected it).
            String ctlContent = "\u0001".repeat(600 * 1024);
            String ctlBody = "{\"project\":\"" + id + "\",\"path\":\"src/Main.java\",\"content\":\""
                    + "\\u0001".repeat(600 * 1024) + "\"}";
            HttpResponse<String> ctl = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "api/project/file"))
                            .header("Authorization", "Bearer " + token())
                            .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(ctlBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ctl.statusCode()).isEqualTo(200);
            assertThat(Files.readString(checkout.resolve("src/Main.java"))).isEqualTo(ctlContent);
        } finally {
            System.clearProperty("jk.env.JK_BUILDS_DIR");
        }
    }

    @Test
    void api_project_file_decodes_query_values_exactly_once() throws Exception {
        // getQuery() already percent-decodes, and a second URLDecoder pass mapped '+'
        // to space and truncated at a decoded '&' — so any file the tree listed with those
        // characters could never be opened, and %252e%252e relied on validation order alone.
        Path buildsDir = stateDir.resolve("decode-builds");
        System.setProperty("jk.env.JK_BUILDS_DIR", buildsDir.toString());
        try {
            Path checkout = stateDir.resolve("src-decode");
            Files.createDirectories(checkout.resolve("src"));
            Files.writeString(checkout.resolve("jk.toml"), """
                    group = "g"
                    name = "n"
                    version = "1"
                    """);
            Files.writeString(checkout.resolve("src/A+B.java"), "class APlusB {}\n");
            Files.writeString(checkout.resolve("src/A&B.java"), "class AAmpB {}\n");
            Files.writeString(checkout.resolve("src/A%2.java"), "class APct {}\n");
            var identity = cc.jumpkick.builds.ProjectIdentity.resolve(checkout);
            cc.jumpkick.builds.ProjectIdentity.IdentityFile.write(
                    cc.jumpkick.builds.ProjectBuilds.projectHome(identity.id()), identity);
            String id = identity.id();

            // Exactly what code.js's encodeURIComponent sends for each listed path.
            HttpResponse<String> plus = get("/api/project/file?project=" + id + "&path=src%2FA%2BB.java");
            assertThat(plus.statusCode()).as("plus sign survives one decode").isEqualTo(200);
            assertThat(plus.body()).contains("class APlusB");

            HttpResponse<String> amp = get("/api/project/file?project=" + id + "&path=src%2FA%26B.java");
            assertThat(amp.statusCode())
                    .as("encoded ampersand does not split the value")
                    .isEqualTo(200);
            assertThat(amp.body()).contains("class AAmpB");

            HttpResponse<String> pct = get("/api/project/file?project=" + id + "&path=src%2FA%252.java");
            assertThat(pct.statusCode())
                    .as("literal percent decodes once, not twice")
                    .isEqualTo(200);
            assertThat(pct.body()).contains("class APct");

            // Double-encoded traversal decodes ONCE to the literal filename "%2e%2e/jk.toml" —
            // not a ".." segment — so the correct answer is "no such file" (404). Anything else
            // would mean a second decode happened somewhere.
            assertThat(get("/api/project/file?project=" + id + "&path=%252e%252e%2Fjk.toml")
                            .statusCode())
                    .isEqualTo(404);
        } finally {
            System.clearProperty("jk.env.JK_BUILDS_DIR");
        }
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
                .contains(scopeJson(cc.jumpkick.runtime.BuildMetrics.SCOPE_GLOBAL))
                .contains(scopeJson(cc.jumpkick.runtime.BuildMetrics.SCOPE_PROJECT))
                .contains(scopeJson(cc.jumpkick.runtime.BuildMetrics.SCOPE_PROJECT_TASK))
                .contains("\"okCount\":3")
                .contains("\"okAvgMillis\":2000")
                .contains("\"coord\":\"g:n\"");

        // ?dir= keeps the global tiers but drops other projects' rows.
        String filtered =
                get("/api/metrics?dir=/p", "Authorization", "Bearer " + token()).body();
        assertThat(filtered)
                .contains(scopeJson(cc.jumpkick.runtime.BuildMetrics.SCOPE_GLOBAL))
                .contains("\"dir\":\"/p\"");
        assertThat(filtered).doesNotContain("/other");
    }

    private static String scopeJson(String scope) {
        return "\"scope\":\"" + scope + "\"";
    }

    @Test
    void api_metrics_is_an_empty_array_when_nothing_has_been_recorded() throws Exception {
        assertThat(get("/api/metrics", "Authorization", "Bearer " + token()).body())
                .isEqualTo("[]");
    }

    @Test
    void api_cache_reports_the_cache_breakdown_with_token() throws Exception {
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
                .contains("\"actionMaxBytes\":1073741824") // fixture cache budget (1 GiB)
                // Store CAS + worker jars; run logs are state, not storage.
                .contains("\"artifactStorageBytes\":35000000")
                .contains("\"maxBytes\":4294967296") // fixture store budget (4 GiB)
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
        HttpResponse<String> noToken = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/log")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noToken.statusCode()).isEqualTo(401);
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
        HttpResponse<String> noToken = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/fs")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noToken.statusCode()).isEqualTo(401);
    }

    @Test
    void fs_lists_subdirectories_and_flags_jk_toml() throws Exception {
        Files.createDirectories(stateDir.resolve("workspace/module-a"));
        Files.createDirectories(stateDir.resolve("workspace/module-b"));
        Files.createDirectories(stateDir.resolve("workspace/.git")); // hidden: skipped
        Files.writeString(stateDir.resolve("workspace/jk.toml"), "");
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
    void fs_rejects_unreadable_paths() throws Exception {
        // Relative segments resolve against $HOME — a non-existent leaf is still 400 (not "must be absolute").
        assertThat(get(
                                "/api/fs?dir=jk-no-such-relative-path-"
                                        + ProcessHandle.current().pid(),
                                "Authorization",
                                "Bearer " + token())
                        .statusCode())
                .isEqualTo(400);
        assertThat(get("/api/fs?dir=" + stateDir.resolve("no-such-dir"), "Authorization", "Bearer " + token())
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void fs_accepts_tilde_and_home_relative_paths() throws Exception {
        // Under $HOME so ~ and bare-relative resolve to the same absolute listing.
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path pick = home.resolve("jk-fs-home-rel-" + ProcessHandle.current().pid());
        Files.createDirectories(pick.resolve("child-a"));
        Files.writeString(pick.resolve("jk.toml"), "");
        try {
            String rel = home.relativize(pick).toString().replace('\\', '/');
            String enc = URLEncoder.encode(rel, UTF_8);
            String encTilde = URLEncoder.encode("~/" + rel, UTF_8);
            HttpResponse<String> fromTilde = get("/api/fs?dir=" + encTilde, "Authorization", "Bearer " + token());
            HttpResponse<String> fromRel = get("/api/fs?dir=" + enc, "Authorization", "Bearer " + token());
            assertThat(fromTilde.statusCode()).isEqualTo(200);
            assertThat(fromRel.statusCode()).isEqualTo(200);
            assertThat(fromTilde.body())
                    .contains("\"dir\":\"" + pick + "\"")
                    .contains("\"dirs\":[\"child-a\"]")
                    .contains("\"hasJkToml\":true");
            assertThat(fromRel.body()).contains("\"dir\":\"" + pick + "\"");
        } finally {
            cc.jumpkick.util.PathUtil.deleteRecursively(pick);
        }
    }

    @Test
    void project_defaults_require_the_token_even_on_loopback() throws Exception {
        // Derived from the owner's git identity + home layout — same class as /api/fs.
        HttpResponse<String> noToken = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/projects/defaults"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noToken.statusCode()).isEqualTo(401);
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
                    HttpRequest.newBuilder(URI.create(restarted.url() + "api/fs?dir=" + stateDir))
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
                List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(collider::start)
                    .isInstanceOf(BindException.class));
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
                List::of,
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
                List::of,
                () -> EMPTY_CACHE,
                null);
        var streams = new ArrayList<HttpResponse<Stream<String>>>();
        try {
            tiny.start();
            String url = tiny.url();
            String tok = Files.readString(stateDir.resolve("tiny.http-token")).trim();
            for (int i = 0; i < 3; i++) { // more streams than the whole RPC budget
                HttpResponse<Stream<String>> resp = client.send(
                        HttpRequest.newBuilder(URI.create(url + "api/events?access_token=" + tok))
                                .build(),
                        HttpResponse.BodyHandlers.ofLines());
                assertThat(resp.statusCode()).isEqualTo(200);
                var lines = resp.body().iterator();
                assertThat(nextLine(lines)).isEqualTo(": connected"); // handler is inside its stream loop
                streams.add(resp);
            }
            HttpResponse<String> rpc = client.send(
                    HttpRequest.newBuilder(URI.create(url + "api/status"))
                            .header("Authorization", "Bearer " + tok)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(rpc.statusCode()).isEqualTo(200); // RPC admission untouched by the streams
        } finally {
            streams.forEach(r -> r.body().close());
            tiny.close();
        }
    }

    @Test
    void mcp_query_token_only_authorizes_the_sse_get() throws Exception {
        String tok = token();
        // A mutation authorized by a URL token would land in shell history and proxy logs.
        HttpResponse<String> post = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "mcp?access_token=" + tok))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(401);
        // Bearer-only everywhere except the SSE GET — discovery included.
        HttpResponse<String> discovery = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "mcp?access_token=" + tok))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(discovery.statusCode()).isEqualTo(401);
        // The SSE GET keeps the query form: EventSource cannot set headers.
        HttpResponse<Stream<String>> sse = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "mcp?access_token=" + tok))
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofLines());
        try {
            assertThat(sse.statusCode()).isEqualTo(200);
            assertThat(nextLine(sse.body().iterator())).isEqualTo(": mcp-events connected");
        } finally {
            sse.body().close();
        }
    }

    @Test
    void mcp_wait_parks_without_holding_the_only_admission_permit() throws Exception {
        // RPC budget of 1: pre-JK-2028 a parked jk_job wait held the permit, so every other
        // request (including the jk_cancel that could un-wedge it) 503'd until timeout.
        HttpEngineServer tiny = new HttpEngineServer(
                httpConfig("127.0.0.1", 0, 1),
                webRoot,
                stateDir.resolve("wait.http-token"),
                stateDir.resolve("wait.log"),
                "9.9.9-test",
                () -> SNAPSHOT,
                new HttpEvents(),
                stubJobs,
                testJournal(),
                List::of,
                () -> EMPTY_CACHE,
                null);
        AtomicBoolean live = new AtomicBoolean(true);
        AtomicInteger livePolls = new AtomicInteger();
        HttpLive.Run run =
                new HttpLive.Run(7L, 1L, "build", "/tmp/x", "c", 1L, 0L, 50.0, "j-1", 0, 0, 1, 2, List.of(), List.of());
        tiny.setLiveRunSupport(
                () -> {
                    livePolls.incrementAndGet();
                    return live.get() ? List.of(run) : List.of();
                },
                null);
        try {
            tiny.start();
            String url = tiny.url();
            String tok = Files.readString(stateDir.resolve("wait.http-token")).trim();
            CompletableFuture<HttpResponse<String>> parked = CompletableFuture.supplyAsync(() -> {
                try {
                    return client.send(
                            HttpRequest.newBuilder(URI.create(url + "mcp"))
                                    .header("Authorization", "Bearer " + tok)
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                                                            + "{\"name\":\"jk_job\",\"arguments\":{\"action\":\"wait\",\"jid\":7,\"timeout_s\":30}}}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            // Wait until the request is provably parked inside the wait loop (it polls the live
            // view) with the only permit back in the semaphore — then probe. Probing earlier
            // would race the parked request's own pre-park admission on the budget of one.
            long deadline = System.currentTimeMillis() + 5_000;
            while ((livePolls.get() < 2 || tiny.admission().availablePermits() < 1)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(livePolls.get()).isGreaterThanOrEqualTo(2); // inside waitUntilGone
            assertThat(tiny.admission().availablePermits()).isEqualTo(1); // permit yielded
            HttpResponse<String> probe = client.send(
                    HttpRequest.newBuilder(URI.create(url + "api/status"))
                            .header("Authorization", "Bearer " + tok)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(probe.statusCode()).isEqualTo(200); // surface alive while the wait parks
            live.set(false); // job "finishes"; the parked wait completes and reacquires
            HttpResponse<String> done = parked.get(10, TimeUnit.SECONDS);
            assertThat(done.statusCode()).isEqualTo(200);
            assertThat(done.body()).contains("\"finished\":true");
        } finally {
            tiny.close();
        }
    }

    @Test
    void sse_beyond_its_own_cap_is_503_without_touching_rpc_admission() throws Exception {
        int drained = server.webSseAdmission().drainPermits();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
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
            HttpResponse<Stream<String>> mcpStream = openMcpEvents();
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
            HttpResponse<Stream<String>> rejected = openMcpEvents();
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
    private Iterator<String> openEvents(String query) throws Exception {
        String q = query == null ? "" : query;
        if (!q.contains("access_token=")) {
            String tok = "access_token=" + token();
            if (q.isEmpty()) q = "?" + tok;
            else if (q.startsWith("?")) q = q + "&" + tok;
            else q = "?" + q + "&" + tok;
        }
        HttpResponse<Stream<String>> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/events" + q)).build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("text/event-stream; charset=utf-8");
        return resp.body().iterator();
    }

    /** Open the MCP progress stream (token + event-stream Accept) without asserting the status. */
    private HttpResponse<Stream<String>> openMcpEvents() throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "mcp"))
                        .header("Authorization", "Bearer " + token())
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofLines());
    }

    /** Read the next line with a timeout — a hung stream must fail the test, not the build. */
    private static String nextLine(Iterator<String> lines) throws Exception {
        return CompletableFuture.supplyAsync(lines::next).get(5, TimeUnit.SECONDS);
    }

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
    private static String awaitSseEvent(Iterator<String> lines, String type) throws Exception {
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
    private static String awaitSseComment(Iterator<String> lines, String comment) throws Exception {
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

    private HttpResponse<String> postBuild(String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/build"))
                        .header("Authorization", "Bearer " + token())
                        .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

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

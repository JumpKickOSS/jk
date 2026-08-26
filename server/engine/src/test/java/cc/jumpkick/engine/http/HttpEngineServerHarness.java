// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.runtime.BuildMetrics;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives a real {@link HttpEngineServer} bound to an OS-assigned loopback port with the JDK's
 * {@link HttpClient} — plus a raw socket where the client won't cooperate (forged {@code Host}
 * headers, literal {@code..} request targets).
 *
 * <p>Runs under {@code :engine:integrationTest} — the unit-tier {@code test} task excludes
 * {@code @Tag("integration")}, so a {@code test --tests} filter naming a subclass matches nothing.
 */
abstract class HttpEngineServerHarness {

    static final StatusSnapshot SNAPSHOT = new StatusSnapshot(
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

    Path tokenFile;
    Path logFile;
    HttpEvents events;
    final List<String> triggeredDirs = new ArrayList<>();

    /** Rows served by {@code GET /api/metrics} — tests seed this list directly. */
    final List<BuildMetrics.Entry> metricsRows = new ArrayList<>();

    /** The snapshot served by {@code GET /api/cache} — tests reassign the field directly. */
    static final CacheSnapshot EMPTY_CACHE =
            new CacheSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    CacheSnapshot cacheSnapshot = EMPTY_CACHE;
    HttpEngineServer server;
    HttpClient client;
    String baseUrl;
    int port;

    /** Config with the default stream budgets and MCP on — most tests only vary host/port/RPC cap. */
    JkHttpConfig httpConfig(String host, int port, int maxConcurrentRequests) {
        return httpConfig(host, port, maxConcurrentRequests, JkHttpConfig.Mcp.DEFAULTS);
    }

    JkHttpConfig httpConfig(String host, int port, int maxConcurrentRequests, JkHttpConfig.Mcp mcp) {
        return new JkHttpConfig(host, port, maxConcurrentRequests, 16, webRoot.toString(), mcp);
    }

    /** A journal rooted under the test's temp state dir — endpoints exist; content is per-test. */
    BuildJournal testJournal() {
        return new BuildJournal(stateDir.resolve("builds").resolve("journal"));
    }

    /** Stub {@link EngineHttpJobs}: records the dir, returns a fixed id, rejects "reject me". */
    final EngineHttpJobs stubJobs = new EngineHttpJobs() {
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

    String token() throws IOException {
        return Files.readString(tokenFile).trim();
    }

    /**
     * Path as a query value — Windows backslashes must be percent-encoded for {@link URI#create}.
     * The {@code +} rewrite matches the server's decoder, which never maps {@code +} to a space:
     * a Windows temp dir under {@code C:\Users\First Last} would otherwise arrive with a literal
     * plus in it.
     */
    static String encDir(Path dir) {
        return URLEncoder.encode(dir.toString(), UTF_8).replace("+", "%20");
    }

    HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
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
    String raw(String target, String hostHeader) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String request = "GET " + target + " HTTP/1.1\r\n"
                    + (hostHeader != null ? "Host: " + hostHeader + "\r\n" : "")
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(UTF_8));
            return new String(socket.getInputStream().readAllBytes(), UTF_8);
        }
    }

    static String scopeJson(String scope) {
        return "\"scope\":\"" + scope + "\"";
    }

    /** Open the SSE stream and return a line iterator (the JDK client de-chunks for us). */
    Iterator<String> openEvents(String query) throws Exception {
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
    HttpResponse<Stream<String>> openMcpEvents() throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "mcp"))
                        .header("Authorization", "Bearer " + token())
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofLines());
    }

    /** Read the next line with a timeout — a hung stream must fail the test, not the build. */
    static String nextLine(Iterator<String> lines) throws Exception {
        return CompletableFuture.supplyAsync(lines::next).get(5, TimeUnit.SECONDS);
    }

    /**
     * Read SSE lines until {@code event: <type>}, then return the following {@code data:} line.
     * Skips connect-hydrate vitals and other interleaved frames.
     */
    static String awaitSseEvent(Iterator<String> lines, String type) throws Exception {
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
    static String awaitSseComment(Iterator<String> lines, String comment) throws Exception {
        for (int i = 0; i < 200; i++) {
            String line = nextLine(lines);
            if (comment.equals(line)) return line;
        }
        throw new AssertionError("did not see " + comment + " within 200 lines");
    }

    HttpResponse<String> postBuild(String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/build"))
                        .header("Authorization", "Bearer " + token())
                        .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

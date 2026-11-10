// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.testing.Symlinks;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Static content over real HTTP: the shipped dashboard alone by default, a directory the user named
 * ({@code web-root}, or a snapshot build's {@code web-overlay}) ahead of it under the shell's CSP,
 * revalidation headers and 304s, HEAD, the 405 on a mutation, traversal, and the {@code Host}
 * header gate. Fixture in {@link HttpEngineServerHarness}.
 */
@Tag("integration")
class HttpStaticContentTest extends HttpEngineServerHarness {

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
        Symlinks.create(webRoot.resolve("leak.txt"), secret);
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
    void a_directory_the_user_named_serves_its_files_under_the_shell_policy(@TempDir Path dev) throws Exception {
        // A set web-root is a dashboard source tree (JK_HTTP_WEB_ROOT at a checkout): its scripts
        // must run, so its files get the shell's policy, its service worker included.
        Files.writeString(dev.resolve("index.html"), "<html>dev dash</html>");
        Files.writeString(dev.resolve(StaticContent.WORKER), "// dev worker");
        HttpEngineServer devServer = serverFor(
                new JkHttpConfig("127.0.0.1", 0, 16, 16, dev.toString(), JkHttpConfig.Mcp.DEFAULTS),
                stateDir,
                "9.9.9-test",
                null);
        try {
            HttpResponse<String> resp = fetch(devServer, "index.html");
            assertThat(resp.body()).isEqualTo("<html>dev dash</html>");
            assertThat(resp.headers().firstValue("Content-Security-Policy")).contains(StaticContent.SHELL_POLICY);
            assertThat(fetch(devServer, StaticContent.WORKER).body()).isEqualTo("// dev worker");
            assertThat(resp.headers().firstValue(StaticContent.OVERLAY_HEADER)).isEmpty();
        } finally {
            devServer.close();
        }
    }

    @Test
    void with_no_directory_named_only_the_shipped_dashboard_is_served(@TempDir Path home) throws Exception {
        // The default location a build can write to is never read: a planted script, worker or page
        // there would run in the dashboard's origin with the page's bearer token in reach.
        Path planted = Files.createDirectories(home.resolve("state/web"));
        Files.writeString(planted.resolve("app.js"), "alert(document.cookie)");
        Files.writeString(planted.resolve(StaticContent.WORKER), "self.addEventListener('fetch', () => {});");
        Files.writeString(planted.resolve("report.html"), "<html>report</html>");
        Files.writeString(planted.resolve("hello.txt"), "planted");
        HttpEngineServer jarOnly = serverFor(
                new JkHttpConfig("127.0.0.1", 0, 16, 16, JkHttpConfig.DEFAULT_WEB_ROOT, JkHttpConfig.Mcp.DEFAULTS),
                home,
                "9.9.9-test",
                null);
        try {
            HttpResponse<String> app = fetch(jarOnly, "app.js");
            assertThat(app.body()).contains("Vue.createApp").doesNotContain("alert(document.cookie)");
            assertThat(app.headers().firstValue("Content-Security-Policy").orElseThrow())
                    .startsWith("default-src 'self'");

            HttpResponse<String> worker = fetch(jarOnly, "sw.js");
            assertThat(worker.statusCode()).isEqualTo(200);
            assertThat(worker.body()).contains("notificationclick").doesNotContain("'fetch'");
            assertThat(worker.headers().firstValue("Content-Type")).contains("text/javascript; charset=utf-8");
            // Revalidated on every update check, so an engine upgrade installs its own worker.
            assertThat(worker.headers().firstValue("Cache-Control")).contains("no-cache");

            // Not even a path the jar does not ship: nothing under the default location is served.
            assertThat(fetch(jarOnly, "report.html").statusCode()).isEqualTo(404);
            assertThat(fetch(jarOnly, "hello.txt").statusCode()).isEqualTo(404);
        } finally {
            jarOnly.close();
        }
    }

    @Test
    void the_shipped_manifest_and_icons_are_served_from_the_jar() throws Exception {
        HttpResponse<String> manifest = get("/manifest.webmanifest");
        assertThat(manifest.statusCode()).isEqualTo(200);
        assertThat(manifest.headers().firstValue("Content-Type")).contains("application/manifest+json; charset=utf-8");
        assertThat(manifest.body()).contains("\"display\": \"standalone\"");
        assertThat(get("/icon-512.png").headers().firstValue("Content-Type")).contains("image/png");
    }

    @Test
    void a_snapshot_build_serves_its_overlay_over_the_shipped_files_and_falls_through_for_the_rest(
            @TempDir Path overlay) throws Exception {
        Files.writeString(overlay.resolve("app.js"), "// overlay app");
        List<String> log = new ArrayList<>();
        HttpEngineServer dev = serverFor(overlayConfig(overlay), stateDir, "9.9.9-SNAPSHOT", log::add);
        try {
            HttpResponse<String> app = fetch(dev, "app.js");
            assertThat(app.body()).isEqualTo("// overlay app");
            assertThat(app.headers().firstValue(StaticContent.OVERLAY_HEADER)).contains("1");
            assertThat(app.headers().firstValue("Content-Security-Policy")).contains(StaticContent.SHELL_POLICY);
            // A shipped file the overlay lacks still comes from the jar, unmarked.
            HttpResponse<String> code = fetch(dev, "code.js");
            assertThat(code.body()).contains("export const CodeView");
            assertThat(code.headers().firstValue(StaticContent.OVERLAY_HEADER)).isEmpty();
            assertThat(log).anyMatch(l -> l.contains("web-overlay") && l.contains(overlay.toString()));
        } finally {
            dev.close();
        }
    }

    @Test
    void a_release_build_ignores_the_overlay_says_so_and_still_starts(@TempDir Path overlay) throws Exception {
        Files.writeString(overlay.resolve("app.js"), "// overlay app");
        for (String release : List.of("9.9.9", "9.9.9-SNAPSHOT-rc1")) {
            List<String> log = new ArrayList<>();
            HttpEngineServer server = serverFor(overlayConfig(overlay), stateDir, release, log::add);
            try {
                HttpResponse<String> app = fetch(server, "app.js");
                assertThat(app.body()).contains("Vue.createApp").doesNotContain("// overlay app");
                assertThat(app.headers().firstValue(StaticContent.OVERLAY_HEADER))
                        .isEmpty();
                assertThat(log).contains("http: web-overlay ignored: not a snapshot build");
            } finally {
                server.close();
            }
        }
    }

    @Test
    void a_web_root_outranks_the_overlay(@TempDir Path overlay, @TempDir Path root) throws Exception {
        Files.writeString(overlay.resolve("app.js"), "// overlay app");
        Files.writeString(root.resolve("app.js"), "// root app");
        JkHttpConfig both = new JkHttpConfig(
                "127.0.0.1", 0, 16, 16, root.toString(), overlay.toString(), JkHttpConfig.Mcp.DEFAULTS);
        HttpEngineServer server = serverFor(both, stateDir, "9.9.9-SNAPSHOT", null);
        try {
            HttpResponse<String> app = fetch(server, "app.js");
            assertThat(app.body()).isEqualTo("// root app");
            assertThat(app.headers().firstValue(StaticContent.OVERLAY_HEADER)).isEmpty();
        } finally {
            server.close();
        }
    }

    @Test
    void a_symlink_in_the_overlay_is_not_served(@TempDir Path overlay) throws Exception {
        Path secret = stateDir.resolve("overlay-secret.txt");
        Files.writeString(secret, "TOP SECRET");
        Symlinks.create(overlay.resolve("leak.txt"), secret);
        HttpEngineServer dev = serverFor(overlayConfig(overlay), stateDir, "9.9.9-SNAPSHOT", null);
        try {
            HttpResponse<String> resp = fetch(dev, "leak.txt");
            assertThat(resp.statusCode()).isNotEqualTo(200);
            assertThat(resp.body()).doesNotContain("TOP SECRET");
            Files.writeString(overlay.resolveSibling("outside.txt"), "outside");
            assertThat(fetch(dev, "%2e%2e/outside.txt").statusCode()).isEqualTo(404);
        } finally {
            dev.close();
        }
    }

    private static JkHttpConfig overlayConfig(Path overlay) {
        return new JkHttpConfig(
                "127.0.0.1", 0, 16, 16, JkHttpConfig.DEFAULT_WEB_ROOT, overlay.toString(), JkHttpConfig.Mcp.DEFAULTS);
    }

    /** A started server of its own, so a test can vary the config and the version the gate reads. */
    private HttpEngineServer serverFor(JkHttpConfig config, Path home, String version, @Nullable Consumer<String> log)
            throws IOException {
        HttpEngineServer started = new HttpEngineServer(
                config,
                home,
                stateDir.resolve(UUID.randomUUID() + ".http-token"),
                logFile,
                version,
                () -> snapshot,
                new HttpEvents(),
                stubJobs,
                testJournal(),
                () -> metricsRows,
                () -> cacheSnapshot,
                log);
        started.start();
        return started;
    }

    private HttpResponse<String> fetch(HttpEngineServer target, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(requireNonNull(target.url()) + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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
        assertThat(get("/code.js").body()).contains("export const CodeView");
        assertThat(get("/route.js").body()).contains("export function routeFromHash");
        assertThat(get("/fold.js").body()).contains("export function foldEvent");
        assertThat(get("/api.js").body()).contains("bootstrapToken");
        assertThat(get("/jk-logo.svg").headers().firstValue("Content-Type")).contains("image/svg+xml");
        assertThat(get("/jumpkick-logo.webp").headers().firstValue("Content-Type"))
                .contains("image/webp");
        HttpResponse<String> css = get("/style.css");
        assertThat(css.headers().firstValue("Content-Type")).contains("text/css; charset=utf-8");
        // Vue rides the CDN, version-pinned and integrity-locked (docs/contributors/webclient.md) — the shell
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
                .contains("font-src https://fonts.gstatic.com data:; worker-src 'self' blob:")
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
        // no-cache + stamp still revalidates after jk install of the same snapshot line.
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
}

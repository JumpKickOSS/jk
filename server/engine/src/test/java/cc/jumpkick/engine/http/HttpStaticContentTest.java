// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Static content over real HTTP: the disk web-root and its classpath fallback, the sandbox CSP,
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
        assertThat(get("/code.js").body()).contains("export const CodeView");
        assertThat(get("/route.js").body()).contains("export function routeFromHash");
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
}

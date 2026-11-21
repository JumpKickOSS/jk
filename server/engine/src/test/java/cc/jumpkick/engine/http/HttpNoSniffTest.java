// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every response the engine's HTTP server sends carries {@code X-Content-Type-Options: nosniff},
 * the ones a handler writes by hand (event streams, the streamed history, the log tail, MCP's empty
 * replies, the coverage redirect) as well as the ones the shared helpers write.
 */
@Tag("integration")
class HttpNoSniffTest extends HttpEngineServerHarness {

    @Test
    void every_kind_of_response_refuses_sniffing() throws Exception {
        assertNoSniff("static file", get("/hello.txt").headers());
        assertNoSniff("json", get("/api/status").headers());
        assertNoSniff("streamed history", get("/api/history").headers());
        assertNoSniff("log tail", get("/api/log").headers());
        assertNoSniff(
                "refused token",
                get("/api/status", "Authorization", "Bearer wrong").headers());

        HttpResponse<Stream<String>> events = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/events?access_token=" + token()))
                        .build(),
                HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> body = events.body()) {
            assertThat(events.statusCode()).isEqualTo(200);
            assertNoSniff("event stream", events.headers());
        }

        HttpResponse<Stream<String>> mcpEvents = openMcpEvents();
        try (Stream<String> body = mcpEvents.body()) {
            assertNoSniff("mcp event stream", mcpEvents.headers());
        }
        HttpResponse<String> mcpClose = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "mcp"))
                        .header("Authorization", "Bearer " + token())
                        .header("Mcp-Session-Id", "never-issued")
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(mcpClose.statusCode()).isEqualTo(404);
        assertNoSniff("mcp close", mcpClose.headers());

        Path ws = Files.createDirectories(stateDir.resolve("ws"));
        Files.createDirectories(ws.resolve("lib/target/site/jacoco"));
        Files.writeString(ws.resolve("lib/target/site/jacoco/index.html"), "<html></html>");
        String id = HttpCoverageReportTest.finished(
                testJournal(), ws, List.of(HttpCoverageReportTest.module(ws, "lib", 1, 1)));
        HttpResponse<String> redirect = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "report/" + token() + "/" + id + "/"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertNoSniff("coverage redirect", redirect.headers());
    }

    private static void assertNoSniff(String what, HttpHeaders headers) {
        assertThat(headers.allValues("X-Content-Type-Options")).as(what).containsExactly("nosniff");
    }
}

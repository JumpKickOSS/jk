// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.engine.activity.Activity;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.notices.Notices;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a client sent wrong is its error: malformed JSON, a bad percent escape or a string that is no
 * path is 400, never 500. A {@code HEAD} of a read is that read's headers, and what a plain answer
 * carries is not cached.
 */
class HttpRequestBoundaryTest extends HttpEngineServerHarness {

    @TempDir
    Path changes;

    @TempDir
    Path project;

    @BeforeEach
    void routes() {
        DecisionQueue queue = new DecisionQueue();
        server.setDecisions(queue);
        server.setNotices(new Notices());
        server.setActivity(new Activity(() -> new ChangeJournal(changes), queue, new Notices()));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path.substring(1)))
                        .header("Authorization", "Bearer " + token())
                        .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> head(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path.substring(1)))
                        .header("Authorization", "Bearer " + token())
                        .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The status line of an authorized raw GET: {@link java.net.http.HttpClient} refuses a bad escape. */
    private String rawGet(String target) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String request = "GET " + target + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Authorization: Bearer " + token() + "\r\n"
                    + "X-Jk-Engine-Epoch: " + SNAPSHOT.engineEpoch() + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(UTF_8));
            String response = new String(socket.getInputStream().readAllBytes(), UTF_8);
            return response.substring(0, response.indexOf("\r\n"));
        }
    }

    @Test
    void a_body_that_is_not_json_is_400() throws Exception {
        for (String path : List.of("/api/decisions/d1", "/api/notices/seen", "/api/events/s1")) {
            HttpResponse<String> resp = post(path, "{not json");
            assertThat(resp.statusCode()).as(path).isEqualTo(400);
            assertThat(resp.body()).as(path).contains("the body is not JSON");
        }
    }

    /** The server refuses a request target with a bad escape before any handler decodes it. */
    @Test
    void a_bad_percent_escape_in_the_query_is_400() throws Exception {
        assertThat(rawGet("/api/activity?kind=%zz")).contains(" 400 ");
        assertThat(rawGet("/api/changes?before=%zz")).contains(" 400 ");
        assertThat(rawGet("/api/activity?kind=build")).contains(" 200 ");
    }

    @Test
    void a_limit_that_is_no_count_is_400() throws Exception {
        for (String path : List.of("/api/project/find", "/api/project/dir")) {
            HttpResponse<String> notNumber = get(path + "?project=p&limit=x");
            assertThat(notNumber.statusCode()).as(path).isEqualTo(400);
            assertThat(notNumber.body()).as(path).contains("\\\"limit\\\" is not a number");
            HttpResponse<String> negative = get(path + "?project=p&limit=-1");
            assertThat(negative.statusCode()).as(path).isEqualTo(400);
            assertThat(negative.body()).as(path).contains("must not be negative");
        }
    }

    @Test
    void a_dir_that_is_no_path_is_400() throws Exception {
        assertThat(get("/api/project?dir=a%00b").statusCode()).isEqualTo(400);
        assertThat(get("/api/project/model?dir=a%00b").statusCode()).isEqualTo(400);
    }

    @Test
    void a_head_of_a_read_answers_its_headers_and_no_body() throws Exception {
        for (String path : List.of("/api/status", "/api/log", "/api/decisions", "/api/history")) {
            HttpResponse<String> resp = head(path);
            assertThat(resp.statusCode()).as(path).isEqualTo(200);
            assertThat(resp.body()).as(path).isEmpty();
        }
        assertThat(head("/api/log").headers().firstValue("Content-Type")).contains("text/plain; charset=utf-8");
    }

    @Test
    void a_head_never_opens_an_event_stream() throws Exception {
        HttpResponse<String> resp = head("/api/events");
        assertThat(resp.statusCode()).isEqualTo(405);
        assertThat(resp.headers().firstValue("Allow")).contains("GET");
    }

    @Test
    void a_plain_text_answer_is_not_cached() throws Exception {
        HttpResponse<String> resp = get("/api/no-such-endpoint");
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(resp.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void the_bearer_scheme_is_case_insensitive() throws Exception {
        assertThat(get("/api/status", "Authorization", "bearer " + token()).statusCode())
                .isEqualTo(200);
        assertThat(get("/api/status", "Authorization", "BEARER " + token()).statusCode())
                .isEqualTo(200);
        assertThat(get("/api/status", "Authorization", "Basic " + token()).statusCode())
                .isEqualTo(401);
    }

    @Test
    void an_artifact_is_served_with_the_projects_secrets_masked() throws Exception {
        Files.writeString(project.resolve("jk.toml"), "group = \"g\"\nname = \"a\"\nversion = \"1\"\n");
        Files.writeString(project.resolve(".env"), "TOKEN=artifact-s3cret\n");
        BuildJournal journal = testJournal();
        String locator = requireNonNull(journal.append(
                record(), new BuildJournal.Snapshot(null, null, "error: rejected artifact-s3cret at login\n")));
        String id = requireNonNull(journal.get(locator).orElseThrow().id());

        HttpResponse<String> resp = get("/api/history/artifact?id=" + id + "&name=" + BuildJournal.DIAGNOSTICS_TXT);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("error: rejected").doesNotContain("artifact-s3cret");
    }

    private BuildRecord record() {
        return new BuildRecord(
                null,
                1L,
                BuildRecord.SCHEMA,
                "build",
                project.toString(),
                "g:a",
                "p-1",
                1_700_000_000_000L,
                1_700_000_001_000L,
                1_000,
                false,
                false,
                1,
                "9.9-test",
                null,
                List.of(),
                List.of(),
                List.of(),
                "cli",
                null,
                null,
                null,
                false,
                null,
                3L,
                null,
                List.of());
    }
}

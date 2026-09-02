// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.BuildMetrics;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The project-facing reads over real HTTP: the module DAG, the identity-scoped file tree and its
 * editor, the metrics and cache breakdowns, the log tail, the filesystem browser, and the new-project
 * defaults. Fixture in {@link HttpEngineServerHarness}.
 */
@Tag("integration")
class HttpApiProjectTest extends HttpEngineServerHarness {

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

        HttpResponse<String> resp = get("/api/project/graph?dir=" + encDir(ws), "Authorization", "Bearer " + token());
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
        String soloBody = get("/api/project/graph?dir=" + encDir(solo), "Authorization", "Bearer " + token())
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
                        "/api/project/graph?dir=" + encDir(solo) + "&scopes="
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
        HttpResponse<String> broken = get("/api/project/graph?dir=" + encDir(ws), "Authorization", "Bearer " + token());
        assertThat(broken.statusCode()).isEqualTo(422);
        assertThat(broken.body()).contains("error").contains("gone");

        Path bad = stateDir.resolve("bad-toml");
        Files.createDirectories(bad);
        Files.writeString(bad.resolve("jk.toml"), "not [ valid toml ===");
        HttpResponse<String> malformed =
                get("/api/project/graph?dir=" + encDir(bad), "Authorization", "Bearer " + token());
        assertThat(malformed.statusCode()).isEqualTo(422);
        assertThat(malformed.body()).contains("error");

        // An ABSENT jk.toml stays a 200 empty graph — a deleted checkout is not an error.
        Path empty = stateDir.resolve("no-toml");
        Files.createDirectories(empty);
        HttpResponse<String> absent =
                get("/api/project/graph?dir=" + encDir(empty), "Authorization", "Bearer " + token());
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
        // over a raw socket like HttpEngineServerHarness#raw, with the bearer token added by hand.
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
        Path buildsRoot = stateDir.resolve("file-state");
        Path buildsDir = buildsRoot.resolve("builds");
        System.setProperty("jk.env.JK_STATE_DIR", buildsRoot.toString());
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
            var identity = ProjectIdentity.resolve(checkout);
            ProjectIdentity.IdentityFile.write(ProjectBuilds.projectHome(identity.id()), identity);
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

            HttpResponse<String> dirOnly = get("/api/project/file?dir=" + encDir(checkout) + "&path=src/Main.java");
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
            System.clearProperty("jk.env.JK_STATE_DIR");
        }
    }

    @Test
    void api_project_file_decodes_query_values_exactly_once() throws Exception {
        // getQuery() already percent-decodes, and a second URLDecoder pass mapped '+'
        // to space and truncated at a decoded '&' — so any file the tree listed with those
        // characters could never be opened, and %252e%252e relied on validation order alone.
        Path buildsRoot = stateDir.resolve("decode-state");
        Path buildsDir = buildsRoot.resolve("builds");
        System.setProperty("jk.env.JK_STATE_DIR", buildsRoot.toString());
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
            var identity = ProjectIdentity.resolve(checkout);
            ProjectIdentity.IdentityFile.write(ProjectBuilds.projectHome(identity.id()), identity);
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
            System.clearProperty("jk.env.JK_STATE_DIR");
        }
    }

    @Test
    void api_metrics_reports_aggregate_rows_with_the_token() throws Exception {
        var ok = new BuildMetrics.Stats(3, 6000, 1000, 3000);
        var empty = BuildMetrics.Stats.EMPTY;
        metricsRows.add(new BuildMetrics.Entry("build", "", null, null, ok, empty, empty, 5L));
        metricsRows.add(new BuildMetrics.Entry("build", "/p", "g:n", null, ok, empty, empty, 5L));
        metricsRows.add(new BuildMetrics.Entry(null, "/other", null, "compile-java", ok, empty, empty, 5L));

        HttpResponse<String> resp = get("/api/metrics", "Authorization", "Bearer " + token());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("application/json; charset=utf-8");
        assertThat(resp.body())
                .contains(scopeJson(BuildMetrics.SCOPE_GLOBAL))
                .contains(scopeJson(BuildMetrics.SCOPE_PROJECT))
                .contains(scopeJson(BuildMetrics.SCOPE_PROJECT_TASK))
                .contains("\"okCount\":3")
                .contains("\"okAvgMillis\":2000")
                .contains("\"coord\":\"g:n\"");

        // ?dir= keeps the global tiers but drops other projects' rows.
        String filtered =
                get("/api/metrics?dir=/p", "Authorization", "Bearer " + token()).body();
        assertThat(filtered).contains(scopeJson(BuildMetrics.SCOPE_GLOBAL)).contains("\"dir\":\"/p\"");
        assertThat(filtered).doesNotContain("/other");
    }

    @Test
    void api_metrics_is_an_empty_array_when_nothing_has_been_recorded() throws Exception {
        assertThat(get("/api/metrics", "Authorization", "Bearer " + token()).body())
                .isEqualTo("[]");
    }

    @Test
    void api_cache_reports_the_cache_breakdown_with_token() throws Exception {
        cacheSnapshot = new CacheSnapshot(
                100,
                5_000_000,
                40,
                200_000,
                0,
                0,
                3,
                30_000_000,
                2,
                100,
                6,
                4_096,
                268_435_456L,
                1_073_741_824L,
                1_700_000_000_000L,
                0,
                0,
                0,
                0,
                // The cache root as one tree — deliberately NOT casCount+actionsCount+… (that sum
                // would read 145 / 35,200,100 here and it counts store bytes a nuke leaves).
                77,
                9_000_000);
        HttpResponse<String> resp = get("/api/cache");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("application/json; charset=utf-8");
        assertThat(resp.body())
                .contains("\"casCount\":100")
                .contains("\"casBytes\":5000000")
                .contains("\"actionsCount\":40")
                .contains("\"workerJarsBytes\":30000000")
                // The total is the cache root walked as one tree, not the sum of the sections
                // above (that sum reads 145 / 35,200,100 for this fixture and includes store
                // bytes a nuke leaves). It is the same fact `jk status` prints as Size on Disk.
                .contains("\"totalCount\":77")
                .contains("\"totalBytes\":9000000")
                .contains("\"actionCacheBytes\":200000") // action index + cache CAS; format stamps are not budgeted
                .contains("\"actionMaxBytes\":1073741824") // fixture cache budget (1 GiB)
                // Store CAS + worker jars.
                .contains("\"artifactStorageBytes\":35000000")
                .doesNotContain("\"maxBytes\"") // the artifact store carries no budget
                .doesNotContain("runLogs") // the run-log tier is gone, not renamed
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
                get("/api/fs?dir=" + encDir(stateDir.resolve("workspace")), "Authorization", "Bearer " + token());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body())
                .contains("\"dirs\":[\"module-a\",\"module-b\"]")
                .contains("\"hasJkToml\":true")
                .contains("\"parent\":" + Jsonl.quote(stateDir.toString()));
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
        assertThat(get("/api/fs?dir=" + encDir(stateDir.resolve("no-such-dir")), "Authorization", "Bearer " + token())
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
            // Jsonl.quote, not hand-built quotes: a Windows path's backslashes are escaped in the
            // JSON the engine emits, so a raw path here only ever matches on POSIX.
            String dirField = "\"dir\":" + Jsonl.quote(pick.toString());
            assertThat(fromTilde.body())
                    .contains(dirField)
                    .contains("\"dirs\":[\"child-a\"]")
                    .contains("\"hasJkToml\":true");
            assertThat(fromRel.body()).contains(dirField);
        } finally {
            PathUtil.deleteRecursively(pick);
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
}

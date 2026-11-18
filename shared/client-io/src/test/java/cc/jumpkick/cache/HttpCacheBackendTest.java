// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.testing.FakeCacheServer;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HTTP client of the cache protocol, version 0, against a stub of the service ({@link
 * FakeCacheServer}) and against answers copied from the protocol document's own examples.
 */
class HttpCacheBackendTest {

    private static final String KEY_A = "a".repeat(64);
    private static final String KEY_B = "b".repeat(64);
    private static final String KEY_C = "c".repeat(64);

    private @Nullable HttpServer canned;

    @AfterEach
    void stop() {
        stopCanned();
    }

    private void stopCanned() {
        if (canned != null) canned.stop(0);
        canned = null;
    }

    private static HttpCacheBackend client(URI url, String token) {
        return new HttpCacheBackend(
                url, Http.failFast().withRequestTimeout(Duration.ofSeconds(5)), () -> Optional.of("Bearer " + token));
    }

    /** Section 3's example answer, verbatim. */
    private static final String CAPABILITIES = """
            {
              "protocol_versions": ["0"],
              "digest_functions": ["sha256"],
              "compression": ["identity"],
              "organisation": "org_dev",
              "identity": {"kind": "developer", "subject": "dev-alice"},
              "scope": {"read": ["trusted", "user:dev-alice"], "write": "user:dev-alice"},
              "dimensions": ["platform", "toolchain"],
              "limits": {
                "batch_entries": 1000,
                "batch_bytes": 4194304,
                "direct_transfer_min_bytes": 1048576,
                "record_max_bytes": 1048576,
                "blob_max_bytes": 5368709120,
                "signed_url_ttl_seconds": 900,
                "follow_max_depth": 8,
                "reads_max_files": 4096,
                "reads_candidates": 4
              },
              "status": "ok"
            }
            """;

    @Test
    void capabilities_read_as_the_protocol_document_shows_them() throws Exception {
        URI url = serve(Map.of("/v0/capabilities", CAPABILITIES));

        HttpCacheBackend.Capabilities caps = client(url, "t").capabilities();

        assertThat(caps.versions()).containsExactly("0");
        assertThat(caps.writeScope()).isEqualTo("user:dev-alice");
        assertThat(caps.writes()).isTrue();
        assertThat(caps.batchEntries()).isEqualTo(1000);
        assertThat(caps.batchBytes()).isEqualTo(4194304);
        assertThat(caps.directTransferMinBytes()).isEqualTo(1048576);
        assertThat(caps.readsMaxFiles()).isEqualTo(4096);
        assertThat(caps.readsCandidates()).isEqualTo(4);
    }

    @Test
    void a_caller_with_no_write_scope_or_an_organisation_over_its_cap_writes_nothing() throws Exception {
        String forkCaller = CAPABILITIES.replace(", \"write\": \"user:dev-alice\"", "");
        assertThat(client(serve(Map.of("/v0/capabilities", forkCaller)), "t").writable())
                .as("no scope.write")
                .isFalse();
        stopCanned();
        String overCap = CAPABILITIES.replace("\"status\": \"ok\"", "\"status\": \"read-only\"");
        assertThat(client(serve(Map.of("/v0/capabilities", overCap)), "t").writable())
                .as("status read-only")
                .isFalse();
    }

    @Test
    void a_service_of_another_protocol_version_or_a_disabled_cache_is_a_failure() throws Exception {
        URI other = serve(Map.of("/v0/capabilities", CAPABILITIES.replace("[\"0\"]", "[\"1\"]")));
        assertThatThrownBy(() -> client(other, "t").records(new CacheBackend.Lookup(List.of(KEY_A), "linux-x86_64")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("protocol 1");
        stopCanned();
        URI disabled = serve(Map.of("/v0/capabilities", CAPABILITIES.replace("\"ok\"", "\"disabled\"")));
        assertThat(client(disabled, "t").writable()).isFalse();
        assertThatThrownBy(() -> client(disabled, "t").records(new CacheBackend.Lookup(List.of(KEY_A), "linux-x86_64")))
                .hasMessageContaining("disabled");
    }

    @Test
    void a_lookup_sends_the_documents_body_and_reads_its_answer() throws Exception {
        // Section 6.2's example answer, with keys and a record in place of its ellipses.
        String record = "TASK compile-main@3f\\nKEY " + KEY_A + "\\nEND 0\\n";
        String answer = """
                {
                  "found": [{
                    "key": "%s",
                    "kind": "java:compile",
                    "writes": "outputs",
                    "scope": "trusted",
                    "record": "%s",
                    "host": "linux-x86_64",
                    "toolchain": "toolchain:jdk:eclipse-adoptium/27.0.1+9-LTS/9b2e",
                    "stored_by": "ci-protected:acme/shop",
                    "stored_at": "2026-11-15T17:03:11Z"
                  }],
                  "missing": ["%s", "%s"],
                  "followed": [],
                  "by_reads": []
                }
                """.formatted(KEY_A, record, KEY_B, KEY_C);
        Map<String, String> bodies = new LinkedHashMap<>();
        URI url = serve(Map.of("/v0/capabilities", CAPABILITIES, "/v0/records/lookup", answer), bodies);
        CacheBackend.Accept corretto =
                new CacheBackend.Accept("toolchain:jdk:oracle-corporation/27.0.1+9/9b2e", 25, "major", "amazon", "27");

        Map<String, String> found = client(url, "t")
                .records(new CacheBackend.Lookup(
                        List.of(KEY_A, KEY_B, KEY_C), "macos-aarch64", Map.of(KEY_A, corretto, KEY_B, corretto)));

        assertThat(found).containsOnlyKeys(KEY_A);
        assertThat(found.get(KEY_A)).isEqualTo("TASK compile-main@3f\nKEY " + KEY_A + "\nEND 0\n");
        Object sent = MiniJson.parse(requireNonNull(bodies.get("/v0/records/lookup")));
        assertThat(MiniJson.list(sent, "keys")).isEqualTo(List.of(KEY_A, KEY_B, KEY_C));
        assertThat(MiniJson.str(sent, "platform")).isEqualTo("macos-aarch64");
        assertThat(number(MiniJson.get(sent, "follow"))).isZero();
        List<?> accept = MiniJson.list(sent, "accept");
        assertThat(accept)
                .as("one entry for the two keys sharing a policy; none for the third")
                .hasSize(1);
        Object entry = accept.getFirst();
        assertThat(MiniJson.list(entry, "keys")).isEqualTo(List.of(KEY_A, KEY_B));
        assertThat(MiniJson.str(entry, "toolchain")).isEqualTo("toolchain:jdk:oracle-corporation/27.0.1+9/9b2e");
        assertThat(number(MiniJson.get(entry, "level"))).isEqualTo(25);
        assertThat(MiniJson.str(entry, "match")).isEqualTo("major");
        assertThat(MiniJson.str(MiniJson.get(entry, "require"), "vendor")).isEqualTo("amazon");
        assertThat(MiniJson.str(MiniJson.get(entry, "require"), "version")).isEqualTo("27");
    }

    @Test
    void results_found_by_read_list_come_with_their_lists() throws Exception {
        // Section 6.2's by_reads entry, with keys and a record in place of its ellipses.
        String answer = """
                {
                  "found": [],
                  "missing": ["%s"],
                  "followed": [],
                  "by_reads": [{
                    "key": "%s", "kind": "java:run-tests", "writes": "test-marker", "scope": "trusted",
                    "record": "TASK run-tests@3f\\nKEY %s\\nEND 0\\n",
                    "host": "linux-x86_64", "toolchain": "toolchain:jdk:eclipse-adoptium/25.0.5+11-LTS/3f1c",
                    "reads": {"base": "%s", "files": ["ws:src/test/resources/fixtures.json"]},
                    "stored_by": "ci-protected:acme/shop", "stored_at": "2026-11-15T17:03:11Z"
                  }]
                }
                """.formatted(KEY_A, KEY_B, KEY_B, KEY_A);
        URI url = serve(Map.of("/v0/capabilities", CAPABILITIES, "/v0/records/lookup", answer));

        CacheBackend.Answer got = client(url, "t").lookup(new CacheBackend.Lookup(List.of(KEY_A), "linux-x86_64"));

        assertThat(got.records()).isEmpty();
        assertThat(got.byReads().get(KEY_A)).singleElement().satisfies(c -> {
            assertThat(c.key()).isEqualTo(KEY_B);
            assertThat(c.recordText()).isEqualTo("TASK run-tests@3f\nKEY " + KEY_B + "\nEND 0\n");
            assertThat(c.reads())
                    .isEqualTo(new CacheBackend.Reads(KEY_A, List.of("ws:src/test/resources/fixtures.json")));
        });
    }

    @Test
    void a_put_carries_the_suites_read_list_and_a_lookup_of_its_base_finds_it() throws Exception {
        try (FakeCacheServer server = FakeCacheServer.start()) {
            HttpCacheBackend writer = client(server.url(), FakeCacheServer.WRITER);
            String marker = marker(KEY_B);
            CacheBackend.Reads reads = new CacheBackend.Reads(KEY_A, List.of("ws:data/fixture.txt"));

            writer.putRecord(KEY_B, marker, reads);

            CacheBackend.Answer got = client(server.url(), FakeCacheServer.READER)
                    .lookup(new CacheBackend.Lookup(List.of(KEY_A), "linux-x86_64"));
            assertThat(got.byReads().get(KEY_A))
                    .singleElement()
                    .isEqualTo(new CacheBackend.Candidate(KEY_B, marker, reads));
        }
    }

    @Test
    void every_request_names_the_protocol_and_a_found_record_needs_its_blobs() throws Exception {
        try (FakeCacheServer server = FakeCacheServer.start()) {
            byte[] payload = "class bytes".getBytes(StandardCharsets.UTF_8);
            String sha = FakeCacheServer.sha256(payload);
            String text = record(KEY_A, sha, payload.length);
            server.seed(KEY_A, text, Map.of(), null);
            HttpCacheBackend reader = client(server.url(), FakeCacheServer.READER);
            Map<String, String> none = reader.records(new CacheBackend.Lookup(List.of(KEY_A), "linux-x86_64"));
            assertThat(none).as("the record's blob is not held").isEmpty();

            server.seed(KEY_B, record(KEY_B, sha, payload.length), Map.of(sha, payload), null);
            Map<String, String> found = reader.records(new CacheBackend.Lookup(List.of(KEY_A, KEY_B), "linux-x86_64"));
            assertThat(found).containsOnlyKeys(KEY_A, KEY_B);
            assertThat(server.calls()).allMatch(c -> c.endsWith(" reader"));
        }
    }

    @Test
    void blobs_move_in_batches_and_by_signed_request_and_the_store_never_sees_the_credential(@TempDir Path tmp)
            throws Exception {
        try (FakeCacheServer server = FakeCacheServer.start().directTransferFrom(64)) {
            HttpCacheBackend writer = client(server.url(), FakeCacheServer.WRITER);
            Path small = Files.writeString(tmp.resolve("small"), "tiny");
            Path large = Files.writeString(tmp.resolve("large"), "x".repeat(10_000));
            String smallSha = FakeCacheServer.sha256(Files.readAllBytes(small));
            String largeSha = FakeCacheServer.sha256(Files.readAllBytes(large));
            Map<String, Long> sizes = Map.of(smallSha, 4L, largeSha, 10_000L);

            assertThat(writer.missing(sizes)).containsExactlyInAnyOrder(smallSha, largeSha);
            writer.putBlobs(Map.of(smallSha, small, largeSha, large));
            assertThat(writer.missing(sizes)).isEmpty();
            assertThat(writer.missing(Map.of(smallSha, 5L)))
                    .as("held at another size")
                    .containsExactly(smallSha);

            Map<String, byte[]> got = new LinkedHashMap<>();
            client(server.url(), FakeCacheServer.READER).fetch(sizes, (sha, in) -> got.put(sha, in.readAllBytes()));
            assertThat(got.get(smallSha)).isEqualTo(Files.readAllBytes(small));
            assertThat(got.get(largeSha)).isEqualTo(Files.readAllBytes(large));
            assertThat(server.calls())
                    .contains("POST /v0/blobs/batch-upload writer", "POST /v0/blobs/upload-urls writer")
                    .contains("POST /v0/blobs/commit writer", "POST /v0/blobs/batch-read reader")
                    .contains("POST /v0/blobs/download-urls reader");
            assertThat(server.storeAuthorizations()).hasSize(2).containsOnly("-");
        }
    }

    @Test
    void a_blob_the_service_does_not_hold_is_not_found() throws Exception {
        try (FakeCacheServer server = FakeCacheServer.start()) {
            assertThatThrownBy(() -> client(server.url(), FakeCacheServer.READER)
                            .fetch(Map.of(KEY_A, 3L), (sha, in) -> in.readAllBytes()))
                    .isInstanceOf(NoSuchFileException.class);
        }
    }

    @Test
    void a_put_answers_by_the_problem_documents_code(@TempDir Path tmp) throws Exception {
        try (FakeCacheServer server = FakeCacheServer.start()) {
            HttpCacheBackend writer = client(server.url(), FakeCacheServer.WRITER);
            byte[] payload = "class bytes".getBytes(StandardCharsets.UTF_8);
            String sha = FakeCacheServer.sha256(payload);
            String text = record(KEY_A, sha, payload.length);

            assertThatThrownBy(() -> writer.putRecord(KEY_A, text))
                    .as("412 blobs_missing names the blobs")
                    .isInstanceOfSatisfying(CacheBackend.BlobsMissing.class, e -> assertThat(e.missing())
                            .containsExactly(sha));
            writer.putBlobs(Map.of(sha, Files.write(tmp.resolve("blob"), payload)));
            writer.putRecord(KEY_A, text);
            writer.putRecord(KEY_A, text.replace("END 1", "WALL 3\nEND 1"));
            assertThat(server.records().get(KEY_A))
                    .as("409 conflict keeps the first")
                    .hasSize(1);
            assertThatThrownBy(() -> writer.putRecord(KEY_A, text.replace("KEY " + KEY_A, "KEY " + KEY_B)))
                    .as("422 invalid skips this record")
                    .isInstanceOf(CacheBackend.Skipped.class);
            assertThatThrownBy(
                            () -> client(server.url(), FakeCacheServer.READER).putRecord(KEY_A, text))
                    .as("403 forbidden: no later write would be taken")
                    .isInstanceOf(CacheBackend.WritesRefused.class)
                    .hasMessageContaining("forbidden");
            server.unavailableFor(1);
            assertThatThrownBy(() -> writer.putRecord(KEY_B, text.replace("KEY " + KEY_A, "KEY " + KEY_B)))
                    .as("503 unavailable: worth trying again")
                    .isInstanceOf(CacheBackend.Unavailable.class)
                    .hasMessageContaining("503 from the service");
            assertThatThrownBy(() -> client(server.url(), "not-a-token")
                            .records(new CacheBackend.Lookup(List.of(KEY_A), "linux-x86_64")))
                    .hasMessageContaining("401 unauthenticated")
                    .hasMessageNotContaining("not-a-token");
        }
    }

    @Test
    void a_silent_service_fails_within_the_requests_timeout() throws Exception {
        try (FakeCacheServer server = FakeCacheServer.start().stall(10_000)) {
            HttpCacheBackend reader = new HttpCacheBackend(
                    server.url(),
                    Http.failFast().withRequestTimeout(Duration.ofMillis(300)),
                    () -> Optional.of("Bearer " + FakeCacheServer.READER));
            long start = System.nanoTime();
            assertThatThrownBy(() -> reader.records(new CacheBackend.Lookup(List.of(KEY_A), "linux-x86_64")))
                    .isInstanceOf(IOException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }
    }

    /** An outputs record naming one blob, as {@link ActionRecordText} writes it. */
    static String record(String key, String sha, long size) {
        return ActionRecordText.render(
                "compile-main@3f",
                key,
                Map.of("src", "ws:src/A.java"),
                Map.of("classes/A.class", sha),
                Set.of(),
                List.of(),
                new ActionRecordText.Meta(
                        ActionRecordText.FORMAT,
                        "java:compile",
                        ActionRecordText.Writes.OUTPUTS,
                        null,
                        null,
                        "linux-x86_64",
                        null,
                        null,
                        -1,
                        Map.of("classes/A.class", size),
                        Map.of()));
    }

    /** A green test marker, as {@link ActionRecordText} writes it. */
    static String marker(String key) {
        return ActionRecordText.render(
                "run-tests@3f",
                key,
                Map.of(),
                Map.of("tests.total", "3", "tests.failed", "0"),
                Set.of(),
                List.of(),
                new ActionRecordText.Meta(
                        ActionRecordText.FORMAT,
                        "java:run-tests",
                        ActionRecordText.Writes.TEST_MARKER,
                        null,
                        null,
                        "linux-x86_64",
                        null,
                        null,
                        -1,
                        Map.of(),
                        Map.of()));
    }

    private static long number(@Nullable Object json) {
        return json instanceof Number n ? n.longValue() : -1;
    }

    private URI serve(Map<String, String> answers) throws IOException {
        return serve(answers, new LinkedHashMap<>());
    }

    /** A server answering each path with its canned JSON, keeping each request body by path. */
    private URI serve(Map<String, String> answers, Map<String, String> bodies) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        canned = server;
        server.createContext("/", ex -> {
            try (ex) {
                ByteArrayOutputStream in = new ByteArrayOutputStream();
                ex.getRequestBody().transferTo(in);
                String path = ex.getRequestURI().getPath();
                synchronized (bodies) {
                    bodies.put(path, in.toString(StandardCharsets.UTF_8));
                }
                String answer = answers.get(path);
                byte[] bytes = (answer == null ? "{}" : answer).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(answer == null ? 404 : 200, bytes.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}

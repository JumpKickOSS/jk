// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.DomXml;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.run.JkThreads;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class S3TransportTest {

    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(JkThreads.platform());
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private S3Transport signed() {
        return new S3Transport(
                new Http(),
                endpoint,
                "us-east-1",
                Optional.of(new AwsCredentials("AKIDEXAMPLE", "secret", null, "us-east-1")),
                () -> Instant.parse("2015-08-30T12:36:00Z"));
    }

    @Test
    void fetch_uses_path_style_url_and_signs() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> amzDate = new AtomicReference<>();
        // Path-style: /<bucket>/<key>
        server.createContext("/my-bucket/maven/g/a/1/a-1.jar", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            amzDate.set(ex.getRequestHeaders().getFirst("x-amz-date"));
            byte[] b = "jar".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });

        Optional<byte[]> body =
                signed().fetch(URI.create("s3://my-bucket/maven/g/a/1/a-1.jar"), RepoCredential.ANONYMOUS);

        assertThat(body).isPresent();
        assertThat(new String(body.get(), StandardCharsets.UTF_8)).isEqualTo("jar");
        assertThat(amzDate.get()).isEqualTo("20150830T123600Z");
        assertThat(auth.get())
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/s3/aws4_request")
                .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date")
                .contains("Signature=");
    }

    @Test
    void missing_object_is_empty() throws Exception {
        server.createContext("/my-bucket/missing.jar", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        assertThat(signed().fetch(URI.create("s3://my-bucket/missing.jar"), RepoCredential.ANONYMOUS))
                .isEmpty();
    }

    @Test
    void fetchStream_streams_the_signed_object() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        byte[] big = new byte[256 * 1024];
        Arrays.fill(big, (byte) 'j');
        server.createContext("/my-bucket/maven/g/a/1/a-1.jar", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.sendResponseHeaders(200, big.length);
            ex.getResponseBody().write(big);
            ex.close();
        });

        Optional<InputStream> body =
                signed().fetchStream(URI.create("s3://my-bucket/maven/g/a/1/a-1.jar"), RepoCredential.ANONYMOUS);

        assertThat(body).isPresent();
        try (InputStream in = body.get()) {
            assertThat(in.readAllBytes()).isEqualTo(big);
        }
        assertThat(auth.get()).startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/s3/aws4_request");
    }

    @Test
    void fetchStream_of_a_missing_object_is_empty_and_an_error_status_throws() throws Exception {
        server.createContext("/my-bucket/missing.jar", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/my-bucket/forbidden.jar", ex -> {
            byte[] b = "<Error/>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(403, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });

        assertThat(signed().fetchStream(URI.create("s3://my-bucket/missing.jar"), RepoCredential.ANONYMOUS))
                .isEmpty();
        assertThatThrownBy(() ->
                        signed().fetchStream(URI.create("s3://my-bucket/forbidden.jar"), RepoCredential.ANONYMOUS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("403");
    }

    @Test
    void put_signs_with_body_hash_and_returns_status() throws Exception {
        AtomicReference<String> contentSha = new AtomicReference<>();
        AtomicReference<byte[]> seenBody = new AtomicReference<>();
        server.createContext("/my-bucket/up.jar", ex -> {
            contentSha.set(ex.getRequestHeaders().getFirst("x-amz-content-sha256"));
            seenBody.set(ex.getRequestBody().readAllBytes());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });

        byte[] payload = {1, 2, 3};
        int status = signed().put(
                        URI.create("s3://my-bucket/up.jar"),
                        payload,
                        "application/java-archive",
                        RepoCredential.ANONYMOUS);

        assertThat(status).isEqualTo(200);
        assertThat(seenBody.get()).isEqualTo(payload);
        assertThat(contentSha.get()).isEqualTo(SigV4Signer.sha256Hex(payload));
    }

    @Test
    void put_file_signs_with_the_file_hash_and_streams_the_file(@TempDir Path dir) throws Exception {
        AtomicReference<String> contentSha = new AtomicReference<>();
        AtomicReference<byte[]> seenBody = new AtomicReference<>();
        server.createContext("/my-bucket/up.jar", ex -> {
            contentSha.set(ex.getRequestHeaders().getFirst("x-amz-content-sha256"));
            seenBody.set(ex.getRequestBody().readAllBytes());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });

        byte[] payload = {4, 5, 6};
        Path file = Files.write(dir.resolve("up.jar"), payload);
        int status = signed().putFile(
                        URI.create("s3://my-bucket/up.jar"),
                        file,
                        "application/java-archive",
                        RepoCredential.ANONYMOUS);

        assertThat(status).isEqualTo(200);
        assertThat(seenBody.get()).isEqualTo(payload);
        assertThat(contentSha.get()).isEqualTo(SigV4Signer.sha256Hex(payload));
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "conflict", "embedded conflict", "completion error", "part error"})
    void multipart_splits_streams_and_aborts_unsuccessful_uploads(String outcome, @TempDir Path dir) throws Exception {
        long partSize = 16L * 1024 * 1024;
        Path file = dir.resolve("large.jar");
        try (var sparse = new RandomAccessFile(file.toFile(), "rw")) {
            for (int i = 0; i < 5; i++) {
                sparse.seek(i * partSize);
                sparse.write(i + 1);
            }
        }

        var firstBatch = new CountDownLatch(4);
        Map<Integer, Long> sizes = new ConcurrentHashMap<>();
        Map<Integer, Integer> firstBytes = new ConcurrentHashMap<>();
        Map<Integer, String> hashes = new ConcurrentHashMap<>();
        var completion = new AtomicReference<String>();
        var completionAuth = new AtomicReference<String>();
        var condition = new AtomicReference<String>();
        var aborted = new AtomicBoolean();
        server.createContext("/bucket/large.jar", exchange -> {
            try (exchange) {
                String query = exchange.getRequestURI().getRawQuery();
                String method = exchange.getRequestMethod();
                if ("uploads".equals(query) && "POST".equals(method)) {
                    byte[] response = "<InitiateMultipartUploadResult><UploadId>id+/=</UploadId>"
                            .concat("</InitiateMultipartUploadResult>")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } else if (query != null
                        && query.startsWith("uploadId=id%2B%2F%3D&partNumber=")
                        && "PUT".equals(method)) {
                    int part = Integer.parseInt(query.substring(query.indexOf("partNumber=") + "partNumber=".length()));
                    firstBatch.countDown();
                    try {
                        if (!firstBatch.await(10, TimeUnit.SECONDS)) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                    var digest = Hashing.newSha256();
                    try (var input = new DigestInputStream(exchange.getRequestBody(), digest)) {
                        firstBytes.put(part, input.read());
                        sizes.put(part, 1 + input.transferTo(OutputStream.nullOutputStream()));
                    }
                    hashes.put(
                            part,
                            Hashing.hex(digest.digest())
                                            .equals(exchange.getRequestHeaders().getFirst("x-amz-content-sha256"))
                                    ? "match"
                                    : "mismatch");
                    exchange.getResponseHeaders().set("ETag", "\"etag-" + part + "\"");
                    exchange.sendResponseHeaders(outcome.equals("part error") && part == 2 ? 403 : 200, -1);
                } else if ("uploadId=id%2B%2F%3D".equals(query) && "POST".equals(method)) {
                    completion.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    completionAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    condition.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
                    String xml =
                            switch (outcome) {
                                case "completion error" -> "<Error><Code>InternalError</Code></Error>";
                                case "embedded conflict" -> "<Error><Code>PreconditionFailed</Code></Error>";
                                default -> "<CompleteMultipartUploadResult/>";
                            };
                    byte[] response = xml.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(outcome.equals("conflict") ? 412 : 200, response.length);
                    exchange.getResponseBody().write(response);
                } else if ("uploadId=id%2B%2F%3D".equals(query) && "DELETE".equals(method)) {
                    aborted.set(true);
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    exchange.sendResponseHeaders(400, -1);
                }
            }
        });

        var transport = signed();
        URI uri = URI.create("s3://bucket/large.jar");
        if (outcome.equals("completion error") || outcome.equals("part error")) {
            assertThatThrownBy(() -> transport.putFile(uri, file, "application/java-archive", RepoCredential.ANONYMOUS))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(outcome.equals("completion error") ? "InternalError" : "returned 403");
        } else {
            assertThat(transport.putFile(uri, file, "application/java-archive", RepoCredential.ANONYMOUS))
                    .isEqualTo(outcome.equals("complete") ? 200 : 412);
        }
        assertThat(aborted.get()).isEqualTo(!outcome.equals("complete"));
        if (outcome.equals("part error")) {
            assertThat(completion.get()).isNull();
            assertThat(sizes).hasSize(4);
            return;
        }
        assertThat(sizes).isEqualTo(Map.of(1, partSize, 2, partSize, 3, partSize, 4, partSize, 5, 1L));
        assertThat(firstBytes).isEqualTo(Map.of(1, 1, 2, 2, 3, 3, 4, 4, 5, 5));
        assertThat(hashes.values()).hasSize(5).containsOnly("match");
        assertThat(condition.get()).isEqualTo("*");
        assertThat(completionAuth.get()).contains("SignedHeaders=content-type;host;if-none-match;");
        var manifest = DomXml.parse(completion.get()).getDocumentElement();
        assertThat(DomXml.childElements(manifest, "Part"))
                .extracting(part -> DomXml.childText(part, "PartNumber"), part -> DomXml.childText(part, "ETag"))
                .containsExactly(
                        tuple("1", "\"etag-1\""),
                        tuple("2", "\"etag-2\""),
                        tuple("3", "\"etag-3\""),
                        tuple("4", "\"etag-4\""),
                        tuple("5", "\"etag-5\""));
    }

    @Test
    void gcs_large_artifact_uses_one_generation_guarded_put(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("large.jar");
        long length = 64L * 1024 * 1024;
        try (var sparse = new RandomAccessFile(file.toFile(), "rw")) {
            sparse.setLength(length);
        }
        var request = new AtomicReference<String>();
        server.createContext("/bucket/large.jar", exchange -> {
            try (exchange) {
                var headers = exchange.getRequestHeaders();
                long received = exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
                request.set(exchange.getRequestMethod() + " " + received + " "
                        + headers.getFirst("If-None-Match") + " " + headers.getFirst("x-goog-if-generation-match")
                        + " " + headers.getFirst("Authorization"));
                exchange.sendResponseHeaders(200, -1);
            }
        });
        URI uri = URI.create("gs://bucket/large.jar");
        var config = new ObjectStoreConfig(null, endpoint.toString(), "GOOGAK", "secret", null);
        var gcs = S3Transport.forGcs(new Http(), uri, config, new AwsCredentialChain(k -> null, dir), k -> null);
        assertThat(gcs.putFile(uri, file, "application/java-archive", RepoCredential.ANONYMOUS))
                .isEqualTo(200);
        assertThat(request.get())
                .startsWith("PUT " + length + " * 0 GOOG4-HMAC-SHA256 ")
                .contains("SignedHeaders=content-type;host;if-none-match;x-goog-content-sha256;x-goog-date;"
                        + "x-goog-if-generation-match");
    }

    @Test
    void gcs_replaces_metadata_using_the_generation_and_etag_from_the_same_read(@TempDir Path dir) throws Exception {
        var condition = new AtomicReference<String>();
        var generation = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        server.createContext("/bucket/maven-metadata.xml", exchange -> {
            try (exchange) {
                if ("GET".equals(exchange.getRequestMethod())) {
                    byte[] body = "<metadata/>".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("ETag", "\"etag\"");
                    exchange.getResponseHeaders().set("x-goog-generation", "123");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } else {
                    var headers = exchange.getRequestHeaders();
                    condition.set(headers.getFirst("If-Match"));
                    generation.set(headers.getFirst("x-goog-if-generation-match"));
                    authorization.set(headers.getFirst("Authorization"));
                    exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
                    exchange.sendResponseHeaders(200, -1);
                }
            }
        });
        URI uri = URI.create("gs://bucket/maven-metadata.xml");
        var config = new ObjectStoreConfig(null, endpoint.toString(), "GOOGAK", "secret", null);
        var gcs = S3Transport.forGcs(new Http(), uri, config, new AwsCredentialChain(k -> null, dir), k -> null);
        var previous = gcs.readRevision(uri, RepoCredential.ANONYMOUS);
        assertThat(previous).isPresent();
        assertThat(gcs.putRevision(uri, new byte[] {1}, "application/xml", RepoCredential.ANONYMOUS, previous))
                .isEqualTo(200);
        assertThat(condition.get()).isEqualTo("\"etag\"");
        assertThat(generation.get()).isEqualTo("123");
        assertThat(authorization.get())
                .contains("SignedHeaders=content-type;host;if-match;x-goog-content-sha256;")
                .contains("x-goog-if-generation-match");
    }

    @Test
    void gcs_via_endpoint_override_signs_with_auto_region() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/gcs-bucket/maven/x.jar", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = "obj".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        // HMAC keys via the AWS env chain; AWS_ENDPOINT_URL points at our server.
        var chain = new AwsCredentialChain(
                k -> switch (k) {
                    case "AWS_ACCESS_KEY_ID" -> "GOOGAK";
                    case "AWS_SECRET_ACCESS_KEY" -> "googsk";
                    default -> null;
                },
                Path.of("/nonexistent"));
        var gcs = S3Transport.forGcs(
                new Http(),
                URI.create("gs://gcs-bucket/maven/x.jar"),
                chain,
                k -> "AWS_ENDPOINT_URL".equals(k) ? endpoint.toString() : null);

        var body = gcs.fetch(URI.create("gs://gcs-bucket/maven/x.jar"), RepoCredential.ANONYMOUS);
        assertThat(body).isPresent();
        assertThat(auth.get()).startsWith("GOOG4-HMAC-SHA256 ").contains("/auto/storage/goog4_request");
    }

    @Test
    void forS3_applies_explicit_config_over_env() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/cfg-bucket/x.jar", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });

        var cfg = new ObjectStoreConfig("eu-central-1", endpoint.toString(), "CFGAK", "cfgsk", null);
        // Empty chain + empty env: config must supply region/endpoint/creds.
        var chain = new AwsCredentialChain(k -> null, Path.of("/nonexistent"));
        S3Transport t = S3Transport.forS3(new Http(), URI.create("s3://cfg-bucket/x.jar"), cfg, chain, k -> null);

        assertThat(t.fetch(URI.create("s3://cfg-bucket/x.jar"), RepoCredential.ANONYMOUS))
                .isPresent();
        assertThat(auth.get()).contains("Credential=CFGAK/").contains("/eu-central-1/s3/aws4_request");
    }

    @Test
    void anonymous_when_no_credentials_sends_no_authorization() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        auth.set("unset");
        server.createContext("/public/x.jar", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.sendResponseHeaders(200, 0);
            ex.close();
        });

        S3Transport anon = new S3Transport(
                new Http(), endpoint, "us-east-1", Optional.empty(), () -> Instant.parse("2015-08-30T12:36:00Z"));
        anon.fetch(URI.create("s3://public/x.jar"), RepoCredential.ANONYMOUS);
        assertThat(auth.get()).isNull(); // no Authorization header on unsigned requests
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.s3.AwsCredentials;
import cc.jumpkick.repo.s3.S3Transport;
import cc.jumpkick.run.JkThreads;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenYankTest {

    private static final String GROUP = "com.example";
    private static final String ARTIFACT = "widget";
    private static final String DIR = "com/example/widget/";
    private static final String METADATA = DIR + "maven-metadata.xml";

    private static byte[] metadata(String... versions) {
        MavenMetadata md = MavenMetadata.empty(GROUP, ARTIFACT);
        for (String v : versions) md = md.withVersion(v);
        return md.render();
    }

    private static MavenMetadata parsed(byte[] body) {
        return MavenMetadata.parse(body, GROUP, ARTIFACT);
    }

    @Nested
    class OnAFileRepository {

        @TempDir
        Path repo;

        private MavenYank yank() {
            return new MavenYank(repo.toUri(), new FileTransport(), RepoCredential.ANONYMOUS);
        }

        @BeforeEach
        void seed() throws IOException {
            write(METADATA, metadata("1.0.0", "1.1.0"));
            for (String v : List.of("1.0.0", "1.1.0")) {
                write(DIR + v + "/widget-" + v + ".jar", "jar " + v);
                write(DIR + v + "/widget-" + v + ".pom", "pom " + v);
            }
        }

        @Test
        void delists_the_version_and_keeps_its_files() throws Exception {
            MavenYank.Outcome outcome = yank().yank(GROUP, ARTIFACT, "1.1.0", false, false);

            assertThat(outcome.listed()).isTrue();
            assertThat(outcome.before()).containsExactly("1.0.0", "1.1.0");
            assertThat(outcome.after()).containsExactly("1.0.0");
            MavenMetadata now = parsed(read(METADATA));
            assertThat(now.versions()).containsExactly("1.0.0");
            assertThat(now.latest()).isEqualTo("1.0.0");
            assertThat(now.release()).isEqualTo("1.0.0");
            assertThat(new String(read(METADATA + ".sha256"), StandardCharsets.US_ASCII))
                    .isEqualTo(Hashing.sha256Hex(read(METADATA)));
            assertThat(repo.resolve(DIR + "1.1.0/widget-1.1.0.jar")).exists();
        }

        @Test
        void a_second_yank_changes_nothing() throws Exception {
            yank().yank(GROUP, ARTIFACT, "1.1.0", false, false);
            byte[] after = read(METADATA);

            MavenYank.Outcome again = yank().yank(GROUP, ARTIFACT, "1.1.0", false, false);

            assertThat(again.nothing()).isTrue();
            assertThat(read(METADATA)).isEqualTo(after);
        }

        @Test
        void yanking_every_version_leaves_an_empty_list() throws Exception {
            yank().yank(GROUP, ARTIFACT, "1.1.0", false, false);
            MavenYank.Outcome last = yank().yank(GROUP, ARTIFACT, "1.0.0", false, false);

            assertThat(last.after()).isEmpty();
            MavenMetadata now = parsed(read(METADATA));
            assertThat(now.versions()).isEmpty();
            assertThat(now.latest()).isNull();
            assertThat(now.release()).isNull();
        }

        @Test
        void delete_removes_the_version_directory_and_nothing_else() throws Exception {
            MavenYank.Outcome outcome = yank().yank(GROUP, ARTIFACT, "1.1.0", true, false);

            assertThat(outcome.files()).containsExactly(DIR + "1.1.0/widget-1.1.0.jar", DIR + "1.1.0/widget-1.1.0.pom");
            assertThat(outcome.refused()).isEmpty();
            assertThat(repo.resolve(DIR + "1.1.0")).doesNotExist();
            assertThat(repo.resolve(DIR + "1.0.0/widget-1.0.0.jar")).exists();
            assertThat(parsed(read(METADATA)).versions()).containsExactly("1.0.0");
        }

        @Test
        void delete_after_a_plain_yank_still_removes_the_files() throws Exception {
            yank().yank(GROUP, ARTIFACT, "1.1.0", false, false);

            MavenYank.Outcome outcome = yank().yank(GROUP, ARTIFACT, "1.1.0", true, false);

            assertThat(outcome.listed()).isFalse();
            assertThat(outcome.nothing()).isFalse();
            assertThat(repo.resolve(DIR + "1.1.0")).doesNotExist();
        }

        @Test
        void a_dry_run_reports_the_change_and_makes_none() throws Exception {
            byte[] before = read(METADATA);

            MavenYank.Outcome outcome = yank().yank(GROUP, ARTIFACT, "1.1.0", true, true);

            assertThat(outcome.after()).containsExactly("1.0.0");
            assertThat(outcome.files()).containsExactly(DIR + "1.1.0/widget-1.1.0.jar", DIR + "1.1.0/widget-1.1.0.pom");
            assertThat(read(METADATA)).isEqualTo(before);
            assertThat(repo.resolve(METADATA + ".sha256")).doesNotExist();
            assertThat(repo.resolve(DIR + "1.1.0/widget-1.1.0.jar")).exists();
        }

        @Test
        void an_artifact_never_published_has_nothing_to_yank() throws Exception {
            assertThat(yank().yank(GROUP, "other", "1.0.0", true, false).nothing())
                    .isTrue();
        }

        private void write(String path, String text) throws IOException {
            write(path, text.getBytes(StandardCharsets.UTF_8));
        }

        private void write(String path, byte[] body) throws IOException {
            Path file = repo.resolve(path);
            Files.createDirectories(file.getParent());
            Files.write(file, body);
        }

        private byte[] read(String path) throws IOException {
            return Files.readAllBytes(repo.resolve(path));
        }
    }

    @Nested
    class OnAnObjectStore {

        private static final String BUCKET = "/bucket/";

        private HttpServer server;
        private final Map<String, byte[]> objects = new TreeMap<>();
        private final AtomicInteger rejected = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private volatile String concurrentVersion = "";

        @BeforeEach
        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(JkThreads.platform());
            server.createContext("/", this::handle);
            server.start();
            objects.put(BUCKET + METADATA, metadata("1.0.0", "1.1.0", "1.2.0"));
            for (String v : List.of("1.1.0", "1.2.0")) {
                for (String ext : List.of(".jar", ".jar.sha1", ".pom")) {
                    objects.put(
                            BUCKET + DIR + v + "/widget-" + v + ext,
                            ("body " + v + ext).getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        @AfterEach
        void stop() {
            server.stop(0);
        }

        private MavenYank yank() {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            S3Transport transport = new S3Transport(
                    Http.failFast(),
                    endpoint,
                    "us-east-1",
                    Optional.of(new AwsCredentials("key", "secret", null, "us-east-1")),
                    () -> Instant.parse("2026-11-26T12:00:00Z"));
            return new MavenYank(URI.create("s3://bucket"), transport, RepoCredential.ANONYMOUS);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String query = exchange.getRequestURI().getRawQuery();
                synchronized (objects) {
                    switch (exchange.getRequestMethod()) {
                        case "GET" -> {
                            if (query != null && query.contains("list-type=2")) {
                                list(exchange, query);
                                return;
                            }
                            byte[] body = objects.get(path);
                            if (body == null) {
                                exchange.sendResponseHeaders(404, -1);
                                return;
                            }
                            exchange.getResponseHeaders().set("ETag", etag(body));
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body);
                        }
                        case "PUT" -> put(exchange, path);
                        case "DELETE" -> {
                            writes.incrementAndGet();
                            objects.remove(path);
                            exchange.sendResponseHeaders(204, -1);
                        }
                        default -> exchange.sendResponseHeaders(405, -1);
                    }
                }
            }
        }

        private void put(HttpExchange exchange, String path) throws IOException {
            writes.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            String match = exchange.getRequestHeaders().getFirst("If-Match");
            String absent = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (path.equals(BUCKET + METADATA) && !concurrentVersion.isEmpty()) {
                // Another publisher lands its version between this writer's read and its write.
                objects.put(
                        path,
                        parsed(requireNonNull(objects.get(path)))
                                .withVersion(concurrentVersion)
                                .render());
                concurrentVersion = "";
            }
            byte[] previous = objects.get(path);
            boolean matches = match != null
                    ? previous != null && match.equals(etag(previous))
                    : "*".equals(absent) && previous == null;
            if (!matches) {
                rejected.incrementAndGet();
                exchange.sendResponseHeaders(412, -1);
                return;
            }
            objects.put(path, body);
            exchange.sendResponseHeaders(200, -1);
        }

        /** ListObjectsV2, two keys a page, so a listing has to follow the continuation token. */
        private void list(HttpExchange exchange, String query) throws IOException {
            Map<String, String> params = new TreeMap<>();
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
            String bucket = exchange.getRequestURI().getPath();
            String prefix = bucket + "/" + params.getOrDefault("prefix", "");
            List<String> keys = objects.keySet().stream()
                    .filter(k -> k.startsWith(prefix))
                    .filter(k -> !params.containsKey("continuation-token")
                            || k.compareTo(params.get("continuation-token")) > 0)
                    .toList();
            List<String> page = keys.subList(0, Math.min(2, keys.size()));
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
            for (String key : page) {
                xml.append("<Contents><Key>")
                        .append(key.substring(bucket.length() + 1))
                        .append("</Key></Contents>");
            }
            boolean truncated = keys.size() > page.size();
            xml.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
            if (truncated) {
                xml.append("<NextContinuationToken>")
                        .append(page.get(page.size() - 1))
                        .append("</NextContinuationToken>");
            }
            byte[] body = xml.append("</ListBucketResult>").toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }

        @Test
        void a_concurrent_publish_is_kept_and_the_yank_retries_on_the_new_revision() throws Exception {
            concurrentVersion = "1.3.0";

            MavenYank.Outcome outcome = yank().yank(GROUP, ARTIFACT, "1.2.0", false, false);

            MavenMetadata now = parsed(object(METADATA));
            assertThat(now.versions()).containsExactly("1.0.0", "1.1.0", "1.3.0");
            assertThat(now.latest()).isEqualTo("1.3.0");
            assertThat(outcome.before()).containsExactly("1.0.0", "1.1.0", "1.2.0", "1.3.0");
            assertThat(rejected).hasValue(1);
            assertThat(new String(object(METADATA + ".sha256"), StandardCharsets.US_ASCII))
                    .isEqualTo(Hashing.sha256Hex(object(METADATA)));
        }

        @Test
        void delete_lists_every_page_of_the_version_and_deletes_it() throws Exception {
            MavenYank.Outcome outcome = yank().yank(GROUP, ARTIFACT, "1.2.0", true, false);

            assertThat(outcome.files())
                    .containsExactly(
                            DIR + "1.2.0/widget-1.2.0.jar",
                            DIR + "1.2.0/widget-1.2.0.jar.sha1",
                            DIR + "1.2.0/widget-1.2.0.pom");
            assertThat(objects.keySet()).noneMatch(k -> k.contains("/1.2.0/"));
            assertThat(objects).containsKey(BUCKET + DIR + "1.1.0/widget-1.1.0.jar");
            assertThat(parsed(object(METADATA)).versions()).containsExactly("1.0.0", "1.1.0");
        }

        @Test
        void a_dry_run_writes_and_deletes_nothing() throws Exception {
            byte[] before = object(METADATA);

            MavenYank.Outcome outcome = yank().yank(GROUP, ARTIFACT, "1.2.0", true, true);

            assertThat(outcome.after()).containsExactly("1.0.0", "1.1.0");
            assertThat(outcome.files()).hasSize(3);
            assertThat(writes).hasValue(0);
            assertThat(object(METADATA)).isEqualTo(before);
        }

        private byte[] object(String path) {
            synchronized (objects) {
                return requireNonNull(objects.get(BUCKET + path), path);
            }
        }

        private static String etag(byte[] body) {
            return "\"" + Hashing.sha256Hex(body) + "\"";
        }
    }
}

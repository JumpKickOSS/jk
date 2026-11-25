// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.Project;
import cc.jumpkick.repo.MavenMetadata;
import cc.jumpkick.run.JkThreads;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectStorePublisherTest {

    private static final String MODULE = "/bucket/com/example/widget/";
    private static final String METADATA = MODULE + "maven-metadata.xml";
    private static final String SNAPSHOT = "1.0-SNAPSHOT";
    private static final String SNAPSHOT_METADATA = MODULE + SNAPSHOT + "/maven-metadata.xml";

    @TempDir
    Path artifacts;

    private HttpServer server;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicInteger rejected = new AtomicInteger();
    private final AtomicInteger unguarded = new AtomicInteger();
    private final CountDownLatch waiting = new CountDownLatch(1);
    private final CountDownLatch resume = new CountDownLatch(1);
    private volatile String holdPath = "";
    private volatile String holdText = "";
    private volatile boolean rejectMetadata;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(JkThreads.platform());
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        resume.countDown();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] body = objects.get(path);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.getResponseHeaders().set("ETag", etag(body));
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (path.equals(holdPath) && text(body).contains(holdText)) {
                holdPath = "";
                waiting.countDown();
                try {
                    if (!resume.await(10, TimeUnit.SECONDS)) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            var headers = exchange.getRequestHeaders();
            String absent = headers.getFirst("If-None-Match");
            String match = headers.getFirst("If-Match");
            String auth = headers.getFirst("Authorization");
            if ((match == null && (!"*".equals(absent) || !auth.contains(";if-none-match;")))
                    || (match != null && !auth.contains(";if-match;"))) {
                unguarded.incrementAndGet();
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            if (match != null && !path.contains("maven-metadata.xml")) {
                unguarded.incrementAndGet();
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            synchronized (objects) {
                byte[] previous = objects.get(path);
                boolean matches = match == null ? previous == null : previous != null && match.equals(etag(previous));
                if (!matches || (rejectMetadata && path.equals(METADATA))) {
                    rejected.incrementAndGet();
                    exchange.sendResponseHeaders(412, -1);
                    return;
                }
                objects.put(path, body);
                exchange.sendResponseHeaders(200, -1);
            }
        }
    }

    private MavenPublisher publisher() {
        var config = new ObjectStoreConfig(
                "us-east-1", "http://127.0.0.1:" + server.getAddress().getPort(), "key", "secret", null);
        return MavenPublisher.withObjectStore(
                URI.create("s3://bucket"), RepoCredential.ANONYMOUS, config, Http.failFast());
    }

    @Test
    void reuses_identical_artifacts_and_repairs_missing_checksums() throws Exception {
        String path = MODULE + "1/widget-1.jar";
        Project project = project("1");
        var artifact = artifact(".jar", "original");
        var publisher = publisher();
        publisher.publish(project, List.of(artifact));
        objects.remove(path + ".sha256");

        var repeated = publisher.publish(project, List.of(artifact));
        assertThat(repeated.alreadyThere()).containsExactly("com/example/widget/1/widget-1.jar");
        assertThat(repeated.statusByPath())
                .containsKey("com/example/widget/1/widget-1.jar.sha256")
                .doesNotContainKey("com/example/widget/1/widget-1.jar");
        assertThat(text(object(path + ".sha256"))).isEqualTo(Hashing.sha256Hex("original"));
        assertThat(unguarded).hasValue(0);

        assertThatThrownBy(() -> publisher.publish(project, List.of(artifact(".jar", "replacement"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("already published with other bytes; raise the version");
        assertThat(text(object(path))).isEqualTo("original");

        objects.put(path + ".sha1", "different".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> publisher.publish(project, List.of(artifact)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("widget-1.jar.sha1 is already published with other bytes");
        assertThat(text(object(path + ".sha1"))).isEqualTo("different");
    }

    @Test
    void concurrent_publishers_merge_their_versions_and_write_current_checksums() throws Exception {
        publisher().publish(project("1"), List.of(artifact(".jar", "one")));
        holdText = "<version>2</version>";
        holdPath = METADATA;
        var first =
                JkThreads.platform().submit(() -> publisher().publish(project("2"), List.of(artifact(".jar", "two"))));
        try {
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
            publisher().publish(project("3"), List.of(artifact(".jar", "three")));
        } finally {
            resume.countDown();
        }
        first.get(10, TimeUnit.SECONDS);

        assertThat(MavenMetadata.parse(object(METADATA), "com.example", "widget")
                        .versions())
                .containsExactly("1", "2", "3");
        assertThat(text(object(METADATA + ".sha256"))).isEqualTo(Hashing.sha256Hex(object(METADATA)));
        assertThat(rejected).hasValue(1);
        assertThat(unguarded).hasValue(0);
    }

    @Test
    void a_delayed_checksum_cannot_replace_the_newer_checksum() throws Exception {
        publisher().publish(project("1"), List.of(artifact(".jar", "one")));
        holdPath = METADATA + ".sha256";
        var first =
                JkThreads.platform().submit(() -> publisher().publish(project("2"), List.of(artifact(".jar", "two"))));
        try {
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
            publisher().publish(project("3"), List.of(artifact(".jar", "three")));
        } finally {
            resume.countDown();
        }
        first.get(10, TimeUnit.SECONDS);

        assertThat(MavenMetadata.parse(object(METADATA), "com.example", "widget")
                        .versions())
                .containsExactly("1", "2", "3");
        assertThat(text(object(METADATA + ".sha256"))).isEqualTo(Hashing.sha256Hex(object(METADATA)));
        assertThat(rejected).hasValue(1);
    }

    @Test
    void concurrent_snapshots_retry_with_a_new_build_and_keep_other_classifiers() throws Exception {
        holdText = "20261025.201500";
        holdPath = SNAPSHOT_METADATA;
        Instant stamp = Instant.parse("2026-10-25T20:15:00Z");
        var first = JkThreads.platform().submit(() -> publisher()
                .withClock(Clock.fixed(stamp, ZoneOffset.UTC))
                .publish(project(SNAPSHOT), List.of(artifact(".jar", "jar"))));
        try {
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
            publisher()
                    .withClock(Clock.fixed(stamp.plusSeconds(1), ZoneOffset.UTC))
                    .publish(project(SNAPSHOT), List.of(artifact("-sources.jar", "sources")));
        } finally {
            resume.countDown();
        }
        first.get(10, TimeUnit.SECONDS);

        assertThat(text(object(SNAPSHOT_METADATA)))
                .contains(
                        "<buildNumber>2</buildNumber>",
                        "<value>1.0-20261025.201500-2</value>",
                        "<value>1.0-20261025.201501-1</value>",
                        "<classifier>sources</classifier>");
        assertThat(objects)
                .containsKeys(
                        MODULE + SNAPSHOT + "/widget-1.0-20261025.201500-2.jar",
                        MODULE + SNAPSHOT + "/widget-1.0-20261025.201501-1-sources.jar");
        assertThat(text(object(SNAPSHOT_METADATA + ".sha256"))).isEqualTo(Hashing.sha256Hex(object(SNAPSHOT_METADATA)));
        assertThat(rejected).hasValue(1);
        assertThat(unguarded).hasValue(0);
    }

    @Test
    void reports_the_metadata_path_after_five_retries() throws Exception {
        rejectMetadata = true;
        assertThatThrownBy(() -> publisher().publish(project("1"), List.of(artifact(".jar", "jar"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("s3://bucket/com/example/widget/maven-metadata.xml: concurrent updates");
        assertThat(rejected).hasValue(6);
        assertThat(objects).doesNotContainKeys(METADATA, METADATA + ".sha256");
    }

    private static Project project(String version) {
        return new Project("com.example", "widget", version, 25);
    }

    private MavenPublisher.Artifact artifact(String suffix, String body) throws IOException {
        return new MavenPublisher.Artifact(
                suffix, Files.writeString(Files.createTempFile(artifacts, "artifact", suffix), body));
    }

    private byte[] object(String path) {
        return requireNonNull(objects.get(path), path);
    }

    private static String etag(byte[] body) {
        return "\"" + Hashing.sha256Hex(body) + "\"";
    }

    private static String text(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }
}

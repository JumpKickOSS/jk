// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.Project;
import cc.jumpkick.plugin.publish.testkit.GpgTestFixture;
import cc.jumpkick.repo.MavenMetadata;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenPublisherTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> received = new HashMap<>();
    private final Map<String, String> authHeaders = new HashMap<>();
    private volatile boolean failNext;

    /** Every PUT the repository took, in order. */
    private final List<String> putLog = new CopyOnWriteArrayList<>();

    /** Non-zero makes every PUT answer with this status (409: a repository that refuses redeploys). */
    private volatile int putStatus;

    /** Non-zero makes every GET answer with this status instead of the stored body. */
    private volatile int getFailureStatus;

    /**
     * A real Maven repository over HTTP: PUT stores, GET reads back what was stored and 404s what
     * was never published. That distinction is the whole subject of {@code publishMetadata} — a
     * harness that 405s every GET would let a truncating publisher look healthy.
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("PUT".equals(exchange.getRequestMethod())) {
                putLog.add(path);
                if (putStatus != 0) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(putStatus, -1);
                    exchange.close();
                    return;
                }
                received.put(path, exchange.getRequestBody().readAllBytes());
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth != null) authHeaders.put(path, auth);
                if (failNext) {
                    failNext = false;
                    exchange.sendResponseHeaders(401, -1);
                } else {
                    exchange.sendResponseHeaders(201, -1);
                }
            } else if ("GET".equals(exchange.getRequestMethod())) {
                byte[] body = received.get(path);
                if (getFailureStatus != 0) {
                    exchange.sendResponseHeaders(getFailureStatus, -1); // a failure, not a 404
                } else if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/repo/");
    }

    private @Nullable String metadataOnServer() {
        byte[] body = received.get("/repo/com/example/widget/maven-metadata.xml");
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    /** The version list read back the way the resolver reads it — through the one owner. */
    private List<String> versionsOnServer() {
        return MavenMetadata.parse(
                        requireNonNull(received.get("/repo/com/example/widget/maven-metadata.xml")),
                        "com.example",
                        "widget")
                .versions();
    }

    private void publishVersion(String version) throws Exception {
        publishVersion(version, new MavenPublisher(base, null, null));
    }

    private void publishVersion(String version, MavenPublisher publisher) throws Exception {
        publisher.publish(
                new Project("com.example", "widget", version, 21),
                List.of(new MavenPublisher.Artifact(".jar", ("jar-" + version).getBytes(StandardCharsets.UTF_8))));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void publishes_jar_pom_and_four_checksums_each() throws Exception {
        MavenPublisher publisher = new MavenPublisher(base, null, null);
        Project project = new Project("com.example", "widget", "1.0.0", 21);
        byte[] jarBytes = "fake-jar".getBytes(StandardCharsets.UTF_8);
        byte[] pomBytes = "<project/>".getBytes(StandardCharsets.UTF_8);
        publisher.publish(
                project,
                List.of(new MavenPublisher.Artifact(".jar", jarBytes), new MavenPublisher.Artifact(".pom", pomBytes)));

        String prefix = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received)
                .containsKeys(
                        prefix + ".jar",
                        prefix + ".jar.md5",
                        prefix + ".jar.sha1",
                        prefix + ".jar.sha256",
                        prefix + ".jar.sha512",
                        prefix + ".pom",
                        prefix + ".pom.md5",
                        prefix + ".pom.sha1",
                        prefix + ".pom.sha256",
                        prefix + ".pom.sha512");
        assertThat(received.get(prefix + ".jar")).isEqualTo(jarBytes);
        assertThat(received.get(prefix + ".pom")).isEqualTo(pomBytes);

        // Each checksum file is the hex digest of its sibling.
        assertThat(new String(received.get(prefix + ".jar.sha256"), StandardCharsets.US_ASCII))
                .isEqualTo(Checksums.sha256Hex(jarBytes));
    }

    @Test
    void publishing_the_same_bytes_again_uploads_nothing() throws Exception {
        MavenPublisher publisher = new MavenPublisher(base, null, null);
        Project project = new Project("com.example", "widget", "1.0.0", 21);
        List<MavenPublisher.Artifact> artifacts = List.of(
                new MavenPublisher.Artifact(".jar", "jar".getBytes(StandardCharsets.UTF_8)),
                new MavenPublisher.Artifact(".pom", "<project/>".getBytes(StandardCharsets.UTF_8)));
        publisher.publish(project, artifacts);
        putLog.clear();

        MavenPublisher.Result second = publisher.publish(project, artifacts);

        assertThat(second.alreadyThere())
                .containsExactlyInAnyOrder(
                        "com/example/widget/1.0.0/widget-1.0.0.jar", "com/example/widget/1.0.0/widget-1.0.0.pom");
        assertThat(putLog)
                .as("only the version list is rewritten, and it is merged, never clobbered")
                .containsExactly(
                        "/repo/com/example/widget/maven-metadata.xml",
                        "/repo/com/example/widget/maven-metadata.xml.md5",
                        "/repo/com/example/widget/maven-metadata.xml.sha1",
                        "/repo/com/example/widget/maven-metadata.xml.sha256",
                        "/repo/com/example/widget/maven-metadata.xml.sha512");
    }

    @Test
    void a_version_published_with_other_bytes_is_refused_and_left_alone() throws Exception {
        MavenPublisher publisher = new MavenPublisher(base, null, null);
        Project project = new Project("com.example", "widget", "1.0.0", 21);
        publisher.publish(
                project, List.of(new MavenPublisher.Artifact(".jar", "first".getBytes(StandardCharsets.UTF_8))));
        putLog.clear();

        assertThatThrownBy(() -> publisher.publish(
                        project,
                        List.of(new MavenPublisher.Artifact(".jar", "second".getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("widget-1.0.0.jar is already published with other bytes")
                .hasMessageContaining("Raise the version in jk.toml");
        assertThat(putLog).isEmpty();
        assertThat(received.get("/repo/com/example/widget/1.0.0/widget-1.0.0.jar"))
                .isEqualTo("first".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_rerun_after_a_partial_publish_uploads_only_what_is_missing() throws Exception {
        MavenPublisher publisher = new MavenPublisher(base, null, null);
        Project project = new Project("com.example", "widget", "1.0.0", 21);
        byte[] jar = "jar".getBytes(StandardCharsets.UTF_8);
        byte[] pom = "<project/>".getBytes(StandardCharsets.UTF_8);
        publisher.publish(project, List.of(new MavenPublisher.Artifact(".jar", jar)));
        putLog.clear();

        MavenPublisher.Result finished = publisher.publish(
                project, List.of(new MavenPublisher.Artifact(".jar", jar), new MavenPublisher.Artifact(".pom", pom)));

        assertThat(finished.alreadyThere()).containsExactly("com/example/widget/1.0.0/widget-1.0.0.jar");
        assertThat(putLog)
                .contains("/repo/com/example/widget/1.0.0/widget-1.0.0.pom")
                .doesNotContain("/repo/com/example/widget/1.0.0/widget-1.0.0.jar");
    }

    @Test
    void a_repository_that_will_not_answer_a_read_is_left_to_the_upload() {
        MavenPublisher publisher = new MavenPublisher(base, null, null);
        getFailureStatus = 403;

        // The version list cannot be read either, which stops the publish after the artifacts — so the
        // jar being PUT at all shows the existence check gave the upload the last word.
        assertThatThrownBy(() -> publisher.publish(
                        new Project("com.example", "widget", "1.0.0", 21),
                        List.of(new MavenPublisher.Artifact(".jar", "jar".getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("refusing to replace it");
        assertThat(putLog).contains("/repo/com/example/widget/1.0.0/widget-1.0.0.jar");
    }

    @Test
    void a_409_says_the_repository_does_not_allow_replacing_a_file() {
        MavenPublisher publisher = new MavenPublisher(base, null, null);
        putStatus = 409;

        assertThatThrownBy(() -> publisher.publish(
                        new Project("com.example", "widget", "1.0.0", 21),
                        List.of(new MavenPublisher.Artifact(".jar", "jar".getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("returned 409")
                .hasMessageContaining("does not allow it to be replaced");
    }

    @Test
    void each_snapshot_publish_adds_a_build_and_the_metadata_names_the_newest() throws Exception {
        Project project = new Project("com.example", "widget", "1.0.1-SNAPSHOT", 21);
        List<MavenPublisher.Artifact> artifacts = List.of(
                new MavenPublisher.Artifact(".jar", "jar".getBytes(StandardCharsets.UTF_8)),
                new MavenPublisher.Artifact(".pom", "<project/>".getBytes(StandardCharsets.UTF_8)),
                new MavenPublisher.Artifact("-sources.jar", "src".getBytes(StandardCharsets.UTF_8)));
        Instant first = Instant.parse("2026-10-25T20:15:00Z");

        new MavenPublisher(base, null, null)
                .withClock(Clock.fixed(first, ZoneOffset.UTC))
                .publish(project, artifacts);
        new MavenPublisher(base, null, null)
                .withClock(Clock.fixed(first.plusSeconds(90), ZoneOffset.UTC))
                .publish(project, artifacts);

        String dir = "/repo/com/example/widget/1.0.1-SNAPSHOT/";
        assertThat(received)
                .containsKeys(
                        dir + "widget-1.0.1-20261025.201500-1.jar",
                        dir + "widget-1.0.1-20261025.201500-1.jar.sha1",
                        dir + "widget-1.0.1-20261025.201630-2.jar",
                        dir + "widget-1.0.1-20261025.201630-2.pom",
                        dir + "widget-1.0.1-20261025.201630-2-sources.jar")
                .doesNotContainKey(dir + "widget-1.0.1-SNAPSHOT.jar");
        String metadata = new String(received.get(dir + "maven-metadata.xml"), StandardCharsets.UTF_8);
        assertThat(metadata)
                .contains("<timestamp>20261025.201630</timestamp>")
                .contains("<buildNumber>2</buildNumber>")
                .contains("<classifier>sources</classifier>")
                .contains("<value>1.0.1-20261025.201630-2</value>");
        assertThat(received).containsKey(dir + "maven-metadata.xml.sha1");
        assertThat(versionsOnServer()).contains("1.0.1-SNAPSHOT");
    }

    @Test
    void basic_auth_header_is_attached_when_credentials_provided() throws Exception {
        MavenPublisher publisher = new MavenPublisher(base, "alice", "swordfish");
        publisher.publish(
                new Project("com.example", "widget", "1.0.0", 21),
                List.of(new MavenPublisher.Artifact(".jar", new byte[] {1, 2, 3})));

        String expected =
                "Basic " + Base64.getEncoder().encodeToString("alice:swordfish".getBytes(StandardCharsets.UTF_8));
        assertThat(authHeaders.values()).isNotEmpty().allMatch(h -> h.equals(expected));
    }

    @Test
    void bearer_credential_attaches_bearer_header() throws Exception {
        MavenPublisher publisher = new MavenPublisher(base, new RepoCredential.Bearer("tok-123"));
        publisher.publish(
                new Project("com.example", "widget", "1.0.0", 21),
                List.of(new MavenPublisher.Artifact(".jar", new byte[] {1, 2, 3})));

        assertThat(authHeaders.values()).isNotEmpty().allMatch(h -> h.equals("Bearer tok-123"));
    }

    @Test
    void signed_publish_emits_asc_files_with_checksums(@TempDir Path tempDir) throws Exception {
        var key = GpgTestFixture.generate(tempDir, "test-pass");
        GpgSigner signer = GpgSigner.fromKeyFile(key.secretKeyFile(), "test-pass".toCharArray());

        MavenPublisher publisher = new MavenPublisher(base, null, null);
        Project project = new Project("com.example", "widget", "1.0.0", 21);
        byte[] jarBytes = "fake-jar".getBytes(StandardCharsets.UTF_8);
        publisher.publish(project, List.of(new MavenPublisher.Artifact(".jar", jarBytes)), SigningOptions.of(signer));

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received)
                .containsKeys(
                        stem + ".jar", stem + ".jar.asc",
                        stem + ".jar.asc.md5", stem + ".jar.asc.sha1",
                        stem + ".jar.asc.sha256", stem + ".jar.asc.sha512");

        // The .asc file is a valid PGP signature over the jar bytes.
        byte[] sig = Objects.requireNonNull(received.get(stem + ".jar.asc"));
        GpgTestFixture.verifyDetached(jarBytes, sig, key.publicRing());
    }

    @Test
    void sigstore_signer_emits_sigstore_bundle_files() throws Exception {
        // Fake sigstore signer returns a deterministic bundle for unit testing.
        SigstoreSigner fake = bytes -> ("{\"fake-bundle-over-" + new String(bytes, StandardCharsets.UTF_8) + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        MavenPublisher publisher = new MavenPublisher(base, null, null);
        Project project = new Project("com.example", "widget", "1.0.0", 21);
        byte[] jarBytes = "fake-jar".getBytes(StandardCharsets.UTF_8);
        publisher.publish(
                project, List.of(new MavenPublisher.Artifact(".jar", jarBytes)), new SigningOptions(null, fake));

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received)
                .containsKeys(
                        stem + ".jar.sigstore",
                        stem + ".jar.sigstore.md5",
                        stem + ".jar.sigstore.sha1",
                        stem + ".jar.sigstore.sha256",
                        stem + ".jar.sigstore.sha512");
        assertThat(new String(received.get(stem + ".jar.sigstore"), StandardCharsets.UTF_8))
                .isEqualTo("{\"fake-bundle-over-fake-jar\"}");
    }

    @Test
    void both_gpg_and_sigstore_emit_both_sidecars(@TempDir Path tempDir) throws Exception {
        var key = GpgTestFixture.generate(tempDir, "p");
        GpgSigner gpg = GpgSigner.fromKeyFile(key.secretKeyFile(), "p".toCharArray());
        SigstoreSigner fake = bytes -> "{}".getBytes(StandardCharsets.UTF_8);

        MavenPublisher publisher = new MavenPublisher(base, null, null);
        publisher.publish(
                new Project("com.example", "widget", "1.0.0", 21),
                List.of(new MavenPublisher.Artifact(".jar", new byte[] {1, 2, 3})),
                new SigningOptions(gpg, fake));

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received).containsKeys(stem + ".jar.asc", stem + ".jar.sigstore");
    }

    @Test
    void first_publish_writes_metadata_with_only_the_new_version() throws Exception {
        publishVersion("0.1.0");

        // The GET 404'd — absent metadata, so a single-version document is exactly right.
        assertThat(versionsOnServer()).containsExactly("0.1.0");
    }

    @Test
    void a_later_publish_merges_into_the_existing_version_list() throws Exception {
        publishVersion("0.1.0");
        publishVersion("0.2.0");
        publishVersion("0.3.0");

        assertThat(versionsOnServer()).containsExactly("0.1.0", "0.2.0", "0.3.0");
        assertThat(metadataOnServer()).contains("<latest>0.3.0</latest>");
    }

    /**
     * The version list is read with a real parser, so what merely looks like a {@code <version>}
     * is not one. A regex over the document text could not tell the difference, and a republish
     * would resurrect a withdrawn version into the list it writes back.
     */
    @Test
    void a_commented_out_version_is_not_republished_as_a_real_one() throws Exception {
        received.put("/repo/com/example/widget/maven-metadata.xml", ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <versioning>
                    <versions>
                      <version>0.1.0</version>
                      <!-- withdrawn: <version>0.2.0</version> -->
                    </versions>
                  </versioning>
                </metadata>
                """).getBytes(StandardCharsets.UTF_8));

        publishVersion("0.3.0");

        assertThat(versionsOnServer()).containsExactly("0.1.0", "0.3.0");
    }

    /**
     * The defect this test exists for: a failed GET is not an absent document. Falling back to a
     * single-version write would erase 0.1.0 and 0.2.0 from a repository that cannot un-publish, so
     * the publish must abort with the old list still standing.
     *
     * <p>A 5xx that outlives {@code Http}'s retry ladder — the transient blip that actually reaches
     * the publisher, since anything shorter the transport rides out on its own. A transport with no
     * ladder delivers the same 5xx without waiting one out.
     */
    @Test
    void a_transient_metadata_get_failure_aborts_without_truncating_the_version_list() throws Exception {
        publishVersion("0.1.0");
        publishVersion("0.2.0");
        String before = metadataOnServer();

        getFailureStatus = 503;
        MavenPublisher noRetries = MavenPublisher.withObjectStore(
                base, RepoCredential.ANONYMOUS, ObjectStoreConfig.EMPTY, Http.failFast());
        assertThatThrownBy(() -> publishVersion("0.3.0", noRetries))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("maven-metadata.xml")
                .hasMessageContaining("503");

        assertThat(metadataOnServer()).isEqualTo(before);
        assertThat(versionsOnServer()).containsExactly("0.1.0", "0.2.0");

        // The 0.3.0 artifacts are up and immutable, so re-running once the blip clears is the whole
        // recovery — and it lands the merged list, not a truncated one.
        assertThat(received).containsKey("/repo/com/example/widget/0.3.0/widget-0.3.0.jar");
        getFailureStatus = 0;
        publishVersion("0.3.0");
        assertThat(versionsOnServer()).containsExactly("0.1.0", "0.2.0", "0.3.0");
    }

    /**
     * A document that arrives but will not parse is the same state of ignorance as one that never
     * arrived: we do not know the version list. Reading it with a real parser is what makes that
     * knowable at all — the old regex found no matches in a corrupt document and could not tell it
     * apart from an empty one, so it wrote the truncated list the abort exists to prevent.
     */
    @Test
    void an_unparseable_existing_document_aborts_instead_of_being_overwritten() throws Exception {
        publishVersion("0.1.0");
        publishVersion("0.2.0");
        byte[] corrupt = "<metadata><versioning><versions><version>0.1.0</vers".getBytes(StandardCharsets.UTF_8);
        received.put("/repo/com/example/widget/maven-metadata.xml", corrupt);

        assertThatThrownBy(() -> publishVersion("0.3.0"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("maven-metadata.xml")
                .hasMessageContaining("re-run publish");

        assertThat(received.get("/repo/com/example/widget/maven-metadata.xml")).isEqualTo(corrupt);
    }

    /**
     * The non-transient shape of the same bug, and the likelier one: a deploy credential with write
     * but not read access. {@code Http} never retries a 4xx, so before the fix every single publish
     * to such a repository silently replaced the version list with one entry.
     */
    @Test
    void a_metadata_get_the_credential_may_not_read_aborts_without_truncating() throws Exception {
        publishVersion("0.1.0");
        publishVersion("0.2.0");
        String before = metadataOnServer();

        getFailureStatus = 403;
        assertThatThrownBy(() -> publishVersion("0.3.0"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("maven-metadata.xml")
                .hasMessageContaining("403");

        assertThat(metadataOnServer()).isEqualTo(before);
    }

    @Test
    void metadata_checksums_match_the_metadata_body() throws Exception {
        publishVersion("0.1.0");

        String metaPath = "/repo/com/example/widget/maven-metadata.xml";
        assertThat(new String(Objects.requireNonNull(received.get(metaPath + ".sha256")), StandardCharsets.US_ASCII))
                .isEqualTo(Checksums.sha256Hex(Objects.requireNonNull(received.get(metaPath))));
    }

    @Test
    void server_error_aborts_with_io_exception() {
        MavenPublisher publisher = new MavenPublisher(base, null, null);
        failNext = true;
        assertThatThrownBy(() -> publisher.publish(
                        new Project("com.example", "widget", "1.0.0", 21),
                        List.of(new MavenPublisher.Artifact(".jar", new byte[] {1, 2, 3}))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("401");
    }
}

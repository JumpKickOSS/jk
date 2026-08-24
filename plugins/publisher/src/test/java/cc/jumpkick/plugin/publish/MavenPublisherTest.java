// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.publish.testkit.GpgTestFixture;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private String metadataOnServer() {
        byte[] body = received.get("/repo/com/example/widget/maven-metadata.xml");
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    private void publishVersion(String version) throws Exception {
        new MavenPublisher(base, null, null)
                .publish(
                        new JkBuild.Project("com.example", "widget", version, 21),
                        List.of(new MavenPublisher.Artifact(
                                ".jar", ("jar-" + version).getBytes(StandardCharsets.UTF_8))));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void publishes_jar_pom_and_four_checksums_each() throws Exception {
        MavenPublisher publisher = new MavenPublisher(base, null, null);
        JkBuild.Project project = new JkBuild.Project("com.example", "widget", "1.0.0", 21);
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
    void basic_auth_header_is_attached_when_credentials_provided() throws Exception {
        MavenPublisher publisher = new MavenPublisher(base, "alice", "swordfish");
        publisher.publish(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                List.of(new MavenPublisher.Artifact(".jar", new byte[] {1, 2, 3})));

        String expected =
                "Basic " + Base64.getEncoder().encodeToString("alice:swordfish".getBytes(StandardCharsets.UTF_8));
        assertThat(authHeaders.values()).isNotEmpty().allMatch(h -> h.equals(expected));
    }

    @Test
    void bearer_credential_attaches_bearer_header() throws Exception {
        MavenPublisher publisher =
                new MavenPublisher(base, new cc.jumpkick.credential.RepoCredential.Bearer("tok-123"));
        publisher.publish(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                List.of(new MavenPublisher.Artifact(".jar", new byte[] {1, 2, 3})));

        assertThat(authHeaders.values()).isNotEmpty().allMatch(h -> h.equals("Bearer tok-123"));
    }

    @Test
    void signed_publish_emits_asc_files_with_checksums(@TempDir Path tempDir) throws Exception {
        var key = GpgTestFixture.generate(tempDir, "test-pass");
        GpgSigner signer = GpgSigner.fromKeyFile(key.secretKeyFile(), "test-pass".toCharArray());

        MavenPublisher publisher = new MavenPublisher(base, null, null);
        JkBuild.Project project = new JkBuild.Project("com.example", "widget", "1.0.0", 21);
        byte[] jarBytes = "fake-jar".getBytes(StandardCharsets.UTF_8);
        publisher.publish(project, List.of(new MavenPublisher.Artifact(".jar", jarBytes)), SigningOptions.of(signer));

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received)
                .containsKeys(
                        stem + ".jar", stem + ".jar.asc",
                        stem + ".jar.asc.md5", stem + ".jar.asc.sha1",
                        stem + ".jar.asc.sha256", stem + ".jar.asc.sha512");

        // The .asc file is a valid PGP signature over the jar bytes.
        byte[] sig = received.get(stem + ".jar.asc");
        GpgTestFixture.verifyDetached(jarBytes, sig, key.publicRing());
    }

    @Test
    void sigstore_signer_emits_sigstore_bundle_files() throws Exception {
        // Fake sigstore signer returns a deterministic bundle for unit testing.
        SigstoreSigner fake = bytes -> ("{\"fake-bundle-over-" + new String(bytes, StandardCharsets.UTF_8) + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        MavenPublisher publisher = new MavenPublisher(base, null, null);
        JkBuild.Project project = new JkBuild.Project("com.example", "widget", "1.0.0", 21);
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
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                List.of(new MavenPublisher.Artifact(".jar", new byte[] {1, 2, 3})),
                new SigningOptions(gpg, fake));

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received).containsKeys(stem + ".jar.asc", stem + ".jar.sigstore");
    }

    @Test
    void first_publish_writes_metadata_with_only_the_new_version() throws Exception {
        publishVersion("0.1.0");

        // The GET 404'd — absent metadata, so a single-version document is exactly right.
        assertThat(MavenPublisher.parseVersions(metadataOnServer())).containsExactly("0.1.0");
    }

    @Test
    void a_later_publish_merges_into_the_existing_version_list() throws Exception {
        publishVersion("0.1.0");
        publishVersion("0.2.0");
        publishVersion("0.3.0");

        assertThat(MavenPublisher.parseVersions(metadataOnServer())).containsExactly("0.1.0", "0.2.0", "0.3.0");
        assertThat(metadataOnServer()).contains("<latest>0.3.0</latest>");
    }

    /**
     * The defect this test exists for: a failed GET is not an absent document. Falling back to a
     * single-version write would erase 0.1.0 and 0.2.0 from a repository that cannot un-publish, so
     * the publish must abort with the old list still standing.
     *
     * <p>A 5xx that outlives {@code Http}'s retry ladder — the transient blip that actually reaches
     * the publisher, since anything shorter the transport rides out on its own.
     */
    @Test
    void a_transient_metadata_get_failure_aborts_without_truncating_the_version_list() throws Exception {
        publishVersion("0.1.0");
        publishVersion("0.2.0");
        String before = metadataOnServer();

        getFailureStatus = 503;
        assertThatThrownBy(() -> publishVersion("0.3.0"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("maven-metadata.xml")
                .hasMessageContaining("503");

        assertThat(metadataOnServer()).isEqualTo(before);
        assertThat(MavenPublisher.parseVersions(metadataOnServer())).containsExactly("0.1.0", "0.2.0");

        // The 0.3.0 artifacts are up and immutable, so re-running once the blip clears is the whole
        // recovery — and it lands the merged list, not a truncated one.
        assertThat(received).containsKey("/repo/com/example/widget/0.3.0/widget-0.3.0.jar");
        getFailureStatus = 0;
        publishVersion("0.3.0");
        assertThat(MavenPublisher.parseVersions(metadataOnServer())).containsExactly("0.1.0", "0.2.0", "0.3.0");
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
        assertThat(new String(received.get(metaPath + ".sha256"), StandardCharsets.US_ASCII))
                .isEqualTo(Checksums.sha256Hex(received.get(metaPath)));
    }

    @Test
    void server_error_aborts_with_io_exception() {
        MavenPublisher publisher = new MavenPublisher(base, null, null);
        failNext = true;
        assertThatThrownBy(() -> publisher.publish(
                        new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                        List.of(new MavenPublisher.Artifact(".jar", new byte[] {1, 2, 3}))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("401");
    }
}

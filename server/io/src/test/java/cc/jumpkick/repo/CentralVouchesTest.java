// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.IoLedger;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.CentralMirror;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.ProxiedCentral;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.task.TransferBoard;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Central vouches, the mirror carries: a release POM or artifact is downloaded from Google's mirror
 * of Central first and verified against the {@code .sha1} Central itself publishes, a pinned one
 * reads no checksum at all, and {@code maven-metadata.xml} stays on Central. While Central refuses
 * this host the mirror vouches for its own bytes, and the run says so.
 *
 * <p>Outside the window, driven against Central's canonical host through a fake transport that
 * routes nothing, so every URL the repository addresses is visible. Inside it, through {@link Http}'s
 * own routing to Central and a mirror on named hosts that a loopback server answers as a proxy.
 */
class CentralVouchesTest {

    private static final URI CENTRAL = URI.create("https://repo.maven.apache.org/maven2/");
    private static final String MIRROR = CentralMirror.MIRROR_BASE + "/";
    private static final String ROUTED_CENTRAL = "http://central.example.test/maven2/";
    private static final String ROUTED_MIRROR = "http://mirror.example.test/maven2/";
    private static final Coordinate WIDGET = Coordinate.of("com.example", "widget", "1.0");
    private static final String JAR = MavenLayout.artifactPath(WIDGET);
    private static final String POM = MavenLayout.pomPath(WIDGET);
    private static final String METADATA = MavenLayout.metadataPath(WIDGET);
    private static final byte[] JAR_BYTES = "the widget jar".getBytes(StandardCharsets.UTF_8);
    private static final byte[] POM_BYTES =
            "<project><groupId>com.example</groupId></project>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TAMPERED = "not the widget jar".getBytes(StandardCharsets.UTF_8);

    private final FakeTransport transport = new FakeTransport();

    @BeforeEach
    void clean() {
        RepoGroup.clearProcessFetchCache();
    }

    @AfterEach
    void reset() {
        RepoGroup.clearProcessFetchCache();
        SessionContext.reset();
    }

    @Test
    void an_unpinned_artifact_comes_from_the_mirror_and_is_verified_by_centrals_sha1_alone(@TempDir Path tmp)
            throws Exception {
        publishJar(JAR_BYTES, JAR_BYTES);

        MavenRepo.Fetched fetched = repo(tmp, true).fetchArtifact(WIDGET);

        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(JAR_BYTES));
        assertThat(transport.requests).containsExactlyInAnyOrder(MIRROR + JAR, CENTRAL + JAR + ".sha1");
    }

    @Test
    void a_pinned_artifact_reads_no_checksum_anywhere(@TempDir Path tmp) throws Exception {
        publishJar(JAR_BYTES, JAR_BYTES);

        MavenRepo.Fetched fetched = repo(tmp, true).fetchArtifact(WIDGET, Hashing.sha256Hex(JAR_BYTES), () -> false);

        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(JAR_BYTES));
        assertThat(transport.requests).containsExactly(MIRROR + JAR);
    }

    @Test
    void a_pinned_artifact_the_mirror_mangles_is_downloaded_from_central(@TempDir Path tmp) throws Exception {
        publishJar(TAMPERED, JAR_BYTES);

        MavenRepo repo = repo(tmp, true);
        long landedBefore = board().artifactsLanded();
        MavenRepo.Fetched fetched = repo.fetchArtifact(WIDGET, Hashing.sha256Hex(JAR_BYTES), () -> false);

        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(JAR_BYTES));
        assertThat(transport.requests).containsExactly(MIRROR + JAR, CENTRAL + JAR);
        assertThat(board().artifactsLanded() - landedBefore)
                .as("the lock rejected the mirror's bytes: one file landed, Central's")
                .isEqualTo(1);
    }

    @Test
    void only_requests_sent_to_centrals_own_hosts_are_counted(@TempDir Path tmp) throws Exception {
        IoLedger io = new IoLedger();
        try (RoutedCentral routed = new RoutedCentral(tmp)) {
            Http http = ProxiedCentral.http(routed.server.getAddress(), routed.window, tmp.resolve("cooldown"));
            SessionContext.where(Session.defaults().withIo(io), () -> {
                assertThat(http.get(URI.create(ROUTED_MIRROR + JAR)).statusCode())
                        .isEqualTo(404);
                assertThat(io.lockNotes()).isEmpty();
                assertThat(http.get(URI.create("http://repo.maven.apache.org/maven2/" + JAR))
                                .statusCode())
                        .isEqualTo(404);
                assertThat(http.get(URI.create("http://repo1.maven.org/maven2/" + JAR))
                                .statusCode())
                        .isEqualTo(404);
                return null;
            });
            assertThat(routed.requests).hasSize(3);
        }
        assertThat(io.lockNotes()).containsExactly("2 requests to Maven Central");
    }

    /** Central's .sha1 is read the same way for either source, so a failed read is not a reason to ask Central for the body. */
    @Test
    void a_checksum_read_that_fails_does_not_download_the_file_again(@TempDir Path tmp) {
        publishJar(JAR_BYTES, JAR_BYTES);
        transport.fail(CENTRAL + JAR + ".sha1");

        assertThatThrownBy(() -> repo(tmp, true).fetchArtifact(WIDGET))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(".sha1 did not answer");
        assertThat(transport.requests).containsExactlyInAnyOrder(MIRROR + JAR, CENTRAL + JAR + ".sha1");
    }

    @Test
    void a_checksum_central_does_not_publish_does_not_download_the_file_again(@TempDir Path tmp) {
        transport.publish(MIRROR + JAR, JAR_BYTES);
        transport.publish(CENTRAL + JAR, JAR_BYTES);

        assertThatThrownBy(() -> repo(tmp, true).fetchArtifact(WIDGET))
                .isInstanceOf(MavenRepo.MissingChecksumException.class);
        assertThat(transport.requests).doesNotContain(CENTRAL + JAR).contains(MIRROR + JAR);
    }

    @Test
    void a_pom_comes_from_the_mirror_first(@TempDir Path tmp) throws Exception {
        transport.publish(MIRROR + POM, POM_BYTES);
        transport.publish(CENTRAL + POM, POM_BYTES);
        transport.publish(CENTRAL + POM + ".sha1", sha1(POM_BYTES));

        repo(tmp, true).fetchPom(WIDGET);

        assertThat(transport.requests).containsExactlyInAnyOrder(MIRROR + POM, CENTRAL + POM + ".sha1");
    }

    @Test
    void a_pom_the_mirror_has_not_caught_up_on_falls_back_to_central(@TempDir Path tmp) throws Exception {
        transport.publish(CENTRAL + POM, POM_BYTES);
        transport.publish(CENTRAL + POM + ".sha1", sha1(POM_BYTES));

        MavenRepo.Fetched fetched = repo(tmp, true).fetchPom(WIDGET);

        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(POM_BYTES);
        assertThat(transport.requests).contains(MIRROR + POM, CENTRAL + POM);
        assertThat(transport.requests.indexOf(MIRROR + POM)).isLessThan(transport.requests.indexOf(CENTRAL + POM));
    }

    @Test
    void a_file_neither_has_is_remembered_as_centrals_miss(@TempDir Path tmp) {
        assertThatThrownBy(() -> repo(tmp, true).fetchPom(WIDGET))
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);

        assertThat(RepoMisses.FETCHES.known(URI.create(CENTRAL + POM))).isTrue();
        assertThat(RepoMisses.FETCHES.known(URI.create(MIRROR + POM))).isFalse();
    }

    @Test
    void mirror_bytes_central_disowns_are_replaced_by_centrals_with_a_note(@TempDir Path tmp) throws Exception {
        publishJar(TAMPERED, JAR_BYTES);

        MavenRepo repo = repo(tmp, true);
        TransferBoard.Snapshot before = board().snapshot();
        long landedBefore = board().artifactsLanded();
        MavenRepo.Fetched fetched = repo.fetchArtifact(WIDGET);

        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(JAR_BYTES));
        assertThat(transport.requests).contains(CENTRAL + JAR).doesNotContain(MIRROR + JAR + ".sha1");
        // The disowned copy streamed but did not land: the run's downloads count Central's alone.
        assertThat(board().artifactsLanded() - landedBefore).isEqualTo(1);
        assertThat(board().snapshot().files() - before.files()).isEqualTo(1);
        assertThat(board().snapshot().inflight()).isZero();
        assertThat(repo.checksumNotes())
                .singleElement()
                .asString()
                .contains("the mirror's " + JAR, "downloaded from Maven Central instead");
    }

    @Test
    void while_central_refuses_the_mirror_vouches_for_itself_and_the_run_says_so(@TempDir Path tmp) throws Exception {
        try (RoutedCentral routed = new RoutedCentral(tmp)) {
            routed.publishJar(JAR_BYTES, JAR_BYTES);
            routed.publish(ROUTED_MIRROR + JAR + ".sha1", sha1(JAR_BYTES));
            routed.window.noteRateLimited();

            MavenRepo repo = routed.repo(RepoCredential.ANONYMOUS);
            repo.fetchArtifact(WIDGET);

            assertThat(routed.requests).containsExactlyInAnyOrder(ROUTED_MIRROR + JAR, ROUTED_MIRROR + JAR + ".sha1");
            assertThat(repo.checksumNotes())
                    .singleElement()
                    .asString()
                    .startsWith("1 file from central verified by the mirror's checksums only");
        }
    }

    /**
     * The window opens after the checksum read was addressed to Central: another request's refusal
     * landed first, or this read drew the 429 itself. The transport sends it to the mirror, and the
     * mirror's answer is counted, since it is the mirror that vouched.
     */
    @Test
    void a_checksum_asked_of_central_and_answered_by_the_mirror_is_counted(@TempDir Path tmp) throws Exception {
        publishJar(JAR_BYTES, JAR_BYTES);
        transport.publish(MIRROR + JAR + ".sha1", sha1(JAR_BYTES));
        transport.reroute(CENTRAL + JAR + ".sha1");

        MavenRepo repo = repo(tmp, true);
        repo.fetchArtifact(WIDGET);

        assertThat(transport.requests).containsExactlyInAnyOrder(MIRROR + JAR, CENTRAL + JAR + ".sha1");
        assertThat(repo.checksumNotes())
                .singleElement()
                .asString()
                .startsWith("1 file from central verified by the mirror's checksums only");
    }

    /**
     * While Central rate-limits this host, a checksum the mirror lacks for a file it carries is asked
     * of Central, once, and is Central's word: not counted as the mirror's.
     */
    @Test
    void while_central_refuses_a_checksum_the_mirror_lacks_is_asked_of_central(@TempDir Path tmp) throws Exception {
        try (RoutedCentral routed = new RoutedCentral(tmp)) {
            routed.publishJar(JAR_BYTES, JAR_BYTES);
            routed.window.noteRateLimited();

            MavenRepo repo = routed.repo(RepoCredential.ANONYMOUS);
            MavenRepo.Fetched fetched = repo.fetchArtifact(WIDGET);

            assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(JAR_BYTES));
            assertThat(routed.requests)
                    .containsExactlyInAnyOrder(
                            ROUTED_MIRROR + JAR, ROUTED_MIRROR + JAR + ".sha1", ROUTED_CENTRAL + JAR + ".sha1");
            assertThat(routed.requests.indexOf(ROUTED_MIRROR + JAR + ".sha1"))
                    .isLessThan(routed.requests.indexOf(ROUTED_CENTRAL + JAR + ".sha1"));
            assertThat(repo.checksumNotes()).isEmpty();
        }
    }

    /** A store copy is confirmed the same way: by Central's .sha1 when the mirror has none. */
    @Test
    void while_central_refuses_a_store_copy_the_mirror_has_no_checksum_for_is_confirmed_by_central(@TempDir Path tmp)
            throws Exception {
        try (RoutedCentral routed = new RoutedCentral(tmp)) {
            routed.publish(ROUTED_CENTRAL + JAR + ".sha1", sha1(JAR_BYTES));
            routed.window.noteRateLimited();
            MavenRepo repo = routed.repo(RepoCredential.ANONYMOUS);
            Path seed = Files.write(tmp.resolve("seed.jar"), JAR_BYTES);
            RepoArtifactStore.forRepository(tmp.resolve("cas"), "central", repo.baseUrl(), false)
                    .materialize(JAR, seed, Hashing.sha256Hex(JAR_BYTES));

            Optional<MavenRepo.Fetched> local = repo.tryLocalArtifact(WIDGET, null, () -> false);

            assertThat(local).isPresent();
            assertThat(routed.requests).containsExactly(ROUTED_MIRROR + JAR + ".sha1", ROUTED_CENTRAL + JAR + ".sha1");
        }
    }

    /** Under a Cloudflare block, which more traffic prolongs, a checksum the mirror lacks is not asked of Central. */
    @Test
    void while_central_blocks_a_checksum_the_mirror_lacks_is_not_asked_of_central(@TempDir Path tmp) throws Exception {
        try (RoutedCentral routed = new RoutedCentral(tmp)) {
            routed.publishJar(JAR_BYTES, JAR_BYTES);
            routed.window.noteBlocked();

            assertThatThrownBy(() -> routed.repo(RepoCredential.ANONYMOUS).fetchArtifact(WIDGET))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Maven Central is blocking this host (Cloudflare) and its mirror");
            assertThat(routed.requests).noneMatch(url -> url.startsWith(ROUTED_CENTRAL));
        }
    }

    /** While Central rate-limits this host, a file the mirror lacks comes from Central, checked by Central's .sha1. */
    @Test
    void while_central_refuses_a_file_the_mirror_lacks_is_checked_by_centrals_sha1(@TempDir Path tmp) throws Exception {
        try (RoutedCentral routed = new RoutedCentral(tmp)) {
            routed.publish(ROUTED_CENTRAL + JAR, JAR_BYTES);
            routed.publish(ROUTED_CENTRAL + JAR + ".sha1", sha1(JAR_BYTES));
            routed.window.noteRateLimited();

            MavenRepo.Fetched fetched = routed.repo(RepoCredential.ANONYMOUS).fetchArtifact(WIDGET);

            assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(JAR_BYTES));
            assertThat(routed.requests)
                    .contains(ROUTED_MIRROR + JAR, ROUTED_CENTRAL + JAR, ROUTED_CENTRAL + JAR + ".sha1");
        }
    }

    @Test
    void a_credential_for_central_goes_to_central_alone(@TempDir Path tmp) throws Exception {
        publishJar(JAR_BYTES, JAR_BYTES);
        RepoCredential token = new RepoCredential.Bearer("central-token");

        repo(tmp, CentralMirror.isolated(tmp.resolve("cache"), true), token).fetchArtifact(WIDGET);

        assertThat(transport.credentials)
                .containsEntry(MIRROR + JAR, RepoCredential.ANONYMOUS)
                .containsEntry(CENTRAL + JAR + ".sha1", token);
    }

    @Test
    void while_central_refuses_its_credential_goes_nowhere(@TempDir Path tmp) throws Exception {
        try (RoutedCentral routed = new RoutedCentral(tmp)) {
            routed.publishJar(JAR_BYTES, JAR_BYTES);
            routed.publish(ROUTED_MIRROR + JAR + ".sha1", sha1(JAR_BYTES));
            routed.window.noteRateLimited();

            routed.repo(new RepoCredential.Bearer("central-token")).fetchArtifact(WIDGET);

            assertThat(routed.requests).containsExactlyInAnyOrder(ROUTED_MIRROR + JAR, ROUTED_MIRROR + JAR + ".sha1");
            assertThat(routed.authorized).isEmpty();
        }
    }

    @Test
    void metadata_stays_on_central(@TempDir Path tmp) throws Exception {
        transport.publish(CENTRAL + METADATA, "<metadata/>".getBytes(StandardCharsets.UTF_8));

        repo(tmp, true).fetchMetadata(WIDGET);

        assertThat(transport.requests).containsExactly(CENTRAL + METADATA);
    }

    @Test
    void a_store_copy_a_fresh_lock_pins_is_confirmed_by_centrals_sha1(@TempDir Path tmp) throws Exception {
        transport.publish(CENTRAL + JAR + ".sha1", sha1(JAR_BYTES));
        MavenRepo repo = repo(tmp, true);
        Path seed = Files.write(tmp.resolve("seed.jar"), JAR_BYTES);
        RepoArtifactStore.forRepository(tmp.resolve("cas"), "central", repo.baseUrl(), false)
                .materialize(JAR, seed, Hashing.sha256Hex(JAR_BYTES));

        Optional<MavenRepo.Fetched> local = repo.tryLocalArtifact(WIDGET, null, () -> false);

        assertThat(local).isPresent();
        assertThat(transport.requests).containsExactly(CENTRAL + JAR + ".sha1");
    }

    @Test
    void with_the_mirror_off_central_carries_and_vouches(@TempDir Path tmp) throws Exception {
        publishJar(TAMPERED, JAR_BYTES);

        repo(tmp, false).fetchArtifact(WIDGET);

        assertThat(transport.requests).containsExactlyInAnyOrder(CENTRAL + JAR, CENTRAL + JAR + ".sha1");
    }

    @Test
    void another_repository_keeps_reading_both_sha_sidecars_beside_the_file(@TempDir Path tmp) throws Exception {
        URI other = URI.create("https://nexus.example.org/repo/");
        transport.publish(other + JAR, JAR_BYTES);
        transport.publish(other + JAR + ".sha1", sha1(JAR_BYTES));

        MavenRepo.overTransport(
                        "nexus",
                        other,
                        transport,
                        new Cas(tmp.resolve("cas")),
                        RepoCredential.ANONYMOUS,
                        null,
                        false,
                        false,
                        false)
                .withCentralMirror(CentralMirror.isolated(tmp.resolve("cache"), true))
                .fetchArtifact(WIDGET);

        assertThat(transport.requests)
                .containsExactlyInAnyOrder(other + JAR, other + JAR + ".sha256", other + JAR + ".sha1");
    }

    /** The mirror serves {@code mirrorBytes}; Central serves {@code centralBytes} and their {@code .sha1}. */
    private void publishJar(byte[] mirrorBytes, byte[] centralBytes) {
        transport.publish(MIRROR + JAR, mirrorBytes);
        transport.publish(CENTRAL + JAR, centralBytes);
        transport.publish(CENTRAL + JAR + ".sha1", sha1(centralBytes));
    }

    private MavenRepo repo(Path tmp, boolean mirrorEnabled) {
        return repo(tmp, CentralMirror.isolated(tmp.resolve("cache"), mirrorEnabled));
    }

    private MavenRepo repo(Path tmp, CentralMirror window) {
        return repo(tmp, window, RepoCredential.ANONYMOUS);
    }

    private MavenRepo repo(Path tmp, CentralMirror window, RepoCredential credential) {
        return MavenRepo.overTransport(
                        "central",
                        CENTRAL,
                        transport,
                        new Cas(tmp.resolve("cas")),
                        credential,
                        null,
                        false,
                        false,
                        false)
                .withCentralMirror(window);
    }

    private static byte[] sha1(byte[] bytes) {
        return Hashing.hashHex("SHA-1", bytes).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Central and its mirror on named hosts, one loopback server answering both as their proxy:
     * serves what was published, 404s the rest, and records every URL that reached it and every one
     * that carried a credential. Requests go through {@link Http}, which routes them by {@link
     * #window} as it does in production.
     */
    private static final class RoutedCentral implements AutoCloseable {
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> authorized = new CopyOnWriteArrayList<>();
        final CentralMirror window;
        private final Path tmp;
        private final HttpServer server;

        RoutedCentral(Path tmp) throws IOException {
            this.tmp = tmp;
            this.window = ProxiedCentral.mirror(
                    tmp.resolve("cache"),
                    URI.create(ROUTED_CENTRAL).getHost(),
                    ROUTED_MIRROR.substring(0, ROUTED_MIRROR.length() - 1));
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String url = "http://" + exchange.getRequestHeaders().getFirst("Host")
                        + exchange.getRequestURI().getRawPath();
                requests.add(url);
                if (exchange.getRequestHeaders().containsKey("Authorization")) authorized.add(url);
                byte[] body = files.get(url);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
        }

        void publish(String url, byte[] body) {
            files.put(url, body);
        }

        /** The mirror serves {@code mirrorBytes}; Central serves {@code centralBytes} and their {@code .sha1}. */
        void publishJar(byte[] mirrorBytes, byte[] centralBytes) {
            publish(ROUTED_MIRROR + JAR, mirrorBytes);
            publish(ROUTED_CENTRAL + JAR, centralBytes);
            publish(ROUTED_CENTRAL + JAR + ".sha1", sha1(centralBytes));
        }

        MavenRepo repo(RepoCredential credential) {
            Http http = ProxiedCentral.http(server.getAddress(), window, tmp.resolve("cooldown"));
            return MavenRepo.overTransport(
                            "central",
                            URI.create(ROUTED_CENTRAL),
                            new HttpTransport(http),
                            new Cas(tmp.resolve("cas")),
                            credential,
                            http,
                            false,
                            false,
                            false)
                    .withCentralMirror(window);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Serves what was published, 404s the rest, and records every URL asked for. */
    private static final class FakeTransport implements RepoTransport {
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final Map<String, RepoCredential> credentials = new ConcurrentHashMap<>();

        /** Central URLs the mirror answers, as Http answers a request it sends to the mirror. */
        private final Set<String> rerouted = ConcurrentHashMap.newKeySet();

        private final Set<String> failing = ConcurrentHashMap.newKeySet();

        void publish(String url, byte[] body) {
            files.put(url, body);
        }

        void reroute(String url) {
            rerouted.add(url);
        }

        /** A URL whose request fails, as one that drops every connection does. */
        void fail(String url) {
            failing.add(url);
        }

        @Override
        public Optional<byte[]> fetch(URI uri, RepoCredential credential) throws IOException {
            return fetchAnswer(uri, credential).map(Answer::body);
        }

        @Override
        public Optional<Answer> fetchAnswer(URI uri, RepoCredential credential) throws IOException {
            requests.add(uri.toString());
            credentials.put(uri.toString(), credential);
            if (failing.contains(uri.toString())) throw new IOException(uri + " did not answer");
            String from = uri.toString();
            if (rerouted.contains(from))
                from = MIRROR + from.substring(CENTRAL.toString().length());
            URI answered = URI.create(from);
            return Optional.ofNullable(files.get(from)).map(body -> new Answer(body, answered));
        }

        @Override
        public int put(URI uri, byte[] body, String contentType, RepoCredential credential) throws IOException {
            throw new IOException("read-only");
        }

        @Override
        public int putFile(URI uri, Path file, String contentType, RepoCredential credential) throws IOException {
            throw new IOException("read-only");
        }
    }

    private static TransferBoard board() {
        return SessionContext.current().io().transfers();
    }
}

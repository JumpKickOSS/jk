// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.CentralMirror;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.task.TransferBoard;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>Driven against Central's canonical host through a fake transport, so every URL the repository
 * asks for is visible and nothing reaches the network.
 */
class CentralVouchesTest {

    private static final URI CENTRAL = URI.create("https://repo.maven.apache.org/maven2/");
    private static final String MIRROR = CentralMirror.MIRROR_BASE + "/";
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
                .contains("the mirror's copy of " + JAR, "downloaded from Maven Central instead");
    }

    @Test
    void while_central_refuses_the_mirror_vouches_for_itself_and_the_run_says_so(@TempDir Path tmp) throws Exception {
        publishJar(JAR_BYTES, JAR_BYTES);
        transport.publish(MIRROR + JAR + ".sha1", sha1(JAR_BYTES));
        CentralMirror window = CentralMirror.isolated(tmp.resolve("cache"), true);
        window.noteRateLimited();

        MavenRepo repo = repo(tmp, window);
        repo.fetchArtifact(WIDGET);

        assertThat(transport.requests).containsExactlyInAnyOrder(MIRROR + JAR, MIRROR + JAR + ".sha1");
        assertThat(repo.checksumNotes())
                .singleElement()
                .asString()
                .startsWith("1 file from central verified against the mirror only");
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
        return MavenRepo.overTransport(
                        "central",
                        CENTRAL,
                        transport,
                        new Cas(tmp.resolve("cas")),
                        RepoCredential.ANONYMOUS,
                        null,
                        false,
                        false,
                        false)
                .withCentralMirror(window);
    }

    private static byte[] sha1(byte[] bytes) {
        return Hashing.hashHex("SHA-1", bytes).getBytes(StandardCharsets.UTF_8);
    }

    /** Serves what was published, 404s the rest, and records every URL asked for. */
    private static final class FakeTransport implements RepoTransport {
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final List<String> requests = new CopyOnWriteArrayList<>();

        void publish(String url, byte[] body) {
            files.put(url, body);
        }

        @Override
        public Optional<byte[]> fetch(URI uri, RepoCredential credential) {
            requests.add(uri.toString());
            return Optional.ofNullable(files.get(uri.toString()));
        }

        @Override
        public int put(URI uri, byte[] body, String contentType, RepoCredential credential) throws IOException {
            throw new IOException("read-only");
        }
    }

    private static TransferBoard board() {
        return SessionContext.current().io().transfers();
    }
}

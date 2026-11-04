// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.testing.MavenStub;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A download reports its bytes to the run's board through the session the fetch already has —
 * nothing is passed down to it, so every caller of {@link MavenRepo} shows its downloads. A store
 * hit reports nothing.
 */
class DownloadLegProgressTest {

    private static final Coordinate LIB = Coordinate.of("com.example", "lib", "1.0");
    private static final String JAR = MavenStub.path("com.example", "lib", "1.0", ".jar");

    private final RemoteStub remote = new RemoteStub("progress.example.test");

    @BeforeEach
    void seed() {
        RepoGroup.clearProcessFetchCache();
        SessionContext.reset();
        new MavenStub(remote.served).leaf("com.example", "lib", "1.0");
    }

    @Test
    void a_download_lands_on_the_run_board_and_a_store_hit_does_not(@TempDir Path tmp) throws Exception {
        MavenRepo repo = MavenRepo.overTransport(
                "progress",
                URI.create("https://progress.example.test/"),
                declaringLength(remote),
                new Cas(tmp.resolve("cas")),
                RepoCredential.ANONYMOUS,
                null,
                false,
                false,
                false);
        TransferBoard board = SessionContext.current().io().transfers();
        TransferBoard.Snapshot before = board.snapshot();

        MavenRepo.Fetched fetched = repo.fetchArtifact(LIB, null, () -> false);

        long size = Objects.requireNonNull(remote.served.get(JAR)).length;
        assertThat(fetched.size()).isEqualTo(size);
        TransferBoard.Snapshot after = board.snapshot();
        assertThat(after.files() - before.files()).isEqualTo(1);
        assertThat(after.bytes() - before.bytes()).isEqualTo(size);
        assertThat(after.inflight()).as("the body landed; its row is gone").isZero();

        repo.fetchArtifact(LIB, fetched.sha256(), () -> false);
        assertThat(board.snapshot()).as("the store answers; no transfer").isEqualTo(after);
    }

    @Test
    void copy_reports_every_chunk_and_returns_the_total() throws IOException {
        byte[] body = new byte[40_000];
        List<Long> seen = new ArrayList<>();
        var out = new ByteArrayOutputStream();
        TransferBoard.Transfer recorder = new TransferBoard.Transfer() {
            @Override
            public void started(long totalBytes) {}

            @Override
            public void advanced(long readBytes) {
                seen.add(readBytes);
            }

            @Override
            public void finished() {}

            @Override
            public void close() {}
        };

        long n = DownloadLeg.copy(new ByteArrayInputStream(body), out, recorder);

        assertThat(n).isEqualTo(body.length);
        assertThat(out.size()).isEqualTo(body.length);
        assertThat(seen).containsExactly(16_384L, 32_768L, 40_000L);
    }

    @Test
    void a_row_names_the_artifact_and_says_when_the_file_is_not_its_jar() {
        assertThat(DownloadLeg.label(LIB, "com/example/lib/1.0/lib-1.0.jar")).isEqualTo("com.example:lib:1.0");
        assertThat(DownloadLeg.label(LIB, "com/example/lib/1.0/lib-1.0.pom")).isEqualTo("com.example:lib:1.0 · pom");
        Coordinate aar = new Coordinate("com.example", "lib", "1.0", null, "aar");
        assertThat(DownloadLeg.label(aar, "com/example/lib/1.0/lib-1.0.aar")).isEqualTo("com.example:lib:1.0!aar");
    }

    @Test
    void the_artifact_is_the_coordinates_own_file_and_not_its_pom_or_metadata() {
        assertThat(DownloadLeg.artifactFile(LIB, "com/example/lib/1.0/lib-1.0.jar"))
                .isTrue();
        assertThat(DownloadLeg.artifactFile(LIB, "com/example/lib/1.0/lib-1.0.pom"))
                .isFalse();
        assertThat(DownloadLeg.artifactFile(LIB, "com/example/lib/maven-metadata.xml"))
                .isFalse();
        Coordinate aar = new Coordinate("com.example", "lib", "1.0", null, "aar");
        assertThat(DownloadLeg.artifactFile(aar, "com/example/lib/1.0/lib-1.0.aar"))
                .isTrue();
        Coordinate bom = new Coordinate("com.example", "bom", "1.0", null, "pom");
        assertThat(DownloadLeg.artifactFile(bom, "com/example/bom/1.0/bom-1.0.pom"))
                .as("a BOM or parent POM is not an artifact")
                .isFalse();
    }

    @Test
    void a_transport_without_a_streamed_body_declares_no_length() throws Exception {
        Optional<RepoTransport.Body> body =
                remote.fetchBody(URI.create("https://progress.example.test" + JAR), RepoCredential.ANONYMOUS);

        assertThat(body).isPresent();
        assertThat(body.get().length()).isEqualTo(-1L);
    }

    /** {@code inner}, with each body's real length declared the way an HTTP server would. */
    private static RepoTransport declaringLength(RemoteStub inner) {
        return new RepoTransport() {
            @Override
            public Optional<byte[]> fetch(URI uri, RepoCredential credential) {
                return inner.fetch(uri, credential);
            }

            @Override
            public Optional<Body> fetchBody(URI uri, RepoCredential credential) {
                return inner.fetch(uri, credential).map(b -> new Body(new ByteArrayInputStream(b), b.length));
            }

            @Override
            public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
                return 405;
            }
        };
    }
}

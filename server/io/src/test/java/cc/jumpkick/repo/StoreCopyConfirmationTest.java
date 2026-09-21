// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A store copy a fresh lock is about to pin is confirmed against the checksum the repository
 * publishes, the way an adopted Maven-local copy is: the sidecar is read, the artifact is not, and
 * a copy the repository disowns is evicted and downloaded again.
 */
class StoreCopyConfirmationTest {

    private static final Coordinate WIDGET = Coordinate.of("com.example", "widget", "1.0");
    private static final String JAR = MavenStub.path("com.example", "widget", "1.0", ".jar");
    private static final byte[] WRONG = "not what the repository publishes".getBytes(StandardCharsets.UTF_8);

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    private MavenStub upstream;

    @BeforeEach
    void publish() {
        RepoGroup.clearProcessFetchCache();
        upstream = new MavenStub(http).leaf("com.example", "widget", "1.0");
    }

    @AfterEach
    void reset() {
        RepoGroup.clearProcessFetchCache();
        SessionContext.reset();
    }

    @Test
    void a_store_copy_the_repository_disowns_is_replaced_by_the_published_bytes(@TempDir Path tmp) throws Exception {
        MavenRepo repo = repo(tmp);
        seed(tmp, repo, WRONG);

        RepoGroup.RepoFetched hit = RepoGroup.of(repo).tryFetchArtifact(WIDGET).orElseThrow();

        assertThat(hit.fetched().sha256()).isEqualTo(Hashing.sha256Hex(MavenStub.EMPTY_JAR));
        assertThat(Files.readAllBytes(hit.fetched().cachePath()))
                .as("the slot holds the repository's bytes")
                .isEqualTo(MavenStub.EMPTY_JAR);
        assertThat(store(tmp, repo).readSha256Sidecar(JAR.substring(1)))
                .as("the memo was rewritten with them")
                .hasValue(Hashing.sha256Hex(MavenStub.EMPTY_JAR));
        assertThat(http.requested()).contains(JAR + ".sha1");
        assertThat(http.requestsFor(JAR)).as("the artifact was downloaded once").isEqualTo(1);
        assertThat(repo.checksumNotes())
                .singleElement()
                .asString()
                .contains("com.example:widget:1.0 from test")
                .contains("the store's copy")
                .contains("discarded");
    }

    @Test
    void a_store_copy_the_repository_confirms_is_pinned_without_a_download(@TempDir Path tmp) throws Exception {
        MavenRepo repo = repo(tmp);
        seed(tmp, repo, MavenStub.EMPTY_JAR);

        RepoGroup.RepoFetched hit = RepoGroup.of(repo).tryFetchArtifact(WIDGET).orElseThrow();

        assertThat(hit.fetched().sha256()).isEqualTo(Hashing.sha256Hex(MavenStub.EMPTY_JAR));
        assertThat(http.requested()).contains(JAR + ".sha1");
        assertThat(http.requestsFor(JAR)).as("the artifact stays in the store").isZero();
        assertThat(repo.verifiedUpstream())
                .as("the repository's checksum vouched for it")
                .isEqualTo(1);
        assertThat(repo.checksumNotes()).isEmpty();
    }

    @Test
    void a_second_resolve_from_one_store_reads_the_sidecar_and_not_the_artifact(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        assertThat(RepoGroup.of(repo(cas)).tryFetchArtifact(WIDGET)).isPresent();
        assertThat(http.requestsFor(JAR)).isEqualTo(1);
        RepoGroup.clearProcessFetchCache();
        http.clearRequests();

        assertThat(RepoGroup.of(repo(cas)).tryFetchArtifact(WIDGET)).isPresent();

        assertThat(http.requestsFor(JAR)).as("no second download").isZero();
        assertThat(http.requestsFor(JAR + ".sha1")).isEqualTo(1);
    }

    @Test
    void a_pinned_copy_needs_no_confirmation(@TempDir Path tmp) throws Exception {
        MavenRepo repo = repo(tmp);
        seed(tmp, repo, WRONG);

        assertThat(repo.tryLocalArtifact(WIDGET, Hashing.sha256Hex(WRONG), () -> false))
                .as("the lock's pin is the authority after the lock")
                .isPresent();
        assertThat(http.requested()).isEmpty();
    }

    @Test
    void offline_the_store_answers_as_it_stands(@TempDir Path tmp) throws Exception {
        MavenRepo repo = repo(tmp);
        seed(tmp, repo, WRONG);
        SessionContext.installConfig(JkConfig.empty().withOffline(true));

        RepoGroup.RepoFetched hit = RepoGroup.of(repo).tryFetchArtifact(WIDGET).orElseThrow();

        assertThat(hit.fetched().sha256()).isEqualTo(Hashing.sha256Hex(WRONG));
        assertThat(http.requested()).isEmpty();
    }

    @Test
    void a_repository_without_checksums_keeps_the_download_rule(@TempDir Path tmp) throws Exception {
        upstream.withoutChecksums();
        MavenRepo strict = repo(tmp.resolve("strict"));
        seed(tmp.resolve("strict"), strict, MavenStub.EMPTY_JAR);
        assertThatThrownBy(() -> RepoGroup.of(strict).tryFetchArtifact(WIDGET))
                .isInstanceOf(MavenRepo.MissingChecksumException.class)
                .hasMessageContaining("allow-unverified = true on [repositories.test]");
        assertThat(store(tmp.resolve("strict"), strict).locate(JAR.substring(1)))
                .as("a copy nobody vouches for is refused, not evicted")
                .isPresent();

        RepoGroup.clearProcessFetchCache();
        http.clearRequests();
        MavenRepo lenient = allowingUnverified(tmp.resolve("lenient"));
        seed(tmp.resolve("lenient"), lenient, MavenStub.EMPTY_JAR);
        assertThat(RepoGroup.of(lenient).tryFetchArtifact(WIDGET)).isPresent();
        assertThat(http.requestsFor(JAR)).isZero();
        assertThat(lenient.unverifiedAllowed()).isEqualTo(1);
    }

    private MavenRepo repo(Path tmp) {
        return repo(new Cas(tmp.resolve("cas")));
    }

    private MavenRepo repo(Cas cas) {
        return new MavenRepo("test", http.base(), new Http(), cas, RepoCredential.ANONYMOUS, false);
    }

    private MavenRepo allowingUnverified(Path tmp) {
        Http client = new Http();
        return MavenRepo.overTransport(
                "test",
                http.base(),
                RepoTransports.forUrl(http.base(), client),
                new Cas(tmp.resolve("cas")),
                RepoCredential.ANONYMOUS,
                client,
                false,
                true,
                false);
    }

    private static RepoArtifactStore store(Path tmp, MavenRepo repo) {
        return RepoArtifactStore.forRepository(tmp.resolve("cas"), "test", repo.baseUrl());
    }

    /** The store slot for the widget jar holds {@code bytes} and a memo that agrees with them. */
    private static void seed(Path tmp, MavenRepo repo, byte[] bytes) throws Exception {
        Path source = Files.write(Files.createDirectories(tmp).resolve("seed.jar"), bytes);
        store(tmp, repo).materialize(JAR.substring(1), source, Hashing.sha256Hex(bytes));
    }
}

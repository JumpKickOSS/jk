// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.RunNotices;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.transport.RepoTransports;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A repository Maven shares (Central, or one {@code settings.xml} declares; the stub here is
 * declared) is read out of {@code ~/.m2} before paying for an artifact.
 *
 * <p>The point of every test here is the integrity rule. {@code ~/.m2} is writable by anything on the
 * machine and Maven enforces no integrity, so a local hit is only ever a <em>candidate</em>; the
 * authority is a checksum fetched from the repository the artifact would have come from. Fetching it
 * remotely rather than reading {@code jk-lock.toml} is what makes the check usable during resolve, when no
 * lock entry exists yet.
 */
class MavenRepoM2LookupTest {

    private static final String REL = "com/example/widget/1.0/widget-1.0.jar";
    private static final byte[] REAL = "the genuine artifact bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IMPOSTOR = "a hand-built impostor".getBytes(StandardCharsets.UTF_8);
    private static final String UNVERIFIED_OFFLINE =
            "offline: com.example:widget:1.0 in ~/.m2 is unverified; run jk lock online first";

    @TempDir
    Path settingsDir;

    private HttpServer server;
    private URI base;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        // Maven shares this repository: the user's settings.xml declares it in an active profile.
        Path xml = settingsDir.resolve("settings.xml");
        Files.writeString(xml, """
                <settings>
                  <profiles><profile><id>p</id><repositories>
                    <repository><id>stub</id><url>%s</url></repository>
                  </repositories></profile></profiles>
                  <activeProfiles><activeProfile>p</activeProfile></activeProfiles>
                </settings>
                """.formatted(base));
        System.setProperty(MavenSettings.SETTINGS_PROPERTY, xml.toString());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        SessionContext.reset();
        System.clearProperty("jk.m2.integration");
    }

    private void serve(String path, int status, byte[] body) {
        server.createContext(path, ex -> {
            hits.add(ex.getRequestURI().getPath());
            ex.sendResponseHeaders(status, body.length);
            if (body.length > 0) ex.getResponseBody().write(body);
            ex.close();
        });
    }

    private static String sha1Of(byte[] data) {
        return Hashing.hashHex("SHA-1", data);
    }

    /** The repository under test with {@code allow-unverified = true} on its table. */
    private MavenRepo allowingUnverified(Path store) {
        Http http = new Http();
        return MavenRepo.overTransport(
                "test",
                base,
                RepoTransports.forUrl(base, http),
                new Cas(store),
                RepoCredential.ANONYMOUS,
                http,
                true,
                true,
                false);
    }

    /** Put {@code bytes} at the coordinate's Maven-layout path inside a throwaway {@code ~/.m2}. */
    private static void seedM2(Path m2, byte[] bytes) throws IOException {
        System.setProperty("jk.m2.local", m2.toString());
        Path p = m2.resolve(REL);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }

    private static Coordinate coord() {
        return Coordinate.of("com.example", "widget", "1.0");
    }

    @Test
    void prefers_the_sha256_sidecar_over_sha1(@TempDir Path tmp) throws Exception {
        // : when the repo publishes.sha256, adopt an ~/.m2 hit on the strong digest and do
        // NOT fall back to the collision-broken .sha1.
        seedM2(tmp.resolve("m2"), REAL);
        serve("/" + REL + ".sha256", 200, Hashing.sha256Hex(REAL).getBytes(StandardCharsets.UTF_8));
        // A .sha1 handler that would REJECT (wrong hash) — if the code fell back to it, adoption fails.
        serve("/" + REL + ".sha1", 200, "0".repeat(40).getBytes(StandardCharsets.UTF_8));
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        MavenRepo.Fetched fetched = repo.fetchArtifact(coord());

        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(REAL));
        assertThat(hits).contains("/" + REL + ".sha256");
    }

    @Test
    void a_local_hit_confirmed_by_the_remote_checksum_skips_the_jar_transfer(@TempDir Path tmp) throws Exception {
        seedM2(tmp.resolve("m2"), REAL);
        serve("/" + REL + ".sha1", 200, sha1Of(REAL).getBytes(StandardCharsets.UTF_8));
        // Deliberately NO handler for the jar itself: reaching for it would 404 and fail the fetch, so a
        // pass here proves the bytes came from ~/.m2.
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        MavenRepo.Fetched fetched = repo.fetchArtifact(coord());

        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(REAL);
        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(REAL));
        assertThat(hits).containsExactly("/" + REL + ".sha1");
    }

    /**
     * A confirmed file in the local repository is the answer where it is: nothing is copied into the
     * store, the memo that vouches for it lives there, and no CAS blob is involved.
     */
    @Test
    void a_confirmed_m2_hit_is_used_in_place_and_nothing_is_copied(@TempDir Path tmp) throws Exception {
        Path m2 = tmp.resolve("m2");
        seedM2(m2, REAL);
        serve("/" + REL + ".sha1", 200, sha1Of(REAL).getBytes(StandardCharsets.UTF_8));
        Path store = tmp.resolve("store");
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(store));

        MavenRepo.Fetched fetched = repo.fetchArtifact(coord());

        Path repoStore = Objects.requireNonNull(
                RepoArtifactStore.forRepository(store, "test", base).root());
        assertThat(fetched.cachePath()).isEqualTo(m2.resolve(REL));
        assertThat(repoStore.resolve(REL)).as("the store holds no second copy").doesNotExist();
        assertThat(new Cas(store).contains(Hashing.sha256Hex(REAL))).isFalse();
        assertThat(ArtifactMemo.read(ArtifactMemo.jkPath(repoStore, REL)))
                .as("the memo lives in the store, and says jk verified the file, not that it downloaded it")
                .get()
                .extracting(ArtifactMemo::fetched)
                .isEqualTo(false);
    }

    @Test
    void bytes_that_do_not_match_the_remote_checksum_are_left_alone_and_the_download_goes_to_the_store(
            @TempDir Path tmp) throws Exception {
        // Something at the right coordinate that is not the right artifact is never used, replaced or
        // removed: the file is Maven's or the user's (a local `mvn install` of a patched build looks
        // exactly like this). The genuine bytes are downloaded into jk's own store, where the file
        // in the local repository is not in the way.
        seedM2(tmp.resolve("m2"), "a hand-built impostor".getBytes(StandardCharsets.UTF_8));
        serve("/" + REL + ".sha1", 200, sha1Of(REAL).getBytes(StandardCharsets.UTF_8));
        serve("/" + REL, 200, REAL);
        Path store = tmp.resolve("store");
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(store));

        MavenRepo.Fetched got = repo.fetchArtifact(coord());

        Path own = Objects.requireNonNull(
                        RepoArtifactStore.forRepository(store, "test", base).root())
                .resolve(REL);
        assertThat(got.cachePath()).isEqualTo(own);
        assertThat(Files.readAllBytes(own)).isEqualTo(REAL);
        assertThat(Files.readAllBytes(tmp.resolve("m2").resolve(REL)))
                .isEqualTo("a hand-built impostor".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sharing_bytes_that_do_not_match_the_remote_checksum_are_refused_and_left_alone(@TempDir Path tmp)
            throws Exception {
        // On a machine that downloads into the local repository the impostor holds the slot the
        // genuine artifact would take. It is not replaced, so the fetch fails and names it.
        System.setProperty("jk.m2.integration", "share");
        seedM2(tmp.resolve("m2"), "a hand-built impostor".getBytes(StandardCharsets.UTF_8));
        serve("/" + REL + ".sha1", 200, sha1Of(REAL).getBytes(StandardCharsets.UTF_8));
        serve("/" + REL, 200, REAL);
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        assertThatThrownBy(() -> repo.fetchArtifact(coord()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("JumpKick did not write it");
        assertThat(Files.readAllBytes(tmp.resolve("m2").resolve(REL)))
                .isEqualTo("a hand-built impostor".getBytes(StandardCharsets.UTF_8));
        try (var walk = Files.walk(tmp.resolve("store"))) {
            assertThat(walk.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".put-")))
                    .as("the refused download leaves no temp in the store")
                    .isEmpty();
        }
    }

    @Test
    void a_missing_sidecar_means_no_authority_so_the_local_file_is_unused(@TempDir Path tmp) throws Exception {
        // No .sha1 to confirm against — even though the local bytes happen to be correct, there is
        // nothing vouching for them, so they are not used. The download that follows has nothing to
        // check against either, so only a repository that allows unverified bytes gets this far.
        seedM2(tmp.resolve("m2"), REAL);
        serve("/" + REL, 200, REAL);
        MavenRepo repo = allowingUnverified(tmp.resolve("store"));

        repo.fetchArtifact(coord());

        assertThat(hits).contains("/" + REL);
        assertThat(repo.unverifiedAllowed()).isEqualTo(1);
    }

    @Test
    void a_sidecar_that_is_not_a_sha1_is_refused(@TempDir Path tmp) throws Exception {
        // Some repositories answer 200 with an HTML error page. Forty hex characters or nothing.
        seedM2(tmp.resolve("m2"), REAL);
        serve("/" + REL + ".sha1", 200, "<html>Not Found</html>".getBytes(StandardCharsets.UTF_8));
        serve("/" + REL, 200, REAL);
        MavenRepo repo = allowingUnverified(tmp.resolve("store"));

        repo.fetchArtifact(coord());

        assertThat(hits).contains("/" + REL);
        assertThat(repo.unverifiedAllowed()).as("an HTML page is no checksum").isEqualTo(1);
    }

    @Test
    void a_checksum_with_a_trailing_filename_still_parses(@TempDir Path tmp) throws Exception {
        // `sha1sum` style: "<hex> <filename>". Common enough that rejecting it would silently disable
        // the optimisation against those repositories.
        seedM2(tmp.resolve("m2"), REAL);
        serve("/" + REL + ".sha1", 200, (sha1Of(REAL) + "  widget-1.0.jar\n").getBytes(StandardCharsets.UTF_8));
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        MavenRepo.Fetched fetched = repo.fetchArtifact(coord());

        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(REAL);
        assertThat(hits).containsExactly("/" + REL + ".sha1");
    }

    @Test
    void nothing_in_m2_costs_one_stat_and_falls_through(@TempDir Path tmp) throws Exception {
        System.setProperty("jk.m2.local", tmp.resolve("empty-m2").toString());
        serve("/" + REL, 200, REAL);
        serve("/" + REL + ".sha1", 200, sha1Of(REAL).getBytes(StandardCharsets.UTF_8));
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        MavenRepo.Fetched fetched = repo.fetchArtifact(coord());

        // Falls straight through to the ordinary download. (The sidecar GETs visible here belong to the
        // normal post-download verification, not to the probe — with no candidate there is nothing to
        // confirm, and the hit-path test above proves the probe's own GET is the only request made when
        // a candidate does exist.)
        assertThat(hits).contains("/" + REL);
        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(REAL);
    }

    /** Where the store keeps the {@code .jk} memo that would vouch for the {@code ~/.m2} file at {@code rel}. */
    private Path memo(Path store, String rel) {
        Path repoStore = Objects.requireNonNull(
                RepoArtifactStore.forRepository(store, "test", base).root());
        return ArtifactMemo.jkPath(repoStore, rel);
    }

    private static void goOffline() {
        SessionContext.installConfig(JkConfig.empty().withOffline(true));
    }

    /** The run's warnings, collected while {@code body} runs. */
    private static List<String> warnings(ThrowingRunnable body) throws Exception {
        List<String> said = new CopyOnWriteArrayList<>();
        RunNotices.openSink(SessionContext.current().io(), (code, message) -> said.add(message));
        try {
            body.run();
        } finally {
            RunNotices.closeSink(SessionContext.current().io());
        }
        return said;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void an_unvouched_m2_copy_is_not_served_when_the_checksum_read_fails(@TempDir Path tmp) throws Exception {
        System.setProperty("jk.m2.integration", "share");
        seedM2(tmp.resolve("m2"), IMPOSTOR);
        serve("/", 500, new byte[0]);
        Path store = tmp.resolve("store");
        MavenRepo repo = new MavenRepo("test", base, Http.failFast(), new Cas(store));

        assertThatThrownBy(() -> repo.fetchArtifact(coord())).isInstanceOf(IOException.class);

        assertThat(ArtifactMemo.read(memo(store, REL))).isEmpty();
        assertThat(Files.readAllBytes(tmp.resolve("m2").resolve(REL))).isEqualTo(IMPOSTOR);
    }

    @Test
    void an_unvouched_m2_copy_is_not_served_while_its_repository_is_unreachable(@TempDir Path tmp) throws Exception {
        System.setProperty("jk.m2.integration", "share");
        seedM2(tmp.resolve("m2"), IMPOSTOR);
        server.stop(0);
        Path store = tmp.resolve("store");
        MavenRepo repo = new MavenRepo("test", base, Http.failFast(), new Cas(store));

        // The first fetch finds the repository down; the second is refused without asking it.
        assertThatThrownBy(() -> repo.fetchArtifact(coord())).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> repo.fetchArtifact(coord()))
                .isInstanceOf(MavenRepo.RepositoryUnreachableException.class);
        assertThat(repo.tryLocalArtifact(coord(), null, () -> false)).isEmpty();

        assertThat(ArtifactMemo.read(memo(store, REL))).isEmpty();
    }

    @Test
    void an_unvouched_m2_copy_is_not_served_once_the_lock_has_failed(@TempDir Path tmp) throws Exception {
        System.setProperty("jk.m2.integration", "share");
        seedM2(tmp.resolve("m2"), IMPOSTOR);
        Path store = tmp.resolve("store");
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(store));

        assertThat(repo.tryLocalArtifact(coord(), null, () -> true)).isEmpty();

        assertThat(hits).isEmpty();
        assertThat(ArtifactMemo.read(memo(store, REL))).isEmpty();
    }

    @Test
    void offline_an_unvouched_m2_copy_is_not_found_and_gets_no_memo(@TempDir Path tmp) throws Exception {
        System.setProperty("jk.m2.integration", "share");
        seedM2(tmp.resolve("m2"), IMPOSTOR);
        server.stop(0);
        goOffline();
        Path store = tmp.resolve("store");
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(store));

        List<String> said = warnings(() -> {
            assertThat(repo.tryLocalArtifact(coord(), null, () -> false)).isEmpty();
            assertThatThrownBy(() -> repo.fetchArtifact(coord()))
                    .isInstanceOf(MavenRepo.ArtifactNotFoundException.class)
                    .hasMessage(UNVERIFIED_OFFLINE);
        });

        assertThat(said).containsExactly("jk: warning: " + UNVERIFIED_OFFLINE);
        assertThat(ArtifactMemo.read(memo(store, REL))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "share"})
    void offline_a_coordinate_held_nowhere_is_an_ordinary_miss(String integration, @TempDir Path tmp) throws Exception {
        System.setProperty("jk.m2.integration", integration);
        System.setProperty("jk.m2.local", tmp.resolve("m2").toString());
        server.stop(0);
        goOffline();
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        List<String> said = warnings(() -> {
            assertThat(repo.tryLocalArtifact(coord(), null, () -> false)).isEmpty();
            assertThatThrownBy(() -> repo.fetchArtifact(coord()))
                    .isInstanceOf(MavenRepo.ArtifactNotFoundException.class)
                    .hasMessageContaining("not in local index for test");
        });

        assertThat(said).isEmpty();
    }

    /**
     * A POM the store holds a memo for is served as stored, so a memo written offline for bytes
     * nothing checked would decide every later online lock.
     */
    @Test
    void an_offline_run_leaves_an_unvouched_m2_pom_to_be_checked_online(@TempDir Path tmp) throws Exception {
        String pomRel = "com/example/widget/1.0/widget-1.0.pom";
        byte[] realPom = "<project>genuine</project>".getBytes(StandardCharsets.UTF_8);
        Path m2 = tmp.resolve("m2");
        System.setProperty("jk.m2.local", m2.toString());
        Files.createDirectories(m2.resolve(pomRel).getParent());
        Files.write(m2.resolve(pomRel), IMPOSTOR);
        Path store = tmp.resolve("store");

        goOffline();
        MavenRepo offline = new MavenRepo("test", base, new Http(), new Cas(store));
        assertThatThrownBy(() -> offline.fetchPom(coord())).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        assertThat(ArtifactMemo.read(memo(store, pomRel))).isEmpty();

        SessionContext.reset();
        serve("/" + pomRel + ".sha1", 200, sha1Of(realPom).getBytes(StandardCharsets.UTF_8));
        serve("/" + pomRel, 200, realPom);
        MavenRepo online = new MavenRepo("test", base, new Http(), new Cas(store));

        assertThat(Files.readAllBytes(online.fetchPom(coord()).cachePath())).isEqualTo(realPom);
    }

    @Test
    void offline_a_pinned_m2_copy_is_served_in_place(@TempDir Path tmp) throws Exception {
        System.setProperty("jk.m2.integration", "share");
        Path m2 = tmp.resolve("m2");
        seedM2(m2, REAL);
        server.stop(0);
        goOffline();
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        MavenRepo.Fetched fetched = repo.fetchArtifact(coord(), Hashing.sha256Hex(REAL), () -> false);

        assertThat(fetched.cachePath()).isEqualTo(m2.resolve(REL));
        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(REAL));
    }

    /** A non-unique snapshot jk downloaded answers as stored while its repository cannot be asked. */
    @Test
    void a_vouched_moving_snapshot_is_served_while_its_repository_is_unreachable(@TempDir Path tmp) throws Exception {
        System.setProperty("jk.m2.local", tmp.resolve("m2").toString());
        Coordinate snapshot = Coordinate.of("com.example", "widget", "1.0-SNAPSHOT");
        String rel = MavenLayout.artifactPath(snapshot);
        serve("/" + rel, 200, REAL);
        serve("/" + rel + ".sha1", 200, sha1Of(REAL).getBytes(StandardCharsets.UTF_8));
        Path store = tmp.resolve("store");
        new MavenRepo("test", base, new Http(), new Cas(store)).fetchArtifact(snapshot);
        server.stop(0);
        MavenRepo repo = new MavenRepo("test", base, Http.failFast(), new Cas(store));

        Optional<MavenRepo.Fetched> stored = repo.tryLocalArtifact(snapshot, null, () -> false);

        assertThat(stored).isPresent();
        assertThat(Files.readAllBytes(stored.get().cachePath())).isEqualTo(REAL);
    }
}

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
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenRepoTest {

    // Fetches mirror into the Maven local repo; point that at a throwaway dir (see
    // M2Dirs) so these tests never write into the developer's real ~/.m2.
    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        SessionContext.reset();
    }

    private static void goOffline() {
        SessionContext.installConfig(JkConfig.empty().withOffline(true));
    }

    @Test
    void fetches_pom_into_cas(@TempDir Path tempDir) throws Exception {
        byte[] pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                </project>
                """.getBytes(StandardCharsets.UTF_8);

        serveArtifact("/com/example/widget/1.0/widget-1.0.pom", pom);

        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));
        MavenRepo.Fetched fetched = repo.fetchPom(Coordinate.of("com.example", "widget", "1.0"));

        assertThat(fetched.url().toString()).endsWith("/widget-1.0.pom");
        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(pom));
        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(pom);
    }

    @Test
    void fetches_jar_into_cas(@TempDir Path tempDir) throws Exception {
        byte[] jar = "fake-jar-bytes".getBytes(StandardCharsets.UTF_8);
        serveArtifact("/com/example/widget/1.0/widget-1.0.jar", jar);

        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));
        MavenRepo.Fetched fetched = repo.fetchArtifact(Coordinate.of("com.example", "widget", "1.0"));

        assertThat(fetched.size()).isEqualTo(jar.length);
        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(jar));
    }

    @Test
    void stale_mirror_copy_against_a_changed_pin_is_evicted_and_refetched(@TempDir Path tempDir) throws Exception {
        // : an internal repo republished the same GAV and the lock was re-pinned. The warm
        // store copy (old bytes) must not dead-end sync — pass the pin so it is evicted and re-fetched.
        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
        String relPath = MavenLayout.artifactPath(coord);

        // Seed a stale store copy with old bytes.
        byte[] oldBytes = "old-widget-bytes".getBytes(StandardCharsets.UTF_8);
        Path stale = tempDir.resolve("stale.jar");
        Files.write(stale, oldBytes);
        RepoArtifactStore.forRepoName(tempDir, "test").materialize(relPath, stale, Hashing.sha256Hex(oldBytes));

        // The repo now serves new bytes.
        byte[] newBytes = "new-widget-bytes".getBytes(StandardCharsets.UTF_8);
        serveArtifact("/" + relPath, newBytes);
        String newSha = Hashing.sha256Hex(newBytes);

        // m2 off so only the store mirror is in play.
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir), RepoCredential.ANONYMOUS, false);

        // Without the pin: the stale mirror copy is served (the old dead-end behavior).
        assertThat(repo.fetchArtifact(coord).sha256()).isEqualTo(Hashing.sha256Hex(oldBytes));

        // With the pin: stale copy evicted, new bytes fetched.
        MavenRepo.Fetched f = repo.fetchArtifact(coord, newSha, () -> false);
        assertThat(f.sha256()).isEqualTo(newSha);
    }

    @Test
    void translates_404_to_typed_exception(@TempDir Path tempDir) {
        // No handler registered → 404 from the SimpleHttpServer fallback.
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));
        assertThatThrownBy(() -> repo.fetchArtifact(Coordinate.of("com.example", "missing", "9.9.9")))
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
    }

    @Test
    void layout_paths_match_maven_convention() {
        Coordinate widget = Coordinate.of("com.fasterxml.jackson.core", "jackson-databind", "2.18.2");
        assertThat(MavenLayout.pomPath(widget))
                .isEqualTo("com/fasterxml/jackson/core/jackson-databind/2.18.2/jackson-databind-2.18.2.pom");
        assertThat(MavenLayout.artifactPath(widget))
                .isEqualTo("com/fasterxml/jackson/core/jackson-databind/2.18.2/jackson-databind-2.18.2.jar");
        assertThat(MavenLayout.metadataPath(widget))
                .isEqualTo("com/fasterxml/jackson/core/jackson-databind/maven-metadata.xml");
        // Classified secondary artifacts share the main GAV POM (never guice-5.1.0-classes.pom).
        Coordinate guiceClasses = new Coordinate("com.google.inject", "guice", "5.1.0", "classes", "jar");
        assertThat(MavenLayout.pomPath(guiceClasses)).isEqualTo("com/google/inject/guice/5.1.0/guice-5.1.0.pom");
        assertThat(MavenLayout.artifactPath(guiceClasses))
                .isEqualTo("com/google/inject/guice/5.1.0/guice-5.1.0-classes.jar");
    }

    @Test
    void online_fetch_mirrors_into_named_repo_store(@TempDir Path tempDir) throws Exception {
        byte[] pom = "<project/>".getBytes(StandardCharsets.UTF_8);
        serveArtifact("/com/example/widget/1.0/widget-1.0.pom", pom);
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));

        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
        repo.fetchPom(coord);

        // metadata is deliberately not mirrored
        assertThat(RepoArtifactStore.forRepoName(tempDir, "test").versions("com.example", "widget"))
                .containsExactly("1.0");
    }

    @Test
    void m2integration_false_keeps_jars_in_the_named_store(@TempDir Path tempDir, @TempDir Path m2) throws Exception {
        String previous = System.setProperty("jk.m2.local", m2.toString());
        try {
            byte[] jar = "fake-jar-bytes".getBytes(StandardCharsets.UTF_8);
            serveArtifact("/com/example/widget/1.0/widget-1.0.jar", jar);
            MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir), RepoCredential.ANONYMOUS, false);

            Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
            repo.fetchArtifact(coord);

            assertThat(RepoArtifactStore.forRepoName(tempDir, "test").locate(MavenLayout.artifactPath(coord)))
                    .isPresent();
            assertThat(m2.resolve(MavenLayout.artifactPath(coord))).doesNotExist();
        } finally {
            restoreM2Local(previous);
        }
    }

    @Test
    void m2integration_true_write_through_populates_m2(@TempDir Path tempDir, @TempDir Path m2) throws Exception {
        String previous = System.setProperty("jk.m2.local", m2.toString());
        try {
            byte[] jar = "fake-jar-bytes".getBytes(StandardCharsets.UTF_8);
            serveArtifact("/com/example/widget/1.0/widget-1.0.jar", jar);
            MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir), RepoCredential.ANONYMOUS, true);

            Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
            MavenRepo.Fetched fetched = repo.fetchArtifact(coord);

            Path m2Jar = m2.resolve(MavenLayout.artifactPath(coord));
            assertThat(fetched.cachePath()).isEqualTo(m2Jar);
            assertThat(m2Jar).exists();
            assertThat(Files.readAllBytes(m2Jar)).isEqualTo(jar);
            assertThat(m2Jar.resolveSibling(m2Jar.getFileName() + ".sha1")).exists();
            assertThat(m2Jar.resolveSibling(m2Jar.getFileName() + ".md5")).exists();
            assertThat(m2Jar.resolveSibling("_remote.repositories")).exists();
        } finally {
            restoreM2Local(previous);
        }
    }

    /** Restore {@code jk.m2.local} to its prior value (the class-level {@link #isolateM2} temp dir). */
    private static void restoreM2Local(String previous) {
        if (previous != null) {
            System.setProperty("jk.m2.local", previous);
        } else {
            System.clearProperty("jk.m2.local");
        }
    }

    @Test
    void offline_fetch_is_served_from_the_named_repo_store(@TempDir Path tempDir) throws Exception {
        byte[] pom = "<project/>".getBytes(StandardCharsets.UTF_8);
        serveArtifact("/com/example/widget/1.0/widget-1.0.pom", pom);
        Cas cas = new Cas(tempDir);
        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");

        // Warm the cache + named-repo store online.
        new MavenRepo("test", base, new Http(), cas).fetchPom(coord);

        // Now offline: stop the server so any network attempt would fail loudly.
        server.stop(0);
        goOffline();
        MavenRepo offline = new MavenRepo("test", base, new Http(), cas);
        MavenRepo.Fetched fetched = offline.fetchPom(coord);

        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(pom));
        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(pom);
    }

    @Test
    void online_warm_fetch_uses_local_store_without_network(@TempDir Path tempDir) throws Exception {
        // second online fetch of the same GAV should not re-HTTP.
        byte[] pom = "<project><modelVersion>4.0.0</modelVersion></project>".getBytes(StandardCharsets.UTF_8);
        serveArtifact("/com/example/widget/1.0/widget-1.0.pom", pom);
        // Checksum sidecars (optional) — miss is OK for TOFU
        Cas cas = new Cas(tempDir);
        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
        MavenRepo repo = new MavenRepo("test", base, new Http(), cas);
        MavenRepo.Fetched first = repo.fetchPom(coord);
        assertThat(first.sha256()).isEqualTo(Hashing.sha256Hex(pom));

        server.stop(0); // any further network would fail
        MavenRepo warm = new MavenRepo("test", base, new Http(), cas);
        MavenRepo.Fetched second = warm.fetchPom(coord);
        assertThat(second.sha256()).isEqualTo(first.sha256());
        assertThat(Files.readAllBytes(second.cachePath())).isEqualTo(pom);
    }

    @Test
    void offline_fetch_of_unindexed_coord_is_not_found(@TempDir Path tempDir) {
        goOffline();
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));
        assertThatThrownBy(() -> repo.fetchPom(Coordinate.of("com.example", "absent", "1.0")))
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
    }

    @Test
    void offline_available_versions_come_from_the_named_repo_store(@TempDir Path tempDir) throws Exception {
        Cas cas = new Cas(tempDir);
        RepoArtifactStore store = RepoArtifactStore.forRepoName(tempDir, "test");
        store.materialize(
                MavenLayout.artifactPath(Coordinate.of("com.example", "widget", "1.0")),
                cas.put("jar-1".getBytes(StandardCharsets.UTF_8)),
                "sha-1");
        store.materialize(
                MavenLayout.artifactPath(Coordinate.of("com.example", "widget", "2.0")),
                cas.put("jar-2".getBytes(StandardCharsets.UTF_8)),
                "sha-2");
        goOffline();

        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));
        assertThat(repo.availableVersions(Coordinate.of("com.example", "widget", "0")))
                .containsExactlyInAnyOrder("1.0", "2.0");
    }

    @Test
    void file_repo_enumerates_versions_via_transport_not_the_http_cache(@TempDir Path tempDir) throws Exception {
        // Regression: a non-HTTP (file://) repo may be paired with an Http client,
        // but version enumeration must go through the transport. The HTTP metadata
        // cache speaks java.net.http directly and throws "invalid URI scheme file"
        // on a file:// URI, so it must not be engaged for such repos.
        Path repoDir = tempDir.resolve("repo");
        Path metaFile = repoDir.resolve(MavenLayout.metadataPath(Coordinate.of("com.example", "widget", "0")));
        Files.createDirectories(metaFile.getParent());
        Files.writeString(metaFile, """
                <metadata><groupId>com.example</groupId><artifactId>widget</artifactId>
                <versioning><versions><version>1.0</version><version>2.0</version></versions></versioning>
                </metadata>
                """);

        MavenRepo repo = new MavenRepo("local", repoDir.toUri(), new Http(), new Cas(tempDir.resolve("cas")));
        assertThat(repo.availableVersions(Coordinate.of("com.example", "widget", "0")))
                .containsExactlyInAnyOrder("1.0", "2.0");
    }

    @Test
    void mismatching_sha256_sidecar_fails_closed(@TempDir Path tempDir) {
        byte[] jar = "tampered-bytes".getBytes(StandardCharsets.UTF_8);
        serve("/com/example/widget/1.0/widget-1.0.jar", 200, jar);
        serve(
                "/com/example/widget/1.0/widget-1.0.jar.sha256",
                200,
                "0000000000000000000000000000000000000000000000000000000000000000".getBytes(StandardCharsets.UTF_8));
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));
        assertThatThrownBy(() -> repo.fetchArtifact(Coordinate.of("com.example", "widget", "1.0")))
                .isInstanceOf(MavenRepo.ChecksumMismatchException.class)
                .hasMessageContaining("checksum mismatch")
                .hasMessageContaining("sha256");
    }

    @Test
    void matching_sha256_sidecar_allows_fetch_and_counts_it_verified(@TempDir Path tempDir) throws Exception {
        byte[] jar = "good-bytes".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        serve("/com/example/widget/1.0/widget-1.0.jar", 200, jar);
        serve(
                "/com/example/widget/1.0/widget-1.0.jar.sha256",
                200,
                (hex + "  widget-1.0.jar\n").getBytes(StandardCharsets.UTF_8));
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));
        MavenRepo.Fetched f = repo.fetchArtifact(Coordinate.of("com.example", "widget", "1.0"));
        assertThat(f.sha256()).isEqualTo(hex);
        assertThat(repo.verifiedUpstream()).isEqualTo(1);
        assertThat(repo.unverifiedAllowed()).isZero();
    }

    @Test
    void a_missing_sidecar_refuses_the_fetch_and_names_the_opt_in(@TempDir Path tempDir) {
        byte[] jar = "no-sidecar".getBytes(StandardCharsets.UTF_8);
        serve("/com/example/widget/1.0/widget-1.0.jar", 200, jar);
        MavenRepo repo = new MavenRepo("mirror", base, new Http(), new Cas(tempDir));
        assertThatThrownBy(() -> repo.fetchArtifact(Coordinate.of("com.example", "widget", "1.0")))
                .isInstanceOf(MavenRepo.MissingChecksumException.class)
                .hasMessageContaining("no upstream checksum for com.example:widget:1.0 from mirror")
                .hasMessageContaining("neither a .sha256 nor a .sha1 sidecar")
                .hasMessageContaining("allow-unverified = true on [repositories.mirror]");
        assertThat(repo.unverifiedAllowed()).isZero();
        assertThat(tempDir.resolve("repos").resolve("mirror"))
                .as("refused bytes are not left in the store")
                .satisfiesAnyOf(dir -> assertThat(dir).doesNotExist(), dir -> assertThat(
                                Files.list(dir).filter(Files::isRegularFile))
                        .isEmpty());
    }

    @Test
    void allow_unverified_pins_a_sidecarless_artifact_and_counts_it(@TempDir Path tempDir) throws Exception {
        byte[] jar = "no-sidecar".getBytes(StandardCharsets.UTF_8);
        serve("/com/example/widget/1.0/widget-1.0.jar", 200, jar);
        MavenRepo repo = allowingUnverified("legacy", tempDir);
        MavenRepo.Fetched f = repo.fetchArtifact(Coordinate.of("com.example", "widget", "1.0"));
        assertThat(f.sha256()).isEqualTo(Hashing.sha256Hex(jar));
        assertThat(repo.unverifiedAllowed()).isEqualTo(1);
        assertThat(repo.verifiedUpstream()).isZero();
    }

    @Test
    void a_lock_pin_vouches_for_a_sidecarless_artifact_after_the_lock(@TempDir Path tempDir) throws Exception {
        byte[] jar = "pinned-bytes".getBytes(StandardCharsets.UTF_8);
        serve("/com/example/widget/1.0/widget-1.0.jar", 200, jar);
        MavenRepo repo = new MavenRepo("mirror", base, new Http(), new Cas(tempDir));
        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");

        MavenRepo.Fetched f = repo.fetchArtifact(coord, Hashing.sha256Hex(jar), () -> false);
        assertThat(f.sha256()).isEqualTo(Hashing.sha256Hex(jar));
        assertThat(repo.unverifiedAllowed()).isZero();

        SessionContext.installConfig(JkConfig.empty().withForce(true));
        assertThatThrownBy(() -> repo.fetchArtifact(coord, "0".repeat(64), () -> false))
                .isInstanceOf(MavenRepo.ChecksumMismatchException.class)
                .hasMessageContaining("jk-lock.toml pins sha256");
    }

    @Test
    void a_file_repository_needs_no_sidecar(@TempDir Path tempDir) throws Exception {
        Path repoDir = tempDir.resolve("repo");
        Path jar = repoDir.resolve(MavenLayout.artifactPath(Coordinate.of("com.example", "widget", "1.0")));
        Files.createDirectories(jar.getParent());
        Files.write(jar, "local-bytes".getBytes(StandardCharsets.UTF_8));
        MavenRepo repo = new MavenRepo("local", repoDir.toUri(), new Http(), new Cas(tempDir.resolve("cas")));
        MavenRepo.Fetched f = repo.fetchArtifact(Coordinate.of("com.example", "widget", "1.0"));
        assertThat(f.sha256()).isEqualTo(Hashing.sha256Hex(jar));
        assertThat(repo.unverifiedAllowed()).isZero();
        assertThat(repo.isPlaintext()).isFalse();
    }

    @Test
    void a_plaintext_repository_reports_itself_and_prints_no_warning(@TempDir Path tempDir) throws Exception {
        byte[] jar = "bytes".getBytes(StandardCharsets.UTF_8);
        serve("/com/example/widget/1.0/widget-1.0.jar", 200, jar);
        serve(
                "/com/example/widget/1.0/widget-1.0.jar.sha256",
                200,
                Hashing.sha256Hex(jar).getBytes(StandardCharsets.UTF_8));
        // The stub is on loopback, which needs no opt-in and reports nothing; opting in reports it.
        Http http = new Http();
        MavenRepo quiet = new MavenRepo("mirror", base, http, new Cas(tempDir));
        MavenRepo repo = MavenRepo.overTransport(
                "mirror",
                base,
                RepoTransports.forUrl(base, http),
                new Cas(tempDir),
                RepoCredential.ANONYMOUS,
                http,
                false,
                false,
                true);
        var err = new ByteArrayOutputStream();
        var original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            repo.fetchArtifact(Coordinate.of("com.example", "widget", "1.0"));
        } finally {
            System.setErr(original);
        }
        assertThat(quiet.isPlaintext()).isFalse();
        assertThat(repo.isPlaintext()).isTrue();
        assertThat(err.toString(StandardCharsets.UTF_8))
                .as("the manifest already refused or allowed http://; the fetch says nothing")
                .isEmpty();
    }

    private MavenRepo allowingUnverified(String name, Path cas) {
        Http http = new Http();
        return MavenRepo.overTransport(
                name,
                base,
                RepoTransports.forUrl(base, http),
                new Cas(cas),
                RepoCredential.ANONYMOUS,
                http,
                false,
                true,
                false);
    }

    /** An artifact and the {@code .sha1} every repository publishes beside it. */
    private void serveArtifact(String path, byte[] body) {
        serve(path, 200, body);
        serve(path + ".sha1", 200, Hashing.hashHex("SHA-1", body).getBytes(StandardCharsets.UTF_8));
    }

    private void serve(String path, int status, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }
}

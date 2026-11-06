// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Offline means no network, not no repositories: a repository or mirror on this machine's disk
 * ({@code file:}) answers an offline resolve as it answers an online one, and one that needs the
 * network is skipped for the store, as before.
 */
class OfflineLocalRepositoryTest {

    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

    /** Where the project believes its artifacts come from; nothing ever answers there. */
    private static final URI ORIGIN = URI.create("https://repo.example.invalid/maven2/");

    private static final Coordinate JSPECIFY = Coordinate.of("org.jspecify", "jspecify", "1.0.1");
    private static final Coordinate BOM = Coordinate.of("com.example", "platform-bom", "3.0");
    private static final byte[] JAR = "jspecify-bytes".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    @AfterEach
    void reset() {
        SessionContext.reset();
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
    }

    private static void goOffline() {
        SessionContext.installConfig(JkConfig.empty().withOffline(true));
    }

    private static void write(Path repo, String relativePath, byte[] body) throws IOException {
        Path file = repo.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, body);
    }

    private static byte[] pom(Coordinate c, String packaging) {
        return ("<project><modelVersion>4.0.0</modelVersion><groupId>" + c.group() + "</groupId><artifactId>"
                        + c.artifact() + "</artifactId><version>" + c.version() + "</version><packaging>"
                        + packaging + "</packaging></project>")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The project's repository, every request of which goes to {@code mirror}. */
    private static MavenRepo mirroredThrough(URI mirror, Path dir) {
        MavenRepo origin = new MavenRepo("corp", ORIGIN, new Http(), new Cas(dir.resolve("cas")));
        return origin.mirroredThrough(new MavenRepo.Mirror("disk", mirror, RepoCredential.ANONYMOUS, "mirror disk"));
    }

    @Test
    void an_exact_pin_served_only_by_a_file_mirror_resolves_offline(@TempDir Path dir) throws Exception {
        Path disk = dir.resolve("mirror");
        write(disk, MavenLayout.pomPath(JSPECIFY), pom(JSPECIFY, "jar"));
        write(disk, MavenLayout.artifactPath(JSPECIFY), JAR);
        MavenRepo repo = mirroredThrough(disk.toUri(), dir);
        goOffline();

        assertThat(repo.local()).isTrue();
        RepoGroup group = RepoGroup.of(repo);
        assertThat(group.tryFetchPom(JSPECIFY)).isPresent();
        Optional<RepoGroup.RepoFetched> jar = group.tryFetchArtifact(JSPECIFY, Hashing.sha256Hex(JAR));

        assertThat(jar).hasValueSatisfying(hit -> {
            assertThat(hit.fetched().sha256()).isEqualTo(Hashing.sha256Hex(JAR));
            // The bytes land in the repository's own store, as they do online.
            assertThat(hit.fetched().cachePath()).startsWith(repo.storeDir());
        });
    }

    @Test
    void the_same_pin_through_an_https_mirror_is_not_found_offline(@TempDir Path dir) throws Exception {
        MavenRepo repo = mirroredThrough(URI.create("https://mirror.example.invalid/maven2/"), dir);
        goOffline();

        assertThat(repo.local()).isFalse();
        RepoGroup group = RepoGroup.of(repo);
        assertThat(group.tryFetchPom(JSPECIFY)).isEmpty();
        assertThat(group.tryFetchArtifact(JSPECIFY, Hashing.sha256Hex(JAR))).isEmpty();
    }

    @Test
    void a_pom_only_coordinate_resolves_offline_from_a_file_mirror(@TempDir Path dir) throws Exception {
        Path disk = dir.resolve("mirror");
        byte[] pom = pom(BOM, "pom");
        write(disk, MavenLayout.pomPath(BOM), pom);
        MavenRepo repo = mirroredThrough(disk.toUri(), dir);
        goOffline();

        RepoGroup group = RepoGroup.of(repo);
        assertThat(group.tryFetchPom(BOM))
                .hasValueSatisfying(hit -> assertThat(hit.fetched().sha256()).isEqualTo(Hashing.sha256Hex(pom)));
        assertThat(group.tryFetchArtifact(BOM))
                .as("a POM-only coordinate has no jar")
                .isEmpty();
    }

    @Test
    void a_file_repository_without_a_mirror_is_read_offline_too(@TempDir Path dir) throws Exception {
        Path disk = dir.resolve("repo");
        write(disk, MavenLayout.pomPath(JSPECIFY), pom(JSPECIFY, "jar"));
        write(disk, MavenLayout.artifactPath(JSPECIFY), JAR);
        MavenRepo repo = new MavenRepo("local", disk.toUri(), new Http(), new Cas(dir.resolve("cas")));
        goOffline();

        assertThat(repo.fetchArtifact(JSPECIFY, Hashing.sha256Hex(JAR), () -> false)
                        .sha256())
                .isEqualTo(Hashing.sha256Hex(JAR));
    }

    @Test
    void offline_versions_of_a_file_mirror_are_its_catalog_and_what_the_store_already_holds(@TempDir Path dir)
            throws Exception {
        Path disk = dir.resolve("mirror");
        write(disk, MavenLayout.metadataPath(JSPECIFY), """
                <metadata><groupId>org.jspecify</groupId><artifactId>jspecify</artifactId>
                <versioning><versions><version>1.0.0</version><version>1.0.1</version></versions></versioning>
                </metadata>
                """.getBytes(StandardCharsets.UTF_8));
        MavenRepo repo = mirroredThrough(disk.toUri(), dir);
        Cas cas = new Cas(dir.resolve("cas"));
        Coordinate stored = Coordinate.of("org.jspecify", "jspecify", "0.3.0");
        RepoArtifactStore.forRepository(cas.root(), "corp", ORIGIN, true)
                .materialize(MavenLayout.artifactPath(stored), cas.put(JAR), Hashing.sha256Hex(JAR));
        goOffline();

        assertThat(repo.availableVersions(JSPECIFY)).containsExactlyInAnyOrder("1.0.0", "1.0.1", "0.3.0");
    }
}

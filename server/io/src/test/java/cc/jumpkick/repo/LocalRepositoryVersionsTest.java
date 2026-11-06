// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code file:} repository with no {@code maven-metadata.xml} — a Maven local repository, a jk
 * store tree — lists its versions by its version directories, so a range or {@code latest} finds
 * them. Only a directory holding the POM a fetch reads counts.
 */
class LocalRepositoryVersionsTest {

    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

    private static final Coordinate LAUNCHER = Coordinate.of("org.junit.platform", "junit-platform-launcher", "0");

    @BeforeEach
    @AfterEach
    void reset() {
        SessionContext.reset();
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
    }

    private static void write(Path repo, String relativePath) throws IOException {
        Path file = repo.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<project/>", StandardCharsets.UTF_8);
    }

    private static Coordinate at(String version) {
        return Coordinate.of(LAUNCHER.group(), LAUNCHER.artifact(), version);
    }

    /**
     * 1.9.0, 1.10.0 and 1.11.0 with their POMs; 6.0.0 with a jar alone; a snapshot with its POM; a
     * stray file beside the version directories. No catalog anywhere.
     */
    private static Path metadataLess(Path dir) throws IOException {
        Path repo = dir.resolve("m2");
        for (String v : new String[] {"1.9.0", "1.11.0", "1.10.0", "7.0.0-SNAPSHOT"}) {
            write(repo, MavenLayout.pomPath(at(v)));
            write(repo, MavenLayout.artifactPath(at(v)));
        }
        write(repo, MavenLayout.artifactPath(at("6.0.0")));
        write(repo, "org/junit/platform/junit-platform-launcher/maven-metadata-local.xml");
        return repo;
    }

    @Test
    void a_metadata_less_file_repository_lists_its_version_directories_that_hold_a_pom(@TempDir Path dir)
            throws Exception {
        MavenRepo repo = new MavenRepo("local", metadataLess(dir).toUri(), new Http(), new Cas(dir.resolve("cas")));

        assertThat(repo.availableVersions(LAUNCHER)).containsExactly("1.9.0", "1.10.0", "1.11.0", "7.0.0-SNAPSHOT");
    }

    @Test
    void the_newest_release_is_what_a_release_selector_sees_and_a_snapshot_only_when_asked(@TempDir Path dir)
            throws Exception {
        MavenRepo repo = new MavenRepo("local", metadataLess(dir).toUri(), new Http(), new Cas(dir.resolve("cas")));
        RepoGroup group = RepoGroup.of(repo);

        assertThat(group.availableVersions(LAUNCHER)).containsExactly("1.9.0", "1.10.0", "1.11.0");
        assertThat(group.availableVersions(LAUNCHER, Set.of(), true)).contains("7.0.0-SNAPSHOT");
        // A releases-only repository never offers its snapshot directories.
        assertThat(RepoGroup.of(repo.withPolicy(true, false)).availableVersions(LAUNCHER, Set.of(), true))
                .doesNotContain("7.0.0-SNAPSHOT");
    }

    @Test
    void a_version_installed_later_is_seen_on_the_next_ask(@TempDir Path dir) throws Exception {
        Path m2 = metadataLess(dir);
        RepoGroup group = RepoGroup.of(new MavenRepo("local", m2.toUri(), new Http(), new Cas(dir.resolve("cas"))));
        assertThat(group.availableVersions(LAUNCHER)).doesNotContain("1.12.0");

        write(m2, MavenLayout.pomPath(at("1.12.0")));

        assertThat(group.availableVersions(LAUNCHER)).contains("1.12.0");
    }

    @Test
    void a_file_mirror_without_metadata_lists_its_directories_offline_too(@TempDir Path dir) throws Exception {
        MavenRepo central = new MavenRepo(
                        "central",
                        URI.create("https://repo.example.invalid/maven2/"),
                        new Http(),
                        new Cas(dir.resolve("cas")))
                .mirroredThrough(new MavenRepo.Mirror(
                        "disk", metadataLess(dir).toUri(), RepoCredential.ANONYMOUS, "mirror disk"));
        SessionContext.installConfig(JkConfig.empty().withOffline(true));

        assertThat(RepoGroup.of(central).availableVersions(LAUNCHER)).contains("1.11.0");
    }

    @Test
    void a_catalog_when_present_is_the_answer(@TempDir Path dir) throws Exception {
        Path repo = metadataLess(dir);
        Files.writeString(repo.resolve(MavenLayout.metadataPath(LAUNCHER)), """
                <metadata><groupId>org.junit.platform</groupId><artifactId>junit-platform-launcher</artifactId>
                <versioning><versions><version>1.9.0</version></versions></versioning></metadata>
                """);

        MavenRepo local = new MavenRepo("local", repo.toUri(), new Http(), new Cas(dir.resolve("cas")));
        assertThat(local.availableVersions(LAUNCHER)).containsExactly("1.9.0");
    }
}

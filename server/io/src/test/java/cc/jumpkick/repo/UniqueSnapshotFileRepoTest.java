// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code file:} repository a snapshot was deployed to in Maven's unique form, as {@code jk
 * publish} writes one, serves the {@code -SNAPSHOT} version's POM and jar under the timestamped
 * names its version directory's {@code maven-metadata.xml} lists.
 */
class UniqueSnapshotFileRepoTest {

    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

    @BeforeEach
    @AfterEach
    void reset() {
        SessionContext.reset();
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
    }

    private static final Coordinate HOST = Coordinate.of("cc.example", "host", "1.0.1-SNAPSHOT");

    private static final String POM = "<project><groupId>cc.example</groupId><artifactId>host</artifactId>"
            + "<version>1.0.1-SNAPSHOT</version></project>";

    private static final String METADATA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata modelVersion="1.1.0">
              <groupId>cc.example</groupId>
              <artifactId>host</artifactId>
              <version>1.0.1-SNAPSHOT</version>
              <versioning>
                <snapshot>
                  <timestamp>20261025.201500</timestamp>
                  <buildNumber>3</buildNumber>
                </snapshot>
                <lastUpdated>20261025201500</lastUpdated>
                <snapshotVersions>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <value>1.0.1-20261025.201500-3</value>
                    <updated>20261025201500</updated>
                  </snapshotVersion>
                  <snapshotVersion>
                    <extension>pom</extension>
                    <value>1.0.1-20261025.201500-3</value>
                    <updated>20261025201500</updated>
                  </snapshotVersion>
                </snapshotVersions>
              </versioning>
            </metadata>
            """;

    private static Path deployed(Path dir) throws IOException {
        Path version = Files.createDirectories(dir.resolve("repo/cc/example/host/1.0.1-SNAPSHOT"));
        Files.writeString(version.resolve("host-1.0.1-20261025.201500-3.pom"), POM, StandardCharsets.UTF_8);
        Files.writeString(version.resolve("host-1.0.1-20261025.201500-3.jar"), "jar", StandardCharsets.UTF_8);
        Files.writeString(version.resolve("maven-metadata.xml"), METADATA, StandardCharsets.UTF_8);
        return dir.resolve("repo");
    }

    @Test
    void a_snapshot_deployed_with_timestamped_names_is_fetched_by_its_snapshot_version(@TempDir Path dir)
            throws Exception {
        MavenRepo repo = new MavenRepo("local", deployed(dir).toUri(), new Http(), new Cas(dir.resolve("cas")));

        assertThat(Files.readString(repo.fetchPom(HOST).cachePath())).isEqualTo(POM);
        assertThat(Files.readString(repo.fetchArtifact(HOST).cachePath())).isEqualTo("jar");
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JK-1064 — exclusive groups hide versions/fetches from non-claiming repos. */
class RepoGroupExclusiveTest {

    @Test
    void exclusive_group_ignores_higher_version_on_public_repo(@TempDir Path tmp) throws Exception {
        Path internalDir = tmp.resolve("internal-repo");
        Path publicDir = tmp.resolve("public-repo");
        writeMeta(internalDir, "com.acme", "secret", "1.0");
        writeMeta(publicDir, "com.acme", "secret", "1.0", "99.0");
        writePom(internalDir, "com.acme", "secret", "1.0");
        writePom(publicDir, "com.acme", "secret", "99.0");

        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo internal = new MavenRepo("internal", internalDir.toUri(), new Http(), cas);
        MavenRepo pub = new MavenRepo("central", publicDir.toUri(), new Http(), cas);
        // Public first — would win without exclusive binding.
        RepoGroup group = new RepoGroup(List.of(pub, internal), List.of(List.of(), List.of("com.acme", "com.acme.*")));

        assertThat(group.availableVersions(Coordinate.of("com.acme", "secret", "0")))
                .containsExactly("1.0");

        Optional<RepoGroup.RepoFetched> hit = group.tryFetchPom(Coordinate.of("com.acme", "secret", "1.0"));
        assertThat(hit).isPresent();
        assertThat(hit.get().repo().name()).isEqualTo("internal");

        assertThat(group.tryFetchPom(Coordinate.of("com.acme", "secret", "99.0")))
                .isEmpty();
    }

    @Test
    void unbound_group_still_unions_all_repos(@TempDir Path tmp) throws Exception {
        Path aDir = tmp.resolve("a");
        Path bDir = tmp.resolve("b");
        writeMeta(aDir, "junit", "junit", "4.12");
        writeMeta(bDir, "junit", "junit", "4.13.2");
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo a = new MavenRepo("internal", aDir.toUri(), new Http(), cas);
        MavenRepo b = new MavenRepo("central", bDir.toUri(), new Http(), cas);
        RepoGroup group = new RepoGroup(List.of(a, b), List.of(List.of("com.acme"), List.of()));

        assertThat(group.availableVersions(Coordinate.of("junit", "junit", "0")))
                .containsExactlyInAnyOrder("4.12", "4.13.2");
    }

    private static void writeMeta(Path repoRoot, String group, String artifact, String... versions) throws Exception {
        Coordinate c = Coordinate.of(group, artifact, "0");
        Path meta = repoRoot.resolve(MavenLayout.metadataPath(c));
        Files.createDirectories(meta.getParent());
        StringBuilder xml = new StringBuilder();
        xml.append("<metadata><groupId>")
                .append(group)
                .append("</groupId><artifactId>")
                .append(artifact)
                .append("</artifactId><versioning><versions>");
        for (String v : versions) xml.append("<version>").append(v).append("</version>");
        xml.append("</versions></versioning></metadata>");
        Files.writeString(meta, xml.toString());
    }

    private static void writePom(Path repoRoot, String group, String artifact, String version) throws Exception {
        Coordinate c = Coordinate.of(group, artifact, version);
        Path pom = repoRoot.resolve(MavenLayout.pomPath(c));
        Files.createDirectories(pom.getParent());
        Files.writeString(
                pom,
                "<project><modelVersion>4.0.0</modelVersion><groupId>"
                        + group
                        + "</groupId><artifactId>"
                        + artifact
                        + "</artifactId><version>"
                        + version
                        + "</version></project>");
    }
}

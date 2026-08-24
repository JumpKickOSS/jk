// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.GitVersion;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A git dependency's version is derived from a ref, and a ref is free-form text: {@link
 * GitVersion#fromTag} hands back a non-version-like tag unchanged and keeps a coercible tag's
 * suffix verbatim. So the {@code maven-metadata.xml} the materializer installs into its per-commit
 * {@code file://} repo has to be escaped, or the very next thing that happens — the resolver
 * enumerating that repo — cannot parse the document jk just wrote.
 */
class GitSourceMetadataTest {

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessVersionsCache();
        RepoGroup.clearProcessFetchCache();
    }

    /** Install one artifact at {@code version} into a fresh {@code file://} repo and enumerate it. */
    private static List<String> installThenEnumerate(Path tmp, String version) throws Exception {
        Path repo = tmp.resolve("repo");
        Path jar = tmp.resolve("built.jar");
        Files.writeString(jar, "not really a jar");
        GitSourceMaterializer.installArtifact(repo, "com.acme", "widgets", version, jar, "<project/>");

        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup group = new RepoGroup(List.of(new MavenRepo("local", repo.toUri(), new Http(), cas)));
        return group.availableVersions(Coordinate.of("com.acme", "widgets", "0"));
    }

    @Test
    void a_tag_with_an_xml_metacharacter_installs_metadata_the_resolver_can_read(@TempDir Path tmp) throws Exception {
        String version = GitVersion.fromTag("v1.2.3-fix&<patch");
        assertThat(version)
                .as("the tag's suffix reaches the version string verbatim — that is why escaping matters")
                .isEqualTo("1.2.3-fix&<patch");

        assertThat(installThenEnumerate(tmp, version)).containsExactly(version);
    }

    @Test
    void a_tag_that_is_not_version_like_at_all_survives_the_same_way(@TempDir Path tmp) throws Exception {
        String version = GitVersion.fromTag("release&candidate");
        assertThat(version).isEqualTo("release&candidate");

        assertThat(installThenEnumerate(tmp, version)).containsExactly(version);
    }

    @Test
    void an_ordinary_tag_still_lands_where_the_maven_layout_says(@TempDir Path tmp) throws Exception {
        assertThat(installThenEnumerate(tmp, "1.0.0")).containsExactly("1.0.0");
        assertThat(tmp.resolve("repo/com/acme/widgets/1.0.0/widgets-1.0.0.jar")).exists();
        assertThat(tmp.resolve("repo/com/acme/widgets/1.0.0/widgets-1.0.0.pom")).exists();
    }
}

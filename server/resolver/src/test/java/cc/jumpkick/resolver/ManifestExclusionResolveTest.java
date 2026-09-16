// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A manifest edge's {@code exclude} governs its subtree the way a POM edge's {@code <exclusions>}
 * does: the excluded coordinate and everything only reachable through it leave the resolution,
 * another path still brings it, a wildcard prunes a whole group, and the row whose expansion
 * dropped the edge says who excluded it.
 */
class ManifestExclusionResolveTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    /** root → parent → child → leaf → deep; the manifest edge to parent excludes leaf. */
    private void publishChain() {
        upstream.metadata("com.foo", "parent", "1.0");
        upstream.metadata("com.foo", "child", "1.0");
        upstream.metadata("com.foo", "leaf", "1.0");
        upstream.metadata("com.foo", "deep", "1.0");
        upstream.pomOnly(
                "com.foo",
                "parent",
                "1.0",
                pom(
                        "parent",
                        "<dependency><groupId>com.foo</groupId><artifactId>child</artifactId><version>1.0</version></dependency>"));
        upstream.pomOnly(
                "com.foo",
                "child",
                "1.0",
                pom(
                        "child",
                        "<dependency><groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version></dependency>"));
        upstream.pomOnly(
                "com.foo",
                "leaf",
                "1.0",
                pom(
                        "leaf",
                        "<dependency><groupId>com.foo</groupId><artifactId>deep</artifactId><version>1.0</version></dependency>"));
        upstream.pomOnly("com.foo", "deep", "1.0", MavenStub.emptyPom("com.foo", "deep", "1.0"));
    }

    @Test
    void an_excluded_transitive_and_what_only_it_reaches_leave_the_resolution(@TempDir Path tempDir) throws Exception {
        publishChain();
        Dependency parent = new Dependency("parent", "com.foo:parent", VersionSelector.parse("=1.0"), null, null, true)
                .withExclusions(List.of("com.foo:leaf"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir)).resolve(List.of(parent));

        assertThat(result.modules()).containsKeys("com.foo:parent:jar:", "com.foo:child:jar:");
        assertThat(result.modules()).doesNotContainKeys("com.foo:leaf:jar:", "com.foo:deep:jar:");
        assertThat(requireNonNull(result.modules().get("com.foo:child:jar:")).excluded())
                .containsExactly("com.foo:leaf <- jk.toml:parent");
        assertThat(requireNonNull(result.modules().get("com.foo:parent:jar:")).excluded())
                .isEmpty();
    }

    @Test
    void another_declared_path_still_brings_the_coordinate(@TempDir Path tempDir) throws Exception {
        publishChain();
        Dependency parent = new Dependency("parent", "com.foo:parent", VersionSelector.parse("=1.0"), null, null, true)
                .withExclusions(List.of("com.foo:leaf"));
        Dependency leaf = new Dependency("leaf", "com.foo:leaf", VersionSelector.parse("=1.0"), null, null, true);

        Resolution result = new PubGrubResolver(repoGroup(tempDir)).resolve(List.of(parent, leaf));

        assertThat(result.modules())
                .containsKeys("com.foo:parent:jar:", "com.foo:child:jar:", "com.foo:leaf:jar:", "com.foo:deep:jar:");
        // The pruned edge is still on record: child's own dependency on leaf is not what brings it.
        assertThat(requireNonNull(result.modules().get("com.foo:child:jar:")).excluded())
                .containsExactly("com.foo:leaf <- jk.toml:parent");
    }

    @Test
    void a_declared_edge_without_exclusions_frees_a_package_a_pom_path_excluded_through(@TempDir Path tempDir)
            throws Exception {
        // root → parent (POM excludes leaf on its child edge) and root → child directly: Maven's
        // nearest edge to child carries no exclusion, so leaf stays.
        upstream.metadata("com.foo", "parent", "1.0");
        upstream.metadata("com.foo", "child", "1.0");
        upstream.metadata("com.foo", "leaf", "1.0");
        upstream.pomOnly("com.foo", "parent", "1.0", pom("parent", """
                <dependency><groupId>com.foo</groupId><artifactId>child</artifactId><version>1.0</version>
                  <exclusions><exclusion><groupId>com.foo</groupId><artifactId>leaf</artifactId></exclusion></exclusions>
                </dependency>"""));
        upstream.pomOnly(
                "com.foo",
                "child",
                "1.0",
                pom(
                        "child",
                        "<dependency><groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version></dependency>"));
        upstream.pomOnly("com.foo", "leaf", "1.0", MavenStub.emptyPom("com.foo", "leaf", "1.0"));
        Dependency parent = new Dependency("parent", "com.foo:parent", VersionSelector.parse("=1.0"), null, null, true);
        Dependency child = new Dependency("child", "com.foo:child", VersionSelector.parse("=1.0"), null, null, true);

        Resolution result = new PubGrubResolver(repoGroup(tempDir)).resolve(List.of(parent, child));

        assertThat(result.modules()).containsKeys("com.foo:parent:jar:", "com.foo:child:jar:", "com.foo:leaf:jar:");
        assertThat(requireNonNull(result.modules().get("com.foo:child:jar:")).excluded())
                .isEmpty();
    }

    @Test
    void a_pom_exclusion_names_the_pom_that_declared_it(@TempDir Path tempDir) throws Exception {
        upstream.metadata("com.foo", "parent", "1.0");
        upstream.metadata("com.foo", "child", "1.0");
        upstream.metadata("com.foo", "leaf", "1.0");
        upstream.pomOnly("com.foo", "parent", "1.0", pom("parent", """
                <dependency><groupId>com.foo</groupId><artifactId>child</artifactId><version>1.0</version>
                  <exclusions><exclusion><groupId>com.foo</groupId><artifactId>leaf</artifactId></exclusion></exclusions>
                </dependency>"""));
        upstream.pomOnly(
                "com.foo",
                "child",
                "1.0",
                pom(
                        "child",
                        "<dependency><groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version></dependency>"));
        upstream.pomOnly("com.foo", "leaf", "1.0", MavenStub.emptyPom("com.foo", "leaf", "1.0"));
        Dependency parent = new Dependency("parent", "com.foo:parent", VersionSelector.parse("=1.0"), null, null, true);

        Resolution result = new PubGrubResolver(repoGroup(tempDir)).resolve(List.of(parent));

        assertThat(result.modules()).doesNotContainKey("com.foo:leaf:jar:");
        assertThat(requireNonNull(result.modules().get("com.foo:child:jar:")).excluded())
                .containsExactly("com.foo:leaf <- com.foo:parent@1.0");
    }

    @Test
    void a_group_wildcard_prunes_every_artifact_of_the_group(@TempDir Path tempDir) throws Exception {
        upstream.metadata("com.foo", "parent", "1.0");
        upstream.metadata("com.bar", "a", "1.0");
        upstream.metadata("com.bar", "b", "1.0");
        upstream.metadata("com.baz", "c", "1.0");
        upstream.pomOnly("com.foo", "parent", "1.0", pom("parent", """
                <dependency><groupId>com.bar</groupId><artifactId>a</artifactId><version>1.0</version></dependency>
                <dependency><groupId>com.bar</groupId><artifactId>b</artifactId><version>1.0</version></dependency>
                <dependency><groupId>com.baz</groupId><artifactId>c</artifactId><version>1.0</version></dependency>"""));
        upstream.pomOnly("com.bar", "a", "1.0", MavenStub.emptyPom("com.bar", "a", "1.0"));
        upstream.pomOnly("com.bar", "b", "1.0", MavenStub.emptyPom("com.bar", "b", "1.0"));
        upstream.pomOnly("com.baz", "c", "1.0", MavenStub.emptyPom("com.baz", "c", "1.0"));
        Dependency parent = new Dependency("parent", "com.foo:parent", VersionSelector.parse("=1.0"), null, null, true)
                .withExclusions(List.of("com.bar:*"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir)).resolve(List.of(parent));

        assertThat(result.modules()).containsKeys("com.foo:parent:jar:", "com.baz:c:jar:");
        assertThat(result.modules()).doesNotContainKeys("com.bar:a:jar:", "com.bar:b:jar:");
        assertThat(requireNonNull(result.modules().get("com.foo:parent:jar:")).excluded())
                .containsExactly("com.bar:a <- jk.toml:parent", "com.bar:b <- jk.toml:parent");
    }

    private static String pom(String artifact, String dependencies) {
        return """
                <project>
                  <groupId>com.foo</groupId><artifactId>%s</artifactId><version>1.0</version>
                  <dependencies>
                  %s
                  </dependencies>
                </project>
                """.formatted(artifact, dependencies);
    }

    private RepoGroup repoGroup(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), cas));
    }
}

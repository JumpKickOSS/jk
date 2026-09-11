// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.UnmappedPolicy;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for {@link PubGrubResolver} with a platform BOM map. Other PubGrub semantics are
 * covered under {@code pubgrub/}. With a non-empty platform map, bare POM edges are exact
 * (enforced); GAs listed in the map use the BOM pin over a different bare string on the edge.
 */
class PubGrubResolverTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @Test
    void bom_pin_overrides_lower_bare_on_transitive_edge(@TempDir Path tempDir) throws Exception {
        // root → middle@1.0 → leaf@1.0 bare; BOM pins leaf = 1.5 → enforced 1.5 (not soft lift).
        upstream.metadata("com.foo", "middle", "1.0");
        upstream.metadata("com.foo", "leaf", "1.0", "1.5", "2.0");
        upstream.pomOnly("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>middle</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.pomOnly("com.foo", "leaf", "1.0", MavenStub.emptyPom("com.foo", "leaf", "1.0"));
        upstream.pomOnly("com.foo", "leaf", "1.5", MavenStub.emptyPom("com.foo", "leaf", "1.5"));
        upstream.pomOnly("com.foo", "leaf", "2.0", MavenStub.emptyPom("com.foo", "leaf", "2.0"));

        RepoGroup repos = repoGroup(tempDir);
        Map<String, String> bom = Map.of("com.foo:leaf", "1.5");
        PubGrubResolver resolver = new PubGrubResolver(repos, bom);

        Resolution result = resolver.resolve(List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).containsKey("com.foo:leaf:jar:");
        assertThat(requireNonNull(result.modules().get("com.foo:leaf:jar:")).version())
                .isEqualTo("1.5");
    }

    @Test
    void platform_active_unmapped_bare_transitive_mediates_by_default(@TempDir Path tempDir) throws Exception {
        // platform map has an unrelated pin (project still "has a BOM"). middle →
        // leaf@1.0 bare; metadata offers 2.0. Default mediates highest-wins → leaf=2.0
        // (Maven/Gradle parity); [resolve] unmapped = "strict" restores the exact fill.
        upstream.metadata("com.foo", "middle", "1.0");
        upstream.metadata("com.foo", "leaf", "1.0", "1.5", "2.0");
        upstream.pomOnly("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>middle</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.pomOnly("com.foo", "leaf", "1.0", MavenStub.emptyPom("com.foo", "leaf", "1.0"));
        upstream.pomOnly("com.foo", "leaf", "1.5", MavenStub.emptyPom("com.foo", "leaf", "1.5"));
        upstream.pomOnly("com.foo", "leaf", "2.0", MavenStub.emptyPom("com.foo", "leaf", "2.0"));

        RepoGroup repos = repoGroup(tempDir);
        Map<String, String> bom = Map.of("com.foo:unrelated", "0.1");

        Resolution mediated = new PubGrubResolver(repos, bom)
                .resolve(List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))));
        assertThat(requireNonNull(mediated.modules().get("com.foo:leaf:jar:")).version())
                .isEqualTo("2.0");

        Resolution strict = new PubGrubResolver(
                        repos, bom, Map.of(), KmpRedirects.NONE, PlatformPolicy.ENFORCED, UnmappedPolicy.STRICT)
                .resolve(List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))));
        assertThat(requireNonNull(strict.modules().get("com.foo:leaf:jar:")).version())
                .isEqualTo("1.0");
    }

    @Test
    void bom_managed_pin_is_enforced_on_transitive_edges(@TempDir Path tempDir) throws Exception {
        // When a GA is in the platform map, the pin is enforced on POM edges (not lifted by a
        // higher bare version on middle→leaf).
        upstream.metadata("com.foo", "middle", "1.0");
        upstream.metadata("com.foo", "leaf", "1.0", "1.5", "2.0");
        upstream.pomOnly("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>middle</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.5</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.pomOnly("com.foo", "leaf", "1.0", MavenStub.emptyPom("com.foo", "leaf", "1.0"));
        upstream.pomOnly("com.foo", "leaf", "1.5", MavenStub.emptyPom("com.foo", "leaf", "1.5"));
        upstream.pomOnly("com.foo", "leaf", "2.0", MavenStub.emptyPom("com.foo", "leaf", "2.0"));

        RepoGroup repos = repoGroup(tempDir);
        Map<String, String> bom = Map.of("com.foo:leaf", "1.0");
        PubGrubResolver resolver = new PubGrubResolver(repos, bom);

        Resolution result = resolver.resolve(List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))));

        assertThat(requireNonNull(result.modules().get("com.foo:leaf:jar:")).version())
                .isEqualTo("1.0");
    }

    @Test
    void exact_root_dep_hard_pins_even_with_bom_prefer(@TempDir Path tempDir) throws Exception {
        // BOM soft-prefers 1.5; root exact-pins 1.0. LockOrchestrator removes the BOM prefer for
        // exact roots, but even if the prefer remains, Exact forces 1.0. Here we pass bom to the
        // resolver directly with an exact root on the same coord — Exact beats prefer.
        upstream.metadata("com.foo", "leaf", "1.0", "1.5", "2.0");
        upstream.pomOnly("com.foo", "leaf", "1.0", MavenStub.emptyPom("com.foo", "leaf", "1.0"));
        upstream.pomOnly("com.foo", "leaf", "1.5", MavenStub.emptyPom("com.foo", "leaf", "1.5"));
        upstream.pomOnly("com.foo", "leaf", "2.0", MavenStub.emptyPom("com.foo", "leaf", "2.0"));

        RepoGroup repos = repoGroup(tempDir);
        Map<String, String> bom = Map.of("com.foo:leaf", "1.5");
        PubGrubResolver resolver = new PubGrubResolver(repos, bom);

        Resolution result = resolver.resolve(List.of(new Dependency("com.foo:leaf", VersionSelector.parse("=1.0"))));

        assertThat(requireNonNull(result.modules().get("com.foo:leaf:jar:")).version())
                .isEqualTo("1.0");
    }

    @Test
    void coord_not_in_any_bom_uses_normal_resolution(@TempDir Path tempDir) throws Exception {
        // A direct dep with a caret selector should pick the highest
        // satisfying version. With no BOM constraint on `com.foo:other`,
        // resolution proceeds as usual.
        upstream.metadata("com.foo", "other", "1.0", "1.5", "2.0");
        upstream.pomOnly("com.foo", "other", "1.5", MavenStub.emptyPom("com.foo", "other", "1.5"));

        RepoGroup repos = repoGroup(tempDir);
        // BOM constrains a *different* coord — should not interfere.
        Map<String, String> bom = Map.of("com.foo:unrelated", "0.1");
        PubGrubResolver resolver = new PubGrubResolver(repos, bom);

        Resolution result =
                resolver.resolve(List.of(new Dependency("com.foo:other", VersionSelector.parseFloating("1.5"))));
        assertThat(requireNonNull(result.modules().get("com.foo:other:jar:")).version())
                .isEqualTo("1.5");
    }

    /**
     * {@code ea6dc765}: the hermetic version of the logback-core regression. An
     * exclusion on one edge decides what gets *selected*; it must not erase a real POM edge
     * pointing at a package that something else brought in anyway, or the closure ships without
     * classes the runtime loads by reflection.
     */
    @Test
    void an_exclusion_on_one_edge_does_not_erase_the_same_edge_elsewhere(@TempDir Path tempDir) throws Exception {
        // app → classic → core, and app → other → core (no exclusion). `classic` excludes `core`
        // on nobody's behalf here; the exclusion sits on app → classic, the shape that used to
        // cascade down and strip classic → core.
        upstream.metadata("com.foo", "app", "1.0");
        upstream.metadata("com.foo", "classic", "1.0");
        upstream.metadata("com.foo", "other", "1.0");
        upstream.metadata("com.foo", "core", "1.0");
        upstream.pomOnly("com.foo", "app", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>classic</artifactId><version>1.0</version>
                      <exclusions>
                        <exclusion><groupId>com.foo</groupId><artifactId>core</artifactId></exclusion>
                      </exclusions>
                    </dependency>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>other</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.pomOnly("com.foo", "classic", "1.0", dependsOnCore("classic"));
        upstream.pomOnly("com.foo", "other", "1.0", dependsOnCore("other"));
        upstream.pomOnly("com.foo", "core", "1.0", MavenStub.emptyPom("com.foo", "core", "1.0"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir), Map.of())
                .resolve(List.of(new Dependency("com.foo:app", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).containsKey("com.foo:core:jar:");
        assertThat(requireNonNull(result.modules().get("com.foo:classic:jar:")).deps())
                .as("classic → core is a real POM edge; the app-level exclusion decided selection, not edges")
                .anyMatch(d -> d.startsWith("com.foo:core:"));
    }

    @Test
    void an_exclusion_that_keeps_a_package_out_entirely_leaves_no_edge(@TempDir Path tempDir) throws Exception {
        // The other half of the rule: with `core` on nobody else's path, the exclusion keeps it
        // out of the resolution and the edge goes with it.
        upstream.metadata("com.foo", "app", "1.0");
        upstream.metadata("com.foo", "classic", "1.0");
        upstream.metadata("com.foo", "core", "1.0");
        upstream.pomOnly("com.foo", "app", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>classic</artifactId><version>1.0</version>
                      <exclusions>
                        <exclusion><groupId>com.foo</groupId><artifactId>core</artifactId></exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.pomOnly("com.foo", "classic", "1.0", dependsOnCore("classic"));
        upstream.pomOnly("com.foo", "core", "1.0", MavenStub.emptyPom("com.foo", "core", "1.0"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir), Map.of())
                .resolve(List.of(new Dependency("com.foo:app", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).doesNotContainKey("com.foo:core:jar:");
        assertThat(requireNonNull(result.modules().get("com.foo:classic:jar:")).deps())
                .noneMatch(d -> d.startsWith("com.foo:core:"));
    }

    private static String dependsOnCore(String artifact) {
        return """
                <project>
                  <groupId>com.foo</groupId><artifactId>%s</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>core</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(artifact);
    }

    private RepoGroup repoGroup(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), cas));
    }

    @SuppressWarnings("unused")
    private MavenPackageSource newSource(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        MavenRepo repo = new MavenRepo("local", http.base(), new Http(), cas);
        return new MavenPackageSource(repo, new EffectivePomBuilder(repo));
    }
}

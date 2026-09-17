// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.pubgrub.PubGrubSolver;
import cc.jumpkick.resolver.pubgrub.Term;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A package only transitive POMs name resolves to the highest version those POMs declare, not to
 * the newest release the repository advertises; the manifest's own floating selectors still float.
 */
class DeclaredVersionResolutionTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    private static final String ANNOTATIONS = "org.jetbrains:annotations:jar:";

    @Test
    void transitive_declared_floors_resolve_to_the_highest_declared_version(@TempDir Path tempDir) throws Exception {
        // Six releases advertised so the compact candidate window (newest four) cannot see 23.0.0
        // or 13.0 on its own; the declared versions have to be admitted as candidates.
        serveAnnotations("13.0", "23.0.0", "26.0.0", "26.0.1", "26.0.2", "26.1.0");
        upstream.metadata("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20");
        upstream.pomOnly(
                "org.jetbrains.kotlin",
                "kotlin-stdlib",
                "2.4.20",
                pomDeclaring(
                        "org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20", "org.jetbrains", "annotations", "13.0"));
        upstream.metadata("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0");
        upstream.pomOnly(
                "org.jetbrains.kotlinx",
                "kotlinx-coroutines-core-jvm",
                "1.11.0",
                pomDeclaring(
                        "org.jetbrains.kotlinx",
                        "kotlinx-coroutines-core-jvm",
                        "1.11.0",
                        "org.jetbrains",
                        "annotations",
                        "23.0.0"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(
                        new Dependency("org.jetbrains.kotlin:kotlin-stdlib", VersionSelector.parse("=2.4.20")),
                        new Dependency(
                                "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
                                VersionSelector.parse("=1.11.0"))));

        assertThat(requireNonNull(result.modules().get(ANNOTATIONS)).version()).isEqualTo("23.0.0");
    }

    @Test
    void a_higher_declaration_found_later_moves_an_earlier_pick_up_to_it(@TempDir Path tempDir) throws Exception {
        // stdlib is decided first and annotations right after it at 13.0; the 23.0.0 declaration
        // sits two hops deeper. The conflict backtracks the 13.0 pick and re-decides at 23.0.0.
        serveAnnotations("13.0", "23.0.0", "26.0.0", "26.0.1", "26.0.2", "26.1.0");
        upstream.metadata("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20");
        upstream.pomOnly(
                "org.jetbrains.kotlin",
                "kotlin-stdlib",
                "2.4.20",
                pomDeclaring(
                        "org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20", "org.jetbrains", "annotations", "13.0"));
        upstream.metadata("com.foo", "app", "1.0");
        upstream.pomOnly(
                "com.foo",
                "app",
                "1.0",
                pomDeclaring(
                        "com.foo", "app", "1.0", "org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0"));
        upstream.metadata("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0");
        upstream.pomOnly(
                "org.jetbrains.kotlinx",
                "kotlinx-coroutines-core-jvm",
                "1.11.0",
                pomDeclaring(
                        "org.jetbrains.kotlinx",
                        "kotlinx-coroutines-core-jvm",
                        "1.11.0",
                        "org.jetbrains",
                        "annotations",
                        "23.0.0"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(
                        new Dependency("org.jetbrains.kotlin:kotlin-stdlib", VersionSelector.parse("=2.4.20")),
                        new Dependency("com.foo:app", VersionSelector.parse("=1.0"))));

        assertThat(requireNonNull(result.modules().get(ANNOTATIONS)).version()).isEqualTo("23.0.0");
    }

    @Test
    void a_floating_manifest_selector_still_floats_to_the_newest_release(@TempDir Path tempDir) throws Exception {
        // The manifest's bare `23.0.0` is a caret: newest 23.x. coroutines declaring 23.0.5 does not
        // hold it there.
        serveAnnotations("13.0", "23.0.0", "23.0.5", "23.1.0");
        upstream.metadata("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0");
        upstream.pomOnly(
                "org.jetbrains.kotlinx",
                "kotlinx-coroutines-core-jvm",
                "1.11.0",
                pomDeclaring(
                        "org.jetbrains.kotlinx",
                        "kotlinx-coroutines-core-jvm",
                        "1.11.0",
                        "org.jetbrains",
                        "annotations",
                        "23.0.5"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(
                        new Dependency("org.jetbrains:annotations", VersionSelector.parse("^23.0.0")),
                        new Dependency(
                                "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
                                VersionSelector.parse("=1.11.0"))));

        assertThat(requireNonNull(result.modules().get(ANNOTATIONS)).version()).isEqualTo("23.1.0");
    }

    @Test
    void a_declared_release_is_not_lifted_to_a_higher_ranked_unknown_qualifier(@TempDir Path tempDir) throws Exception {
        // 2.0.1.MR sorts above 2.0.1 under Maven ordering, and nothing declares it: the declared
        // 2.0.1 is the pick. A manifest floor on 2.0.1 does ask for the newest, and gets the .MR.
        upstream.metadata("jakarta.inject", "jakarta.inject-api", "2.0.1", "2.0.1.MR");
        for (String v : List.of("2.0.1", "2.0.1.MR")) {
            upstream.pomOnly(
                    "jakarta.inject",
                    "jakarta.inject-api",
                    v,
                    MavenStub.emptyPom("jakarta.inject", "jakarta.inject-api", v));
        }
        upstream.metadata("io.micronaut", "micronaut-inject", "5.1.15");
        upstream.pomOnly(
                "io.micronaut",
                "micronaut-inject",
                "5.1.15",
                pomDeclaring(
                        "io.micronaut", "micronaut-inject", "5.1.15", "jakarta.inject", "jakarta.inject-api", "2.0.1"));

        String injectApi = "jakarta.inject:jakarta.inject-api:jar:";
        Resolution transitive = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(new Dependency("io.micronaut:micronaut-inject", VersionSelector.parse("=5.1.15"))));
        assertThat(requireNonNull(transitive.modules().get(injectApi)).version())
                .isEqualTo("2.0.1");

        Resolution floating = new PubGrubResolver(repoGroup(tempDir.resolve("floating")))
                .resolve(List.of(new Dependency("jakarta.inject:jakarta.inject-api", VersionSelector.parse("^2.0.1"))));
        assertThat(requireNonNull(floating.modules().get(injectApi)).version()).isEqualTo("2.0.1.MR");
    }

    /**
     * A catalog that omits a published release: drone names spacelift 1.0.2, whose POM is served,
     * while maven-metadata lists only the Alphas. The declared version is a candidate in a solve
     * that reads full catalogs up front (the retry a compact-list unsat earns) as it is in the
     * compact one, and a lock preference naming that very version does not hide it.
     */
    @Test
    void a_wide_solve_admits_a_declared_version_the_catalog_omits_under_a_lock_preference(@TempDir Path tempDir)
            throws Exception {
        String spacelift = "org.arquillian.spacelift:arquillian-spacelift:jar:";
        upstream.metadata("org.arquillian.spacelift", "arquillian-spacelift", "1.0.0.Alpha8", "1.0.0.Alpha9");
        for (String v : List.of("1.0.0.Alpha8", "1.0.0.Alpha9", "1.0.2")) {
            upstream.pomOnly(
                    "org.arquillian.spacelift",
                    "arquillian-spacelift",
                    v,
                    MavenStub.emptyPom("org.arquillian.spacelift", "arquillian-spacelift", v));
        }
        upstream.metadata("org.jboss.arquillian.extension", "arquillian-drone-webdriver", "3.0.1.Final");
        upstream.pomOnly(
                "org.jboss.arquillian.extension",
                "arquillian-drone-webdriver",
                "3.0.1.Final",
                pomDeclaring(
                        "org.jboss.arquillian.extension",
                        "arquillian-drone-webdriver",
                        "3.0.1.Final",
                        "org.arquillian.spacelift",
                        "arquillian-spacelift",
                        "1.0.2"));
        RepoGroup repos = repoGroup(tempDir);
        MavenPackageSource source = new MavenPackageSource(
                repos,
                new EffectivePomBuilder(repos),
                Map.of(),
                Map.of("org.arquillian.spacelift:arquillian-spacelift", "1.0.2"));
        Term drone = Term.positive(
                "org.jboss.arquillian.extension:arquillian-drone-webdriver:jar:", VersionSet.exact("3.0.1.Final"));

        Map<String, String> compact = new PubGrubSolver(source).solve("<root>", "0.0.0", List.of(drone));
        Map<String, String> wide =
                new PubGrubSolver(source).withWideUniverses().solve("<root>", "0.0.0", List.of(drone));

        assertThat(compact).containsEntry(spacelift, "1.0.2");
        assertThat(wide).containsEntry(spacelift, "1.0.2");
    }

    private void serveAnnotations(String... versions) {
        upstream.metadata("org.jetbrains", "annotations", versions);
        for (String v : versions) {
            upstream.pomOnly("org.jetbrains", "annotations", v, MavenStub.emptyPom("org.jetbrains", "annotations", v));
        }
    }

    private static String pomDeclaring(
            String group, String artifact, String version, String depGroup, String depArtifact, String depVersion) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(group, artifact, version, depGroup, depArtifact, depVersion);
    }

    private RepoGroup repoGroup(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), cas));
    }
}

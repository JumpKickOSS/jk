// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Gradle metadata constraint's {@code rejects} fences off releases even inside its {@code
 * requires}: {@code guard} constrains {@code lib} to {@code requires 1.2, rejects 1.3}, so a graph
 * whose highest-wins pick would be 1.3 moves past it to 1.4, and one that needs exactly 1.3 fails
 * naming the module that rejected it.
 */
class GmmRejectsResolveTest {

    private static final String GROUP = "com.acme";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void publish() {
        KmpRedirects.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        RepoGroup.clearProcessFetchCache();
        upstream.metadata(GROUP, "lib", "1.2", "1.3", "1.4");
        for (String v : List.of("1.2", "1.3", "1.4")) upstream.pom(GROUP, "lib", v, plainPom("lib", v, null, null));
        upstream.metadata(GROUP, "guard", "1.0");
        upstream.pom(GROUP, "guard", "1.0", """
                <?xml version="1.0"?>
                <!-- %s -->
                <project>
                  <groupId>%s</groupId><artifactId>guard</artifactId><version>1.0</version>
                </project>
                """.formatted(GradleModuleMetadata.POM_MARKER, GROUP));
        upstream.text(MavenStub.path(GROUP, "guard", "1.0", ".module"), """
                {
                  "formatVersion": "1.1",
                  "component": { "group": "%1$s", "module": "guard", "version": "1.0" },
                  "variants": [
                    {
                      "name": "runtimeElements",
                      "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" },
                      "dependencyConstraints": [
                        { "group": "%1$s", "module": "lib", "version": { "requires": "1.2", "rejects": ["1.3"] } }
                      ]
                    }
                  ]
                }
                """.formatted(GROUP));
        // bumper raises lib's floor to 1.3, so highest-wins alone picks 1.3.
        upstream.metadata(GROUP, "bumper", "1.0");
        upstream.pom(GROUP, "bumper", "1.0", plainPom("bumper", "1.0", "lib", "1.3"));
        // strict needs exactly the rejected release.
        upstream.metadata(GROUP, "strict", "1.0");
        upstream.pom(GROUP, "strict", "1.0", plainPom("strict", "1.0", "lib", "[1.3]"));
    }

    @Test
    void a_rejected_release_is_never_picked(@TempDir Path tmp) throws Exception {
        Resolution result =
                resolver(tmp, new KmpRedirects(repoGroup(tmp), "standard-jvm")).resolve(roots("guard", "bumper"));

        assertThat(version(result, "lib")).isEqualTo("1.4");
    }

    @Test
    void without_the_metadata_highest_wins_picks_the_rejected_release(@TempDir Path tmp) throws Exception {
        Resolution result = resolver(tmp, KmpRedirects.NONE).resolve(roots("guard", "bumper"));

        assertThat(version(result, "lib")).isEqualTo("1.3");
    }

    @Test
    void a_graph_that_needs_only_the_rejected_release_fails_naming_the_rejecting_module(@TempDir Path tmp) {
        PubGrubResolver resolver = resolver(tmp, new KmpRedirects(repoGroup(tmp), "standard-jvm"));

        assertThatThrownBy(() -> resolver.resolve(roots("guard", "strict")))
                .hasMessageContaining(GROUP + ":guard")
                .hasMessageContaining("lib");
    }

    @Test
    void rejects_subtract_versions_and_ranges() {
        VersionSet floor = VersionSet.atLeast("1.0", true);

        VersionSet left = MavenPackageSource.withoutRejected(floor, List.of("1.3", "[2.0,3.0)"));

        assertThat(left.contains("1.2")).isTrue();
        assertThat(left.contains("1.3")).isFalse();
        assertThat(left.contains("1.4")).isTrue();
        assertThat(left.contains("2.5")).isFalse();
        assertThat(left.contains("3.0")).isTrue();
    }

    private PubGrubResolver resolver(Path tmp, KmpRedirects kmp) {
        return new PubGrubResolver(repoGroup(tmp), Map.of(), Map.of(), kmp);
    }

    private static List<Dependency> roots(String... artifacts) {
        return Arrays.stream(artifacts)
                .map(a -> new Dependency(GROUP + ":" + a, VersionSelector.parse("=1.0")))
                .toList();
    }

    private static String version(Resolution result, String artifact) {
        return requireNonNull(result.modules().get(GROUP + ":" + artifact + ":jar:"), artifact)
                .version();
    }

    private RepoGroup repoGroup(Path tmp) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tmp.resolve("cache"))));
    }

    private static String plainPom(
            String artifact, String version, @Nullable String dependsOn, @Nullable String depVersion) {
        String dependency = dependsOn == null ? "" : """
                  <dependencies>
                    <dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></dependency>
                  </dependencies>
                """.formatted(GROUP, dependsOn, depVersion);
        return """
                <project>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                %s</project>
                """.formatted(GROUP, artifact, version, dependency);
    }
}

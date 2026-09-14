// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gradle module metadata {@code dependencyConstraints} align a same-group family the way androidx
 * publishes core and core-ktx: each version's {@code .module} constrains the sibling to the same
 * version. Consumers that name different versions of the two end up on one line, and a constraint
 * on a module nothing depends on brings nothing in.
 */
class GmmConstraintResolveTest {

    private static final String GROUP = "com.acme";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void publish() {
        KmpRedirects.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        RepoGroup.clearProcessFetchCache();
        // The test scope's defaults, which the orchestrator adds on its own.
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");

        upstream.metadata(GROUP, "core", "1.0", "1.5");
        upstream.metadata(GROUP, "core-ktx", "1.0", "1.5");
        upstream.metadata(GROUP, "fragment", "1.0");
        upstream.metadata(GROUP, "activity", "1.0");
        for (String v : List.of("1.0", "1.5")) {
            upstream.pom(GROUP, "core", v, gradlePom("core", v, null));
            upstream.text(MavenStub.path(GROUP, "core", v, ".module"), module("core", v, "core-ktx", true));
            upstream.pom(GROUP, "core-ktx", v, gradlePom("core-ktx", v, "core"));
            upstream.text(MavenStub.path(GROUP, "core-ktx", v, ".module"), module("core-ktx", v, "core", false));
        }
        // fragment asks for core-ktx 1.0; activity asks for core 1.5.
        upstream.pom(GROUP, "fragment", "1.0", plainPom("fragment", "core-ktx", "1.0"));
        upstream.pom(GROUP, "activity", "1.0", plainPom("activity", "core", "1.5"));
    }

    @Test
    void same_group_modules_align_on_the_version_their_metadata_constrains(@TempDir Path tmp) throws Exception {
        RepoGroup repos = repoGroup(tmp);
        // An Android build: the plain-Android variants carry no environment attribute and stand in.
        PubGrubResolver resolver = new PubGrubResolver(repos, Map.of(), Map.of(), new KmpRedirects(repos, "android"));

        Resolution result = resolver.resolve(roots());

        assertThat(version(result, "core")).isEqualTo("1.5");
        assertThat(version(result, "core-ktx")).isEqualTo("1.5");
        assertThat(result.modules()).doesNotContainKey(GROUP + ":ghost:jar:");
    }

    @Test
    void without_the_metadata_the_pair_resolves_misaligned(@TempDir Path tmp) throws Exception {
        PubGrubResolver resolver = new PubGrubResolver(repoGroup(tmp), Map.of(), Map.of(), KmpRedirects.NONE);

        Resolution result = resolver.resolve(roots());

        assertThat(version(result, "core")).isEqualTo("1.5");
        assertThat(version(result, "core-ktx")).isEqualTo("1.0");
    }

    @Test
    void a_lock_over_the_family_carries_both_modules_at_the_aligned_version(@TempDir Path tmp) throws Exception {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, roots());
        JkBuild project = new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));

        Lockfile lock = new LockOrchestrator(repoGroup(tmp)).lock(project, "test");

        assertThat(lock.artifacts())
                .filteredOn(a -> a.packageKey().startsWith(GROUP + ":core"))
                .extracting(Lockfile.Artifact::packageKey, Lockfile.Artifact::version)
                .containsExactlyInAnyOrder(tuple(GROUP + ":core:jar:", "1.5"), tuple(GROUP + ":core-ktx:jar:", "1.5"));
        assertThat(lock.artifacts()).noneMatch(a -> a.packageKey().contains(":ghost:"));
    }

    private static List<Dependency> roots() {
        return List.of(
                new Dependency(GROUP + ":fragment", VersionSelector.parse("=1.0")),
                new Dependency(GROUP + ":activity", VersionSelector.parse("=1.0")));
    }

    private static String version(Resolution result, String artifact) {
        return requireNonNull(result.modules().get(GROUP + ":" + artifact + ":jar:"), artifact)
                .version();
    }

    private RepoGroup repoGroup(Path tmp) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tmp.resolve("cache"))));
    }

    /** A POM Gradle published: the metadata marker, and one same-version dependency when asked. */
    private static String gradlePom(String artifact, String version, @Nullable String dependsOn) {
        String dependency = dependsOn == null ? "" : """
                  <dependencies>
                    <dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></dependency>
                  </dependencies>
                """.formatted(GROUP, dependsOn, version);
        return """
                <?xml version="1.0"?>
                <!-- %s -->
                <project>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                %s</project>
                """.formatted(GradleModuleMetadata.POM_MARKER, GROUP, artifact, version, dependency);
    }

    private static String plainPom(String artifact, String dependsOn, String version) {
        return """
                <project>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></dependency>
                  </dependencies>
                </project>
                """.formatted(GROUP, artifact, GROUP, dependsOn, version);
    }

    /**
     * androidx's plain-Android shape: api and runtime library variants without an environment
     * attribute, each constraining {@code sibling} to this module's own version. {@code withGhost}
     * adds a constraint on a module nothing publishes, which must never be pulled into a graph.
     */
    private static String module(String artifact, String version, String sibling, boolean withGhost) {
        String ghost = withGhost
                ? ", { \"group\": \"%s\", \"module\": \"ghost\", \"version\": { \"requires\": \"3.0\" } }"
                        .formatted(GROUP)
                : "";
        return """
                {
                  "formatVersion": "1.1",
                  "component": { "group": "%1$s", "module": "%2$s", "version": "%3$s" },
                  "variants": [
                    {
                      "name": "releaseVariantReleaseApiPublication",
                      "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-api" },
                      "dependencyConstraints": [
                        { "group": "%1$s", "module": "%4$s", "version": { "requires": "%3$s" } }%5$s
                      ]
                    },
                    {
                      "name": "releaseVariantReleaseRuntimePublication",
                      "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" },
                      "dependencyConstraints": [
                        { "group": "%1$s", "module": "%4$s", "version": { "requires": "%3$s" } }
                      ]
                    }
                  ]
                }
                """.formatted(GROUP, artifact, version, sibling, ghost);
    }
}

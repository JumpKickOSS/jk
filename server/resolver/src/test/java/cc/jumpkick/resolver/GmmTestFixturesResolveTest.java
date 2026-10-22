// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Gradle module whose runtime needs another module's test fixtures says so only in its {@code
 * .module} file ({@code requestedCapabilities} {@code <module>-test-fixtures}); its POM names the
 * plain module. The fixtures jar, the {@code test-fixtures} classifier, joins the graph.
 */
class GmmTestFixturesResolveTest {

    private static final String GROUP = "com.acme";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void publish() {
        KmpRedirects.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        RepoGroup.clearProcessFetchCache();
        upstream.leaf(GROUP, "web", "2.0");
        upstream.metadata(GROUP, "testing", "2.0");
        upstream.pom(GROUP, "testing", "2.0", """
                <?xml version="1.0"?>
                <!-- %s -->
                <project>
                  <groupId>%s</groupId><artifactId>testing</artifactId><version>2.0</version>
                  <dependencies>
                    <dependency><groupId>%s</groupId><artifactId>web</artifactId><version>2.0</version><scope>runtime</scope></dependency>
                  </dependencies>
                </project>
                """.formatted(GradleModuleMetadata.POM_MARKER, GROUP, GROUP));
        upstream.text(MavenStub.path(GROUP, "testing", "2.0", ".module"), """
                {
                  "formatVersion": "1.1",
                  "component": { "group": "%1$s", "module": "testing", "version": "2.0" },
                  "variants": [
                    {
                      "name": "runtimeElements",
                      "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" },
                      "dependencies": [
                        { "group": "%1$s", "module": "web", "version": { "requires": "2.0" } },
                        { "group": "%1$s", "module": "web", "version": { "requires": "2.0" },
                          "requestedCapabilities": [ { "group": "%1$s", "name": "web-test-fixtures" } ] }
                      ]
                    }
                  ]
                }
                """.formatted(GROUP));
    }

    @Test
    void the_fixtures_jar_a_runtime_variant_requests_joins_the_graph(@TempDir Path tmp) throws Exception {
        PubGrubResolver resolver =
                new PubGrubResolver(repos(tmp), Map.of(), Map.of(), new KmpRedirects(repos(tmp), "standard-jvm"));

        Resolution result = resolver.resolve(roots());

        assertThat(result.modules()).containsKeys(GROUP + ":web:jar:", GROUP + ":web:jar:test-fixtures");
        assertThat(requireNonNull(result.modules().get(GROUP + ":web:jar:test-fixtures"))
                        .version())
                .isEqualTo("2.0");
        // The edge is recorded, so a classpath walked from the lock reaches the fixtures jar.
        assertThat(requireNonNull(result.modules().get(GROUP + ":testing:jar:")).deps())
                .contains(GROUP + ":web:jar:test-fixtures@2.0");
    }

    @Test
    void without_the_metadata_only_the_plain_module_resolves(@TempDir Path tmp) throws Exception {
        PubGrubResolver resolver = new PubGrubResolver(repos(tmp), Map.of(), Map.of(), KmpRedirects.NONE);

        Resolution result = resolver.resolve(roots());

        assertThat(result.modules())
                .containsKey(GROUP + ":web:jar:")
                .doesNotContainKey(GROUP + ":web:jar:test-fixtures");
    }

    private static List<Dependency> roots() {
        return List.of(new Dependency(GROUP + ":testing", VersionSelector.parse("=2.0")));
    }

    private RepoGroup repos(Path tmp) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tmp.resolve("cache"))));
    }
}

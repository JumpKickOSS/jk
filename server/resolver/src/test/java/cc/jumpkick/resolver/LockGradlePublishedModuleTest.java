// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module Gradle published carries the module-metadata marker in a POM whose {@code packaging}
 * says nothing about the files it ships: SpotBugs publishes {@code packaging=pom} beside its jar.
 * The lock asks for such a module's jar and pins it when a repository serves it; a Gradle-published
 * BOM, marker and all, still locks as a row without a file.
 */
class LockGradlePublishedModuleTest {

    private static final String MARKER = "<!-- " + GradleModuleMetadata.POM_MARKER + " -->";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    private MavenStub upstream;

    @BeforeEach
    void publishJunitDefaults() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        upstream = new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    @Test
    void a_gradle_published_module_with_pom_packaging_locks_its_jar(@TempDir Path dir) throws Exception {
        upstream.metadata("com.foo", "tool", "1.0");
        upstream.pom("com.foo", "tool", "1.0", MARKER + """
                <project>
                  <groupId>com.foo</groupId><artifactId>tool</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                </project>
                """);
        upstream.jar("com.foo", "tool", "1.0");
        upstream.metadata("com.foo", "bom", "1.0");
        upstream.pomOnly("com.foo", "bom", "1.0", MARKER + """
                <project>
                  <groupId>com.foo</groupId><artifactId>bom</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                </project>
                """);
        RepoGroup repos =
                RepoGroup.of(new MavenRepo("maven-stub", http.base(), new Http(), new Cas(dir.resolve("cache"))));

        Lockfile lock = new LockOrchestrator(repos).lock(project(), "test");

        Lockfile.Artifact tool = row(lock, "com.foo:tool");
        assertThat(tool.checksum())
                .as("the jar beside the pom-packaging POM is pinned")
                .startsWith("sha256:");
        assertThat(tool.source()).startsWith("maven-stub+");
        assertThat(row(lock, "com.foo:bom").checksum())
                .as("a Gradle-published BOM has no file")
                .isNull();
    }

    private static Lockfile.Artifact row(Lockfile lock, String ga) {
        return lock.artifacts().stream()
                .filter(a -> a.name().startsWith(ga + ":"))
                .findFirst()
                .orElseThrow();
    }

    private static JkBuild project() {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.putAll(Map.of(
                Scope.MAIN,
                List.of(
                        new Dependency("com.foo:tool", VersionSelector.parse("=1.0")),
                        new Dependency("com.foo:bom", VersionSelector.parse("=1.0")))));
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
    }
}

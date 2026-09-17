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
 * A BOM's {@code dependencyManagement} exclusions govern the roots it manages the way they do
 * under Maven: a declared dependency the BOM manages and that declares no {@code exclude} of its
 * own prunes what the BOM excluded, whether its version comes from the BOM or is written; a root
 * with its own list keeps that list alone.
 */
class PlatformManagedExclusionsLockTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    /** parent → child → leaf → deep; the BOM manages parent 1.0 and excludes leaf under it. */
    @BeforeEach
    void publish() {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        chain("parent", "child");
        chain("child", "leaf");
        chain("leaf", "deep");
        upstream.metadata("com.foo", "deep", "1.0");
        upstream.pom("com.foo", "deep", "1.0", MavenStub.emptyPom("com.foo", "deep", "1.0"));
        upstream.jar("com.foo", "deep", "1.0");
        upstream.pom("org.example", "the-bom", "1.0", """
                <project>
                  <groupId>org.example</groupId><artifactId>the-bom</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>parent</artifactId><version>1.0</version>
                      <exclusions><exclusion><groupId>com.foo</groupId><artifactId>leaf</artifactId></exclusion></exclusions>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
    }

    private void chain(String artifact, String dependsOn) {
        upstream.metadata("com.foo", artifact, "1.0");
        upstream.pom("com.foo", artifact, "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>%s</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.foo</groupId><artifactId>%s</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """.formatted(artifact, dependsOn));
        upstream.jar("com.foo", artifact, "1.0");
    }

    @Test
    void a_versionless_root_the_bom_manages_prunes_what_the_bom_excludes(@TempDir Path tempDir) throws Exception {
        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .lock(project(Dependency.platformManaged("parent", "com.foo:parent")), "test");

        assertThat(keys(lock)).contains("com.foo:parent:jar:", "com.foo:child:jar:");
        assertThat(keys(lock)).doesNotContain("com.foo:leaf:jar:", "com.foo:deep:jar:");
    }

    @Test
    void a_root_with_a_written_version_the_bom_manages_prunes_what_the_bom_excludes(@TempDir Path tempDir)
            throws Exception {
        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .lock(project(Dependency.of("parent", "com.foo:parent", VersionSelector.parse("=1.0"))), "test");

        assertThat(keys(lock)).contains("com.foo:parent:jar:", "com.foo:child:jar:");
        assertThat(keys(lock)).doesNotContain("com.foo:leaf:jar:", "com.foo:deep:jar:");
    }

    @Test
    void a_roots_own_exclude_list_replaces_the_boms(@TempDir Path tempDir) throws Exception {
        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .lock(
                        project(Dependency.platformManaged("parent", "com.foo:parent")
                                .withExclusions(List.of("com.foo:deep"))),
                        "test");

        assertThat(keys(lock)).contains("com.foo:parent:jar:", "com.foo:child:jar:", "com.foo:leaf:jar:");
        assertThat(keys(lock)).doesNotContain("com.foo:deep:jar:");
    }

    private static List<String> keys(Lockfile lock) {
        return lock.artifacts().stream().map(Lockfile.Artifact::packageKey).toList();
    }

    private static JkBuild project(Dependency parent) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(parent));
        byScope.put(
                Scope.PLATFORM,
                List.of(Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"))));
        return new JkBuild(
                new Project("com.example", "test", "0.1.0", 25), new JkBuild.Dependencies(Map.copyOf(byScope)));
    }

    private RepoGroup repoGroup(Path dir) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(dir.resolve("cache"))));
    }
}

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
 * Spark's shape: its parent POM declares a mirror of Central as a repository, so its subtree is
 * served by Central and by a repository that holds the very same files. A lock downloads each of
 * those files once.
 */
class LockDeclaredMirrorTest {

    @RegisterExtension
    final LoopbackHttp central = new LoopbackHttp().concurrent();

    @RegisterExtension
    final LoopbackHttp mirror = new LoopbackHttp().concurrent();

    private static final String LEAF_JAR = MavenStub.path("com.foo", "leaf", "1.0", ".jar");
    private static final String ONLY_THERE_JAR = MavenStub.path("com.foo", "only-there", "2.0", ".jar");

    @BeforeEach
    void publish() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        new MavenStub(central)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        String pom = """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>engine</artifactId>
                  <version>3.5.4</version>
                  <repositories>
                    <repository>
                      <id>gcs-maven-central-mirror</id>
                      <url>%s</url>
                    </repository>
                  </repositories>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId>
                      <artifactId>leaf</artifactId>
                      <version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.foo</groupId>
                      <artifactId>only-there</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(mirror.baseUrl());
        for (LoopbackHttp host : List.of(central, mirror)) {
            new MavenStub(host)
                    .metadata("org.example", "engine", "3.5.4")
                    .pom("org.example", "engine", "3.5.4", pom)
                    .jar("org.example", "engine", "3.5.4")
                    .leaf("com.foo", "leaf", "1.0");
        }
        new MavenStub(mirror).leaf("com.foo", "only-there", "2.0");
    }

    @Test
    void a_file_both_repositories_serve_is_downloaded_once(@TempDir Path dir) throws Exception {
        Lockfile lock = new LockOrchestrator(RepoGroup.of(
                        new MavenRepo("central", central.base(), new Http(), new Cas(dir.resolve("cache")))))
                .lock(project("org.example:engine", "=3.5.4"), "test", List.of(), true, ResolveObserver.NOOP);

        assertThat(lock.artifacts()).anyMatch(a -> a.name().startsWith("com.foo:leaf:"));
        assertThat(central.requestsFor(LEAF_JAR) + mirror.requestsFor(LEAF_JAR))
                // Every request each server saw, in order: the one time this failed (JK-R8911) it
                // said only "2 from central", which no rerun has reproduced since.
                .as(
                        "leaf's jar: %d from central, %d from the declared mirror%ncentral saw %s%nmirror saw %s",
                        central.requestsFor(LEAF_JAR),
                        mirror.requestsFor(LEAF_JAR),
                        central.requested(),
                        mirror.requested())
                .isEqualTo(1);
    }

    /**
     * The declared repository served the POM, so it holds the artifact: the jar is asked of it
     * first, and Central, which already missed the POM, is not asked for the jar at all.
     */
    @Test
    void a_file_only_the_declared_repository_has_is_asked_of_it_first(@TempDir Path dir) throws Exception {
        new LockOrchestrator(RepoGroup.of(
                        new MavenRepo("central", central.base(), new Http(), new Cas(dir.resolve("cache")))))
                .lock(project("org.example:engine", "=3.5.4"), "test", List.of(), true, ResolveObserver.NOOP);

        assertThat(mirror.requestsFor(ONLY_THERE_JAR)).isEqualTo(1);
        assertThat(central.requestsFor(ONLY_THERE_JAR)).isZero();
    }

    private static JkBuild project(String ga, String selector) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.putAll(Map.of(Scope.MAIN, List.of(new Dependency(ga, VersionSelector.parse(selector)))));
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
    }
}

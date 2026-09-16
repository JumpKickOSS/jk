// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A repository a dependency's POM declares serves that POM's subtree and nothing else: the
 * transitive it names locks with the repository as its {@code source} and a note naming the POM,
 * while the project's own declaration of the same artifact still has no repository.
 */
class LockDeclaredRepositoryTest {

    @RegisterExtension
    final LoopbackHttp central = new LoopbackHttp().concurrent();

    @RegisterExtension
    final LoopbackHttp jitpack = new LoopbackHttp().concurrent();

    private final List<String> notes = new ArrayList<>();

    @BeforeEach
    void publish() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        new MavenStub(central)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0")
                .metadata("io.apicurio", "schema-util-json", "2.6.13")
                .pom("io.apicurio", "schema-util-json", "2.6.13", """
                        <project>
                          <groupId>io.apicurio</groupId>
                          <artifactId>schema-util-json</artifactId>
                          <version>2.6.13</version>
                          <repositories>
                            <repository>
                              <id>jitpack.io</id>
                              <url>%s</url>
                            </repository>
                          </repositories>
                          <dependencies>
                            <dependency>
                              <groupId>com.github.everit-org.json-schema</groupId>
                              <artifactId>org.everit.json.schema</artifactId>
                              <version>1.14.4</version>
                            </dependency>
                          </dependencies>
                        </project>
                        """.formatted(jitpack.baseUrl()));
        new MavenStub(jitpack).leaf("com.github.everit-org.json-schema", "org.everit.json.schema", "1.14.4");
    }

    @Test
    void a_transitive_only_the_poms_repository_has_locks_with_that_source_and_a_note(@TempDir Path dir)
            throws Exception {
        Lockfile lock = new LockOrchestrator(repos(dir))
                .lock(project("io.apicurio:schema-util-json", "=2.6.13"), "test", List.of(), true, observer());

        Lockfile.Artifact everit = lock.artifacts().stream()
                .filter(a -> a.name().startsWith("com.github.everit-org.json-schema:"))
                .findFirst()
                .orElseThrow();
        assertThat(everit.version()).isEqualTo("1.14.4");
        assertThat(everit.source()).isEqualTo("jitpack.io+" + jitpack.baseUrl());
        assertThat(everit.checksum()).startsWith("sha256:");
        assertThat(notes).singleElement().satisfies(note -> assertThat(note)
                .startsWith("repository `jitpack.io` at " + jitpack.baseUrl())
                .contains("declared by the POM of io.apicurio:schema-util-json:2.6.13"));
    }

    @Test
    void the_projects_own_declaration_does_not_see_a_repository_another_pom_declared(@TempDir Path dir) {
        assertThatThrownBy(() -> new LockOrchestrator(repos(dir))
                        .lock(
                                project("com.github.everit-org.json-schema:org.everit.json.schema", "=1.14.4"),
                                "test",
                                List.of(),
                                true,
                                observer()))
                .hasMessageContaining("org.everit.json.schema");
        assertThat(notes).isEmpty();
    }

    private RepoGroup repos(Path dir) {
        return RepoGroup.of(new MavenRepo("central", central.base(), new Http(), new Cas(dir.resolve("cache"))));
    }

    private ResolveObserver observer() {
        return new ResolveObserver() {
            @Override
            public void onTotal(int total) {}

            @Override
            public void onPackage(String module, String version) {}

            @Override
            public void onNote(String line) {
                notes.add(line);
            }
        };
    }

    private static JkBuild project(String ga, String selector) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.putAll(Map.of(Scope.MAIN, List.of(new Dependency(ga, VersionSelector.parse(selector)))));
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
    }
}

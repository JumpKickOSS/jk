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

    /** A POM that declares a repository no row of the lock came from earns no note. */
    @Test
    void a_declared_repository_that_served_no_row_is_not_noted(@TempDir Path dir) throws Exception {
        new MavenStub(central)
                .metadata("io.apicurio", "declares-only", "1.0")
                .pom("io.apicurio", "declares-only", "1.0", """
                        <project>
                          <groupId>io.apicurio</groupId>
                          <artifactId>declares-only</artifactId>
                          <version>1.0</version>
                          <repositories>
                            <repository>
                              <id>jitpack.io</id>
                              <url>%s</url>
                            </repository>
                          </repositories>
                          <dependencies>
                            <dependency>
                              <groupId>com.foo</groupId>
                              <artifactId>leaf</artifactId>
                              <version>1.0</version>
                            </dependency>
                          </dependencies>
                        </project>
                        """.formatted(jitpack.baseUrl()))
                .jar("io.apicurio", "declares-only", "1.0")
                .leaf("com.foo", "leaf", "1.0");

        Lockfile lock = new LockOrchestrator(repos(dir))
                .lock(project("io.apicurio:declares-only", "=1.0"), "test", List.of(), true, observer());

        assertThat(lock.artifacts()).anyMatch(a -> a.name().startsWith("com.foo:leaf:"));
        assertThat(notes).isEmpty();
    }

    /** The sources jar of a row a declared repository served is asked of that repository. */
    @Test
    void sources_attach_looks_in_the_repository_that_served_the_row(@TempDir Path dir) throws Exception {
        new MavenStub(jitpack).sourcesJar("com.github.everit-org.json-schema", "org.everit.json.schema", "1.14.4");
        LockOrchestrator orchestrator = new LockOrchestrator(repos(dir));
        Lockfile lock = orchestrator.lock(
                project("io.apicurio:schema-util-json", "=2.6.13"), "test", List.of(), true, observer());

        Lockfile withSources = orchestrator.attachSources(lock);

        Lockfile.Artifact everit = withSources.artifacts().stream()
                .filter(a -> a.name().startsWith("com.github.everit-org.json-schema:"))
                .findFirst()
                .orElseThrow();
        assertThat(everit.sourcesChecksum()).startsWith("sha256:");
    }

    /**
     * The repository the sources pass rebuilds from a row's {@code source} carries the policy the
     * declaring POM wrote: a releases-only repository is not asked for a snapshot's sources, and is
     * asked for a release's.
     */
    @Test
    void sources_attach_rebuilds_a_declared_repository_with_the_policy_its_pom_wrote(@TempDir Path dir)
            throws Exception {
        new MavenStub(central)
                .metadata("io.apicurio", "schema-util-json", "2.6.13", "2.6.14")
                .pom("io.apicurio", "schema-util-json", "2.6.14", """
                        <project>
                          <groupId>io.apicurio</groupId>
                          <artifactId>schema-util-json</artifactId>
                          <version>2.6.14</version>
                          <repositories>
                            <repository>
                              <id>jitpack.io</id>
                              <url>%s</url>
                              <snapshots><enabled>false</enabled></snapshots>
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
        LockOrchestrator orchestrator = new LockOrchestrator(repos(dir));
        Lockfile lock = orchestrator.lock(
                project("io.apicurio:schema-util-json", "=2.6.14"), "test", List.of(), true, observer());
        String source = "jitpack.io+" + jitpack.baseUrl();
        List<Lockfile.Artifact> rows = new ArrayList<>(lock.artifacts());
        rows.add(new Lockfile.Artifact("com.foo:snap:jar:", "1.0-SNAPSHOT", source, "sha256:00", null, List.of()));
        rows.add(new Lockfile.Artifact("com.foo:rel:jar:", "1.0", source, "sha256:00", null, List.of()));
        jitpack.clearRequests();

        orchestrator.attachSources(lock.withArtifacts(rows));

        assertThat(jitpack.requestsFor("/com/foo/snap/1.0-SNAPSHOT/snap-1.0-SNAPSHOT-sources.jar"))
                .as("a releases-only repository is not asked for a snapshot's sources")
                .isZero();
        assertThat(jitpack.requestsFor("/com/foo/rel/1.0/rel-1.0-sources.jar"))
                .as("the same repository is asked for a release's sources")
                .isEqualTo(1);
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

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
import cc.jumpkick.model.Workspace;
import cc.jumpkick.model.WorkspaceMerge;
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
 * A workspace member's coordinate is the member's wherever a dependency POM asks for it: the edge
 * is served by the workspace, as Maven's reactor stands in for a published artifact of the same
 * coordinate, so the lock carries no published row for it and every classpath reads the member.
 */
class LockOrchestratorWorkspaceSubstitutionTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void start() {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        // middle depends on the published com.example:lib 1.0 and on leaf; lib 1.0 is published too.
        upstream.leaf("com.foo", "leaf", "1.0");
        upstream.metadata("com.foo", "middle", "1.0");
        upstream.pom("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>middle</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.jar("com.foo", "middle", "1.0");
        upstream.metadata("com.example", "lib", "1.0");
        upstream.pom("com.example", "lib", "1.0", """
                <project>
                  <groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>published-only</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.jar("com.example", "lib", "1.0");
        upstream.leaf("com.foo", "published-only", "1.0");
        // A second publisher asking for the same member coordinate at a different version.
        upstream.metadata("com.foo", "other", "1.0");
        upstream.pom("com.foo", "other", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>other</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>lib</artifactId><version>2.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.jar("com.foo", "other", "1.0");
    }

    /**
     * {@code app} depends on the sibling {@code lib} and on {@code middle}, whose POM asks for the
     * published {@code com.example:lib}: the workspace serves that edge, so the lock has no row for
     * lib, none for what only the published lib's POM pulled, and a note says which POM asked.
     */
    @Test
    void a_published_edge_onto_a_member_is_served_by_the_member(@TempDir Path tempDir) throws Exception {
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild lib = manifest("lib", Map.of());
        JkBuild app = manifest("app", Map.of(Scope.MAIN, List.of(Dependency.workspace("lib"), middle)));
        List<String> notes = new ArrayList<>();

        Lockfile lock = lockWorkspace(tempDir, List.of(lib, app), notes);

        assertThat(lock.artifacts())
                .extracting(Lockfile.Artifact::packageKey)
                .contains("com.foo:middle:jar:", "com.foo:leaf:jar:")
                .doesNotContain("com.example:lib:jar:", "com.foo:published-only:jar:");
        assertThat(notes).anySatisfy(line -> assertThat(line)
                .startsWith("com.foo:middle 1.0 depends on com.example:lib, which this workspace builds")
                .contains("no row is locked for it"));
    }

    /**
     * {@code app} depends only on published {@code middle}. {@code middle}'s POM asks for the
     * published {@code com.example:lib}, which this workspace builds, so the lock has no published
     * lib row and none of the dependencies only that published POM declared.
     */
    @Test
    void a_transitive_pom_edge_onto_a_member_locks_neither_the_member_nor_its_published_deps(@TempDir Path tempDir)
            throws Exception {
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        Dependency own = new Dependency("com.foo:leaf", VersionSelector.parse("=1.0"));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(own)));
        JkBuild app = manifest("app", Map.of(Scope.MAIN, List.of(middle)));
        List<String> notes = new ArrayList<>();

        Lockfile lock = lockWorkspace(tempDir, List.of(lib, app), notes);

        assertThat(lock.artifacts())
                .extracting(Lockfile.Artifact::packageKey)
                .contains("com.foo:middle:jar:", "com.foo:leaf:jar:")
                .doesNotContain("com.example:lib:jar:", "com.foo:published-only:jar:");
        assertThat(notes).anySatisfy(line -> assertThat(line)
                .startsWith("com.foo:middle 1.0 depends on com.example:lib, which this workspace builds"));
        assertThat(lock.artifacts().stream()
                        .filter(a -> a.packageKey().equals("com.foo:middle:jar:"))
                        .flatMap(a -> a.deps().stream())
                        .anyMatch(dep -> dep.startsWith("com.example:lib")))
                .isTrue();
    }

    /** A standalone project serves its own coordinate the same way: a dependency that depends back on it locks no row for it. */
    @Test
    void a_standalone_project_is_its_own_coordinate(@TempDir Path tempDir) throws Exception {
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(lib, "test");

        assertThat(lock.artifacts())
                .extracting(Lockfile.Artifact::packageKey)
                .contains("com.foo:middle:jar:", "com.foo:leaf:jar:")
                .doesNotContain("com.example:lib:jar:");
    }

    private Lockfile lockWorkspace(Path tempDir, List<JkBuild> modules, List<String> notes) throws Exception {
        List<String> names = modules.stream().map(m -> m.project().name()).toList();
        JkBuild root = JkBuild.builder(new Project("com.example", "root", "0.1.0", 25))
                .dependencies(new JkBuild.Dependencies(new EnumMap<>(Scope.class)))
                .workspace(new Workspace(names))
                .build();
        List<LockOrchestrator.Member> members = new ArrayList<>();
        for (JkBuild module : modules) {
            members.add(new LockOrchestrator.Member(
                    module.project().name(), WorkspaceMerge.applyToModule(root, module, modules)));
        }
        ResolveObserver recording = new ResolveObserver() {
            @Override
            public void onTotal(int total) {}

            @Override
            public void onPackage(String module, String version) {}

            @Override
            public void onNote(String line) {
                notes.add(line);
            }
        };
        return new LockOrchestrator(repoGroup(tempDir))
                .withMembers(members)
                .lock(WorkspaceMerge.merge(root, modules), "test", List.of(), true, recording);
    }

    private RepoGroup repoGroup(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), cas));
    }

    /**
     * Two published POMs asking for the member at different versions. The member answers both, so
     * neither edge constrains it: a coordinate the lock never carries a row for must not surface
     * as a version conflict the user has no way to resolve.
     */
    @Test
    void two_pom_edges_at_different_versions_onto_one_member_do_not_conflict(@TempDir Path tempDir) throws Exception {
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        Dependency other = new Dependency("com.foo:other", VersionSelector.parse("=1.0"));
        JkBuild lib = manifest("lib", Map.of());
        JkBuild app = manifest("app", Map.of(Scope.MAIN, List.of(Dependency.workspace("lib"), middle, other)));
        List<String> notes = new ArrayList<>();

        Lockfile lock = lockWorkspace(tempDir, List.of(lib, app), notes);

        assertThat(lock.artifacts())
                .extracting(Lockfile.Artifact::packageKey)
                .contains("com.foo:middle:jar:", "com.foo:other:jar:")
                .doesNotContain("com.example:lib:jar:");
        assertThat(notes).anySatisfy(line -> assertThat(line).contains("com.example:lib, which this workspace builds"));
    }

    private static JkBuild manifest(String name, Map<Scope, List<Dependency>> byScope) {
        EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
        copy.putAll(byScope);
        return new JkBuild(new Project("com.example", name, "0.1.0", 25), new JkBuild.Dependencies(copy));
    }
}

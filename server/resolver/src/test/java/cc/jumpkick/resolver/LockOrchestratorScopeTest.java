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
import java.io.IOException;
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
 * R5 / finding 15: processor graph is solved separately from main. Shared modules may dual-row when
 * versions diverge (listenablefuture dance).
 */
class LockOrchestratorScopeTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void start() throws IOException {
        // junit defaults
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    @Test
    void processor_cannot_force_main_version_of_shared_module(@TempDir Path tempDir) throws Exception {
        // Main depends on lib-main, which declares shared 2.0; processor depends on lib-proc, which
        // needs shared = 1.0 exactly. A unified solve would force shared=1.0 on main. Per-scope:
        // main keeps 2.0, processor dual-rows 1.0.
        upstream.metadata("com.foo", "lib-main", "1.0");
        upstream.metadata("com.foo", "lib-proc", "1.0");
        upstream.metadata("com.foo", "shared", "1.0", "2.0");
        upstream.pom("com.foo", "lib-main", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>lib-main</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>shared</artifactId><version>2.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.pom("com.foo", "lib-proc", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>lib-proc</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>shared</artifactId><version>[1.0,1.0]</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.pom("com.foo", "shared", "1.0", MavenStub.emptyPom("com.foo", "shared", "1.0"));
        upstream.pom("com.foo", "shared", "2.0", MavenStub.emptyPom("com.foo", "shared", "2.0"));

        JkBuild project = jkBuild(Map.of(
                Scope.MAIN, List.of(new Dependency("com.foo:lib-main", VersionSelector.parse("=1.0"))),
                Scope.PROCESSOR, List.of(new Dependency("com.foo:lib-proc", VersionSelector.parse("=1.0")))));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");

        List<Lockfile.Artifact> sharedRows = lock.artifacts().stream()
                .filter(a -> a.packageKey().equals("com.foo:shared:jar:"))
                .toList();
        assertThat(sharedRows).hasSize(2);

        Lockfile.Artifact mainShared = sharedRows.stream()
                .filter(a -> a.scopes().contains(Scope.MAIN) || a.scopes().contains(Scope.EXPORT))
                .findFirst()
                .orElseGet(() -> sharedRows.stream()
                        .filter(a -> !a.scopes().equals(List.of(Scope.PROCESSOR)))
                        .findFirst()
                        .orElseThrow());
        Lockfile.Artifact procShared = sharedRows.stream()
                .filter(a -> a.scopes().contains(Scope.PROCESSOR) && a.scopes().size() == 1)
                .findFirst()
                .orElseThrow();

        assertThat(mainShared.version()).isEqualTo("2.0");
        assertThat(procShared.version()).isEqualTo("1.0");
        assertThat(procShared.scopes()).containsExactly(Scope.PROCESSOR);
    }

    /**
     * Main floats onto shared 2.0; a test dependency's POM holds shared at 3.0. The test graph
     * honours its edge, but the test classpath carries main's 2.0, so the lock says the 3.0 row is
     * one nothing reads and names the dependency holding the floor.
     */
    @Test
    void a_test_dependency_flooring_a_module_above_main_is_noted_as_a_dead_test_row(@TempDir Path tempDir)
            throws Exception {
        upstream.metadata("com.foo", "shared", "1.0", "2.0", "3.0");
        upstream.metadata("com.foo", "lib-test", "1.0");
        for (String v : new String[] {"1.0", "2.0", "3.0"}) {
            upstream.pom("com.foo", "shared", v, MavenStub.emptyPom("com.foo", "shared", v));
        }
        upstream.pom("com.foo", "lib-test", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>lib-test</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>shared</artifactId><version>[3.0,3.0]</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        JkBuild project = jkBuild(Map.of(
                Scope.MAIN, List.of(new Dependency("com.foo:shared", VersionSelector.parse("^2.0"))),
                Scope.TEST, List.of(new Dependency("com.foo:lib-test", VersionSelector.parse("=1.0")))));
        List<String> overrides = new ArrayList<>();

        Lockfile lock =
                new LockOrchestrator(repoGroup(tempDir)).lock(project, "test", List.of(), true, recording(overrides));

        assertThat(lock.artifacts().stream()
                        .filter(a -> a.packageKey().equals("com.foo:shared:jar:"))
                        .map(Lockfile.Artifact::version))
                .containsExactlyInAnyOrder("2.0", "3.0");
        assertThat(overrides)
                .singleElement()
                .asString()
                .contains("com.foo:shared resolves 2.0 in the main graph and 3.0 in the test graph")
                .contains("held there by com.foo:lib-test@1.0 ([3.0,3.0])")
                .contains("the test classpath carries main's 2.0")
                .contains("declare com.foo:shared at 3.0 in a main scope");
    }

    private static ResolveObserver recording(List<String> overrides) {
        return new ResolveObserver() {
            @Override
            public void onTotal(int total) {}

            @Override
            public void onPackage(String module, String version) {}

            @Override
            public void onOverride(String line) {
                overrides.add(line);
            }
        };
    }

    @Test
    void same_version_in_both_graphs_is_single_row_with_both_scopes(@TempDir Path tempDir) throws Exception {
        upstream.metadata("com.foo", "lib", "1.0");
        upstream.metadata("com.foo", "shared", "1.0");
        upstream.pom("com.foo", "lib", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>lib</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>shared</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.pom("com.foo", "shared", "1.0", MavenStub.emptyPom("com.foo", "shared", "1.0"));

        // Same lib on main and processor → shared once with both scopes.
        JkBuild project = jkBuild(Map.of(
                Scope.MAIN, List.of(new Dependency("com.foo:lib", VersionSelector.parse("=1.0"))),
                Scope.PROCESSOR, List.of(new Dependency("com.foo:lib", VersionSelector.parse("=1.0")))));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");
        List<Lockfile.Artifact> shared = lock.artifacts().stream()
                .filter(a -> a.packageKey().equals("com.foo:shared:jar:"))
                .toList();
        assertThat(shared).hasSize(1);
        assertThat(shared.getFirst().scopes()).contains(Scope.MAIN, Scope.PROCESSOR);
    }

    @Test
    void test_cannot_force_main_version_of_shared_module(@TempDir Path tempDir) throws Exception {
        // Main's library declares shared 2.0; test's wants exact 1.0. Separate graphs.
        upstream.metadata("com.foo", "lib-main", "1.0");
        upstream.metadata("com.foo", "lib-test", "1.0");
        upstream.metadata("com.foo", "shared", "1.0", "2.0");
        upstream.pom("com.foo", "lib-main", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>lib-main</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>shared</artifactId><version>2.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.pom("com.foo", "lib-test", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>lib-test</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>shared</artifactId><version>[1.0,1.0]</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.pom("com.foo", "shared", "1.0", MavenStub.emptyPom("com.foo", "shared", "1.0"));
        upstream.pom("com.foo", "shared", "2.0", MavenStub.emptyPom("com.foo", "shared", "2.0"));

        JkBuild project = jkBuild(Map.of(
                Scope.MAIN, List.of(new Dependency("com.foo:lib-main", VersionSelector.parse("=1.0"))),
                Scope.TEST, List.of(new Dependency("com.foo:lib-test", VersionSelector.parse("=1.0")))));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");
        List<Lockfile.Artifact> sharedRows = lock.artifacts().stream()
                .filter(a -> a.packageKey().equals("com.foo:shared:jar:"))
                .toList();
        assertThat(sharedRows).hasSize(2);
        assertThat(sharedRows.stream().map(Lockfile.Artifact::version)).containsExactlyInAnyOrder("1.0", "2.0");
    }

    @Test
    void processor_only_module_tagged_processor(@TempDir Path tempDir) throws Exception {
        upstream.metadata("com.foo", "proc", "1.0");
        upstream.pom("com.foo", "proc", "1.0", MavenStub.emptyPom("com.foo", "proc", "1.0"));

        JkBuild project =
                jkBuild(Map.of(Scope.PROCESSOR, List.of(new Dependency("com.foo:proc", VersionSelector.parse("1.0")))));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");
        Lockfile.Artifact proc = lock.artifacts().stream()
                .filter(a -> a.packageKey().equals("com.foo:proc:jar:"))
                .findFirst()
                .orElseThrow();
        assertThat(proc.scopes()).contains(Scope.PROCESSOR);
        assertThat(proc.scopes()).doesNotContain(Scope.MAIN);
    }

    @Test
    void a_test_processor_root_rides_the_processor_graph_under_its_own_tag(@TempDir Path tempDir) throws Exception {
        upstream.metadata("com.foo", "proc", "1.0");
        upstream.metadata("com.foo", "test-proc", "1.0");
        upstream.pom("com.foo", "proc", "1.0", MavenStub.emptyPom("com.foo", "proc", "1.0"));
        upstream.pom("com.foo", "test-proc", "1.0", MavenStub.emptyPom("com.foo", "test-proc", "1.0"));

        JkBuild project = jkBuild(Map.of(
                Scope.PROCESSOR, List.of(new Dependency("com.foo:proc", VersionSelector.parse("1.0"))),
                Scope.TEST_PROCESSOR, List.of(new Dependency("com.foo:test-proc", VersionSelector.parse("1.0")))));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");
        Lockfile.Artifact proc = lock.artifacts().stream()
                .filter(a -> a.packageKey().equals("com.foo:proc:jar:"))
                .findFirst()
                .orElseThrow();
        Lockfile.Artifact testProc = lock.artifacts().stream()
                .filter(a -> a.packageKey().equals("com.foo:test-proc:jar:"))
                .findFirst()
                .orElseThrow();
        assertThat(proc.scopes()).containsExactly(Scope.PROCESSOR);
        assertThat(testProc.scopes()).containsExactly(Scope.TEST_PROCESSOR);
    }

    private static JkBuild jkBuild(Map<Scope, List<Dependency>> byScope) {
        EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
        copy.putAll(byScope);
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(copy));
    }

    private RepoGroup repoGroup(Path tempDir) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tempDir.resolve("cache"))));
    }
}

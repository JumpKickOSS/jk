// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PinPolicy;
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
 * A {@code [managed-dependencies]} entry governs the version of a module only a dependency's POM
 * brings in, as Maven's inline {@code dependencyManagement} does: {@code middle 1.0} asks for
 * {@code leaf 1.5}, the repository advertises {@code 2.0}, and the manifest's entry lands the lock
 * on {@code 1.0} under both pin policies, with the entry named on the row. The entry beats a BOM
 * that manages the same module, an exact pin the project declares itself beats the entry, and the
 * entry's {@code exclude} prunes every edge onto its module, a POM's and the project's own alike.
 */
class ManagedDependenciesLockTest {

    private static final String LEAF = "com.foo:leaf:jar:";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void publish() {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.metadata("com.foo", "middle", "1.0");
        upstream.pom("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>middle</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.5</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.jar("com.foo", "middle", "1.0");
        upstream.metadata("com.foo", "leaf", "1.0", "1.5", "2.0");
        for (String v : List.of("1.0", "1.5", "2.0")) {
            upstream.pom("com.foo", "leaf", v, MavenStub.emptyPom("com.foo", "leaf", v));
            upstream.jar("com.foo", "leaf", v);
        }
        upstream.pom(
                "org.example",
                "the-bom",
                "1.0",
                MavenStub.bom("org.example", "the-bom", "1.0", List.of("com.foo:leaf:2.0")));
    }

    @Test
    void a_managed_entry_pins_a_transitive_nothing_declares_under_both_policies(@TempDir Path tempDir)
            throws Exception {
        JkBuild project = project(Map.of(
                Scope.MAIN, List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))),
                Scope.MANAGED, List.of(Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("1.0")))));

        for (PinPolicy policy : PinPolicy.values()) {
            Lockfile lock = new LockOrchestrator(repoGroup(tempDir.resolve(policy.name())))
                    .withPinPolicy(policy)
                    .lock(project, "test");

            Lockfile.Artifact leaf = row(lock, LEAF);
            assertThat(leaf.version()).as(policy.name()).isEqualTo("1.0");
            assertThat(leaf.pinnedBy()).as(policy.name()).isEqualTo("jk.toml:leaf");
            assertThat(row(lock, "com.foo:middle:jar:").declaredFor(LEAF + "@1.0"))
                    .as("the edge still says what middle asked for")
                    .isEqualTo("1.5");
            assertThat(lock.platformPins()).as("a manifest entry is not a BOM").doesNotContainKey("jk.toml");
        }
    }

    @Test
    void a_managed_entry_beats_a_bom_under_the_exact_policy_and_the_lock_says_so(@TempDir Path tempDir)
            throws Exception {
        JkBuild project = project(Map.of(
                Scope.MAIN, List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))),
                Scope.MANAGED, List.of(Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("1.0"))),
                Scope.PLATFORM,
                        List.of(Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0")))));
        List<String> overrides = new ArrayList<>();

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(PinPolicy.EXACT)
                .lock(project, "test", List.of(), true, recording(overrides));

        assertThat(row(lock, LEAF).version()).isEqualTo("1.0");
        assertThat(row(lock, LEAF).pinnedBy()).isEqualTo("jk.toml:leaf");
        assertThat(overrides).hasSize(1);
        assertThat(overrides.getFirst())
                .contains("com.foo:leaf 1.0 is the project's [managed-dependencies] entry (jk.toml:leaf)")
                .contains("org.example:the-bom:1.0 constrains to 2.0");
    }

    @Test
    void an_exact_pin_the_project_declares_beats_its_managed_entry(@TempDir Path tempDir) throws Exception {
        JkBuild project = project(Map.of(
                Scope.MAIN,
                        List.of(
                                new Dependency("com.foo:middle", VersionSelector.parse("=1.0")),
                                Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("2.0"))),
                Scope.MANAGED, List.of(Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("1.0")))));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");

        assertThat(row(lock, LEAF).version()).isEqualTo("2.0");
        assertThat(row(lock, LEAF).pinnedBy()).isNull();
    }

    /** The root's table reaches every member: the member depends on middle, the root manages leaf. */
    @Test
    void a_workspace_roots_managed_table_governs_a_members_transitive(@TempDir Path tempDir) throws Exception {
        EnumMap<Scope, List<Dependency>> rootDeps = new EnumMap<>(Scope.class);
        rootDeps.put(Scope.MANAGED, List.of(Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("1.0"))));
        JkBuild root = JkBuild.builder(new Project("com.example", "parent", "1.0", 25))
                .workspace(new Workspace(List.of("app")))
                .dependencies(new JkBuild.Dependencies(rootDeps))
                .build(JkBuild.Build.EMPTY.withPinPolicy(PinPolicy.NEAREST))
                .build();
        EnumMap<Scope, List<Dependency>> appDeps = new EnumMap<>(Scope.class);
        appDeps.put(Scope.MAIN, List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))));
        JkBuild app = new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(appDeps));
        JkBuild merged = WorkspaceMerge.merge(root, List.of(app));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(PinPolicy.NEAREST)
                .lock(merged, "test");

        assertThat(row(lock, LEAF).version()).isEqualTo("1.0");
        assertThat(row(lock, LEAF).pinnedBy()).isEqualTo("jk.toml:leaf");
    }

    /**
     * Two members manage {@code leaf} at different versions; neither entry is the workspace's. The
     * plain row is the 1.5 {@code middle} declares, and each member reads a row of its own at its
     * entry's version, named on the row.
     */
    @Test
    void members_whose_managed_entries_disagree_each_read_their_own_row(@TempDir Path tempDir) throws Exception {
        JkBuild app = member(
                "app",
                Map.of(
                        Scope.MAIN, List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))),
                        Scope.MANAGED, List.of(Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("1.0")))));
        JkBuild lib = member(
                "lib",
                Map.of(
                        Scope.MAIN, List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))),
                        Scope.MANAGED, List.of(Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("2.0")))));

        Lockfile lock = lockWorkspace(tempDir, List.of(app, lib));

        assertThat(rows(lock, LEAF))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy, Lockfile.Artifact::members)
                .containsExactlyInAnyOrder(
                        tuple("1.5", null, List.of()),
                        tuple("1.0", "jk.toml:leaf", List.of("app")),
                        tuple("2.0", "jk.toml:leaf", List.of("lib")));
        assertThat(row(lock.forMember("app"), LEAF).version()).isEqualTo("1.0");
        assertThat(row(lock.forMember("lib"), LEAF).version()).isEqualTo("2.0");
    }

    /** An entry every member declares alike is the workspace's: one plain row at its version, no partition. */
    @Test
    void a_managed_entry_every_member_holds_constrains_the_workspaces_row(@TempDir Path tempDir) throws Exception {
        Map<Scope, List<Dependency>> deps = Map.of(
                Scope.MAIN, List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))),
                Scope.MANAGED, List.of(Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("1.0"))));

        Lockfile lock = lockWorkspace(tempDir, List.of(member("app", deps), member("lib", deps)));

        assertThat(lock.artifacts()).allMatch(r -> !r.isPartition());
        assertThat(rows(lock, LEAF))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy)
                .containsExactly(tuple("1.0", "jk.toml:leaf"));
    }

    /**
     * One member's entry reaches its own graph only: the plain row is what {@code middle} declares,
     * the holder reads its entry's version, the other member the plain row.
     */
    @Test
    void a_managed_entry_one_member_holds_never_moves_the_plain_row(@TempDir Path tempDir) throws Exception {
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild app = member(
                "app",
                Map.of(
                        Scope.MAIN, List.of(middle),
                        Scope.MANAGED, List.of(Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("1.0")))));
        JkBuild lib = member("lib", Map.of(Scope.MAIN, List.of(middle)));

        Lockfile lock = lockWorkspace(tempDir, List.of(app, lib));

        assertThat(rows(lock, LEAF))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy, Lockfile.Artifact::members)
                .containsExactlyInAnyOrder(tuple("1.5", null, List.of()), tuple("1.0", "jk.toml:leaf", List.of("app")));
        assertThat(row(lock.forMember("lib"), LEAF).version()).isEqualTo("1.5");
    }

    /** The workspace locked as the pipeline locks it: the merged manifest, with every member behind it. */
    private Lockfile lockWorkspace(Path tempDir, List<JkBuild> modules) throws Exception {
        List<String> names = modules.stream().map(m -> m.project().name()).toList();
        JkBuild root = JkBuild.builder(new Project("com.example", "root", "0.1.0", 25))
                .workspace(new Workspace(names))
                .build();
        List<LockOrchestrator.Member> members = new ArrayList<>();
        for (JkBuild module : modules) {
            members.add(new LockOrchestrator.Member(
                    module.project().name(), WorkspaceMerge.applyToModule(root, module, modules)));
        }
        return new LockOrchestrator(repoGroup(tempDir))
                .withMembers(members)
                .lock(WorkspaceMerge.merge(root, modules), "test");
    }

    private static JkBuild member(String name, Map<Scope, List<Dependency>> byScope) {
        EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
        copy.putAll(byScope);
        return new JkBuild(new Project("com.example", name, "0.1.0", 25), new JkBuild.Dependencies(copy));
    }

    private static List<Lockfile.Artifact> rows(Lockfile lock, String packageKey) {
        return lock.artifacts().stream().filter(a -> a.packageKey().equals(packageKey)).toList();
    }

    /** leaf 1.0 brings deep; the managed entry on leaf excludes everything under it. */
    private void publishLeafWithDeep() {
        upstream.metadata("com.foo", "deep", "1.0");
        upstream.pom("com.foo", "deep", "1.0", MavenStub.emptyPom("com.foo", "deep", "1.0"));
        upstream.jar("com.foo", "deep", "1.0");
        upstream.pom("com.foo", "leaf", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>deep</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
    }

    @Test
    void a_managed_entrys_exclusions_prune_a_pom_edge_onto_its_module(@TempDir Path tempDir) throws Exception {
        publishLeafWithDeep();
        JkBuild project = project(Map.of(
                Scope.MAIN, List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))),
                Scope.MANAGED,
                        List.of(Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("1.0"))
                                .withExclusions(List.of("*:*")))));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");

        assertThat(lock.artifacts())
                .extracting(Lockfile.Artifact::packageKey)
                .contains("com.foo:middle:jar:", LEAF)
                .doesNotContain("com.foo:deep:jar:");
        assertThat(row(lock, LEAF).version()).isEqualTo("1.0");
        assertThat(row(lock, LEAF).excludedBy())
                .as("the row whose edge was pruned names the managed entry")
                .containsExactly("com.foo:deep <- jk.toml:leaf");
    }

    @Test
    void a_managed_entrys_exclusions_prune_the_projects_own_edge_onto_its_module(@TempDir Path tempDir)
            throws Exception {
        publishLeafWithDeep();
        JkBuild project = project(Map.of(
                Scope.MAIN, List.of(Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("=1.0"))),
                Scope.MANAGED,
                        List.of(Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("1.0"))
                                .withExclusions(List.of("com.foo:deep")))));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");

        assertThat(lock.artifacts()).extracting(Lockfile.Artifact::packageKey).doesNotContain("com.foo:deep:jar:");
        assertThat(row(lock, LEAF).excludedBy()).containsExactly("com.foo:deep <- jk.toml:leaf");
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

    private static JkBuild project(Map<Scope, List<Dependency>> byScope) {
        EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
        copy.putAll(byScope);
        return new JkBuild(new Project("com.example", "test", "0.1.0", 25), new JkBuild.Dependencies(copy));
    }

    private static Lockfile.Artifact row(Lockfile lock, String packageKey) {
        return lock.artifacts().stream()
                .filter(a -> a.packageKey().equals(packageKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError(packageKey + " is not in the lock: " + lock.artifacts()));
    }

    private RepoGroup repoGroup(Path dir) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(dir.resolve("cache"))));
    }
}

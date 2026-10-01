// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A member's exact pin is that member's version, as a module's own {@code <version>} is under
 * Maven: the pin is the workspace's row, and a sibling that reaches the module without pinning it
 * and whose own graph asks for another version reads a row of its own. A {@code [workspace.dependencies]}
 * version is every member's, and the processor path keeps its own rules.
 */
class LockOrchestratorSiblingPinsTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void start() {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.metadata("com.foo", "middle", "1.0");
        upstream.metadata("com.foo", "leaf", "1.0", "2.0");
        upstream.pom("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>middle</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.jar("com.foo", "middle", "1.0");
        for (String v : List.of("1.0", "2.0")) {
            upstream.pom("com.foo", "leaf", v, leafPom("leaf", v));
            upstream.jar("com.foo", "leaf", v);
        }
    }

    /**
     * A member's exact pin is that member's version, not its siblings': app pins leaf 2.0, lib
     * reaches leaf through middle, which declares 1.0. app's pin is the workspace's row and lib,
     * whose own graph asks for 1.0, reads a row of its own, as each would under Maven.
     */
    @Test
    void a_members_exact_pin_is_its_own_and_a_sibling_reads_the_version_its_graph_asks_for(@TempDir Path tempDir)
            throws Exception {
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        Dependency leafPin = new Dependency("com.foo:leaf", VersionSelector.parse("=2.0"));
        JkBuild app = manifest("app", Map.of(Scope.MAIN, List.of(leafPin, middle)));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));
        List<String> notes = new ArrayList<>();

        Lockfile lock = lockWorkspace(tempDir, List.of(app, lib), notes);

        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::members)
                .containsExactlyInAnyOrder(tuple("2.0", List.of()), tuple("1.0", List.of("lib")));
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
        assertThat(rows(lock, "com.foo:middle:jar:")).hasSize(1).allMatch(r -> !r.isPartition());
        assertThat(notes).contains("lib reads its own rows for 1 coordinate: com.foo:leaf 1.0 (workspace 2.0)");
    }

    /**
     * Two members pin leaf at two versions and a third reaches it unpinned: each pinning member reads
     * its own version, and the one that pins nothing reads the version its own graph asks for.
     */
    @Test
    void two_members_pin_differently_and_a_member_that_pins_nothing_reads_its_graphs_version(@TempDir Path tempDir)
            throws Exception {
        upstream.metadata("com.foo", "leaf", "1.0", "2.0", "3.0");
        upstream.pom("com.foo", "leaf", "3.0", leafPom("leaf", "3.0"));
        upstream.jar("com.foo", "leaf", "3.0");
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild newer = manifest(
                "newer", Map.of(Scope.MAIN, List.of(new Dependency("com.foo:leaf", VersionSelector.parse("=2.0")))));
        JkBuild newest = manifest(
                "newest", Map.of(Scope.MAIN, List.of(new Dependency("com.foo:leaf", VersionSelector.parse("=3.0")))));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));

        Lockfile lock = lockWorkspace(tempDir, List.of(newer, newest, lib));

        assertThat(rows(lock.forMember("newer"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
        assertThat(rows(lock.forMember("newest"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("3.0");
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .filteredOn(r -> !r.isPartition())
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
    }

    /**
     * {@code leaf.workspace = true} reads the root's {@code [workspace.dependencies]} version, and
     * that version is the workspace's: a member that reaches leaf without declaring it reads it too,
     * and nothing is partitioned.
     */
    @Test
    void a_workspace_dependency_is_the_workspaces_version_for_every_member(@TempDir Path tempDir) throws Exception {
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild app = manifest("app", Map.of(Scope.MAIN, List.of(Dependency.workspace("leaf"), middle)));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));
        Workspace workspace = new Workspace(
                List.of("app", "lib"),
                Map.of(
                        "leaf",
                        new Workspace.WorkspaceDependency("com.foo", "leaf", VersionSelector.parse("=2.0"), null)));
        JkBuild root = JkBuild.builder(new Project("com.example", "root", "0.1.0", 25))
                .workspace(workspace)
                .build();

        Lockfile lock = lockWorkspace(tempDir, root, List.of(app, lib), Set.of("com.foo:leaf"), new ArrayList<>());

        assertThat(lock.artifacts()).allMatch(r -> !r.isPartition());
        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
    }

    /**
     * A member that depends on a sibling reads the sibling's pin as the nearest declaration, as
     * Maven's nearest-wins does: lib pins leaf 2.0, app depends on lib, and both read 2.0 while
     * other, which reaches leaf through middle alone, reads the 1.0 middle declares.
     */
    @Test
    void a_member_depending_on_a_sibling_reads_the_siblings_pin(@TempDir Path tempDir) throws Exception {
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild lib = manifest(
                "lib", Map.of(Scope.MAIN, List.of(new Dependency("com.foo:leaf", VersionSelector.parse("=2.0")))));
        JkBuild app = manifest("app", Map.of(Scope.MAIN, List.of(Dependency.workspace("lib"), middle)));
        JkBuild other = manifest("other", Map.of(Scope.MAIN, List.of(middle)));

        Lockfile lock = lockWorkspace(tempDir, List.of(lib, app, other));

        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::members)
                .containsExactlyInAnyOrder(tuple("2.0", List.of()), tuple("1.0", List.of("other")));
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
        assertThat(rows(lock.forMember("other"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
    }

    /**
     * A pin no sibling's graph reaches, or one every sibling that reaches it asks for anyway, is the
     * workspace's row and nothing is partitioned.
     */
    @Test
    void a_pin_its_siblings_agree_with_partitions_nothing(@TempDir Path tempDir) throws Exception {
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild app = manifest(
                "app", Map.of(Scope.MAIN, List.of(new Dependency("com.foo:leaf", VersionSelector.parse("=1.0")))));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));
        JkBuild bystander = manifest(
                "bystander",
                Map.of(Scope.MAIN, List.of(new Dependency("com.foo:widget", VersionSelector.parse("=1.0")))));
        upstream.metadata("com.foo", "widget", "1.0");
        upstream.pom("com.foo", "widget", "1.0", leafPom("widget", "1.0"));
        upstream.jar("com.foo", "widget", "1.0");

        Lockfile lock = lockWorkspace(tempDir, List.of(app, lib, bystander));

        assertThat(lock.artifacts()).allMatch(r -> !r.isPartition());
        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
    }

    /**
     * The processor path keeps its own rules: a member's processor pin is not a sibling's main
     * version, and a sibling whose main graph reaches the module reads its own graph's version with
     * no partition anywhere.
     */
    @Test
    void a_processor_pin_is_never_a_siblings_main_version(@TempDir Path tempDir) throws Exception {
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild app = manifest(
                "app",
                Map.of(
                        Scope.MAIN,
                        List.of(middle),
                        Scope.PROCESSOR,
                        List.of(new Dependency("com.foo:leaf", VersionSelector.parse("=2.0")))));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));

        Lockfile lock = lockWorkspace(tempDir, List.of(app, lib));

        assertThat(lock.artifacts()).allMatch(r -> !r.isPartition());
        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::scopes)
                .containsExactlyInAnyOrder(tuple("1.0", List.of(Scope.MAIN)), tuple("2.0", List.of(Scope.PROCESSOR)));
    }

    /**
     * A member solved on its own only because of a sibling's pin keeps the workspace's processor
     * path: app's main graph reads the 1.0 middle declares, while its processor path keeps the 2.0
     * row the workspace's processor graph resolved, as an Error Prone processor path keeps the
     * {@code -jre} Guava beside an {@code -android} main classpath.
     */
    @Test
    void a_sibling_pin_moves_a_members_main_rows_and_leaves_its_processor_path(@TempDir Path tempDir) throws Exception {
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild lib = manifest(
                "lib", Map.of(Scope.MAIN, List.of(new Dependency("com.foo:leaf", VersionSelector.parse("=2.0")))));
        JkBuild app = manifest("app", Map.of(Scope.MAIN, List.of(middle), Scope.PROCESSOR, List.of(middle)));

        Lockfile lock = lockWorkspace(tempDir, List.of(lib, app));

        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .filteredOn(Lockfile.Artifact::isPartition)
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::scopes, Lockfile.Artifact::members)
                .containsExactly(tuple("1.0", List.of(Scope.MAIN), List.of("app")));
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::scopes)
                .containsExactlyInAnyOrder(tuple("2.0", List.of(Scope.PROCESSOR)), tuple("1.0", List.of(Scope.MAIN)));
    }

    /**
     * A sibling's dependencies never raise a member either: lib's {@code upper} declares leaf 2.0,
     * which is the workspace's row, while app's graph reaches leaf only through middle's 1.0 and
     * reads that, with the edges of its own row.
     */
    @Test
    void a_version_another_members_graph_raises_is_not_a_members(@TempDir Path tempDir) throws Exception {
        upstream.metadata("com.foo", "upper", "1.0");
        upstream.pom("com.foo", "upper", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>upper</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.foo</groupId><artifactId>leaf</artifactId><version>2.0</version></dependency>
                  </dependencies>
                </project>
                """);
        upstream.jar("com.foo", "upper", "1.0");
        JkBuild lib = manifest(
                "lib", Map.of(Scope.MAIN, List.of(new Dependency("com.foo:upper", VersionSelector.parse("=1.0")))));
        JkBuild app = manifest(
                "app", Map.of(Scope.MAIN, List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0")))));

        Lockfile lock = lockWorkspace(tempDir, List.of(lib, app));

        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::members)
                .containsExactlyInAnyOrder(tuple("2.0", List.of()), tuple("1.0", List.of("app")));
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
    }

    /** The workspace locked as the pipeline locks it, under a root with no declarations of its own. */
    private Lockfile lockWorkspace(Path tempDir, List<JkBuild> modules) throws Exception {
        return lockWorkspace(tempDir, modules, new ArrayList<>());
    }

    /** {@link #lockWorkspace(Path, List)}, recording the lock's notes into {@code notes}. */
    private Lockfile lockWorkspace(Path tempDir, List<JkBuild> modules, List<String> notes) throws Exception {
        List<String> names = modules.stream().map(m -> m.project().name()).toList();
        JkBuild root = JkBuild.builder(new Project("com.example", "root", "0.1.0", 25))
                .workspace(new Workspace(names))
                .build();
        return lockWorkspace(tempDir, root, modules, Set.of(), notes);
    }

    /**
     * The merged manifest of {@code root} and {@code modules} with every member behind it, as the
     * pipeline locks it; {@code workspaceVersions} are the modules it reads as the workspace's own.
     */
    private Lockfile lockWorkspace(
            Path tempDir, JkBuild root, List<JkBuild> modules, Set<String> workspaceVersions, List<String> notes)
            throws Exception {
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
                .withWorkspaceVersions(workspaceVersions)
                .lock(WorkspaceMerge.merge(root, modules), "test", List.of(), true, recording);
    }

    private static String leafPom(String artifact, String version) {
        return "<project><groupId>com.foo</groupId><artifactId>" + artifact + "</artifactId><version>" + version
                + "</version></project>";
    }

    private static List<Lockfile.Artifact> rows(Lockfile lock, String packageKey) {
        List<Lockfile.Artifact> out = new ArrayList<>();
        for (Lockfile.Artifact row : lock.artifacts()) if (row.packageKey().equals(packageKey)) out.add(row);
        return out;
    }

    private static JkBuild manifest(String name, Map<Scope, List<Dependency>> byScope) {
        EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
        copy.putAll(byScope);
        return new JkBuild(new Project("com.example", name, "0.1.0", 25), new JkBuild.Dependencies(copy));
    }

    private RepoGroup repoGroup(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), cas));
    }
}

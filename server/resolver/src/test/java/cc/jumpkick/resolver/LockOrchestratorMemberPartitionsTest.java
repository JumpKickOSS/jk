// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Feature;
import cc.jumpkick.model.Features;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace lock partitions a coordinate per member only where the merged answer cannot be a
 * member's: a BOM one member holds manages a transitive at a version the workspace's row — solved
 * under the BOMs every member holds — does not carry, or two members pin one coordinate exactly at
 * different versions. Members that agree share one row.
 */
class LockOrchestratorMemberPartitionsTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void start() {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    /**
     * The merged manifest carries the BOM one member holds, as {@code WorkspaceMerge} merges it; the
     * merged solve runs under the BOMs every member holds, so the workspace's row is the version
     * {@code middle} declares and the holder reads a row of its own at the BOM's.
     */
    @Test
    void a_bom_one_member_holds_gives_that_member_its_row_and_lifts_no_other(@TempDir Path tempDir) throws Exception {
        serveMiddleOverLeaf();
        Dependency bom = Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"));
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild app = manifest("app", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(middle)));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));
        JkBuild merged = manifest("root", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(middle)));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withMembers(List.of(new LockOrchestrator.Member("app", app), new LockOrchestrator.Member("lib", lib)))
                .lock(merged, "test");

        List<Lockfile.Artifact> leaf = rows(lock, "com.foo:leaf:jar:");
        assertThat(leaf).extracting(Lockfile.Artifact::version).containsExactlyInAnyOrder("1.0", "2.0");
        Lockfile.Artifact workspace =
                leaf.stream().filter(r -> !r.isPartition()).findFirst().orElseThrow();
        assertThat(workspace.version()).isEqualTo("1.0");
        assertThat(workspace.pinnedBy()).isNull();
        Lockfile.Artifact apps =
                leaf.stream().filter(Lockfile.Artifact::isPartition).findFirst().orElseThrow();
        assertThat(apps.version()).isEqualTo("2.0");
        assertThat(apps.pinnedBy()).isEqualTo("org.example:the-bom:1.0");
        assertThat(apps.members()).containsExactly("app");
        assertThat(apps.scopes()).contains(Scope.MAIN);
        // middle is the same for both members: one row, no partition.
        assertThat(rows(lock, "com.foo:middle:jar:")).hasSize(1).allMatch(r -> !r.isPartition());

        // Each member's classpath reads its own row; a member on no row reads the workspace's.
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
        assertThat(rows(lock.forMember("other"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
    }

    /**
     * A versionless root under the BOM one member holds puts leaf 1.1 on the workspace's row, within
     * the line the other member's graph declared it on: {@code middle} declares leaf 1.0. The
     * declaration is a floor 1.1 satisfies, so both members read the workspace's row and no
     * partition is written.
     */
    @Test
    void a_compatible_lift_by_a_siblings_bom_is_a_floor_the_member_shares(@TempDir Path tempDir) throws Exception {
        serveMiddleOverLeaf();
        upstream.metadata("com.foo", "leaf", "1.0", "1.1");
        upstream.pom("com.foo", "leaf", "1.1", leafPom("leaf", "1.1"));
        upstream.jar("com.foo", "leaf", "1.1");
        upstream.pom(
                "org.example",
                "the-bom",
                "1.0",
                MavenStub.bom("org.example", "the-bom", "1.0", List.of("com.foo:leaf:1.1")));
        Dependency bom = Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"));
        Dependency leaf = Dependency.platformManaged("leaf", "com.foo:leaf");
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild app = manifest("app", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(leaf)));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));
        JkBuild merged = manifest("root", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(leaf, middle)));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withMembers(List.of(new LockOrchestrator.Member("app", app), new LockOrchestrator.Member("lib", lib)))
                .lock(merged, "test");

        assertThat(lock.artifacts()).allMatch(r -> !r.isPartition());
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy)
                .containsExactly(tuple("1.1", "org.example:the-bom:1.0"));
    }

    /**
     * The BOM a framework table implies ({@code [spring-boot] version} → {@code
     * spring-boot-dependencies}) is the declaring member's platform like one it wrote out: it
     * governs that member's rows, written beside the workspace's, and reaches no member that never
     * depends on it, whose rows stay the workspace's.
     */
    @Test
    void a_bom_a_framework_table_implies_constrains_only_the_member_that_holds_it(@TempDir Path tempDir)
            throws Exception {
        serveMiddleOverLeaf();
        Dependency implied =
                new Dependency("org.example:the-bom", VersionSelector.parse("=1.0")).withImpliedBy("spring-boot");
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild server = manifest("server", Map.of(Scope.PLATFORM, List.of(implied), Scope.MAIN, List.of(middle)));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));
        JkBuild merged = manifest("root", Map.of(Scope.PLATFORM, List.of(implied), Scope.MAIN, List.of(middle)));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(PinPolicy.NEAREST)
                .withMembers(
                        List.of(new LockOrchestrator.Member("server", server), new LockOrchestrator.Member("lib", lib)))
                .lock(merged, "test");

        assertThat(rows(lock.forMember("server"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy, Lockfile.Artifact::members)
                .containsExactly(tuple("2.0", "org.example:the-bom:1.0", List.of("server")));
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy, Lockfile.Artifact::members)
                .containsExactly(tuple("1.0", null, List.of()));
    }

    /**
     * Two members manage one versionless coordinate through different BOMs. The first-declared BOM
     * decides the workspace row under {@code nearest}; the member whose own BOM says another
     * version is solved on its own and reads that version.
     */
    @Test
    void a_members_own_bom_beats_a_siblings_bom_on_the_members_rows(@TempDir Path tempDir) throws Exception {
        serveMiddleOverLeaf();
        upstream.pom(
                "org.example",
                "lib-bom",
                "1.0",
                MavenStub.bom("org.example", "lib-bom", "1.0", List.of("com.foo:leaf:1.0")));
        Dependency serverBom = Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"));
        Dependency libBom = Dependency.of("lib-bom", "org.example:lib-bom", VersionSelector.parse("=1.0"));
        Dependency leaf = Dependency.platformManaged("leaf", "com.foo:leaf");
        JkBuild server = manifest("server", Map.of(Scope.PLATFORM, List.of(serverBom), Scope.MAIN, List.of(leaf)));
        JkBuild lib = manifest("lib", Map.of(Scope.PLATFORM, List.of(libBom), Scope.MAIN, List.of(leaf)));
        // The merged manifest carries the first declaration of each BOM and root, as WorkspaceMerge does.
        JkBuild merged =
                manifest("root", Map.of(Scope.PLATFORM, List.of(serverBom, libBom), Scope.MAIN, List.of(leaf)));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(PinPolicy.NEAREST)
                .withMembers(
                        List.of(new LockOrchestrator.Member("server", server), new LockOrchestrator.Member("lib", lib)))
                .lock(merged, "test");

        assertThat(rows(lock.forMember("server"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::members)
                .containsExactly(tuple("1.0", List.of("lib")));
    }

    @Test
    void members_that_agree_share_one_row_and_no_members_key(@TempDir Path tempDir) throws Exception {
        serveMiddleOverLeaf();
        Dependency bom = Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"));
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild app = manifest("app", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(middle)));
        JkBuild api = manifest("api", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(middle)));
        JkBuild merged = manifest("root", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(middle)));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withMembers(List.of(new LockOrchestrator.Member("app", app), new LockOrchestrator.Member("api", api)))
                .lock(merged, "test");

        assertThat(lock.artifacts()).allMatch(r -> !r.isPartition());
        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
    }

    /**
     * The BOM only {@code app} holds manages leaf at the version the workspace's row takes anyway
     * ({@code bridge} declares leaf 2.0), so no member reads a row of its own; the plain row still
     * says who pinned it, for every member's {@code jk why}. A module the holder's graph never
     * reaches gets no provenance from its table.
     */
    @Test
    void a_plain_row_a_members_bom_agrees_with_carries_that_boms_provenance(@TempDir Path tempDir) throws Exception {
        serveMiddleOverLeaf();
        upstream.metadata("com.foo", "bridge", "1.0");
        upstream.pom("com.foo", "bridge", "1.0", depending("bridge", "leaf", "2.0"));
        upstream.jar("com.foo", "bridge", "1.0");
        upstream.leaf("com.foo", "widget", "1.0");
        upstream.pom(
                "org.example",
                "the-bom",
                "1.0",
                MavenStub.bom("org.example", "the-bom", "1.0", List.of("com.foo:leaf:2.0", "com.foo:widget:1.0")));
        Dependency bom = Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"));
        Dependency bridge = new Dependency("com.foo:bridge", VersionSelector.parse("=1.0"));
        Dependency widget = new Dependency("com.foo:widget", VersionSelector.parse("=1.0"));
        JkBuild app = manifest("app", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(bridge)));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(bridge, widget)));

        Lockfile lock = lockWorkspace(tempDir, Map.of(), List.of(app, lib));

        assertThat(lock.artifacts()).allMatch(r -> !r.isPartition());
        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy)
                .containsExactly(tuple("2.0", "org.example:the-bom:1.0"));
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::pinnedBy)
                .containsExactly("org.example:the-bom:1.0");
        assertThat(rows(lock, "com.foo:widget:jar:"))
                .as("app's graph never reaches widget, so app's BOM says nothing about the row")
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy)
                .containsExactly(tuple("1.0", null));
        assertThat(rows(lock, "com.foo:bridge:jar:"))
                .extracting(Lockfile.Artifact::pinnedBy)
                .containsExactly((String) null);
    }

    /**
     * The BOM only {@code app} holds manages leaf at the version both members pin and writes an
     * exclusion on it. The versions agree, so the workspace's leaf row keeps the edge onto deep;
     * app reads a leaf row of its own at the same version without that edge, the note says what the
     * row lacks, and lib's classpath walk still reaches deep through the plain row.
     */
    @Test
    void a_member_only_boms_exclusions_reach_the_member_through_a_row_of_its_own(@TempDir Path tempDir)
            throws Exception {
        upstream.leaf("com.foo", "deep", "1.0");
        upstream.metadata("com.foo", "leaf", "1.0");
        upstream.pom("com.foo", "leaf", "1.0", depending("leaf", "deep", "1.0"));
        upstream.jar("com.foo", "leaf", "1.0");
        upstream.pom("org.example", "pruning-bom", "1.0", """
                <project>
                  <groupId>org.example</groupId><artifactId>pruning-bom</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                      <exclusions><exclusion><groupId>com.foo</groupId><artifactId>deep</artifactId></exclusion></exclusions>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        Dependency bom = Dependency.of("pruning-bom", "org.example:pruning-bom", VersionSelector.parse("=1.0"));
        Dependency leaf = new Dependency("com.foo:leaf", VersionSelector.parse("=1.0"));
        JkBuild app = manifest("app", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(leaf)));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(leaf)));
        List<String> notes = new ArrayList<>();

        Lockfile lock = lockWorkspace(tempDir, Map.of(), List.of(app, lib), notes);

        String deepRef = "com.foo:deep:jar:@1.0";
        Lockfile.Artifact workspace = rows(lock, "com.foo:leaf:jar:").stream()
                .filter(r -> !r.isPartition())
                .findFirst()
                .orElseThrow();
        assertThat(workspace.version()).isEqualTo("1.0");
        assertThat(workspace.deps()).contains(deepRef);
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::deps, Lockfile.Artifact::members)
                .containsExactly(tuple("1.0", List.of(), List.of("app")));
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:").getFirst().excludedBy())
                .singleElement()
                .asString()
                .startsWith("com.foo:deep <- ");
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::deps)
                .containsExactly(List.of(deepRef));
        assertThat(rows(lock, "com.foo:deep:jar:")).hasSize(1).allMatch(r -> !r.isPartition());
        assertThat(notes)
                .contains(
                        "app reads its own rows for 1 coordinate: com.foo:leaf 1.0 (the workspace's without com.foo:deep)");
    }

    /**
     * The versionless root {@code app} declares under its own BOM takes the BOM's version as the
     * workspace's row, and the BOM's exclusion on it reaches {@code app} alone: the workspace's leaf
     * row keeps the edge onto deep, {@code lib} reads that row, and {@code app} reads a leaf row of
     * its own without the edge.
     */
    @Test
    void a_member_only_boms_exclusions_on_a_versionless_root_reach_the_holder_alone(@TempDir Path tempDir)
            throws Exception {
        upstream.leaf("com.foo", "deep", "1.0");
        upstream.metadata("com.foo", "leaf", "1.0");
        upstream.pom("com.foo", "leaf", "1.0", depending("leaf", "deep", "1.0"));
        upstream.jar("com.foo", "leaf", "1.0");
        upstream.pom("org.example", "pruning-bom", "1.0", """
                <project>
                  <groupId>org.example</groupId><artifactId>pruning-bom</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                      <exclusions><exclusion><groupId>com.foo</groupId><artifactId>deep</artifactId></exclusion></exclusions>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        Dependency bom = Dependency.of("pruning-bom", "org.example:pruning-bom", VersionSelector.parse("=1.0"));
        Dependency managedLeaf = Dependency.platformManaged("leaf", "com.foo:leaf");
        Dependency pinnedLeaf = new Dependency("com.foo:leaf", VersionSelector.parse("=1.0"));
        JkBuild app = manifest("app", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(managedLeaf)));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(pinnedLeaf)));

        Lockfile lock = lockWorkspace(tempDir, Map.of(), List.of(app, lib));

        String deepRef = "com.foo:deep:jar:@1.0";
        Lockfile.Artifact workspace = rows(lock, "com.foo:leaf:jar:").stream()
                .filter(r -> !r.isPartition())
                .findFirst()
                .orElseThrow();
        assertThat(workspace.version()).isEqualTo("1.0");
        assertThat(workspace.deps())
                .as("the workspace's row keeps the edge the BOM excludes")
                .contains(deepRef);
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::deps)
                .containsExactly(List.of(deepRef));
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::deps, Lockfile.Artifact::members)
                .containsExactly(tuple("1.0", List.of(), List.of("app")));
    }

    /**
     * Two members declare middle at one version; the later one excludes deep, a grandchild of
     * middle. The merged manifest carries the first declaration, so the workspace's rows keep the
     * edge onto deep; the later member reads a leaf row of its own without it, its lock row naming
     * the member's own {@code exclude}, and the first member reads the workspace's rows.
     */
    @Test
    void a_later_members_root_exclude_reaches_it_through_a_row_of_its_own(@TempDir Path tempDir) throws Exception {
        upstream.leaf("com.foo", "deep", "1.0");
        upstream.metadata("com.foo", "leaf", "1.0");
        upstream.pom("com.foo", "leaf", "1.0", depending("leaf", "deep", "1.0"));
        upstream.jar("com.foo", "leaf", "1.0");
        upstream.metadata("com.foo", "middle", "1.0");
        upstream.pom("com.foo", "middle", "1.0", depending("middle", "leaf", "1.0"));
        upstream.jar("com.foo", "middle", "1.0");
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        Dependency middleWithoutDeep = middle.withExclusions(List.of("com.foo:deep"));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));
        JkBuild app = manifest("app", Map.of(Scope.MAIN, List.of(middleWithoutDeep)));
        List<String> notes = new ArrayList<>();

        Lockfile lock = lockWorkspace(tempDir, Map.of(), List.of(lib, app), notes);

        String deepRef = "com.foo:deep:jar:@1.0";
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::deps)
                .containsExactly(List.of(deepRef));
        List<Lockfile.Artifact> appLeaf = rows(lock.forMember("app"), "com.foo:leaf:jar:");
        assertThat(appLeaf)
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::deps, Lockfile.Artifact::members)
                .containsExactly(tuple("1.0", List.of(), List.of("app")));
        assertThat(appLeaf.getFirst().excludedBy())
                .singleElement()
                .asString()
                .isEqualTo("com.foo:deep <- jk.toml:middle");
        assertThat(rows(lock, "com.foo:middle:jar:")).hasSize(1).allMatch(r -> !r.isPartition());
        assertThat(notes)
                .contains(
                        "app reads its own rows for 1 coordinate: com.foo:leaf 1.0 (the workspace's without com.foo:deep)");
    }

    @Test
    void two_members_pinning_one_coordinate_differently_each_read_their_own(@TempDir Path tempDir) throws Exception {
        upstream.metadata("com.foo", "widget", "1.0", "2.0");
        for (String v : List.of("1.0", "2.0")) {
            upstream.pom("com.foo", "widget", v, leafPom("widget", v));
            upstream.jar("com.foo", "widget", v);
        }
        Dependency widgetOld = new Dependency("com.foo:widget", VersionSelector.parse("=1.0"));
        Dependency widgetNew = new Dependency("com.foo:widget", VersionSelector.parse("=2.0"));
        JkBuild legacy = manifest("legacy", Map.of(Scope.PROVIDED, List.of(widgetOld)));
        JkBuild current = manifest("current", Map.of(Scope.MAIN, List.of(widgetNew)));
        // The merged manifest carries the first declaration, as WorkspaceMerge does.
        JkBuild merged = manifest("root", Map.of(Scope.PROVIDED, List.of(widgetOld)));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(PinPolicy.NEAREST)
                .withMembers(List.of(
                        new LockOrchestrator.Member("legacy", legacy), new LockOrchestrator.Member("current", current)))
                .lock(merged, "test");

        List<Lockfile.Artifact> widget = rows(lock, "com.foo:widget:jar:");
        assertThat(widget).extracting(Lockfile.Artifact::version).containsExactlyInAnyOrder("1.0", "2.0");
        Lockfile.Artifact partition = widget.stream()
                .filter(Lockfile.Artifact::isPartition)
                .findFirst()
                .orElseThrow();
        assertThat(partition.version()).isEqualTo("2.0");
        assertThat(partition.members()).containsExactly("current");
        assertThat(partition.scopes()).containsExactly(Scope.MAIN);
        assertThat(rows(lock.forMember("current"), "com.foo:widget:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
        assertThat(rows(lock.forMember("legacy"), "com.foo:widget:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
    }

    /**
     * Each flagged member costs a solve of its own, so the pass says which member it is on and how
     * many there are; a member that agrees with the workspace needs no solve and no label.
     */
    @Test
    void the_member_pass_labels_each_solve_with_its_member_and_the_count(@TempDir Path tempDir) throws Exception {
        upstream.metadata("com.foo", "widget", "1.0", "2.0");
        for (String v : List.of("1.0", "2.0")) {
            upstream.pom("com.foo", "widget", v, leafPom("widget", v));
            upstream.jar("com.foo", "widget", v);
        }
        Dependency widgetOld = new Dependency("com.foo:widget", VersionSelector.parse("=1.0"));
        Dependency widgetNew = new Dependency("com.foo:widget", VersionSelector.parse("=2.0"));
        JkBuild legacy = manifest("legacy", Map.of(Scope.PROVIDED, List.of(widgetOld)));
        JkBuild current = manifest("current", Map.of(Scope.MAIN, List.of(widgetNew)));
        JkBuild merged = manifest("root", Map.of(Scope.PROVIDED, List.of(widgetOld)));
        List<String> phases = new ArrayList<>();
        ResolveObserver observer = new ResolveObserver() {
            @Override
            public void onTotal(int total) {}

            @Override
            public void onPackage(String module, String version) {}

            @Override
            public void onPhase(String label) {
                phases.add(label);
            }
        };

        new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(PinPolicy.NEAREST)
                .withMembers(List.of(
                        new LockOrchestrator.Member("legacy", legacy), new LockOrchestrator.Member("current", current)))
                .lock(merged, "test", List.of(), true, observer);

        // legacy's provided pin is the workspace's row already: one member is flagged, one solve is labelled.
        assertThat(phases)
                .contains("Solving members on their own… 1 of 1: current")
                .noneMatch(label -> label.contains("legacy"));
    }

    /**
     * One member holds a framework table whose BOM manages Jupiter at the version its versionless
     * test starter declares and a widget below its latest release; two plain members hold no
     * platform table, one asks for the widget at {@code latest} and none declares a test dependency.
     * The BOM moves no plain row: the widget is the latest the floating selector asks for, Jupiter is
     * the starter's declaration, and the holder's table agrees with that row, so every member reads
     * the workspace's rows and the lock carries no {@code members} key; the Jupiter row names the
     * BOM that agrees with it.
     */
    @Test
    void a_member_without_a_platform_table_reads_the_workspaces_rows(@TempDir Path tempDir) throws Exception {
        upstream.metadata("org.junit.jupiter", "junit-jupiter", "6.0.3", "6.1.0");
        upstream.pom(
                "org.junit.jupiter",
                "junit-jupiter",
                "6.0.3",
                MavenStub.emptyPom("org.junit.jupiter", "junit-jupiter", "6.0.3"));
        upstream.metadata("com.foo", "widget", "1.0", "2.0");
        for (String v : List.of("1.0", "2.0")) upstream.pom("com.foo", "widget", v, leafPom("widget", v));
        upstream.metadata("com.foo", "starter-test", "1.0");
        upstream.pom("com.foo", "starter-test", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>starter-test</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>6.0.3</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.pom(
                "org.example",
                "boot-bom",
                "1.0",
                MavenStub.bom(
                        "org.example",
                        "boot-bom",
                        "1.0",
                        List.of(
                                "org.junit.jupiter:junit-jupiter:6.0.3",
                                "com.foo:widget:1.0",
                                "com.foo:starter-test:1.0")));
        Dependency implied =
                new Dependency("org.example:boot-bom", VersionSelector.parse("=1.0")).withImpliedBy("spring-boot");
        Dependency starterTest = Dependency.platformManaged("starter-test", "com.foo:starter-test");
        Dependency widgetLatest = new Dependency("com.foo:widget", VersionSelector.parse("latest"));
        JkBuild web = manifest("web", Map.of(Scope.PLATFORM, List.of(implied), Scope.TEST, List.of(starterTest)));
        JkBuild domain = manifest("domain", Map.of());
        JkBuild service = manifest("service", Map.of(Scope.MAIN, List.of(widgetLatest)));
        JkBuild merged = manifest(
                "root",
                Map.of(
                        Scope.PLATFORM,
                        List.of(implied),
                        Scope.MAIN,
                        List.of(widgetLatest),
                        Scope.TEST,
                        List.of(starterTest)));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withMembers(List.of(
                        new LockOrchestrator.Member("domain", domain),
                        new LockOrchestrator.Member("service", service),
                        new LockOrchestrator.Member("web", web)))
                .lock(merged, "test");

        assertThat(lock.artifacts()).allMatch(r -> !r.isPartition());
        assertThat(rows(lock, "org.junit.jupiter:junit-jupiter:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy)
                .containsExactly(tuple("6.0.3", "org.example:boot-bom:1.0"));
        assertThat(rows(lock, "com.foo:starter-test:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy)
                .containsExactly(tuple("1.0", "org.example:boot-bom:1.0"));
        assertThat(rows(lock, "com.foo:widget:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
        for (String member : List.of("domain", "service", "web")) {
            assertThat(rows(lock.forMember(member), "org.junit.jupiter:junit-jupiter:jar:"))
                    .extracting(Lockfile.Artifact::version)
                    .containsExactly("6.0.3");
        }
    }

    /**
     * The root's requested features reach a member only where the member declares them: a {@code
     * --features} name a member lacks is not that member's to activate, so the member's own solve
     * runs without it instead of refusing the lock as an unknown feature.
     */
    @Test
    void a_requested_feature_a_member_lacks_is_ignored_for_that_member(@TempDir Path tempDir) throws Exception {
        serveMiddleOverLeaf();
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        Dependency leafOn = Dependency.of("leaf", "com.foo:leaf", VersionSelector.parse("=2.0"))
                .withOptional(true);
        Features extra = new Features(Map.of("extra", new Feature("extra", List.of("leaf"), List.of())), List.of());
        JkBuild app = withFeatures(manifest("app", Map.of(Scope.MAIN, List.of(middle, leafOn))), extra);
        JkBuild lib = manifest(
                "lib",
                Map.of(Scope.MAIN, List.of(middle, new Dependency("com.foo:leaf", VersionSelector.parse("=1.0")))));
        JkBuild merged = withFeatures(manifest("root", Map.of(Scope.MAIN, List.of(middle, leafOn))), extra);

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withMembers(List.of(new LockOrchestrator.Member("app", app), new LockOrchestrator.Member("lib", lib)))
                .lock(merged, "test", List.of("extra"), true);

        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::members)
                .containsExactlyInAnyOrder(tuple("2.0", List.of()), tuple("1.0", List.of("lib")));
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
    }

    /**
     * A member row agrees with the workspace only where a merged row at that version carries the
     * member row's scope group: {@code lib} pins leaf 3.0 for its main classpath while the workspace
     * holds 3.0 as a test-only dual under a main row at 2.0, so {@code lib} gets a main-scoped row
     * of its own instead of reading the workspace's 2.0.
     */
    @Test
    void a_member_row_matching_only_a_test_dual_is_a_partition(@TempDir Path tempDir) throws Exception {
        upstream.metadata("com.foo", "leaf", "1.0", "2.0", "3.0");
        for (String v : List.of("1.0", "2.0", "3.0")) {
            upstream.pom("com.foo", "leaf", v, leafPom("leaf", v));
            upstream.jar("com.foo", "leaf", v);
        }
        upstream.metadata("com.foo", "middle", "1.0");
        upstream.pom("com.foo", "middle", "1.0", depending("middle", "leaf", "[1.0,2.0]"));
        upstream.jar("com.foo", "middle", "1.0");
        upstream.metadata("com.foo", "tester", "1.0");
        upstream.pom("com.foo", "tester", "1.0", depending("tester", "leaf", "3.0"));
        upstream.jar("com.foo", "tester", "1.0");
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        Dependency tester = new Dependency("com.foo:tester", VersionSelector.parse("=1.0"));
        JkBuild app = manifest("app", Map.of(Scope.MAIN, List.of(middle), Scope.TEST, List.of(tester)));
        JkBuild lib = manifest(
                "lib", Map.of(Scope.MAIN, List.of(new Dependency("com.foo:leaf", VersionSelector.parse("=3.0")))));
        JkBuild merged = manifest("root", Map.of(Scope.MAIN, List.of(middle), Scope.TEST, List.of(tester)));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withMembers(List.of(new LockOrchestrator.Member("app", app), new LockOrchestrator.Member("lib", lib)))
                .lock(merged, "test");

        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::scopes, Lockfile.Artifact::members)
                .containsExactlyInAnyOrder(
                        tuple("2.0", List.of(Scope.MAIN), List.of()),
                        tuple("3.0", List.of(Scope.TEST), List.of()),
                        tuple("3.0", List.of(Scope.MAIN), List.of("lib")));
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::scopes)
                .containsExactly(tuple("3.0", List.of(Scope.MAIN)));
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactlyInAnyOrder("2.0", "3.0");
    }

    /**
     * The jk-quarkus shape: one of three members holds a BOM that manages thirty transitives at a
     * version above the one every member's graph declares. The workspace's rows are the rows the same
     * workspace locks to without the BOM, and the holder alone reads rows of its own at the BOM's
     * versions.
     */
    @Test
    void a_bom_one_member_holds_leaves_the_plain_rows_and_gives_the_holder_its_own(@TempDir Path tempDir)
            throws Exception {
        List<String> managed = new ArrayList<>();
        StringBuilder edges = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            String artifact = "lib" + i;
            upstream.metadata("com.foo", artifact, "1.0", "2.0");
            for (String v : List.of("1.0", "2.0")) {
                upstream.pom("com.foo", artifact, v, leafPom(artifact, v));
                upstream.jar("com.foo", artifact, v);
            }
            managed.add("com.foo:" + artifact + ":2.0");
            edges.append("<dependency><groupId>com.foo</groupId><artifactId>")
                    .append(artifact)
                    .append("</artifactId><version>1.0</version></dependency>");
        }
        upstream.metadata("com.foo", "hub", "1.0");
        upstream.pom(
                "com.foo",
                "hub",
                "1.0",
                "<project><groupId>com.foo</groupId><artifactId>hub</artifactId><version>1.0</version>"
                        + "<dependencies>" + edges + "</dependencies></project>");
        upstream.jar("com.foo", "hub", "1.0");
        upstream.pom("org.example", "the-bom", "1.0", MavenStub.bom("org.example", "the-bom", "1.0", managed));
        Dependency bom = Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"));
        Dependency hub = new Dependency("com.foo:hub", VersionSelector.parse("=1.0"));
        JkBuild cli = manifest("cli", Map.of(Scope.MAIN, List.of(hub)));
        JkBuild engine = manifest("engine", Map.of(Scope.MAIN, List.of(hub)));
        JkBuild plainQuarkus = manifest("quarkus", Map.of(Scope.MAIN, List.of(hub)));
        JkBuild quarkus = manifest("quarkus", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(hub)));

        Lockfile without = lockWorkspace(tempDir, Map.of(), List.of(plainQuarkus, cli, engine));
        Lockfile with = lockWorkspace(tempDir, Map.of(), List.of(quarkus, cli, engine));

        assertThat(plainRows(with)).containsExactlyInAnyOrderElementsOf(plainRows(without));
        List<Lockfile.Artifact> partitions =
                with.artifacts().stream().filter(Lockfile.Artifact::isPartition).toList();
        assertThat(partitions).hasSize(30).allSatisfy(row -> {
            assertThat(row.members()).containsExactly("quarkus");
            assertThat(row.version()).isEqualTo("2.0");
            assertThat(row.pinnedBy()).isEqualTo("org.example:the-bom:1.0");
        });
        assertThat(rows(with.forMember("cli"), "com.foo:lib7:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
        assertThat(rows(with.forMember("quarkus"), "com.foo:lib7:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
    }

    /**
     * A member that depends on the holder folds the holder's BOM into its own table, so it reads the
     * holder's rows; a member that does not depend on it reads the workspace's.
     */
    @Test
    void a_members_bom_reaches_the_members_that_depend_on_it_and_no_other(@TempDir Path tempDir) throws Exception {
        serveMiddleOverLeaf();
        Dependency bom = Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"));
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild quarkus = manifest("quarkus", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(middle)));
        JkBuild app = manifest("app", Map.of(Scope.MAIN, List.of(Dependency.workspace("quarkus"))));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));

        Lockfile lock = lockWorkspace(tempDir, Map.of(), List.of(quarkus, app, lib));

        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy, Lockfile.Artifact::members)
                .containsExactlyInAnyOrder(
                        tuple("1.0", null, List.of()),
                        tuple("2.0", "org.example:the-bom:1.0", List.of("app", "quarkus")));
        assertThat(rows(lock, "com.foo:middle:jar:")).hasSize(1).allMatch(r -> !r.isPartition());
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
    }

    /**
     * A versionless dependency a member declares under its own BOM takes the BOM's version as the
     * workspace's row, as an exact pin the member wrote would; a sibling whose graph declares the
     * module below that line reads a row of its own.
     */
    @Test
    void a_versionless_root_under_a_members_own_bom_is_the_workspaces_row(@TempDir Path tempDir) throws Exception {
        serveMiddleOverLeaf();
        Dependency bom = Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"));
        Dependency leaf = Dependency.platformManaged("leaf", "com.foo:leaf");
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild quarkus = manifest("quarkus", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(leaf)));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));

        Lockfile lock = lockWorkspace(tempDir, Map.of(), List.of(quarkus, lib));

        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy, Lockfile.Artifact::members)
                .containsExactlyInAnyOrder(
                        tuple("2.0", "org.example:the-bom:1.0", List.of()), tuple("1.0", null, List.of("lib")));
    }

    /** A BOM the root holds, or one every member holds, is the workspace's constraint: one row, no partition. */
    @Test
    void a_bom_the_root_or_every_member_holds_is_the_workspaces_constraint(@TempDir Path tempDir) throws Exception {
        serveMiddleOverLeaf();
        Dependency bom = Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"));
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild plainApp = manifest("app", Map.of(Scope.MAIN, List.of(middle)));
        JkBuild plainLib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));
        JkBuild app = manifest("app", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(middle)));
        JkBuild lib = manifest("lib", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(middle)));

        Lockfile underRoot = lockWorkspace(tempDir, Map.of(Scope.PLATFORM, List.of(bom)), List.of(plainApp, plainLib));
        Lockfile underEvery = lockWorkspace(tempDir, Map.of(), List.of(app, lib));

        for (Lockfile lock : List.of(underRoot, underEvery)) {
            assertThat(lock.artifacts()).allMatch(r -> !r.isPartition());
            assertThat(rows(lock, "com.foo:leaf:jar:"))
                    .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy)
                    .containsExactly(tuple("2.0", "org.example:the-bom:1.0"));
        }
    }

    /** The workspace locked as the pipeline locks it: the merged manifest, with every member behind it. */
    private Lockfile lockWorkspace(Path tempDir, Map<Scope, List<Dependency>> rootDeps, List<JkBuild> modules)
            throws Exception {
        return lockWorkspace(tempDir, rootDeps, modules, new ArrayList<>());
    }

    /** {@link #lockWorkspace(Path, Map, List)}, recording the lock's notes into {@code notes}. */
    private Lockfile lockWorkspace(
            Path tempDir, Map<Scope, List<Dependency>> rootDeps, List<JkBuild> modules, List<String> notes)
            throws Exception {
        List<String> names = modules.stream().map(m -> m.project().name()).toList();
        EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
        copy.putAll(rootDeps);
        JkBuild root = JkBuild.builder(new Project("com.example", "root", "0.1.0", 25))
                .dependencies(new JkBuild.Dependencies(copy))
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

    /** Every row without a {@code members} key as (package, version, scopes, pinned-by). */
    private static List<String> plainRows(Lockfile lock) {
        return lock.artifacts().stream()
                .filter(r -> !r.isPartition())
                .map(r -> r.packageKey() + "@" + r.version() + " " + r.scopes() + " " + r.pinnedBy())
                .collect(Collectors.toList());
    }

    /** {@code the-bom} manages leaf at 2.0; {@code middle} declares leaf 1.0; both leaf releases exist. */
    private void serveMiddleOverLeaf() {
        upstream.pom(
                "org.example",
                "the-bom",
                "1.0",
                MavenStub.bom("org.example", "the-bom", "1.0", List.of("com.foo:leaf:2.0")));
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

    private static String depending(String artifact, String dep, String version) {
        return """
                <project>
                  <groupId>com.foo</groupId><artifactId>%s</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>%s</artifactId><version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(artifact, dep, version);
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

    private static JkBuild withFeatures(JkBuild manifest, Features features) {
        return JkBuild.builder(manifest.project())
                .dependencies(manifest.dependencies())
                .features(features)
                .build();
    }

    private RepoGroup repoGroup(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), cas));
    }
}

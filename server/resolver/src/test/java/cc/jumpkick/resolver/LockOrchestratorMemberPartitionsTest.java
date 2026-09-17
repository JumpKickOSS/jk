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
 * A workspace lock partitions a coordinate per member only where the merged answer cannot be a
 * member's: a BOM one member holds lifts a transitive the other member's graph declares lower, or
 * two members pin one coordinate exactly at different versions. Members that agree share one row.
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

    @Test
    void a_bom_one_member_holds_does_not_lift_the_other_members_transitive(@TempDir Path tempDir) throws Exception {
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
        assertThat(leaf).extracting(Lockfile.Artifact::version).containsExactlyInAnyOrder("2.0", "1.0");
        Lockfile.Artifact workspace =
                leaf.stream().filter(r -> !r.isPartition()).findFirst().orElseThrow();
        assertThat(workspace.version()).isEqualTo("2.0");
        assertThat(workspace.pinnedBy()).isEqualTo("org.example:the-bom:1.0");
        Lockfile.Artifact libs =
                leaf.stream().filter(Lockfile.Artifact::isPartition).findFirst().orElseThrow();
        assertThat(libs.version()).isEqualTo("1.0");
        assertThat(libs.members()).containsExactly("lib");
        assertThat(libs.scopes()).contains(Scope.MAIN);
        // middle is the same for both members: one row, no partition.
        assertThat(rows(lock, "com.foo:middle:jar:")).hasSize(1).allMatch(r -> !r.isPartition());

        // Each member's classpath reads its own row.
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
        assertThat(rows(lock.forMember("other"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
    }

    /**
     * The BOM one member holds lifts a transitive within the line the other member's graph declared
     * it on: {@code middle} declares leaf 1.0 and the BOM manages 1.1. The declaration is a floor 1.1
     * satisfies, so both members read the workspace's row and no partition is written.
     */
    @Test
    void a_compatible_lift_by_a_siblings_bom_is_a_floor_the_member_shares(@TempDir Path tempDir) throws Exception {
        serveMiddleOverLeaf();
        upstream.metadata("com.foo", "leaf", "1.0", "1.1");
        upstream.pom("com.foo", "leaf", "1.1", leafPom("leaf", "1.1"));
        upstream.pom(
                "org.example",
                "the-bom",
                "1.0",
                MavenStub.bom("org.example", "the-bom", "1.0", List.of("com.foo:leaf:1.1")));
        Dependency bom = Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"));
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild app = manifest("app", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(middle)));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));
        JkBuild merged = manifest("root", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(middle)));

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
     * governs that member's rows and reaches no member that never depends on it.
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
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy)
                .containsExactly(tuple("2.0", "org.example:the-bom:1.0"));
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::members)
                .containsExactly(tuple("1.0", List.of("lib")));
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
     * One member holds a framework table whose BOM manages Jupiter and a widget below their latest
     * releases; three plain members hold no platform table, one asks for the widget at {@code latest}
     * and none declares a test dependency, so the runner injects Jupiter at {@code latest} for them. A
     * floating selector is a floor the workspace's row satisfies, so every member reads the BOM's
     * versions and the lock carries no {@code members} key.
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
        assertThat(rows(lock, "com.foo:widget:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
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

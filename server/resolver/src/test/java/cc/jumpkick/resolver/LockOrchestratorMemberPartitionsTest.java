// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
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

    private RepoGroup repoGroup(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), cas));
    }
}

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
 * A workspace main row is read on a member's test classpath too, so a member's test graph that wants
 * the version such a row carries is no reason for a row of the member's own.
 */
class LockOrchestratorMemberTestRowsTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void start() {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    /**
     * A member whose test graph alone wants the version a workspace main row carries reads that row:
     * the test classpath is the main classpath plus the test rows, so {@code current}, solved on its
     * own for its widget pin and carrying leaf from a sibling only its tests depend on, gets no leaf
     * row of its own.
     */
    @Test
    void a_member_test_row_at_a_workspace_main_rows_version_is_the_workspaces_row(@TempDir Path tempDir)
            throws Exception {
        upstream.metadata("com.foo", "widget", "1.0", "2.0");
        for (String v : List.of("1.0", "2.0")) {
            upstream.pom("com.foo", "widget", v, leafPom("widget", v));
            upstream.jar("com.foo", "widget", v);
        }
        upstream.metadata("com.foo", "leaf", "1.0");
        upstream.pom("com.foo", "leaf", "1.0", leafPom("leaf", "1.0"));
        upstream.jar("com.foo", "leaf", "1.0");
        Dependency widgetOld = new Dependency("com.foo:widget", VersionSelector.parse("=1.0"));
        Dependency leaf = new Dependency("com.foo:leaf", VersionSelector.parse("1.0"));
        JkBuild fixtures = manifest("fixtures", Map.of(Scope.MAIN, List.of(widgetOld, leaf)));
        JkBuild current = manifest(
                "current",
                Map.of(
                        Scope.MAIN,
                        List.of(new Dependency("com.foo:widget", VersionSelector.parse("=2.0"))),
                        Scope.TEST,
                        List.of(leaf)));
        JkBuild merged = manifest("root", Map.of(Scope.MAIN, List.of(widgetOld, leaf)));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withMembers(List.of(
                        new LockOrchestrator.Member("fixtures", fixtures),
                        new LockOrchestrator.Member("current", current)))
                .lock(merged, "test");

        assertThat(rows(lock, "com.foo:widget:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::members)
                .containsExactlyInAnyOrder(tuple("1.0", List.of()), tuple("2.0", List.of("current")));
        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::scopes, Lockfile.Artifact::members)
                .containsExactly(tuple("1.0", List.of(Scope.MAIN), List.of()));
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

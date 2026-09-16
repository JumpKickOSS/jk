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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code -SNAPSHOT} dependency resolves from a repository that serves snapshots, or the refusal
 * names the repositories that were asked and their policy.
 */
class LockSnapshotPolicyTest {

    @RegisterExtension
    final LoopbackHttp central = new LoopbackHttp().concurrent();

    @RegisterExtension
    final LoopbackHttp snapshots = new LoopbackHttp().concurrent();

    @BeforeEach
    void publish() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        new MavenStub(central)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0")
                .metadata("org.questdb", "questdb-client", "1.3.9", "1.3.8")
                .pom(
                        "org.questdb",
                        "questdb-client",
                        "1.3.9",
                        MavenStub.emptyPom("org.questdb", "questdb-client", "1.3.9"));
        new MavenStub(snapshots)
                .leaf("org.questdb", "questdb-client", "1.3.10-SNAPSHOT")
                .metadata("org.junit.platform", "junit-platform-launcher", "6.2.0-SNAPSHOT");
    }

    @Test
    void a_snapshot_no_repository_serves_is_refused_naming_the_repositories_and_their_policy(@TempDir Path dir) {
        RepoGroup repos = RepoGroup.of(centralRepo(dir));
        assertThatThrownBy(() -> new LockOrchestrator(repos)
                        .lock(project("org.questdb:questdb-client", "1.3.10-SNAPSHOT"), "test"))
                .hasMessageContaining("No versions of org.questdb:questdb-client match 1.3.10-SNAPSHOT")
                .hasMessageContaining("1.3.10-SNAPSHOT is a snapshot, and no repository org.questdb:questdb-client may"
                        + " resolve from serves snapshots: central (releases only)");
    }

    @Test
    void a_snapshot_resolves_from_the_declared_repository_that_serves_snapshots(@TempDir Path dir) throws Exception {
        RepoGroup repos = new RepoGroup(List.of(centralRepo(dir), snapshotsRepo(dir, true)));
        Lockfile lock =
                new LockOrchestrator(repos).lock(project("org.questdb:questdb-client", "1.3.10-SNAPSHOT"), "test");

        Lockfile.Artifact client = lock.artifacts().stream()
                .filter(a -> a.name().startsWith("org.questdb:questdb-client"))
                .findFirst()
                .orElseThrow();
        assertThat(client.version()).isEqualTo("1.3.10-SNAPSHOT");
        assertThat(client.source()).isEqualTo("snapshots+" + snapshots.baseUrl());
        assertThat(central.requestsFor(MavenStub.path("org.questdb", "questdb-client", "1.3.10-SNAPSHOT", ".pom")))
                .as("a releases-only repository is never asked for the snapshot")
                .isZero();
    }


    private MavenRepo centralRepo(Path dir) {
        return new MavenRepo("central", central.base(), new Http(), new Cas(dir.resolve("cache")))
                .withPolicy(true, false);
    }

    private MavenRepo snapshotsRepo(Path dir, boolean releases) {
        return new MavenRepo("snapshots", snapshots.base(), new Http(), new Cas(dir.resolve("cache")))
                .withPolicy(releases, true);
    }

    private static JkBuild project(String ga, String selector) {
        return build(Scope.MAIN, ga, selector);
    }

    private static JkBuild testProject(String ga, String selector) {
        return build(Scope.TEST, ga, selector);
    }

    private static JkBuild build(Scope scope, String ga, String selector) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.putAll(Map.of(scope, List.of(new Dependency(ga, VersionSelector.parse(selector)))));
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
    }
}

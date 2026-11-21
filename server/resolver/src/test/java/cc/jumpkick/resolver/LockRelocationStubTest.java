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
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A relocation stub publishes a POM and nothing else: the lock carries it as a row without a
 * file, the way it carries a BOM, and the target row holds the bytes.
 */
class LockRelocationStubTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    private MavenStub upstream;

    @BeforeEach
    void publishJunitDefaults() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        upstream = new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    @Test
    void a_relocation_stub_locks_without_a_jar_of_its_own(@TempDir Path dir) throws Exception {
        upstream.metadata("com.foo", "old", "1.0");
        upstream.pomOnly("com.foo", "old", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>old</artifactId><version>1.0</version>
                  <distributionManagement>
                    <relocation><artifactId>new</artifactId></relocation>
                  </distributionManagement>
                </project>
                """);
        upstream.leaf("com.foo", "new", "1.0");
        RepoGroup repos =
                RepoGroup.of(new MavenRepo("maven-stub", http.base(), new Http(), new Cas(dir.resolve("cache"))));

        Lockfile lock = new LockOrchestrator(repos).lock(project("com.foo:old", "=1.0"), "test");

        Lockfile.Artifact stub = lock.artifacts().stream()
                .filter(a -> a.name().startsWith("com.foo:old"))
                .findFirst()
                .orElseThrow();
        Lockfile.Artifact target = lock.artifacts().stream()
                .filter(a -> a.name().startsWith("com.foo:new"))
                .findFirst()
                .orElseThrow();
        assertThat(stub.checksum()).as("the stub stands beside no jar").isNull();
        assertThat(stub.path()).as("the row names the POM it stands for").isEqualTo("old-1.0.pom");
        assertThat(stub.pomOnly()).isTrue();
        assertThat(target.checksum()).as("the target's bytes are pinned").startsWith("sha256:");
        assertThat(http.requestsFor(MavenStub.path("com.foo", "old", "1.0", ".jar")))
                .as("no repository is asked for a jar the stub's POM says does not exist")
                .isZero();
    }

    /** A relocation carries the classifier the edge asked for: the target's classified jar is locked. */
    @Test
    void a_classified_edge_onto_a_relocation_stub_locks_the_targets_classified_jar(@TempDir Path dir) throws Exception {
        upstream.metadata("com.foo", "old", "1.0");
        upstream.pomOnly("com.foo", "old", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>old</artifactId><version>1.0</version>
                  <distributionManagement>
                    <relocation><artifactId>new</artifactId></relocation>
                  </distributionManagement>
                </project>
                """);
        upstream.leaf("com.foo", "new", "1.0");
        upstream.bytes(MavenStub.path("com.foo", "new", "1.0", "-natives.jar"), new byte[] {1, 2, 3});
        RepoGroup repos =
                RepoGroup.of(new MavenRepo("maven-stub", http.base(), new Http(), new Cas(dir.resolve("cache"))));

        Lockfile lock = new LockOrchestrator(repos)
                .lock(
                        project(new Dependency("com.foo:old", VersionSelector.parse("=1.0")).withClassifier("natives")),
                        "test");

        assertThat(lock.artifacts())
                .extracting(Lockfile.Artifact::name)
                .contains("com.foo:new:jar:natives")
                .doesNotContain("com.foo:new:jar:");
    }

    private static JkBuild project(String ga, String selector) {
        return project(new Dependency(ga, VersionSelector.parse(selector)));
    }

    private static JkBuild project(Dependency dep) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.putAll(Map.of(Scope.MAIN, List.of(dep)));
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
    }
}

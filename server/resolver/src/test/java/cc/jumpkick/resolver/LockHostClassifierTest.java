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
import cc.jumpkick.repo.HostClassifiers;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A transitive edge whose classifier a POM spells with {@code ${javafx.platform}} locks this
 * host's artifact, and the lock says so in one note naming the edge and the expression.
 */
class LockHostClassifierTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @Test
    void a_host_spelled_classifier_locks_this_hosts_artifact_with_a_note(@TempDir Path dir) throws Exception {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        String javafx = HostClassifiers.properties().get("javafx.platform");
        new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0")
                .metadata("org.demo", "graphics", "1.0")
                .pom("org.demo", "graphics", "1.0", """
                        <project>
                          <groupId>org.demo</groupId>
                          <artifactId>graphics</artifactId>
                          <version>1.0</version>
                          <dependencies>
                            <dependency>
                              <groupId>org.demo</groupId>
                              <artifactId>graphics</artifactId>
                              <version>1.0</version>
                              <classifier>${javafx.platform}</classifier>
                            </dependency>
                          </dependencies>
                        </project>
                        """)
                .bytes(MavenStub.path("org.demo", "graphics", "1.0", "-" + javafx + ".jar"), new byte[] {0x50, 0x4b});
        Cas cas = new Cas(dir.resolve("cache"));
        RepoGroup repos = RepoGroup.of(new MavenRepo("maven-stub", http.base(), new Http(), cas));
        List<String> notes = new ArrayList<>();
        ResolveObserver observer = new ResolveObserver() {
            @Override
            public void onTotal(int total) {}

            @Override
            public void onPackage(String module, String version) {}

            @Override
            public void onNote(String line) {
                notes.add(line);
            }
        };

        Lockfile lock = new LockOrchestrator(repos)
                .lock(project("org.demo:graphics", "=1.0"), "test", List.of(), true, observer);

        assertThat(lock.artifacts())
                .extracting(Lockfile.Artifact::name)
                .as("the classified row is this host's")
                .contains("org.demo:graphics:jar:" + javafx);
        assertThat(notes).singleElement().satisfies(note -> assertThat(note)
                .startsWith("org.demo:graphics 1.0 depends on org.demo:graphics with classifier `" + javafx + "`")
                .contains("${javafx.platform}")
                .contains("follows the host"));
    }

    private static JkBuild project(String ga, String selector) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.putAll(Map.of(Scope.MAIN, List.of(new Dependency(ga, VersionSelector.parse(selector)))));
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
    }
}

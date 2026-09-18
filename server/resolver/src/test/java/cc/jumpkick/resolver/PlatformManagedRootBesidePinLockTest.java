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
 * A versionless root a BOM manages, declared beside an exact pin on the same coordinate in another
 * table of the manifest — a workspace merge puts one member's {@code managed} row and another's
 * pin side by side — takes the pin's version: a written version is a user root, and a user root
 * beats the BOM for that coordinate wherever the coordinate is declared.
 */
class PlatformManagedRootBesidePinLockTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void publish() {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.metadata("com.foo", "thing", "1.0", "1.1");
        for (String version : List.of("1.0", "1.1")) {
            upstream.pom("com.foo", "thing", version, MavenStub.emptyPom("com.foo", "thing", version));
            upstream.jar("com.foo", "thing", version);
        }
        upstream.pom("org.example", "the-bom", "1.0", """
                <project>
                  <groupId>org.example</groupId><artifactId>the-bom</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.foo</groupId><artifactId>thing</artifactId><version>1.0</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
    }

    @Test
    void a_managed_root_beside_an_exact_pin_in_another_table_takes_the_pins_version(@TempDir Path tempDir)
            throws Exception {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(Dependency.platformManaged("thing", "com.foo:thing")));
        byScope.put(Scope.TEST, List.of(Dependency.of("thing", "com.foo:thing", VersionSelector.parse("=1.1"))));
        byScope.put(
                Scope.PLATFORM,
                List.of(Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"))));
        JkBuild project = new JkBuild(
                new Project("com.example", "test", "0.1.0", 25), new JkBuild.Dependencies(Map.copyOf(byScope)));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");

        assertThat(lock.artifacts())
                .filteredOn(a -> a.packageKey().equals("com.foo:thing:jar:"))
                .extracting(Lockfile.Artifact::version)
                .as("one row, at the pin's version, for main and test alike")
                .containsExactly("1.1");
    }

    private RepoGroup repoGroup(Path dir) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(dir.resolve("cache"))));
    }
}

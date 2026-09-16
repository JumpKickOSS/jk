// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static java.util.Objects.requireNonNull;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The injected Vintage engine declares a {@code junit:junit} of its own; the suite's declared pin is
 * the one the lock keeps. Vintage's POM is hand-written here in the shape Maven Central serves — a
 * bare version, which the resolver reads as a floor — so the mediation is exercised, not bypassed.
 */
class LockTestEnginesTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void start() {
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.metadata("junit", "junit", "4.12", "4.13.2");
        upstream.pom("junit", "junit", "4.12", MavenStub.emptyPom("junit", "junit", "4.12"));
        upstream.pom("junit", "junit", "4.13.2", MavenStub.emptyPom("junit", "junit", "4.13.2"));
        upstream.metadata("org.junit.vintage", "junit-vintage-engine", "6.1.0");
        upstream.pom("org.junit.vintage", "junit-vintage-engine", "6.1.0", """
                <project>
                  <groupId>org.junit.vintage</groupId><artifactId>junit-vintage-engine</artifactId><version>6.1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
    }

    @Test
    void the_declared_junit4_pin_wins_over_the_engines_own_edge(@TempDir Path tempDir) throws Exception {
        JkBuild project = junit4Project("=4.12");

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");

        Map<String, Lockfile.Artifact> byKey = new HashMap<>();
        for (Lockfile.Artifact a : lock.artifacts()) byKey.put(a.packageKey(), a);
        assertThat(requireNonNull(byKey.get("junit:junit:jar:")).version()).isEqualTo("4.12");
        assertThat(byKey)
                .as("the engine is in the lock without being declared")
                .containsKey("org.junit.vintage:junit-vintage-engine:jar:");
        Lockfile.Artifact vintage = requireNonNull(byKey.get("org.junit.vintage:junit-vintage-engine:jar:"));
        assertThat(vintage.version()).isEqualTo("6.1.0");
        assertThat(vintage.scopes()).as("and it is a test-classpath artifact").containsExactly(Scope.TEST);
    }

    @Test
    void a_junit4_pin_the_engine_cannot_run_is_refused_before_any_solve(@TempDir Path tempDir) {
        assertThatThrownBy(() -> new LockOrchestrator(repoGroup(tempDir)).lock(junit4Project("=3.8.2"), "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("raise the pin to 4.13.2");
    }

    private static JkBuild junit4Project(String junitSelector) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.TEST, List.of(new Dependency("junit:junit", VersionSelector.parse(junitSelector))));
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
    }

    private RepoGroup repoGroup(Path tempDir) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tempDir.resolve("cache"))));
    }
}

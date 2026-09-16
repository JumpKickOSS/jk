// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project pins {@code jakarta.inject-api 2.0.1}; its dependency {@code cryptofs 2.10.0} declares
 * {@code 2.0.1.MR}, a floor the pin sits below. Under the default policy that is a conflict the lock
 * refuses; under {@code [resolve] pins = "nearest"} the pin wins as it does under Maven, the lock
 * edge still carries what cryptofs asked for, and the observer hears one override.
 */
class NearestPinsLockTest {

    private static final String INJECT_API = "jakarta.inject:jakarta.inject-api:jar:";
    private static final String CRYPTOFS = "org.cryptomator:cryptofs:jar:";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void publish() {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.metadata("jakarta.inject", "jakarta.inject-api", "2.0.1", "2.0.1.MR");
        for (String v : List.of("2.0.1", "2.0.1.MR")) {
            upstream.pom(
                    "jakarta.inject",
                    "jakarta.inject-api",
                    v,
                    MavenStub.emptyPom("jakarta.inject", "jakarta.inject-api", v));
        }
        upstream.metadata("org.cryptomator", "cryptofs", "2.10.0");
        upstream.pom("org.cryptomator", "cryptofs", "2.10.0", """
                <project>
                  <groupId>org.cryptomator</groupId><artifactId>cryptofs</artifactId><version>2.10.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>jakarta.inject</groupId><artifactId>jakarta.inject-api</artifactId><version>2.0.1.MR</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
    }

    @Test
    void a_hand_written_pin_below_a_transitive_floor_is_refused(@TempDir Path tempDir) {
        assertThatThrownBy(() -> new LockOrchestrator(repoGroup(tempDir)).lock(project(), "test"))
                .isInstanceOf(UnsatisfiableException.class)
                .hasMessageContaining("jakarta.inject-api");
    }

    @Test
    void a_nearest_pin_wins_and_the_lock_says_what_was_asked(@TempDir Path tempDir) throws Exception {
        List<String> overrides = new ArrayList<>();
        ResolveObserver observer = new ResolveObserver() {
            @Override
            public void onTotal(int total) {}

            @Override
            public void onPackage(String module, String version) {}

            @Override
            public void onOverride(String line) {
                overrides.add(line);
            }
        };

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(PinPolicy.NEAREST)
                .lock(project(), "test", List.of(), true, observer);

        Lockfile.Artifact injectApi = row(lock, INJECT_API);
        assertThat(injectApi.version()).isEqualTo("2.0.1");
        Lockfile.Artifact cryptofs = row(lock, CRYPTOFS);
        assertThat(cryptofs.deps()).contains(INJECT_API + "@2.0.1");
        assertThat(cryptofs.declaredFor(INJECT_API + "@2.0.1")).isEqualTo("2.0.1.MR");
        assertThat(overrides).hasSize(1);
        assertThat(overrides.getFirst())
                .contains("jakarta.inject:jakarta.inject-api 2.0.1")
                .contains("org.cryptomator:cryptofs 2.10.0")
                .contains("2.0.1.MR");
    }

    @Test
    void a_pin_a_transitive_already_accepts_is_no_override(@TempDir Path tempDir) throws Exception {
        List<String> overrides = new ArrayList<>();
        ResolveObserver observer = new ResolveObserver() {
            @Override
            public void onTotal(int total) {}

            @Override
            public void onPackage(String module, String version) {}

            @Override
            public void onOverride(String line) {
                overrides.add(line);
            }
        };

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(PinPolicy.NEAREST)
                .lock(project("2.0.1.MR"), "test", List.of(), true, observer);

        assertThat(row(lock, INJECT_API).version()).isEqualTo("2.0.1.MR");
        assertThat(overrides).isEmpty();
    }

    private static JkBuild project() {
        return project("2.0.1");
    }

    private static JkBuild project(String injectApiPin) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                List.of(
                        new Dependency("org.cryptomator:cryptofs", VersionSelector.parse("=2.10.0")),
                        new Dependency(
                                "jakarta.inject:jakarta.inject-api", VersionSelector.parse("=" + injectApiPin))));
        return new JkBuild(new Project("org.cryptomator", "cryptomator", "1.0", 25), new JkBuild.Dependencies(byScope));
    }

    private static Lockfile.Artifact row(Lockfile lock, String packageKey) {
        return lock.artifacts().stream()
                .filter(a -> a.packageKey().equals(packageKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError(packageKey + " is not in the lock: " + lock.artifacts()));
    }

    private RepoGroup repoGroup(Path tempDir) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tempDir.resolve("cache"))));
    }
}

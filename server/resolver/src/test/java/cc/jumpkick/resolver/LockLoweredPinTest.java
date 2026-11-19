// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
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
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pin written lower than the lock it relocks over takes down what only it brought in: every
 * classified row of the pinned artifact and each package the old pin's graph alone held. The
 * family is shaped as OpenJFX publishes it: each module's POM names its own jar under the host
 * classifier ({@code ${javafx.platform}}) and the module below it, and every module ships a
 * four-part release next to the three-part one.
 */
class LockLoweredPinTest {

    private static final String LOW = "27.0.1";
    private static final String HIGH = "27.0.1.1";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    private final String host =
            Objects.requireNonNull(HostClassifiers.properties().get("javafx.platform"));

    private MavenStub upstream;

    @BeforeEach
    void publishTheFamily() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        upstream = new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        module("base", null);
        module("graphics", "base");
        module("controls", "graphics");
        upstream.metadata("org.demo", "chart", "1.0").pom("org.demo", "chart", "1.0", """
                <project>
                  <groupId>org.demo</groupId><artifactId>chart</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>org.demo</groupId><artifactId>graphics</artifactId><version>27.0.1</version></dependency>
                  </dependencies>
                </project>
                """);
    }

    @Test
    void a_lowered_pin_relocks_every_row_of_its_graph_to_the_pin_as_a_fresh_lock_does(@TempDir Path dir)
            throws Exception {
        Lockfile old = new LockOrchestrator(repos(dir.resolve("old"))).lock(project("controls", HIGH), "test");
        assertThat(versions(old)).as("the lock being replaced").allSatisfy((name, v) -> assertThat(v)
                .isEqualTo(HIGH));

        JkBuild lowered = project("controls", LOW);
        Lockfile fresh = new LockOrchestrator(repos(dir.resolve("fresh"))).lock(lowered, "test");
        Lockfile kept = new LockOrchestrator(repos(dir.resolve("kept")))
                .lockConservative(lowered, old, "test", List.of(), true, ResolveObserver.NOOP);
        Lockfile floated = new LockOrchestrator(repos(dir.resolve("floated")))
                .lockFloating(lowered, old, "test", List.of(), true, ResolveObserver.NOOP);

        Map<String, String> expected = new TreeMap<>();
        for (String m : List.of("base", "graphics", "controls")) {
            expected.put("org.demo:" + m + ":jar:", LOW);
            expected.put("org.demo:" + m + ":jar:" + host, LOW);
        }
        assertThat(versions(fresh)).isEqualTo(expected);
        assertThat(versions(kept)).as("keep-pins relock (jk build, jk lock)").isEqualTo(expected);
        assertThat(versions(floated))
                .as("floating relock (jk update, jk lock -F)")
                .isEqualTo(expected);
    }

    /**
     * A package an untouched root also reaches keeps the old lock's version, as every relock keeps
     * it: the lowered pin speaks only for what its own graph alone held.
     */
    @Test
    void a_package_an_untouched_root_also_reaches_keeps_its_locked_version(@TempDir Path dir) throws Exception {
        Lockfile old =
                new LockOrchestrator(repos(dir.resolve("old"))).lock(project("controls", HIGH, "chart", "1.0"), "test");
        assertThat(versions(old)).containsEntry("org.demo:graphics:jar:", HIGH);

        Lockfile floated = new LockOrchestrator(repos(dir.resolve("floated")))
                .lockFloating(
                        project("controls", LOW, "chart", "1.0"), old, "test", List.of(), true, ResolveObserver.NOOP);

        Map<String, String> after = versions(floated);
        assertThat(after)
                .containsEntry("org.demo:controls:jar:", LOW)
                .containsEntry("org.demo:controls:jar:" + host, LOW);
        assertThat(after)
                .as("chart still reaches graphics, so the old lock's floor holds it")
                .containsEntry("org.demo:graphics:jar:", HIGH)
                .containsEntry("org.demo:base:jar:", HIGH);
    }

    /** One module at both releases: its POM names its own host-classified jar and the module below. */
    private void module(String artifact, @Nullable String below) {
        upstream.metadata("org.demo", artifact, HIGH, LOW);
        for (String v : List.of(LOW, HIGH)) {
            String next = below == null
                    ? ""
                    : "<dependency><groupId>org.demo</groupId><artifactId>" + below + "</artifactId><version>" + v
                            + "</version></dependency>";
            upstream.pom("org.demo", artifact, v, """
                            <project>
                              <groupId>org.demo</groupId><artifactId>%s</artifactId><version>%s</version>
                              <dependencies>
                                <dependency><groupId>org.demo</groupId><artifactId>%s</artifactId><version>%s</version><classifier>${javafx.platform}</classifier></dependency>
                                %s
                              </dependencies>
                            </project>
                            """.formatted(artifact, v, artifact, v, next))
                    .bytes(MavenStub.path("org.demo", artifact, v, "-" + host + ".jar"), new byte[] {0x50, 0x4b});
        }
    }

    private RepoGroup repos(Path dir) {
        return RepoGroup.of(new MavenRepo(
                "maven-stub", http.base(), new Http(), new Cas(dir.resolve("cache")), RepoCredential.ANONYMOUS, false));
    }

    /** A project pinning each {@code artifact, version} pair of {@code org.demo} with a bare version. */
    private static JkBuild project(String... pins) {
        List<Dependency> deps = new ArrayList<>();
        for (int i = 0; i < pins.length; i += 2) {
            deps.add(new Dependency("org.demo:" + pins[i], VersionSelector.parse(pins[i + 1])));
        }
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, deps);
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
    }

    /** The {@code org.demo} rows of {@code lock}, name to version, excluding {@code chart}. */
    private static Map<String, String> versions(Lockfile lock) {
        Map<String, String> out = new TreeMap<>();
        for (Lockfile.Artifact a : lock.artifacts()) {
            if (a.name().startsWith("org.demo:") && !a.name().startsWith("org.demo:chart:")) {
                out.put(a.name(), a.version());
            }
        }
        return out;
    }
}

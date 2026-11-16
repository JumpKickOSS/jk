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
 * JUnit ships as one train: Jupiter 5.x.y runs on Platform 1.x.y, Jupiter 6 on Platform 6. A module
 * locked first with the implicit Jupiter (the newest, 6.x) and then declaring {@code junit-jupiter
 * 5.12.2} kept the old lock's Platform engine and commons at 6.x under {@code jk lock}'s keep-pins
 * preferences, as a Maven requirement is a soft one, and every test failed with a NoSuchMethodError.
 * The declared Jupiter decides the train, as it does in a fresh lock.
 */
class LockJupiterTrainTest {

    private static final String SOURCE_PREFIX = "maven-stub+";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    private MavenStub upstream;

    @BeforeEach
    void publishBothTrains() {
        upstream = new MavenStub(http);
        upstream.metadata("org.junit.jupiter", "junit-jupiter", "5.12.2", "6.1.3");
        upstream.metadata("org.junit.jupiter", "junit-jupiter-api", "5.12.2", "6.1.3");
        upstream.metadata("org.junit.jupiter", "junit-jupiter-engine", "5.12.2", "6.1.3");
        upstream.metadata("org.junit.platform", "junit-platform-engine", "1.12.2", "6.1.3");
        upstream.metadata("org.junit.platform", "junit-platform-commons", "1.12.2", "6.1.3");
        upstream.metadata("org.junit.platform", "junit-platform-launcher", "1.12.2", "6.1.3");
        for (String[] train : List.of(new String[] {"5.12.2", "1.12.2"}, new String[] {"6.1.3", "6.1.3"})) {
            String jupiter = train[0];
            String platform = train[1];
            upstream.pom(
                    "org.junit.jupiter",
                    "junit-jupiter",
                    jupiter,
                    pom(
                            "org.junit.jupiter",
                            "junit-jupiter",
                            jupiter,
                            "org.junit.jupiter:junit-jupiter-api:" + jupiter,
                            "org.junit.jupiter:junit-jupiter-engine:" + jupiter));
            upstream.pom(
                    "org.junit.jupiter",
                    "junit-jupiter-api",
                    jupiter,
                    pom(
                            "org.junit.jupiter",
                            "junit-jupiter-api",
                            jupiter,
                            "org.junit.platform:junit-platform-commons:" + platform));
            upstream.pom(
                    "org.junit.jupiter",
                    "junit-jupiter-engine",
                    jupiter,
                    pom(
                            "org.junit.jupiter",
                            "junit-jupiter-engine",
                            jupiter,
                            "org.junit.jupiter:junit-jupiter-api:" + jupiter,
                            "org.junit.platform:junit-platform-engine:" + platform));
            upstream.pom(
                    "org.junit.platform",
                    "junit-platform-engine",
                    platform,
                    pom(
                            "org.junit.platform",
                            "junit-platform-engine",
                            platform,
                            "org.junit.platform:junit-platform-commons:" + platform));
            upstream.pom(
                    "org.junit.platform",
                    "junit-platform-commons",
                    platform,
                    MavenStub.emptyPom("org.junit.platform", "junit-platform-commons", platform));
            upstream.pom(
                    "org.junit.platform",
                    "junit-platform-launcher",
                    platform,
                    pom(
                            "org.junit.platform",
                            "junit-platform-launcher",
                            platform,
                            "org.junit.platform:junit-platform-engine:" + platform));
        }
    }

    @Test
    void a_declared_jupiter_5_relocks_the_platform_a_jupiter_6_lock_held_onto_its_own_train(@TempDir Path dir)
            throws Exception {
        String source = SOURCE_PREFIX + http.base() + "/";
        List<Lockfile.Artifact> sixRows = new ArrayList<>();
        for (String name : List.of(
                "org.junit.jupiter:junit-jupiter:jar:",
                "org.junit.jupiter:junit-jupiter-api:jar:",
                "org.junit.jupiter:junit-jupiter-engine:jar:",
                "org.junit.platform:junit-platform-engine:jar:",
                "org.junit.platform:junit-platform-commons:jar:",
                "org.junit.platform:junit-platform-launcher:jar:")) {
            sixRows.add(new Lockfile.Artifact(
                    name, "6.1.3", source, "sha256:0", null, List.of(Scope.TEST), List.of(), null));
        }
        Lockfile implicitSix =
                new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, sixRows);
        JkBuild declaresFive = project(Map.of(
                Scope.TEST,
                List.of(new Dependency("org.junit.jupiter:junit-jupiter", VersionSelector.parse("5.12.2")))));

        Lockfile relocked = new LockOrchestrator(repos(dir))
                .lockConservative(declaresFive, implicitSix, "test", List.of(), true, ResolveObserver.NOOP);

        assertThat(version(relocked, "org.junit.jupiter:junit-jupiter:jar:")).isEqualTo("5.12.2");
        assertThat(version(relocked, "org.junit.jupiter:junit-jupiter-api:jar:"))
                .isEqualTo("5.12.2");
        assertThat(version(relocked, "org.junit.jupiter:junit-jupiter-engine:jar:"))
                .isEqualTo("5.12.2");
        assertThat(version(relocked, "org.junit.platform:junit-platform-engine:jar:"))
                .as("the Platform engine follows the declared Jupiter, not the kept 6.1.3 pin")
                .isEqualTo("1.12.2");
        assertThat(version(relocked, "org.junit.platform:junit-platform-commons:jar:"))
                .isEqualTo("1.12.2");
        assertThat(version(relocked, "org.junit.platform:junit-platform-launcher:jar:"))
                .isEqualTo("1.12.2");
    }

    @Test
    void a_pin_on_the_declared_train_is_still_kept(@TempDir Path dir) throws Exception {
        String source = SOURCE_PREFIX + http.base() + "/";
        upstream.metadata("org.junit.platform", "junit-platform-commons", "1.12.2", "1.12.3", "6.1.3");
        upstream.pom(
                "org.junit.platform",
                "junit-platform-commons",
                "1.12.3",
                MavenStub.emptyPom("org.junit.platform", "junit-platform-commons", "1.12.3"));
        Lockfile held = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(new Lockfile.Artifact(
                        "org.junit.platform:junit-platform-commons:jar:",
                        "1.12.3",
                        source,
                        "sha256:0",
                        null,
                        List.of(Scope.TEST),
                        List.of(),
                        null)));
        JkBuild declaresFive = project(Map.of(
                Scope.TEST,
                List.of(new Dependency("org.junit.jupiter:junit-jupiter", VersionSelector.parse("5.12.2")))));

        Lockfile relocked = new LockOrchestrator(repos(dir))
                .lockConservative(declaresFive, held, "test", List.of(), true, ResolveObserver.NOOP);

        assertThat(version(relocked, "org.junit.platform:junit-platform-commons:jar:"))
                .as("keep-pins still holds a pin on the declared Jupiter's own train")
                .isEqualTo("1.12.3");
    }

    private static String pom(String group, String artifact, String version, String... deps) {
        StringBuilder out = new StringBuilder("<project><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><version>" + version + "</version><dependencies>");
        for (String dep : deps) {
            String[] gav = dep.split(":");
            out.append("<dependency><groupId>")
                    .append(gav[0])
                    .append("</groupId><artifactId>")
                    .append(gav[1])
                    .append("</artifactId><version>")
                    .append(gav[2])
                    .append("</version></dependency>");
        }
        return out.append("</dependencies></project>").toString();
    }

    private RepoGroup repos(Path dir) {
        return RepoGroup.of(new MavenRepo(
                "maven-stub", http.base(), new Http(), new Cas(dir.resolve("cache")), RepoCredential.ANONYMOUS, false));
    }

    private static JkBuild project(Map<Scope, List<Dependency>> byScope) {
        return new JkBuild(
                new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(new EnumMap<>(byScope)));
    }

    private static String version(Lockfile lock, String name) {
        return lock.artifacts().stream()
                .filter(a -> a.packageKey().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + lock.artifacts()))
                .version();
    }
}

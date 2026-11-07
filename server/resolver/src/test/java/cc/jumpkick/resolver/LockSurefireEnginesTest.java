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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lock settles the JUnit Platform the way Maven Surefire does over the solved test graph: a
 * Jupiter API reached without an engine, declared or brought in by a test library, gets the engine
 * at the API's version, and the launcher rides the resolved Jupiter's Platform line instead of
 * {@code latest}. The JUnit artifacts are served in the shape Central serves them: Jupiter 5.12.2 on
 * Platform 1.12.2, and Jupiter 6.1.0 on Platform 6.1.0 as the newest of each.
 */
class LockSurefireEnginesTest {

    private static final String API = "org.junit.jupiter:junit-jupiter-api:jar:";
    private static final String ENGINE = "org.junit.jupiter:junit-jupiter-engine:jar:";
    private static final String LAUNCHER = "org.junit.platform:junit-platform-launcher:jar:";
    private static final String PLATFORM_ENGINE = "org.junit.platform:junit-platform-engine:jar:";
    private static final String VINTAGE = "org.junit.vintage:junit-vintage-engine:jar:";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void serveJunit() {
        String platform = "org.junit.platform";
        String jupiter = "org.junit.jupiter";
        upstream.metadata(platform, "junit-platform-commons", "1.12.2", "6.1.0");
        upstream.metadata(platform, "junit-platform-engine", "1.12.2", "6.1.0");
        upstream.metadata(platform, "junit-platform-launcher", "1.12.2", "6.1.0");
        upstream.metadata(jupiter, "junit-jupiter-api", "5.12.2", "6.1.0");
        upstream.metadata(jupiter, "junit-jupiter-engine", "5.12.2", "6.1.0");
        upstream.metadata(jupiter, "junit-jupiter", "5.12.2", "6.1.0");
        upstream.metadata("org.junit.vintage", "junit-vintage-engine", "5.12.2", "6.1.0");
        for (String[] line : new String[][] {{"5.12.2", "1.12.2"}, {"6.1.0", "6.1.0"}}) {
            String j = line[0];
            String p = line[1];
            upstream.pom(
                    platform, "junit-platform-commons", p, MavenStub.emptyPom(platform, "junit-platform-commons", p));
            upstream.pom(
                    platform,
                    "junit-platform-engine",
                    p,
                    pom(platform, "junit-platform-engine", p, platform + ":junit-platform-commons:" + p));
            upstream.pom(
                    platform,
                    "junit-platform-launcher",
                    p,
                    pom(platform, "junit-platform-launcher", p, platform + ":junit-platform-engine:" + p));
            upstream.pom(
                    jupiter,
                    "junit-jupiter-api",
                    j,
                    pom(jupiter, "junit-jupiter-api", j, platform + ":junit-platform-commons:" + p));
            upstream.pom(
                    jupiter,
                    "junit-jupiter-engine",
                    j,
                    pom(
                            jupiter,
                            "junit-jupiter-engine",
                            j,
                            jupiter + ":junit-jupiter-api:" + j,
                            platform + ":junit-platform-engine:" + p));
            upstream.pom(
                    jupiter,
                    "junit-jupiter",
                    j,
                    pom(
                            jupiter,
                            "junit-jupiter",
                            j,
                            jupiter + ":junit-jupiter-api:" + j,
                            jupiter + ":junit-jupiter-engine:" + j));
            upstream.pom(
                    "org.junit.vintage",
                    "junit-vintage-engine",
                    j,
                    pom(
                            "org.junit.vintage",
                            "junit-vintage-engine",
                            j,
                            platform + ":junit-platform-engine:" + p,
                            "junit:junit:4.13.2"));
        }
        upstream.leaf("junit", "junit", "4.13.2");
        upstream.metadata("io.helidon.webserver.testing.junit5", "helidon-webserver-testing-junit5", "27.0.1");
        upstream.pom(
                "io.helidon.webserver.testing.junit5",
                "helidon-webserver-testing-junit5",
                "27.0.1",
                pom(
                        "io.helidon.webserver.testing.junit5",
                        "helidon-webserver-testing-junit5",
                        "27.0.1",
                        jupiter + ":junit-jupiter-api:5.12.2"));
        upstream.metadata("org.testcontainers", "testcontainers", "1.21.3");
        upstream.pom(
                "org.testcontainers",
                "testcontainers",
                "1.21.3",
                pom("org.testcontainers", "testcontainers", "1.21.3", "junit:junit:4.13.2"));
        upstream.leaf("com.foo", "truth", "1.0");
    }

    @Test
    void a_declared_jupiter_api_alone_locks_its_engine_and_a_launcher_on_its_line(@TempDir Path tempDir)
            throws Exception {
        Lockfile lock = lock(tempDir, project(test("org.junit.jupiter:junit-jupiter-api", "=5.12.2")));

        assertThat(versions(lock, ENGINE)).containsExactly("5.12.2");
        assertThat(versions(lock, LAUNCHER)).containsExactly("1.12.2");
        assertThat(versions(lock, PLATFORM_ENGINE)).containsExactly("1.12.2");
        assertThat(row(lock, ENGINE).scopes()).containsExactly(Scope.TEST);
    }

    @Test
    void a_jupiter_api_at_latest_locks_the_newest_engine_and_launcher(@TempDir Path tempDir) throws Exception {
        Lockfile lock = lock(tempDir, project(test("org.junit.jupiter:junit-jupiter-api", "latest")));

        assertThat(versions(lock, API)).containsExactly("6.1.0");
        assertThat(versions(lock, ENGINE)).containsExactly("6.1.0");
        assertThat(versions(lock, LAUNCHER)).containsExactly("6.1.0");
    }

    @Test
    void a_jupiter_a_test_library_brings_in_locks_without_a_launcher_pin(@TempDir Path tempDir) throws Exception {
        Lockfile lock = lock(
                tempDir,
                project(test("io.helidon.webserver.testing.junit5:helidon-webserver-testing-junit5", "=27.0.1")));

        assertThat(versions(lock, API)).containsExactly("5.12.2");
        assertThat(versions(lock, ENGINE)).containsExactly("5.12.2");
        assertThat(versions(lock, LAUNCHER))
                .as("Surefire's alignment: the launcher follows the resolved Platform, not latest")
                .containsExactly("1.12.2");
        assertThat(versions(lock, PLATFORM_ENGINE)).containsExactly("1.12.2");
    }

    @Test
    void a_declared_jupiter_keeps_its_line_and_no_test_dependencies_is_the_newest(@TempDir Path tempDir)
            throws Exception {
        Lockfile declared = lock(tempDir, project(test("org.junit.jupiter:junit-jupiter", "=5.12.2")));
        assertThat(versions(declared, LAUNCHER)).containsExactly("1.12.2");
        assertThat(versions(declared, ENGINE)).containsExactly("5.12.2");

        Lockfile none = lock(tempDir, project());
        assertThat(versions(none, "org.junit.jupiter:junit-jupiter:jar:")).containsExactly("6.1.0");
        assertThat(versions(none, LAUNCHER)).containsExactly("6.1.0");
    }

    @Test
    void a_declared_launcher_on_another_line_is_still_refused(@TempDir Path tempDir) {
        JkBuild project = project(
                test("org.junit.jupiter:junit-jupiter-api", "=5.12.2"),
                test("org.junit.platform:junit-platform-launcher", "=6.1.0"));

        assertThatThrownBy(() -> lock(tempDir, project))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("junit-platform-launcher 6.1.0 does not run junit-jupiter 5.12.2")
                .hasMessageContaining("pin the launcher to 1.12.2");
    }

    @Test
    void a_declared_junit4_gets_vintage_and_a_launcher_on_one_platform_line(@TempDir Path tempDir) throws Exception {
        Lockfile alone = lock(tempDir, project(test("junit:junit", "=4.13.2")));
        assertThat(versions(alone, VINTAGE)).containsExactly("6.1.0");
        assertThat(versions(alone, LAUNCHER)).containsExactly("6.1.0");
        assertThat(versions(alone, ENGINE)).isEmpty();

        Lockfile beside = lock(
                tempDir,
                project(
                        test("junit:junit", "=4.13.2"),
                        test("io.helidon.webserver.testing.junit5:helidon-webserver-testing-junit5", "=27.0.1")));
        assertThat(versions(beside, VINTAGE))
                .as("Vintage rides the resolved Jupiter's line")
                .containsExactly("5.12.2");
        assertThat(versions(beside, ENGINE)).containsExactly("5.12.2");
        assertThat(versions(beside, LAUNCHER)).containsExactly("1.12.2");
    }

    @Test
    void a_junit4_only_a_test_library_brings_in_injects_no_engine(@TempDir Path tempDir) throws Exception {
        Lockfile lock = lock(tempDir, project(test("org.testcontainers:testcontainers", "=1.21.3")));

        assertThat(versions(lock, "junit:junit:jar:")).containsExactly("4.13.2");
        assertThat(versions(lock, VINTAGE))
                .as("unlike Surefire: a transitive JUnit 4 is no suite")
                .isEmpty();
        assertThat(versions(lock, ENGINE)).isEmpty();
    }

    @Test
    void a_workspace_member_settles_its_own_line_and_its_jupiter_rows_are_test_rows(@TempDir Path tempDir)
            throws Exception {
        Dependency api = test("org.junit.jupiter:junit-jupiter-api", "=5.12.2");
        Dependency truth = test("com.foo:truth", "=1.0");
        JkBuild app = manifest("app", api);
        JkBuild lib = manifest("lib", truth);
        JkBuild merged = manifest("root", api, truth);

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withMembers(List.of(new LockOrchestrator.Member("app", app), new LockOrchestrator.Member("lib", lib)))
                .lock(merged, "test");

        assertThat(versions(lock.forMember("app"), ENGINE)).containsExactly("5.12.2");
        assertThat(versions(lock.forMember("app"), LAUNCHER)).containsExactly("1.12.2");
        assertThat(row(lock, ENGINE).scopes()).containsExactly(Scope.TEST);
    }

    private Lockfile lock(Path tempDir, JkBuild project) throws Exception {
        return new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");
    }

    private static List<String> versions(Lockfile lock, String key) {
        List<String> out = new ArrayList<>();
        for (Lockfile.Artifact a : lock.artifacts()) if (a.packageKey().equals(key)) out.add(a.version());
        return out;
    }

    private static Lockfile.Artifact row(Lockfile lock, String key) {
        return lock.artifacts().stream()
                .filter(a -> a.packageKey().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static Dependency test(String ga, String selector) {
        return new Dependency(ga, VersionSelector.parse(selector));
    }

    private static JkBuild project(Dependency... testDeps) {
        return manifest("app", testDeps);
    }

    private static JkBuild manifest(String name, Dependency... testDeps) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        if (testDeps.length > 0) byScope.put(Scope.TEST, List.of(testDeps));
        return new JkBuild(new Project("com.example", name, "1.0", 25), new JkBuild.Dependencies(byScope));
    }

    /** A POM for {@code group:artifact:version} depending on each {@code g:a:v} of {@code deps}. */
    private static String pom(String group, String artifact, String version, String... deps) {
        StringBuilder body = new StringBuilder("<project><modelVersion>4.0.0</modelVersion>")
                .append("<groupId>")
                .append(group)
                .append("</groupId>")
                .append("<artifactId>")
                .append(artifact)
                .append("</artifactId>")
                .append("<version>")
                .append(version)
                .append("</version><dependencies>");
        for (String dep : deps) {
            String[] gav = dep.split(":");
            body.append("<dependency><groupId>")
                    .append(gav[0])
                    .append("</groupId><artifactId>")
                    .append(gav[1])
                    .append("</artifactId><version>")
                    .append(gav[2])
                    .append("</version></dependency>");
        }
        return body.append("</dependencies></project>").toString();
    }

    private RepoGroup repoGroup(Path tempDir) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tempDir.resolve("cache"))));
    }
}

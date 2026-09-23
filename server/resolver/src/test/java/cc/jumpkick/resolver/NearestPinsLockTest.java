// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PinPolicy;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.model.WorkspaceMerge;
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
 * edge still carries what cryptofs asked for, and the observer hears one override. The same holds
 * across a workspace, where the pin sits in one member and the floor arrives through another
 * member's dependency as an open range.
 */
class NearestPinsLockTest {

    private static final String INJECT_API = "jakarta.inject:jakarta.inject-api:jar:";
    private static final String CRYPTOFS = "org.cryptomator:cryptofs:jar:";
    private static final String SHIRO_LANG = "org.apache.shiro:shiro-lang:jar:";

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
        upstream.metadata("org.apache.shiro", "shiro-lang", "3.0.0");
        upstream.pom("org.apache.shiro", "shiro-lang", "3.0.0", """
                <project>
                  <groupId>org.apache.shiro</groupId><artifactId>shiro-lang</artifactId><version>3.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>jakarta.inject</groupId><artifactId>jakarta.inject-api</artifactId><version>[2.0.1.MR,)</version>
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
        ResolveObserver observer = recording(overrides);

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(PinPolicy.NEAREST)
                .lock(project(), "test", List.of(), true, observer);

        Lockfile.Artifact injectApi = row(lock, INJECT_API);
        assertThat(injectApi.version()).isEqualTo("2.0.1");
        Lockfile.Artifact cryptofs = row(lock, CRYPTOFS);
        assertThat(cryptofs.deps()).contains(INJECT_API + "@2.0.1");
        assertThat(new EdgeSelectors(repoGroup(tempDir), lock).declared(cryptofs, injectApi))
                .isEqualTo("2.0.1.MR");
        assertThat(overrides).hasSize(1);
        assertThat(overrides.getFirst())
                .contains("jakarta.inject:jakarta.inject-api 2.0.1")
                .contains("org.cryptomator:cryptofs 2.10.0")
                .contains("2.0.1.MR");
    }

    @Test
    void a_pin_a_transitive_already_accepts_is_no_override(@TempDir Path tempDir) throws Exception {
        List<String> overrides = new ArrayList<>();
        ResolveObserver observer = recording(overrides);

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(PinPolicy.NEAREST)
                .lock(project("2.0.1.MR"), "test", List.of(), true, observer);

        assertThat(row(lock, INJECT_API).version()).isEqualTo("2.0.1.MR");
        assertThat(overrides).isEmpty();
    }

    /**
     * The workspace lock is the root's policy applied to every member's roots: the pin one member
     * declares wins over the open floor a sibling's dependency declares, and the sibling's row
     * says what it asked for.
     */
    @Test
    void a_members_pin_wins_over_a_siblings_open_floor_across_the_workspace(@TempDir Path tempDir) throws Exception {
        JkBuild root = JkBuild.builder(new Project("org.neo4j", "parent", "1.0", 25))
                .workspace(new Workspace(List.of("server", "security")))
                .build(BuildBlock.EMPTY.withPinPolicy(PinPolicy.NEAREST))
                .build();
        JkBuild server =
                member("server", new Dependency("jakarta.inject:jakarta.inject-api", VersionSelector.parse("=2.0.1")));
        JkBuild security =
                member("security", new Dependency("org.apache.shiro:shiro-lang", VersionSelector.parse("=3.0.0")));
        JkBuild merged = WorkspaceMerge.merge(root, List.of(server, security));
        List<String> overrides = new ArrayList<>();

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(merged.build().pinPolicy())
                .lock(merged, "test", List.of(), true, recording(overrides));

        assertThat(row(lock, INJECT_API).version()).isEqualTo("2.0.1");
        Lockfile.Artifact shiro = row(lock, SHIRO_LANG);
        assertThat(shiro.deps()).contains(INJECT_API + "@2.0.1");
        assertThat(new EdgeSelectors(repoGroup(tempDir), lock).declared(shiro, row(lock, INJECT_API)))
                .isEqualTo("[2.0.1.MR,)");
        assertThat(overrides).hasSize(1);
        assertThat(overrides.getFirst())
                .contains("jakarta.inject:jakarta.inject-api 2.0.1")
                .contains("org.apache.shiro:shiro-lang 3.0.0")
                .contains("2.0.1.MR");
    }

    /**
     * Every dependency the pin overrides is one line per pinned module, counted and naming each
     * dependency and what it asked for, not a line per (dependency, module) pair.
     */
    @Test
    void the_dependencies_a_pin_overrides_are_summarized_per_module(@TempDir Path tempDir) throws Exception {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                List.of(
                        new Dependency("org.cryptomator:cryptofs", VersionSelector.parse("=2.10.0")),
                        new Dependency("org.apache.shiro:shiro-lang", VersionSelector.parse("=3.0.0")),
                        new Dependency("jakarta.inject:jakarta.inject-api", VersionSelector.parse("=2.0.1"))));
        JkBuild project = new JkBuild(
                new Project("org.cryptomator", "cryptomator", "1.0", 25), new JkBuild.Dependencies(byScope));
        List<String> overrides = new ArrayList<>();

        new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(PinPolicy.NEAREST)
                .lock(project, "test", List.of(), true, recording(overrides));

        assertThat(overrides).hasSize(1);
        assertThat(overrides.getFirst())
                .startsWith("jakarta.inject:jakarta.inject-api 2.0.1 is the project's pin; 2 dependencies asked for"
                        + " other versions: org.apache.shiro:shiro-lang 3.0.0 asked for [2.0.1.MR,+∞),"
                        + " org.cryptomator:cryptofs 2.10.0 asked for 2.0.1.MR — the pin wins")
                .endsWith("as a direct dependency does under Maven");
    }

    /**
     * A test-scope dependency asks for more than a main-scope pin allows. The test classpath is
     * the main classpath plus the test rows, so the pin is the version there too: the test solve
     * takes it for every edge onto the module under both policies, the lock carries one row with
     * both scopes rather than a test row above the pin, and the edge still says what was asked.
     */
    @Test
    void a_main_pin_governs_the_test_solve(@TempDir Path tempDir) throws Exception {
        for (PinPolicy policy : PinPolicy.values()) {
            List<String> overrides = new ArrayList<>();
            EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
            byScope.put(
                    Scope.MAIN,
                    List.of(new Dependency("jakarta.inject:jakarta.inject-api", VersionSelector.parse("=2.0.1"))));
            byScope.put(
                    Scope.TEST, List.of(new Dependency("org.cryptomator:cryptofs", VersionSelector.parse("=2.10.0"))));
            JkBuild project = new JkBuild(
                    new Project("org.cryptomator", "cryptomator", "1.0", 25), new JkBuild.Dependencies(byScope));

            Lockfile lock = new LockOrchestrator(repoGroup(tempDir.resolve(policy.name())))
                    .withPinPolicy(policy)
                    .lock(project, "test", List.of(), true, recording(overrides));

            List<Lockfile.Artifact> injectRows = lock.artifacts().stream()
                    .filter(a -> a.packageKey().equals(INJECT_API))
                    .toList();
            assertThat(injectRows).as(policy.name()).hasSize(1);
            assertThat(injectRows.getFirst().version()).as(policy.name()).isEqualTo("2.0.1");
            assertThat(injectRows.getFirst().scopes()).as(policy.name()).contains(Scope.MAIN, Scope.TEST);
            Lockfile.Artifact cryptofs = row(lock, CRYPTOFS);
            assertThat(cryptofs.deps()).as(policy.name()).contains(INJECT_API + "@2.0.1");
            assertThat(new EdgeSelectors(repoGroup(tempDir.resolve(policy.name())), lock)
                            .declared(cryptofs, injectRows.getFirst()))
                    .as(policy.name())
                    .isEqualTo("2.0.1.MR");
            assertThat(overrides).as(policy.name()).hasSize(1);
            assertThat(overrides.getFirst()).as(policy.name()).contains("org.cryptomator:cryptofs 2.10.0");
        }
    }

    /**
     * A test-scope exact pin on a module the main graph resolves is the main version's to give way
     * to: the test classpath is the main classpath plus the test rows, so main's version is the one
     * there whatever the pin asks. The lock writes one row at main's version for both scopes, and
     * one override line names the displaced pin.
     */
    @Test
    void a_test_pin_under_a_main_row_gives_way_to_it(@TempDir Path tempDir) throws Exception {
        upstream.metadata("com.foo", "leaf", "1.0", "2.0");
        for (String v : List.of("1.0", "2.0")) {
            upstream.pom("com.foo", "leaf", v, MavenStub.emptyPom("com.foo", "leaf", v));
        }
        upstream.metadata("com.foo", "middle", "1.0");
        upstream.pom("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>middle</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>2.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))));
        byScope.put(Scope.TEST, List.of(new Dependency("com.foo:leaf", VersionSelector.parse("=1.0"))));
        JkBuild project = new JkBuild(new Project("com.foo", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
        List<String> overrides = new ArrayList<>();

        Lockfile lock =
                new LockOrchestrator(repoGroup(tempDir)).lock(project, "test", List.of(), true, recording(overrides));

        List<Lockfile.Artifact> leaf = lock.artifacts().stream()
                .filter(a -> a.packageKey().equals("com.foo:leaf:jar:"))
                .toList();
        assertThat(leaf).hasSize(1);
        assertThat(leaf.getFirst().version()).isEqualTo("2.0");
        assertThat(leaf.getFirst().scopes()).contains(Scope.MAIN, Scope.TEST);
        assertThat(overrides).hasSize(1);
        assertThat(overrides.getFirst())
                .contains("com.foo:leaf")
                .contains("1.0")
                .contains("2.0");
    }

    private static ResolveObserver recording(List<String> overrides) {
        return new ResolveObserver() {
            @Override
            public void onTotal(int total) {}

            @Override
            public void onPackage(String module, String version) {}

            @Override
            public void onOverride(String line) {
                overrides.add(line);
            }
        };
    }

    private static JkBuild member(String name, Dependency dependency) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(dependency));
        return new JkBuild(new Project("org.neo4j", name, "1.0", 25), new JkBuild.Dependencies(byScope));
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

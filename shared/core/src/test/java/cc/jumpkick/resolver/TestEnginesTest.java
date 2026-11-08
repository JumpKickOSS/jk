// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The roots jk adds to a test graph are read off the solved graph the way Maven Surefire reads the
 * resolved test classpath: a reached Jupiter API gets its engine, the launcher rides the resolved
 * Jupiter's Platform line, and the walk a lock-row reader runs names the same packages.
 */
class TestEnginesTest {

    private static final String API = key(JupiterLine.API);
    private static final String ENGINE = key(JupiterLine.ENGINE);
    private static final String LAUNCHER = key(JupiterLine.LAUNCHER);
    private static final String PLATFORM_ENGINE = key(JupiterLine.PLATFORM_ENGINE);
    private static final String VINTAGE = key("org.junit.vintage:junit-vintage-engine");

    @Test
    void a_declared_jupiter_api_alone_gets_its_engine_and_a_launcher_on_its_line() {
        JkBuild project = project(Map.of(Scope.TEST, List.of(dep(JupiterLine.API, "5.12.2"))));
        Graph graph = new Graph()
                .edge(API, key("org.junit.platform:junit-platform-commons"))
                .edge(LAUNCHER, PLATFORM_ENGINE)
                .at(JupiterLine.API, "5.12.2")
                .at(JupiterLine.PLATFORM_ENGINE, "6.1.0");

        assertThat(roots(project, graph))
                .containsExactly("org.junit.platform:junit-platform-launcher =1.12.2", JupiterLine.ENGINE + " =5.12.2");
        assertThat(keys(project, graph)).containsExactly(LAUNCHER, ENGINE);
    }

    @Test
    void a_jupiter_a_test_library_brings_in_moves_the_launcher_onto_its_platform_line() {
        JkBuild project = project(Map.of(Scope.TEST, List.of(dep("io.helidon:testing-junit5", "27.0.1"))));
        Graph graph = new Graph()
                .edge(key("io.helidon:testing-junit5"), API)
                .at(JupiterLine.API, "5.12.2")
                .at(JupiterLine.LAUNCHER, "6.1.0");

        assertThat(roots(project, graph))
                .containsExactly("org.junit.platform:junit-platform-launcher =1.12.2", JupiterLine.ENGINE + " =5.12.2");
    }

    @Test
    void an_engine_already_on_the_graph_sets_the_line_and_the_injected_engine_matches_it() {
        JkBuild project = project(Map.of(Scope.TEST, List.of(dep("org.junit.jupiter:junit-jupiter", "5.11.4"))));
        Graph graph = new Graph()
                .edge(key("org.junit.jupiter:junit-jupiter"), API, ENGINE)
                .edge(ENGINE, API)
                .at(JupiterLine.API, "5.11.4")
                .at(JupiterLine.ENGINE, "5.11.4");

        assertThat(roots(project, graph))
                .containsExactly("org.junit.platform:junit-platform-launcher =1.11.4", JupiterLine.ENGINE + " =5.11.4");
    }

    @Test
    void no_test_dependencies_is_jupiter_at_latest_and_a_launcher_on_its_line() {
        JkBuild project = project(Map.of());
        Graph graph = new Graph()
                .edge(key("org.junit.jupiter:junit-jupiter"), API, ENGINE)
                .at(JupiterLine.API, "6.1.0")
                .at(JupiterLine.ENGINE, "6.1.0");

        assertThat(roots(project, graph))
                .containsExactly(
                        "org.junit.platform:junit-platform-launcher =6.1.0",
                        "org.junit.jupiter:junit-jupiter latest",
                        JupiterLine.ENGINE + " =6.1.0");
    }

    @Test
    void a_provided_jupiter_api_with_no_test_dependencies_puts_jupiter_engine_and_launcher_on_its_line() {
        JkBuild project = project(Map.of(Scope.PROVIDED, List.of(dep(JupiterLine.API, "5.12.2"))));
        Graph graph = new Graph()
                .edge(API, key("org.junit.platform:junit-platform-commons"))
                .at(JupiterLine.API, "5.12.2");

        assertThat(seeds(project))
                .as("the first solve already seeds Jupiter on the provided API's line")
                .containsExactly(
                        "org.junit.platform:junit-platform-launcher 1.12.2", "org.junit.jupiter:junit-jupiter 5.12.2");
        assertThat(roots(project, graph))
                .as("Surefire reads provided on the test classpath: the API it finds there sets the line")
                .containsExactly(
                        "org.junit.platform:junit-platform-launcher =1.12.2",
                        "org.junit.jupiter:junit-jupiter =5.12.2",
                        JupiterLine.ENGINE + " =5.12.2");
        assertThat(keys(project, graph)).containsExactly(LAUNCHER, key("org.junit.jupiter:junit-jupiter"), ENGINE);
    }

    @Test
    void a_jupiter_api_a_main_dependency_brings_in_gets_its_engine_beside_declared_test_dependencies() {
        JkBuild project = project(Map.of(
                Scope.MAIN, List.of(dep("io.helidon:testing-junit5", "27.0.1")),
                Scope.TEST, List.of(dep("org.hamcrest:hamcrest", "3.0"))));
        Graph graph = new Graph().edge(key("io.helidon:testing-junit5"), API).at(JupiterLine.API, "5.12.2");

        assertThat(roots(project, graph))
                .containsExactly("org.junit.platform:junit-platform-launcher =1.12.2", JupiterLine.ENGINE + " =5.12.2");
        assertThat(keys(project, graph)).containsExactly(LAUNCHER, ENGINE);
    }

    @Test
    void a_jupiter_engine_the_module_declares_in_main_is_not_injected_again() {
        JkBuild project = project(Map.of(
                Scope.PROVIDED, List.of(dep(JupiterLine.API, "5.12.2"), dep(JupiterLine.ENGINE, "5.12.2")),
                Scope.TEST, List.of(dep("org.hamcrest:hamcrest", "3.0"))));
        Graph graph =
                new Graph().edge(ENGINE, API).at(JupiterLine.API, "5.12.2").at(JupiterLine.ENGINE, "5.12.2");

        assertThat(roots(project, graph)).containsExactly("org.junit.platform:junit-platform-launcher =1.12.2");
        assertThat(keys(project, graph)).containsExactly(LAUNCHER);
    }

    @Test
    void a_declared_launcher_is_the_users_and_nothing_replaces_it() {
        JkBuild project = project(
                Map.of(Scope.TEST, List.of(dep(JupiterLine.API, "5.12.2"), dep(JupiterLine.LAUNCHER, "1.12.2"))));
        Graph graph = new Graph().at(JupiterLine.API, "5.12.2").edge(LAUNCHER).edge(API);

        assertThat(roots(project, graph)).containsExactly(JupiterLine.ENGINE + " =5.12.2");
        assertThat(keys(project, graph)).containsExactly(ENGINE);
    }

    @Test
    void a_declared_junit4_gets_vintage_on_the_resolved_jupiters_line_or_follows_the_platform_without_one() {
        JkBuild junit4 = project(Map.of(Scope.TEST, List.of(dep("junit:junit", "4.13.2"))));
        Graph alone = new Graph().edge(VINTAGE, PLATFORM_ENGINE).at(JupiterLine.PLATFORM_ENGINE, "6.1.0");

        assertThat(roots(junit4, alone))
                .as("no Jupiter: the launcher follows the junit-platform-engine Vintage carries")
                .containsExactly(
                        "org.junit.platform:junit-platform-launcher =6.1.0",
                        "org.junit.vintage:junit-vintage-engine latest");
        assertThat(keys(junit4, alone)).containsExactly(LAUNCHER, VINTAGE);

        JkBuild beside = project(
                Map.of(Scope.TEST, List.of(dep("junit:junit", "4.13.2"), dep("io.helidon:testing-junit5", "27.0.1"))));
        Graph withJupiter = new Graph()
                .edge(key("io.helidon:testing-junit5"), API)
                .at(JupiterLine.API, "5.12.2")
                .at(JupiterLine.PLATFORM_ENGINE, "6.1.0");

        assertThat(roots(beside, withJupiter))
                .containsExactly(
                        "org.junit.platform:junit-platform-launcher =1.12.2",
                        "org.junit.vintage:junit-vintage-engine =5.12.2",
                        JupiterLine.ENGINE + " =5.12.2");
    }

    @Test
    void a_junit4_only_a_test_library_brings_in_injects_no_engine() {
        JkBuild project = project(Map.of(Scope.TEST, List.of(dep("org.testcontainers:testcontainers", "1.21.3"))));
        Graph graph = new Graph().edge(key("org.testcontainers:testcontainers"), key("junit:junit"));

        assertThat(roots(project, graph)).containsExactly("org.junit.platform:junit-platform-launcher latest");
        assertThat(keys(project, graph)).containsExactly(LAUNCHER);
    }

    @Test
    void a_jupiter_api_outside_the_modules_own_reach_brings_no_engine() {
        JkBuild member = project(Map.of(Scope.TEST, List.of(dep("org.testng:testng", "7.12.0"))));
        Graph workspace = new Graph()
                .edge(key("com.example:siblings-lib"), API)
                .edge(key("org.junit.support:testng-engine"), key("org.testng:testng"), PLATFORM_ENGINE)
                .at(JupiterLine.API, "5.12.2");

        assertThat(keys(member, workspace))
                .as("another member's Jupiter is in the rows, not in this member's test graph")
                .containsExactly(LAUNCHER, key("org.junit.support:testng-engine"));
    }

    /** Each injected root as {@code group:artifact selector}. */
    private static List<String> roots(JkBuild project, Graph graph) {
        return TestEngines.injectedRoots(project, graph::reach, graph.versions::get).stream()
                .map(d -> d.module() + " " + d.version().raw())
                .toList();
    }

    /** Each seed root as {@code group:artifact selector}. */
    private static List<String> seeds(JkBuild project) {
        return TestEngines.seedRoots(project).stream()
                .map(d -> d.module() + " " + d.version().raw())
                .toList();
    }

    /** The injected keys a lock-row reader walks to, from the module's test and classpath declarations. */
    private static List<String> keys(JkBuild project, Graph graph) {
        return TestEngines.injectedKeys(
                project,
                TestEngines.declaredTestKeys(project),
                TestEngines.declaredClasspathKeys(project),
                graph::reach);
    }

    private static String key(String ga) {
        return new Dependency(ga, VersionSelector.parse("latest")).packageKey();
    }

    private static Dependency dep(String ga, String version) {
        return new Dependency(ga, VersionSelector.parse("=" + version));
    }

    private static JkBuild project(Map<Scope, List<Dependency>> deps) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.putAll(deps);
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
    }

    /** A solved test graph: package-key edges and resolved versions by {@code group:artifact}. */
    private static final class Graph {
        final Map<String, List<String>> edges = new HashMap<>();
        final Map<String, String> versions = new HashMap<>();

        Graph edge(String from, String... to) {
            edges.put(from, List.of(to));
            return this;
        }

        Graph at(String ga, String version) {
            versions.put(ga, version);
            return this;
        }

        Set<String> reach(Set<String> roots) {
            Set<String> seen = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>(roots);
            while (!queue.isEmpty()) {
                String next = queue.poll();
                if (seen.add(next)) queue.addAll(edges.getOrDefault(next, List.of()));
            }
            return seen;
        }
    }
}

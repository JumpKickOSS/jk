// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code [[contribute.processor-dependency]]}: annotation processors a plugin appends to the
 * compile-main and compile-test processor paths, each a closure the lock pins, gated like the other
 * contributions and left to the module's own declaration of the same processor.
 */
class ProcessorDependencyContributionTest {

    private static final String HEAD = """
            [plugin]
            id = "codegen-fixture"
            table = "codegen-fixture"
            version = "0.1.0"

            [schema]
            version = { type = "string", required = true }

            [[contribute.platform-dependency]]
            coordinate = "io.helidon:helidon-dependencies:${config.version}"

            """;

    private static final PluginDescriptor FIXTURE = PluginDescriptors.parse(HEAD + """
            [[contribute.processor-dependency]]
            coordinate = "io.helidon.codegen:helidon-codegen-apt:${config.version}"

            [[contribute.processor-dependency]]
            coordinate = "io.helidon.service:helidon-service-codegen:${config.version}"
            when = { classpath-has = "io.helidon.service:helidon-service-registry" }

            [[contribute.processor-dependency]]
            coordinate = "io.helidon.bundles:helidon-bundles-apt:${config.version}"
            scope = "main"

            [[contribute.processor-dependency]]
            coordinate = "io.helidon.testing:helidon-testing-apt:${config.version}"
            scope = "test"
            """, "codegen-fixture.toml");

    @BeforeEach
    void install() {
        PluginTableRegistry.putBuiltIn(FIXTURE, null);
    }

    @AfterEach
    void uninstall() {
        PluginTableRegistry.unregister(FIXTURE);
    }

    // ---- parser ------------------------------------------------------------------------------

    @Test
    void each_entry_names_a_coordinate_and_the_compiles_it_joins() {
        assertThat(FIXTURE.contributions().processorDependencies())
                .extracting(
                        PluginDescriptor.ProcessorDependency::coordinate,
                        PluginDescriptor.ProcessorDependency::main,
                        PluginDescriptor.ProcessorDependency::test)
                .containsExactly(
                        tuple("io.helidon.codegen:helidon-codegen-apt:${config.version}", true, true),
                        tuple("io.helidon.service:helidon-service-codegen:${config.version}", true, true),
                        tuple("io.helidon.bundles:helidon-bundles-apt:${config.version}", true, false),
                        tuple("io.helidon.testing:helidon-testing-apt:${config.version}", false, true));
        assertThat(FIXTURE.contributions().processorDependencies().get(1).when())
                .isEqualTo(new PluginDescriptor.Condition.ClasspathHas("io.helidon.service:helidon-service-registry"));
        assertThat(FIXTURE.contributions().isEmpty()).isFalse();
    }

    @Test
    void a_malformed_entry_fails_at_load_naming_it() {
        assertThatThrownBy(() -> PluginDescriptors.parse(HEAD + """
                        [[contribute.processor-dependency]]
                        scope = "main"
                        """, "codegen-fixture.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("codegen-fixture.toml.contribute.processor-dependency.coordinate is required");
        assertThatThrownBy(() -> PluginDescriptors.parse(HEAD + """
                        [[contribute.processor-dependency]]
                        coordinate = "io.helidon.codegen:helidon-codegen-apt"
                        """, "codegen-fixture.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must be \"group:artifact:version\"");
        assertThatThrownBy(() -> PluginDescriptors.parse(HEAD + """
                        [[contribute.processor-dependency]]
                        coordinate = "io.helidon.codegen:helidon-codegen-apt:${config.missing}"
                        """, "codegen-fixture.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> PluginDescriptors.parse(HEAD + """
                        [[contribute.processor-dependency]]
                        coordinate = "io.helidon.codegen:helidon-codegen-apt:${config.version}"
                        scope = "runtime"
                        """, "codegen-fixture.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("scope must be `main`, `test` or `both` — got: runtime");
        assertThatThrownBy(() -> PluginDescriptors.parse(HEAD + """
                        [[contribute.processor-dependency]]
                        coordinate = "io.helidon.codegen:helidon-codegen-apt:${config.version}"

                        [[contribute.processor-dependency]]
                        coordinate = "io.helidon.codegen:helidon-codegen-apt:${config.version}"
                        scope = "test"
                        """, "codegen-fixture.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("is declared twice");
    }

    // ---- evaluation --------------------------------------------------------------------------

    @Test
    void the_coordinates_take_the_locked_platform_version_and_classpath_has_gates() {
        JkBuild build = project("^4", "");
        Map<String, String> pins = Map.of("io.helidon:helidon-dependencies", "4.3.2");

        assertThat(roots(build, pins, Set.of()))
                .as("no service registry on the classpath: its codegen stays out")
                .containsExactly(
                        tuple("io.helidon.codegen:helidon-codegen-apt:4.3.2", true, true),
                        tuple("io.helidon.bundles:helidon-bundles-apt:4.3.2", true, false),
                        tuple("io.helidon.testing:helidon-testing-apt:4.3.2", false, true));
        assertThat(roots(build, pins, Set.of("io.helidon.service:helidon-service-registry")))
                .contains(tuple("io.helidon.service:helidon-service-codegen:4.3.2", true, true));
        assertThat(PluginContributions.processorDependencies(build, null, pins, Set.of()))
                .allSatisfy(dep -> assertThat(dep.closure().isClosure())
                        .as("every contribution is a transitive closure the lock pins")
                        .isTrue());
        assertThat(PluginContributions.processorClosures(build, null, pins, Set.of()))
                .extracting(PluginContributions.StepDep::coordinateSpec)
                .containsExactly(
                        "io.helidon.codegen:helidon-codegen-apt:4.3.2",
                        "io.helidon.bundles:helidon-bundles-apt:4.3.2",
                        "io.helidon.testing:helidon-testing-apt:4.3.2");
    }

    @Test
    void a_module_that_declares_the_processor_itself_keeps_its_own() {
        JkBuild shared = project("4.3.2", """
                [processor-dependencies]
                apt = { group = "io.helidon.codegen", name = "helidon-codegen-apt", version = "4.2.0" }
                """);
        assertThat(PluginContributions.processorDependencies(shared, null, Map.of(), Set.of()))
                .as("[processor-dependencies] covers both compiles: the contribution steps aside")
                .extracting(dep -> dep.closure().artifact())
                .doesNotContain("io.helidon.codegen:helidon-codegen-apt")
                .contains("io.helidon.bundles:helidon-bundles-apt");

        JkBuild testOnly = project("4.3.2", """
                [test-processor-dependencies]
                apt = { group = "io.helidon.codegen", name = "helidon-codegen-apt", version = "4.2.0" }
                """);
        assertThat(roots(testOnly, Map.of(), Set.of()))
                .as("[test-processor-dependencies] covers compile-test alone: main still takes the plugin's")
                .contains(tuple("io.helidon.codegen:helidon-codegen-apt:4.3.2", true, false));
    }

    @Test
    void a_module_without_the_plugins_table_gets_none() {
        JkBuild build = JkBuildParser.parse("""
                name = "plain"
                group = "com.example"
                version = "1.0.0"
                """);
        assertThat(PluginContributions.processorDependencies(build, null, Map.of(), Set.of()))
                .isEmpty();
    }

    private static JkBuild project(String version, String extra) {
        return JkBuildParser.parse("""
                name = "demo"
                group = "com.example"
                version = "1.0.0"

                [codegen-fixture]
                version = "%s"
                """.formatted(version) + extra);
    }

    private static List<Tuple> roots(JkBuild build, Map<String, String> pins, Set<String> classpath) {
        return PluginContributions.processorDependencies(build, null, pins, classpath).stream()
                .map(dep -> tuple(dep.closure().coordinateSpec(), dep.main(), dep.test()))
                .toList();
    }
}

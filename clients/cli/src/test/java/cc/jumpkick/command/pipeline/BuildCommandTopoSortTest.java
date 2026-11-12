// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.BuildGraph;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link BuildGraph#orderModules} ordering, with focus on the {@code [build].order-after}
 * build-order-only edges: they must affect order without being dependencies.
 */
class BuildCommandTopoSortTest {

    private static JkBuild module(String name, String orderAfterToml) {
        return JkBuildParser.parse("""
                group   = "g"
                name    = "%s"
                version = "1.0.0"
                %s
                """.formatted(name, orderAfterToml));
    }

    @Test
    void order_after_forces_a_module_to_build_after_its_named_sibling() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        // Declare "b" first so declaration order alone would put b before a;
        // order-after must override that.
        modules.put(Path.of("b"), module("b", "[build]\norder-after = [\"a\"]"));
        modules.put(Path.of("a"), module("a", ""));

        List<Path> sorted = BuildGraph.orderModules(modules);

        assertThat(sorted.indexOf(Path.of("a"))).as("a builds before b").isLessThan(sorted.indexOf(Path.of("b")));
        assertThat(sorted).containsExactlyInAnyOrder(Path.of("a"), Path.of("b"));
    }

    @Test
    void order_after_resolves_by_group_artifact_coordinate_too() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(Path.of("b"), module("b", "[build]\norder-after = [\"g:a\"]"));
        modules.put(Path.of("a"), module("a", ""));

        List<Path> sorted = BuildGraph.orderModules(modules);

        assertThat(sorted.indexOf(Path.of("a"))).isLessThan(sorted.indexOf(Path.of("b")));
    }

    @Test
    void test_worker_jar_source_is_a_build_order_prerequisite() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        // engine hands a's worker jar to its test JVM → a must build first, with no
        // explicit order-after: a test-plugin-jars entry is a build-order edge of its own.
        modules.put(Path.of("engine"), module("engine", "[build]\ntest-plugin-jars = [\"a\"]"));
        modules.put(Path.of("a"), module("a", ""));

        List<Path> sorted = BuildGraph.orderModules(modules);

        assertThat(sorted.indexOf(Path.of("a")))
                .as("a builds before the module that consumes its worker jar")
                .isLessThan(sorted.indexOf(Path.of("engine")));
    }

    /**
     * A rule pack named for its framework sits beside the worker {@code jk-<short>}: the short name
     * is the worker, whose jar the tests load, never the pack (engine's "helidon" once ordered its
     * tests after cc.jumpkick.guards:helidon and left jk-helidon unbuilt).
     */
    @Test
    void a_test_plugin_jar_short_name_is_the_worker_not_a_pack_that_shares_it() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(Path.of("engine"), module("engine", "[build]\ntest-plugin-jars = [\"helidon\"]"));
        modules.put(Path.of("pack"), module("helidon", ""));
        modules.put(Path.of("worker"), module("jk-helidon", ""));

        assertThat(enginePrereqs(modules)).containsExactly(Path.of("worker"));
    }

    /** A coordinate names exactly that module: a pack's coordinate never widens to the worker. */
    @Test
    void a_test_plugin_jar_coordinate_names_exactly_that_module() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(Path.of("engine"), module("engine", "[build]\ntest-plugin-jars = [\"g:helidon\"]"));
        modules.put(Path.of("pack"), module("helidon", ""));
        modules.put(Path.of("worker"), module("jk-helidon", ""));

        assertThat(enginePrereqs(modules)).containsExactly(Path.of("pack"));
    }

    /** A short name with no worker beside it is the module of that name. */
    @Test
    void a_test_plugin_jar_short_name_without_a_worker_is_the_module_of_that_name() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(Path.of("engine"), module("engine", "[build]\ntest-plugin-jars = [\"helidon\"]"));
        modules.put(Path.of("pack"), module("helidon", ""));

        assertThat(enginePrereqs(modules)).containsExactly(Path.of("pack"));
    }

    private static Set<Path> enginePrereqs(Map<Path, JkBuild> modules) {
        JkBuild engine = Objects.requireNonNull(modules.get(Path.of("engine")));
        return ModuleOrder.modulePrereqs(Path.of("engine"), engine, coords(modules), names(modules));
    }

    private static Map<String, Path> coords(Map<Path, JkBuild> modules) {
        Map<String, Path> out = new LinkedHashMap<>();
        modules.forEach(
                (dir, m) -> out.put(m.project().group() + ":" + m.project().name(), dir));
        return out;
    }

    private static Map<String, Path> names(Map<Path, JkBuild> modules) {
        Map<String, Path> out = new LinkedHashMap<>();
        modules.forEach((dir, m) -> out.put(m.project().name(), dir));
        return out;
    }

    @Test
    void unknown_order_after_name_is_ignored() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(Path.of("a"), module("a", "[build]\norder-after = [\"nope\"]"));

        List<Path> sorted = BuildGraph.orderModules(modules);

        assertThat(sorted).containsExactly(Path.of("a"));
    }

    @Test
    void mutual_order_after_cycle_falls_back_without_dropping_modules() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(Path.of("a"), module("a", "[build]\norder-after = [\"b\"]"));
        modules.put(Path.of("b"), module("b", "[build]\norder-after = [\"a\"]"));

        List<Path> sorted = BuildGraph.orderModules(modules);

        // Cycle → no valid order, but every module still appears (build still tries).
        assertThat(sorted).containsExactlyInAnyOrder(Path.of("a"), Path.of("b"));
    }
}

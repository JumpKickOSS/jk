// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A {@code --class} pattern qualified with its module holds in that module alone. */
class TestSelectionModuleTest {

    @Test
    void the_last_colon_splits_the_module_from_the_class() {
        assertThat(TestSelection.moduleOf("server/engine:cc.jumpkick.EngineIT")).isEqualTo("server/engine");
        assertThat(TestSelection.classOf("server/engine:cc.jumpkick.EngineIT")).isEqualTo("cc.jumpkick.EngineIT");
        assertThat(TestSelection.moduleOf("C:\\ws\\orders:OrdersIT#refund*"))
                .as("a drive letter is part of the module")
                .isEqualTo("C:\\ws\\orders");
        assertThat(TestSelection.classOf("C:\\ws\\orders:OrdersIT#refund*")).isEqualTo("OrdersIT#refund*");
        assertThat(TestSelection.moduleOf("OrdersIT")).isNull();
        assertThat(TestSelection.classOf("OrdersIT")).isEqualTo("OrdersIT");
        assertThat(TestSelection.qualify("/ws/orders", "app:OrdersIT")).isEqualTo("/ws/orders:OrdersIT");
    }

    @Test
    void a_named_module_runs_its_classes_and_the_unqualified_ones(@TempDir Path ws) throws Exception {
        Path orders = Files.createDirectories(ws.resolve("orders"));
        TestSelection selection =
                TestSelection.DEFAULT.withClasses(List.of("orders:OrdersIT", "*SmokeIT", "billing:BillingIT"));

        assertThat(selection.forModule(orders, ws).classes()).containsExactly("OrdersIT", "*SmokeIT");
        assertThat(selection.namesModule(orders, ws)).isTrue();
        assertThat(selection.unqualifiedClasses()).containsExactly("*SmokeIT");
    }

    @Test
    void a_module_no_qualified_pattern_names_runs_its_whole_selection(@TempDir Path ws) throws Exception {
        Path app = Files.createDirectories(ws.resolve("app"));
        TestSelection qualified = TestSelection.DEFAULT.withClasses(List.of("orders:OrdersIT"));

        assertThat(qualified.forModule(app, ws).classes())
                .as("no class pattern holds in app, so its suites run whole")
                .isEmpty();
        assertThat(qualified.namesModule(app, ws)).isFalse();
    }

    @Test
    void an_absolute_module_is_matched_by_its_directory_and_the_result_names_no_module(@TempDir Path ws)
            throws Exception {
        Path orders = Files.createDirectories(ws.resolve("orders"));
        TestSelection sent =
                TestSelection.DEFAULT.withClasses(List.of(TestSelection.qualify(orders.toString(), "OrdersIT")));
        TestSelection plain = TestSelection.DEFAULT.withClasses(List.of("OrdersIT"));

        TestSelection local = sent.forModule(ws.resolve("x/../orders"), Path.of("/elsewhere"));

        assertThat(local)
                .as("the module's suite keys what a plain --class run of it keys")
                .isEqualTo(plain);
        assertThat(local.identityToken()).isEqualTo(plain.identityToken());
        assertThat(local.forModule(orders, ws))
                .as("a module's own selection is its own again")
                .isEqualTo(local);
    }

    @Test
    void unqualified_patterns_are_left_alone(@TempDir Path ws) {
        TestSelection plain = TestSelection.DEFAULT.withClasses(List.of("OrdersIT"));
        assertThat(plain.forModule(ws, ws)).isSameAs(plain);
        assertThat(plain.namesModule(ws, ws)).isFalse();
    }
}

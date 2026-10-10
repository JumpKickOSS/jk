// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The jk command a gutter Run or Debug spawns. */
class JkCommandLinesTest {

    @Test
    void a_test_class_in_a_workspace_module_runs_from_the_root_with_a_module_and_class_filter() {
        assertThat(JkCommandLines.args(JkCommandLines.KIND_TEST, "shared/host", "cc.jumpkick.host.HashingTest", null))
                .isEqualTo(List.of("test", "-m", "shared/host", "--class", "cc.jumpkick.host.HashingTest"));
        assertThat(JkCommandLines.args(JkCommandLines.KIND_TEST, "", "com.acme.OrdersTest", null))
                .isEqualTo(List.of("test", "--class", "com.acme.OrdersTest"));
    }

    @Test
    void a_method_level_gutter_action_selects_one_method_of_the_class() {
        String selector = JkRunConfigurationProducer.selector("com.acme.OrdersTest", "refunds");
        assertThat(selector).isEqualTo("com.acme.OrdersTest#refunds");
        assertThat(JkCommandLines.args(JkCommandLines.KIND_TEST, "app", selector, "localhost:41873"))
                .isEqualTo(List.of(
                        "test", "-m", "app", "--class", "com.acme.OrdersTest#refunds", "--debug-jvm=localhost:41873"));
    }

    @Test
    void debug_adds_the_jdwp_address_jk_listens_on() {
        assertThat(JkCommandLines.args(JkCommandLines.KIND_TEST, "app", "com.acme.OrdersTest", "localhost:41873"))
                .isEqualTo(
                        List.of("test", "-m", "app", "--class", "com.acme.OrdersTest", "--debug-jvm=localhost:41873"));
        assertThat(JkCommandLines.args(JkCommandLines.KIND_RUN, "app", null, "localhost:5005"))
                .isEqualTo(List.of("run", "app", "--debug-jvm=localhost:5005"));
        assertThat(JkCommandLines.args(JkCommandLines.KIND_RUN, "", null, null)).isEqualTo(List.of("run", "."));
    }

    @Test
    void a_free_port_is_in_the_ephemeral_range() throws Exception {
        int port = JkCommandLines.freePort();
        assertThat(port > 1024 && port < 65536).as(String.valueOf(port)).isTrue();
    }

    @Test
    void the_module_is_named_relative_to_the_root() {
        assertThat(JkRunConfigurationProducer.relative("/ws", "/ws/shared/host"))
                .isEqualTo("shared/host");
        assertThat(JkRunConfigurationProducer.relative("/ws", "/ws")).isEqualTo("");
        assertThat(JkRunConfigurationProducer.relative("/ws", "/elsewhere/app")).isEqualTo("");
    }
}

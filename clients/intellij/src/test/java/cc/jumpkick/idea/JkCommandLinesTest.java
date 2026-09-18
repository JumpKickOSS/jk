// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** The jk command a gutter Run or Debug spawns. */
public class JkCommandLinesTest {

    @Test
    public void a_test_class_in_a_workspace_module_runs_from_the_root_with_a_module_and_class_filter() {
        assertEquals(
                List.of("test", "-m", "shared/host", "--class", "cc.jumpkick.host.HashingTest"),
                JkCommandLines.args(JkCommandLines.KIND_TEST, "shared/host", "cc.jumpkick.host.HashingTest", null));
        assertEquals(
                List.of("test", "--class", "com.acme.OrdersTest"),
                JkCommandLines.args(JkCommandLines.KIND_TEST, "", "com.acme.OrdersTest", null));
    }

    @Test
    public void a_method_level_gutter_action_selects_one_method_of_the_class() {
        String selector = JkRunConfigurationProducer.selector("com.acme.OrdersTest", "refunds");
        assertEquals("com.acme.OrdersTest#refunds", selector);
        assertEquals(
                List.of("test", "-m", "app", "--class", "com.acme.OrdersTest#refunds", "--debug-jvm=localhost:41873"),
                JkCommandLines.args(JkCommandLines.KIND_TEST, "app", selector, "localhost:41873"));
    }

    @Test
    public void debug_adds_the_jdwp_address_jk_listens_on() {
        assertEquals(
                List.of("test", "-m", "app", "--class", "com.acme.OrdersTest", "--debug-jvm=localhost:41873"),
                JkCommandLines.args(JkCommandLines.KIND_TEST, "app", "com.acme.OrdersTest", "localhost:41873"));
        assertEquals(
                List.of("run", "app", "--debug-jvm=localhost:5005"),
                JkCommandLines.args(JkCommandLines.KIND_RUN, "app", null, "localhost:5005"));
        assertEquals(List.of("run", "."), JkCommandLines.args(JkCommandLines.KIND_RUN, "", null, null));
    }

    @Test
    public void a_free_port_is_in_the_ephemeral_range() throws Exception {
        int port = JkCommandLines.freePort();
        assertTrue(String.valueOf(port), port > 1024 && port < 65536);
    }

    @Test
    public void the_module_is_named_relative_to_the_root() {
        assertEquals("shared/host", JkRunConfigurationProducer.relative("/ws", "/ws/shared/host"));
        assertEquals("", JkRunConfigurationProducer.relative("/ws", "/ws"));
        assertEquals("", JkRunConfigurationProducer.relative("/ws", "/elsewhere/app"));
    }
}

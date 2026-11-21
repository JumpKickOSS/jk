// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.test.MarkdownTestReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A failed test's snapshot, under its block in the agent report. */
class JkResultsSnapshotTest {

    private static final List<String> STATE = List.of(
            "Order.ship(Order.java:50) order=Order{id=7, lines=ArrayList(size=2)}, qty=3, available=2",
            "OrderTest.ships(OrderTest.java:12) order=Order{id=7, lines=ArrayList(size=2)}");

    @Test
    void a_failed_test_carries_the_state_of_each_frame_it_threw_through() {
        String stack = """
                java.lang.IllegalStateException: only 2 lines
                \tat com.acme.Order.ship(Order.java:50)
                \tat com.acme.OrderTest.ships(OrderTest.java:12)
                """;
        BuildRecord.Diag err = new BuildRecord.Diag(
                "error",
                "/ws/app",
                "run-tests",
                "test-failure",
                "only 2 lines",
                null,
                "java.lang.IllegalStateException",
                null,
                "junit",
                "com.acme.OrderTest",
                "ships",
                stack,
                "",
                0,
                0,
                0,
                List.of(),
                0,
                "");
        var run = new MarkdownTestReport.ModuleRun(
                "/ws/app", "app", List.of(), Map.of(), Map.of("com.acme.OrderTest#ships", STATE));
        BuildRecord r = JkResultsAgentTest.record(
                "test", false, false, 1_200, new BuildRecord.Tests(1, 0, 1, 0), List.of(err), List.of());
        assertThat(JkResultsAgent.render(r, List.of(run))).contains("""
                        T com.acme.OrderTest#ships
                          only 2 lines
                          at Order.java:50
                          at OrderTest.java:12
                          state when it threw:
                            Order.ship(Order.java:50) order=Order{id=7, lines=ArrayList(size=2)}, qty=3, available=2
                            OrderTest.ships(OrderTest.java:12) order=Order{id=7, lines=ArrayList(size=2)}
                        """);
    }

    @Test
    void a_failure_known_only_from_the_report_carries_it_too_and_one_without_says_nothing() {
        var fail = new MarkdownTestReport.Entry("com.acme.OrderTest", "ships()", 5, "only 2 lines", null, null);
        var other = new MarkdownTestReport.Entry("com.acme.OrderTest", "cancels()", 5, "boom", null, null);
        var run = new MarkdownTestReport.ModuleRun(
                "/ws/app", "app", List.of(fail, other), Map.of(), Map.of("com.acme.OrderTest#ships", STATE));
        BuildRecord r = JkResultsAgentTest.record(
                "test", false, false, 100, new BuildRecord.Tests(2, 0, 2, 0), List.of(), List.of());
        String text = JkResultsAgent.render(r, List.of(run));
        assertThat(text)
                .contains("T com.acme.OrderTest#ships\n  only 2 lines\n  state when it threw:\n    Order.ship(")
                .contains("T com.acme.OrderTest#cancels\n  boom\n");
        assertThat(text.indexOf("state when it threw:")).isEqualTo(text.lastIndexOf("state when it threw:"));
    }
}

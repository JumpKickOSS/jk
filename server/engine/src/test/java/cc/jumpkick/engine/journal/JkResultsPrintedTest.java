// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.test.MarkdownTestReport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** What a run's tests printed, in the agent report. */
class JkResultsPrintedTest {

    @Test
    void a_failed_test_whose_class_printed_says_how_much_and_how_to_see_it() {
        MarkdownTestReport.Entry fail = new MarkdownTestReport.Entry(
                "com.example.AppTests", "adds()", 10, "expected: 3 but was: 4", null, null);
        var printed = Map.of(
                "com.example.AppTests",
                new MarkdownTestReport.Printed(3, List.of(), "/ws/rest-service/target/surefire-reports/TEST-x.xml"));
        var run = new MarkdownTestReport.ModuleRun("/ws", "app", List.of(fail), printed);
        BuildRecord r = JkResultsAgentTest.record(
                "test", false, false, 100, new BuildRecord.Tests(1, 0, 1, 0), List.of(), List.of());
        assertThat(JkResultsAgent.render(r, List.of(run)))
                .contains(
                        "T com.example.AppTests#adds\n  expected: 3 but was: 4\n  printed 3 lines; jk test -v shows them\n")
                .doesNotContain("OUT ");
    }

    @Test
    void a_green_run_that_kept_output_quotes_it_under_each_class() {
        MarkdownTestReport.Entry pass =
                new MarkdownTestReport.Entry("com.example.AppTests", "adds()", 10, null, null, null);
        Map<String, MarkdownTestReport.Printed> printed = new LinkedHashMap<>();
        printed.put(
                "com.example.AppTests",
                new MarkdownTestReport.Printed(
                        5, List.of("debug 4", "debug 5"), "/ws/rest-service/target/surefire-reports/TEST-A.xml"));
        printed.put("com.example.QuietTests", new MarkdownTestReport.Printed(1, List.of(), ""));
        var run = new MarkdownTestReport.ModuleRun("/ws", "app", List.of(pass), printed);
        BuildRecord r = JkResultsAgentTest.record(
                "test", true, false, 100, new BuildRecord.Tests(1, 1, 0, 0), List.of(), List.of());
        String text = JkResultsAgent.render(r, List.of(run));
        assertThat(text)
                .startsWith("OK test")
                .endsWith("OUT com.example.AppTests printed 5 lines\n"
                        + "  +3 lines before: target/surefire-reports/TEST-A.xml\n"
                        + "  debug 4\n"
                        + "  debug 5\n")
                .doesNotContain("QuietTests");
        assertThat(JkResultsAgent.renderAll(r, List.of(run))).doesNotContain("OUT ");
    }

    @Test
    void quoted_output_stops_at_its_budget_and_names_where_the_rest_is() {
        List<String> full = IntStream.range(0, JkResultsPrinted.MAX_PRINTED)
                .mapToObj(i -> "line " + i)
                .toList();
        Map<String, MarkdownTestReport.Printed> printed = new LinkedHashMap<>();
        printed.put("a.FirstTests", new MarkdownTestReport.Printed(full.size(), full, "/ws/rest-service/r/TEST-a.xml"));
        printed.put("b.SecondTests", new MarkdownTestReport.Printed(1, List.of("x"), "/ws/rest-service/r/TEST-b.xml"));
        printed.put("c.ThirdTests", new MarkdownTestReport.Printed(1, List.of("y"), "/ws/rest-service/r/TEST-c.xml"));
        var run = new MarkdownTestReport.ModuleRun(
                "/ws",
                "app",
                List.of(new MarkdownTestReport.Entry("a.FirstTests", "t()", 1, null, null, null)),
                printed);
        BuildRecord r = JkResultsAgentTest.record(
                "test", true, false, 100, new BuildRecord.Tests(1, 1, 0, 0), List.of(), List.of());
        assertThat(JkResultsAgent.render(r, List.of(run)))
                .contains("OUT a.FirstTests printed " + JkResultsPrinted.MAX_PRINTED + " lines\n")
                .doesNotContain("OUT b.SecondTests")
                .endsWith("+2 more classes printed: r/TEST-b.xml\n");
    }
}

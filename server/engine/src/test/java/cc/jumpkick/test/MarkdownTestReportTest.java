// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.JobWorkers;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownTestReportTest {

    @Test
    void publish_then_take_under_project_dir(@TempDir Path ws) {
        Path core = ws.resolve("core");
        Path other = ws.resolveSibling("other-project");
        MarkdownTestReport a = new MarkdownTestReport();
        a.recordFinished("[class:com.acme.FooTest]", "bar()", 12, null);
        a.publish(core.toString(), "g:core");
        MarkdownTestReport b = new MarkdownTestReport();
        b.recordFinished("[class:com.other.ZedTest]", "zed()", 3, "{\"message\":\"boom\",\"stack\":\"at Z\"}");
        b.publish(other.toString(), "g:other");

        List<MarkdownTestReport.ModuleRun> mine = MarkdownTestReport.takeUnder(ws);
        assertThat(mine).hasSize(1);
        assertThat(mine.getFirst().label()).isEqualTo("g:core");
        assertThat(mine.getFirst().entries()).hasSize(1);
        assertThat(mine.getFirst().entries().getFirst().className()).isEqualTo("com.acme.FooTest");
        assertThat(mine.getFirst().entries().getFirst().isPass()).isTrue();

        List<MarkdownTestReport.ModuleRun> leftover = MarkdownTestReport.takeUnder(other);
        assertThat(leftover).hasSize(1);
        assertThat(leftover.getFirst().entries().getFirst().isFail()).isTrue();
    }

    @Test
    void a_publish_after_its_request_ended_is_dropped(@TempDir Path ws) {
        long request = -4242L;
        JobWorkers.open(request);
        try {
            JobWorkers.shutdownForRequest(request, 0L);
            MarkdownTestReport late = new MarkdownTestReport();
            late.recordFinished("[class:com.acme.LateTest]", "late()", 1, null);
            late.publish(ws.resolve("core").toString(), "g:core");
            assertThat(MarkdownTestReport.takeUnder(ws)).isEmpty();
        } finally {
            JobWorkers.close();
        }
    }

    @Test
    void a_failure_payload_is_bounded_and_keeps_its_cause_chain(@TempDir Path ws) {
        StringBuilder stack = new StringBuilder("java.lang.AssertionError: top\n");
        for (int i = 0; i < 4000; i++)
            stack.append("\tat com.acme.Frame").append(i).append("(Frame.java:1)\n");
        stack.append("Caused by: java.io.IOException: the root\n\tat com.acme.Root(Root.java:1)\n");
        String message = "m".repeat(100_000);
        MarkdownTestReport report = new MarkdownTestReport();
        report.recordFinished(
                "[class:com.acme.BigTest]",
                "big()",
                5,
                "{\"message\":\"" + message + "\",\"stack\":\""
                        + stack.toString().replace("\t", "\\t").replace("\n", "\\n") + "\"}");
        report.publish(ws.resolve("core").toString(), "g:core");
        MarkdownTestReport.Entry e =
                MarkdownTestReport.takeUnder(ws).getFirst().entries().getFirst();
        assertThat(e.failureMessage()).hasSizeLessThanOrEqualTo(MarkdownTestReport.MAX_MESSAGE_CHARS + 1);
        assertThat(e.failureStack()).hasSizeLessThanOrEqualTo(MarkdownTestReport.MAX_STACK_CHARS + 1);
        assertThat(e.failureStack())
                .startsWith("java.lang.AssertionError: top")
                .contains("Caused by: java.io.IOException: the root");
    }

    @Test
    void retain_under_drops_every_run_outside_the_open_roots(@TempDir Path ws) {
        Path open = ws.resolve("open");
        Path stale = ws.resolveSibling("stale-project");
        MarkdownTestReport a = new MarkdownTestReport();
        a.recordFinished("[class:com.acme.FooTest]", "bar()", 12, null);
        a.publish(open.resolve("core").toString(), "g:core");
        MarkdownTestReport b = new MarkdownTestReport();
        b.recordFinished("[class:com.other.ZedTest]", "zed()", 3, null);
        b.publish(stale.resolve("app").toString(), "g:app");
        MarkdownTestReport.retainUnder(List.of(open.toString()));
        assertThat(MarkdownTestReport.takeUnder(stale)).isEmpty();
        assertThat(MarkdownTestReport.takeUnder(open)).hasSize(1);
    }

    @Test
    void class_name_from_junit_unique_id() {
        assertThat(MarkdownTestReport.classNameFrom("[engine:junit-jupiter]/[class:com.acme.FooTest]/[method:bar()]"))
                .isEqualTo("com.acme.FooTest");
        assertThat(MarkdownTestReport.classNameFrom("not-a-unique-id")).isEqualTo("not-a-unique-id");
    }
}

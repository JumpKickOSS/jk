// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.run.JkThreads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A launch's JUnit results land in the sink of the request that ran it, and nowhere else. */
class MarkdownTestReportTest {

    @AfterEach
    void closeSink() {
        RunResults.close();
    }

    @Test
    void a_publish_lands_in_the_open_requests_sink_and_merges_runs_of_one_module(@TempDir Path ws) {
        RunResults mine = new RunResults();
        RunResults.open(mine);
        Path core = ws.resolve("core");
        MarkdownTestReport a = new MarkdownTestReport();
        a.recordFinished("[class:com.acme.FooTest]", "bar()", 12, null);
        a.publish(core.toString(), "g:core");
        MarkdownTestReport b = new MarkdownTestReport();
        b.recordFinished("[class:com.acme.FooTest]", "baz()", 3, "{\"message\":\"boom\",\"stack\":\"at Z\"}");
        b.publish(core.toString(), "");

        List<MarkdownTestReport.ModuleRun> runs = mine.takeTests();
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().label()).isEqualTo("g:core");
        assertThat(runs.getFirst().entries())
                .extracting(MarkdownTestReport.Entry::displayName, MarkdownTestReport.Entry::isPass)
                .containsExactly(tuple("bar()", true), tuple("baz()", false));
        assertThat(mine.takeTests()).as("a take drains the sink").isEmpty();
    }

    @Test
    void a_publish_outside_any_request_is_dropped(@TempDir Path ws) {
        RunResults.close();
        MarkdownTestReport late = new MarkdownTestReport();
        late.recordFinished("[class:com.acme.LateTest]", "late()", 1, null);
        late.publish(ws.resolve("core").toString(), "g:core");
        assertThat(RunResults.ambient()).isNull();
    }

    @Test
    void two_requests_sinks_never_see_each_others_runs(@TempDir Path ws) throws Exception {
        RunResults first = new RunResults();
        RunResults second = new RunResults();
        RunResults.open(first);
        MarkdownTestReport a = new MarkdownTestReport();
        a.recordFinished("[class:com.acme.FooTest]", "bar()", 12, null);
        a.publish(ws.resolve("one").toString(), "g:one");
        RunResults.open(second);
        // A pool hop keeps the submitting thread's sink, not whatever the worker inherited.
        JkThreads.cpu()
                .submit(() -> {
                    MarkdownTestReport b = new MarkdownTestReport();
                    b.recordFinished("[class:com.other.ZedTest]", "zed()", 3, null);
                    b.publish(ws.resolve("two").toString(), "g:two");
                })
                .get(30, TimeUnit.SECONDS);

        assertThat(first.takeTests())
                .singleElement()
                .extracting(MarkdownTestReport.ModuleRun::label)
                .isEqualTo("g:one");
        assertThat(second.takeTests())
                .singleElement()
                .extracting(MarkdownTestReport.ModuleRun::label)
                .isEqualTo("g:two");
    }

    @Test
    void a_failure_payload_is_bounded_and_keeps_its_cause_chain(@TempDir Path ws) {
        RunResults sink = new RunResults();
        RunResults.open(sink);
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
        MarkdownTestReport.Entry e = sink.takeTests().getFirst().entries().getFirst();
        assertThat(e.failureMessage()).hasSizeLessThanOrEqualTo(MarkdownTestReport.MAX_MESSAGE_CHARS + 1);
        assertThat(e.failureStack()).hasSizeLessThanOrEqualTo(MarkdownTestReport.MAX_STACK_CHARS + 1);
        assertThat(e.failureStack())
                .startsWith("java.lang.AssertionError: top")
                .contains("Caused by: java.io.IOException: the root");
    }

    @Test
    void coverage_publishes_into_the_same_sink_in_module_order(@TempDir Path ws) {
        RunResults sink = new RunResults();
        RunResults.open(sink);
        CoverageResults.publish(ws.resolve("web"), module(ws.resolve("web"), "g:web"));
        CoverageResults.publish(ws.resolve("core"), module(ws.resolve("core"), "g:core"));
        CoverageResults.publish(ws.resolve("core"), module(ws.resolve("core"), "g:core-again"));
        assertThat(sink.takeCoverage())
                .extracting(CoverageResults.Module::label)
                .containsExactly("g:core-again", "g:web");
        assertThat(sink.takeCoverage()).isEmpty();
    }

    /**
     * The engine's own shape, as a journaled e2e drives it: register the request, bind its sink,
     * publish from the run, close, and the request's record holds the rows the write drains.
     */
    @Test
    void a_run_published_between_a_writers_open_and_close_lands_on_that_requests_record(@TempDir Path ws)
            throws Exception {
        JobSessions sessions = new JobSessions(() -> 0L);
        JournalWriter writer = new JournalWriter(
                sessions,
                new BuildJournal(ws.resolve("builds")),
                new JkHistoryConfig(false, 30, 512),
                () -> ws.resolve("metrics.json"),
                System::currentTimeMillis,
                "9.9-test",
                line -> {});
        Path project = Files.createDirectories(ws.resolve("calc"));
        writer.register(7L, "build", project.toString(), "cli", null, true, false, 0L, null);
        writer.openResults(7L);
        try {
            MarkdownTestReport run = new MarkdownTestReport();
            run.recordFinished("[class:com.example.CalcTest]", "subtracts()", 3, null);
            run.publish(project.toString(), "com.example:calc");
        } finally {
            writer.closeResults();
        }
        BuildAccumulator acc = Objects.requireNonNull(sessions.accumulator(7L));
        assertThat(acc.results().takeTests())
                .singleElement()
                .extracting(MarkdownTestReport.ModuleRun::label)
                .isEqualTo("com.example:calc");
    }

    private static CoverageResults.Module module(Path dir, String label) {
        String d = dir.toAbsolutePath().normalize().toString();
        return new CoverageResults.Module(d, label, 1, 1, 1, 1, d + "/index.html");
    }

    @Test
    void class_name_from_junit_unique_id() {
        assertThat(MarkdownTestReport.classNameFrom("[engine:junit-jupiter]/[class:com.acme.FooTest]/[method:bar()]"))
                .isEqualTo("com.acme.FooTest");
        assertThat(MarkdownTestReport.classNameFrom("not-a-unique-id")).isEqualTo("not-a-unique-id");
    }
}

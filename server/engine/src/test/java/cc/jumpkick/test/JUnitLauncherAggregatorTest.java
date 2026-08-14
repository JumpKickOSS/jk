// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TestSummary;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * Whitebox tests for {@link JUnitLauncher.ResultAggregator}. The aggregator processes the JSONL
 * event stream the workers emit; we feed it raw lines here rather than spinning up a real fork.
 */
class JUnitLauncherAggregatorTest {

    @Test
    void counts_successful_failed_and_skipped_tests() {
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("{\"event\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"event\":\"finished\",\"id\":\"b\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"[engine:junit-jupiter]/[class:C]/[method:c()]\","
                        + "\"testEngine\":\"junit-jupiter\",\"testClass\":\"C\",\"testMethod\":\"c()\","
                        + "\"type\":\"TEST\",\"status\":\"FAILED\","
                        + "\"throwable\":{\"class\":\"AssertionError\",\"message\":\"nope\",\"stack\":\"AssertionError: nope\\n\\tat C.c(C.java:1)\"}}");
        agg.accept("{\"event\":\"skipped\",\"uniqueId\":\"d\",\"type\":\"TEST\",\"reason\":\"@Disabled\"}");

        var result = agg.toResult(0);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.testName()).isEqualTo("c()");
            assertThat(f.method()).isEqualTo("c()");
            assertThat(f.className()).isEqualTo("C");
            assertThat(f.testEngine()).isEqualTo("junit-jupiter");
            assertThat(f.exceptionClass()).isEqualTo("AssertionError");
            assertThat(f.message()).isEqualTo("nope");
            assertThat(f.stack()).contains("at C.c(C.java:1)");
        });
    }

    @Test
    void pathological_stacks_are_truncated_at_capture() {
        // The stack is worker-controlled input copied onto wire, SSE, and journal — a
        // deep-recursion failure must not ride megabytes of frames through the pipeline (JK-1880).
        String frame = "\tat C.recurse(C.java:2)\n";
        String stack = "StackOverflowError\n" + frame.repeat(200_000 / frame.length());
        String truncated = JUnitLauncher.ResultAggregator.truncateStack(stack);
        assertThat(truncated.length()).isLessThanOrEqualTo(JUnitLauncher.ResultAggregator.MAX_STACK_CHARS + 64);
        assertThat(truncated).endsWith("more chars)");
        // Cuts on a line boundary, keeping whole frames.
        assertThat(truncated).contains("... stack truncated (");
        assertThat(JUnitLauncher.ResultAggregator.truncateStack("short")).isEqualTo("short");
    }

    @Test
    void pathological_messages_are_truncated_at_capture() {
        // Same rationale as the stack cap (JK-1948): an assertEquals diff of two multi-MB
        // strings is a single-line message that rides wire, SSE, journal, and web card.
        String message = "expected: <" + "x".repeat(3_000_000) + "> but was: <y>";
        String truncated = JUnitLauncher.ResultAggregator.truncateMessage(message);
        assertThat(truncated.length()).isLessThanOrEqualTo(JUnitLauncher.ResultAggregator.MAX_MESSAGE_CHARS + 64);
        assertThat(truncated).contains("... message truncated (");
        assertThat(JUnitLauncher.ResultAggregator.truncateMessage("short")).isEqualTo("short");
        // A cut landing on a surrogate pair backs off one char instead of emitting a lone surrogate.
        String astral = "a".repeat(JUnitLauncher.ResultAggregator.MAX_MESSAGE_CHARS - 1) + "😀tail";
        String cutAstral = JUnitLauncher.ResultAggregator.truncateMessage(astral);
        assertThat(cutAstral).doesNotContain("😀");
        assertThat(Character.isHighSurrogate(cutAstral.charAt(cutAstral.indexOf(" ... message truncated") - 1)))
                .isFalse();
    }

    @Test
    void container_events_do_not_count_toward_test_totals() {
        // JUnit fires FINISHED for engine roots and test classes too — those
        // are CONTAINER nodes and must not inflate the test count.
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("{\"event\":\"finished\",\"id\":\"engine\",\"type\":\"CONTAINER\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"event\":\"finished\",\"id\":\"class\",\"type\":\"CONTAINER\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"event\":\"finished\",\"id\":\"method\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");

        assertThat(agg.toResult(0).total()).isEqualTo(1);
    }

    @Test
    void engines_without_class_method_segments_keep_their_display_label() {
        // Spock/Cucumber uniqueIds have no [class:]/[method:] segments; the worker sends the
        // display name for those and labels must use it — not the raw bracketed id (JK-1903).
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"[engine:spock]/[spec:LockSpec]/[feature:floats the lock]\","
                + "\"testEngine\":\"spock\",\"display\":\"floats the lock\","
                + "\"type\":\"TEST\",\"status\":\"FAILED\","
                + "\"throwable\":{\"class\":\"E\",\"message\":\"m\",\"stack\":\"\"}}");
        var result = agg.toResult(0);
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.testName()).isEqualTo("floats the lock");
        });
    }

    @Test
    void merges_event_streams_from_multiple_workers() {
        // Simulate two parallel workers each running a couple of classes.
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"w1.a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\",\"worker\":1}");
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"w2.x\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\",\"worker\":2}");
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"w1.b\",\"type\":\"TEST\",\"status\":\"FAILED\",\"worker\":1,"
                + "\"testMethod\":\"b()\",\"throwable\":{\"class\":\"E\",\"message\":\"m\",\"stack\":\"\"}}");
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"w2.y\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\",\"worker\":2}");

        var result = agg.toResult(0);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.succeeded()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void ready_and_plan_events_are_ignored_for_counts() {
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("{\"event\":\"ready\",\"w\":1}");
        agg.accept("{\"event\":\"plan_started\"}");
        agg.accept("{\"event\":\"plan_finished\",\"duration_ms\":100}");
        agg.accept("{\"event\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");

        assertThat(agg.toResult(0).total()).isEqualTo(1);
    }

    @Test
    void unique_id_percent_decode_and_class_extract() {
        assertThat(JUnitLauncher.percentDecode("bar(int%5B%5D)")).isEqualTo("bar(int[])");
        assertThat(JUnitLauncher.percentDecode("foo%2Fbar%251")).isEqualTo("foo/bar%1");
        assertThat(JUnitLauncher.percentDecode("plain")).isEqualTo("plain");
        assertThat(JUnitLauncher.classFromUniqueId(
                        "[engine:junit-jupiter]/[class:demo.FooTest]/[method:bar(int%5B%5D)]"))
                .isEqualTo("demo.FooTest");
        assertThat(JUnitLauncher.engineFromUniqueId(
                        "[engine:junit-jupiter]/[class:demo.FooTest]/[method:bar(int%5B%5D)]"))
                .isEqualTo("junit-jupiter");
    }

    @Test
    void malformed_json_does_not_blow_up_the_aggregator() {
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("not actually json");
        agg.accept("{\"event\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        assertThat(agg.toResult(0).total()).isEqualTo(1);
    }

    @Test
    void dynamic_registered_events_mark_subsequent_finished_as_non_static() {
        // The aggregator must remodule every id it saw via dynamic_registered
        // (TEST type) and stamp wasStatic=false on the matching finished
        // event. Plain @Test methods are not preceded by dynamic_registered
        // and should arrive as wasStatic=true.
        var captured = new ArrayList<boolean[]>(); // [isTest, wasStatic]
        var listener = new TestProgressListener() {
            @Override
            public void onTestFinished(
                    String id,
                    String display,
                    String status,
                    boolean isTest,
                    boolean wasStatic,
                    long durationMs,
                    int workerId) {
                captured.add(new boolean[] {isTest, wasStatic});
            }
        };
        var agg = new JUnitLauncher.ResultAggregator(listener, 0);

        // Plain static test — no preceding dynamic_registered.
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"static-1\"," + "\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        // Parameterized invocation — preceded by dynamic_registered.
        agg.accept("{\"event\":\"dynamic_registered\",\"uniqueId\":\"dyn-1\",\"type\":\"TEST\"}");
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"dyn-1\"," + "\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        // CONTAINER-typed dynamic_registered must NOT count as a dynamic
        // test id — its later finished (also CONTAINER) shouldn't affect
        // progress regardless.
        agg.accept("{\"event\":\"dynamic_registered\",\"uniqueId\":\"c-1\",\"type\":\"CONTAINER\"}");
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"c-1\"," + "\"type\":\"CONTAINER\",\"status\":\"SUCCESSFUL\"}");

        assertThat(captured).hasSize(3);
        assertThat(captured.get(0)).containsExactly(true, true); // static @Test
        assertThat(captured.get(1)).containsExactly(true, false); // parameterized
        assertThat(captured.get(2)).containsExactly(false, false); // container
    }

    @Test
    void non_zero_exit_with_empty_results_reports_a_run_level_failure() {
        // Worker crashed before emitting any tests — we still want a non-zero
        // pass/fail signal.
        var agg = new JUnitLauncher.ResultAggregator();
        var result = agg.toResult(2);
        assertThat(result.allPassed()).isFalse();
        assertThat(result.failures())
                .singleElement()
                .extracting(TestSummary.Failure::testName)
                .isEqualTo("(test run)");
    }

    @Test
    void failed_test_keeps_the_full_stack_trace() {
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"c\",\"type\":\"TEST\",\"status\":\"FAILED\","
                + "\"testMethod\":\"c()\",\"throwable\":{\"class\":\"AssertionError\","
                + "\"message\":\"nope\",\"stack\":\"AssertionError: nope\\n\\tat Foo.c(Foo.java:9)\"}}");
        assertThat(agg.toResult(0).failures()).singleElement().satisfies(f -> assertThat(f.stack())
                .contains("AssertionError: nope")
                .contains("at Foo.c(Foo.java:9)"));
    }

    @Test
    void stack_line_array_is_joined_for_legacy_runners() {
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"c\",\"type\":\"TEST\",\"status\":\"FAILED\","
                + "\"testMethod\":\"c()\",\"throwable\":{\"class\":\"AssertionError\","
                + "\"message\":\"nope\",\"stack\":[\"AssertionError: nope\",\"\\tat Foo.c(Foo.java:9)\"]}}");
        assertThat(agg.toResult(0).failures()).singleElement().satisfies(f -> assertThat(f.stack())
                .contains("AssertionError: nope")
                .contains("at Foo.c(Foo.java:9)"));
    }

    @Test
    void container_failure_is_captured_so_init_errors_are_visible() {
        // A class initializer / @BeforeAll error finishes the CONTAINER as FAILED
        // and fires no TEST event — capture it instead of a silent "runner exited".
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"cls\",\"testClass\":\"FooTest\",\"type\":\"CONTAINER\","
                        + "\"status\":\"FAILED\",\"throwable\":{\"class\":\"ExceptionInInitializerError\","
                        + "\"message\":\"\",\"stack\":\"ExceptionInInitializerError\\n\\tat FooTest.<clinit>(FooTest.java:3)\"}}");
        var result = agg.toResult(0);
        assertThat(result.allPassed()).isFalse();
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.className()).isEqualTo("FooTest");
            assertThat(f.stack()).contains("ExceptionInInitializerError");
        });
    }

    @Test
    void crash_output_is_attached_to_the_synthetic_failure() {
        var agg = new JUnitLauncher.ResultAggregator();
        String crash = "Exception in thread \"main\" java.lang.NoClassDefFoundError: Missing\n"
                + "\tat cc.jumpkick.Boot.main(Boot.java:1)";
        var result = agg.toResult(1, crash); // no events, non-zero exit
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.testName()).isEqualTo("(test run)");
            assertThat(f.stack()).contains("NoClassDefFoundError").contains("at cc.jumpkick.Boot.main");
        });
    }

    @Test
    void capture_buffer_keeps_only_the_last_lines() {
        var buf = new JUnitLauncher.CaptureBuffer();
        for (int i = 0; i < 1000; i++) buf.add("line " + i);
        String text = buf.text();
        assertThat(text).contains("line 999").doesNotContain("line 0\n");
        assertThat(text.split("\n")).hasSizeLessThanOrEqualTo(400);
    }
}

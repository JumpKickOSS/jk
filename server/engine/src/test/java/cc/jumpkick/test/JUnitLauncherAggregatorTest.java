// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.plugin.protocol.JUnitUniqueIds;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whitebox tests for {@link ResultAggregator}. The aggregator processes the JSONL
 * event stream the workers emit; we feed it raw lines here rather than spinning up a real fork.
 */
class JUnitLauncherAggregatorTest {

    private static final String PLAN_FINISHED = "{\"event\":\"plan_finished\",\"duration_ms\":100,\"failed\":false}";

    /** The summary of a fork that ran its plan to the end and exited 0, as the runner does. */
    private static TestSummary completed(ResultAggregator agg) {
        agg.accept(PLAN_FINISHED);
        return agg.toResult(0);
    }

    @Test
    void the_xml_names_an_invocation_by_its_display_name_and_a_plain_test_by_its_method(@TempDir Path dir)
            throws Exception {
        var xml = new XmlTestReport();
        var agg = new ResultAggregator(xml);
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"[engine:junit-jupiter]/[class:C]/[test-template:t(String)]"
                        + "/[test-template-invocation:#1]\",\"testEngine\":\"junit-jupiter\",\"testClass\":\"C\","
                        + "\"testMethod\":\"t(String)[#1]\",\"display\":\"[1] a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"[engine:junit-jupiter]/[class:C]/[method:p()]\","
                + "\"testEngine\":\"junit-jupiter\",\"testClass\":\"C\",\"testMethod\":\"p()\","
                + "\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        xml.writeAll(dir);
        String report = Files.readString(dir.resolve("TEST-C.xml"));
        assertThat(report).contains("testcase name=\"[1] a\"").contains("testcase name=\"p()\"");
    }

    @Test
    void output_printed_while_a_class_runs_lands_in_that_class_s_system_out(@TempDir Path dir) throws Exception {
        var xml = new XmlTestReport();
        var agg = new ResultAggregator(xml);
        agg.userOutput("JVM banner before any class");
        agg.accept("{\"event\":\"started\",\"uniqueId\":\"[engine:junit-jupiter]/[class:A]\",\"type\":\"CONTAINER\"}");
        agg.userOutput("A says hello");
        agg.accept("{\"event\":\"started\",\"uniqueId\":\"[engine:junit-jupiter]/[class:A]/[method:a()]\","
                + "\"testClass\":\"A\",\"testMethod\":\"a()\",\"type\":\"TEST\"}");
        agg.userOutput("a() dumps its diagnostics");
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"[engine:junit-jupiter]/[class:A]/[method:a()]\","
                + "\"testClass\":\"A\",\"testMethod\":\"a()\",\"type\":\"TEST\",\"status\":\"FAILED\","
                + "\"throwable\":{\"class\":\"AssertionError\",\"message\":\"70 != 130\",\"stack\":\"\"}}");
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"[engine:junit-jupiter]/[class:A]\",\"type\":\"CONTAINER\","
                + "\"status\":\"SUCCESSFUL\"}");
        agg.userOutput("between classes");
        agg.accept("{\"event\":\"started\",\"uniqueId\":\"[engine:junit-jupiter]/[class:B]\",\"type\":\"CONTAINER\"}");
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"[engine:junit-jupiter]/[class:B]/[method:b()]\","
                + "\"testClass\":\"B\",\"testMethod\":\"b()\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        xml.writeAll(dir);

        String a = Files.readString(dir.resolve("TEST-A.xml"));
        assertThat(a)
                .contains("A says hello\na() dumps its diagnostics\n")
                .doesNotContain("JVM banner")
                .doesNotContain("between classes");
        assertThat(Files.readString(dir.resolve("TEST-B.xml"))).contains("<system-out><![CDATA[]]></system-out>");
    }

    @Test
    void counts_successful_failed_and_skipped_tests() {
        var agg = new ResultAggregator();
        agg.accept("{\"event\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"event\":\"finished\",\"id\":\"b\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"[engine:junit-jupiter]/[class:C]/[method:c()]\","
                        + "\"testEngine\":\"junit-jupiter\",\"testClass\":\"C\",\"testMethod\":\"c()\","
                        + "\"type\":\"TEST\",\"status\":\"FAILED\","
                        + "\"throwable\":{\"class\":\"AssertionError\",\"message\":\"nope\",\"stack\":\"AssertionError: nope\\n\\tat C.c(C.java:1)\"}}");
        agg.accept("{\"event\":\"skipped\",\"uniqueId\":\"d\",\"type\":\"TEST\",\"reason\":\"@Disabled\"}");

        var result = completed(agg);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.method()).isEqualTo("c()");
            assertThat(f.className()).isEqualTo("C");
            assertThat(f.engine()).isEqualTo("junit-jupiter");
            assertThat(f.exceptionClass()).isEqualTo("AssertionError");
            assertThat(f.message()).isEqualTo("nope");
            assertThat(f.stack()).contains("at C.c(C.java:1)");
        });
    }

    @Test
    void container_events_do_not_count_toward_test_totals() {
        // JUnit fires FINISHED for engine roots and test classes too — those
        // are CONTAINER nodes and must not inflate the test count.
        var agg = new ResultAggregator();
        agg.accept("{\"event\":\"finished\",\"id\":\"engine\",\"type\":\"CONTAINER\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"event\":\"finished\",\"id\":\"class\",\"type\":\"CONTAINER\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"event\":\"finished\",\"id\":\"method\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");

        assertThat(completed(agg).total()).isEqualTo(1);
    }

    @Test
    void engines_without_class_method_segments_keep_their_display_label() {
        // Spock/Cucumber uniqueIds have no [class:]/[method:] segments; the worker sends the
        // display name for those and labels must use it — not the raw bracketed id.
        var agg = new ResultAggregator();
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"[engine:spock]/[spec:LockSpec]/[feature:floats the lock]\","
                + "\"testEngine\":\"spock\",\"display\":\"floats the lock\","
                + "\"type\":\"TEST\",\"status\":\"FAILED\","
                + "\"throwable\":{\"class\":\"E\",\"message\":\"m\",\"stack\":\"\"}}");
        var result = completed(agg);
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.method()).isEqualTo("floats the lock");
        });
    }

    @Test
    void merges_event_streams_from_multiple_workers() {
        // Simulate two parallel workers each running a couple of classes.
        var agg = new ResultAggregator();
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"w1.a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\",\"worker\":1}");
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"w2.x\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\",\"worker\":2}");
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"w1.b\",\"type\":\"TEST\",\"status\":\"FAILED\",\"worker\":1,"
                + "\"testMethod\":\"b()\",\"throwable\":{\"class\":\"E\",\"message\":\"m\",\"stack\":\"\"}}");
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"w2.y\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\",\"worker\":2}");

        var result = completed(agg);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.succeeded()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void ready_and_plan_events_are_ignored_for_counts() {
        var agg = new ResultAggregator();
        agg.accept("{\"event\":\"ready\",\"worker\":1}");
        agg.accept("{\"event\":\"plan_started\"}");
        agg.accept("{\"event\":\"plan_finished\",\"duration_ms\":100}");
        agg.accept("{\"event\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");

        assertThat(agg.toResult(0).total()).isEqualTo(1);
    }

    /**
     * A test, or the code under it, that calls {@code System.exit(0)} ends the fork cleanly with
     * the rest of the plan unrun: no {@code plan_finished}, exit 0, every reported test green. That
     * is the same death as a non-zero exit and gets the same {@code (test run)} row.
     */
    @Test
    void a_fork_that_exits_0_without_finishing_its_plan_is_not_green() {
        var agg = new ResultAggregator();
        agg.accept("{\"event\":\"discovery_total\",\"classes\":2,\"tests\":3}");
        agg.accept("{\"event\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"event\":\"finished\",\"id\":\"b\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");

        var result = agg.toResult(0);

        assertThat(result.allPassed()).isFalse();
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures()).singleElement().satisfies(row -> {
            assertThat(row.method()).isEqualTo("(test run)");
            assertThat(row.message())
                    .startsWith("worker exited 0 before finishing its plan (2 of 3 discovered tests reported)")
                    .contains("System.exit");
        });
    }

    @Test
    void a_fork_that_exits_0_with_no_event_and_no_plan_is_a_launcher_failure() {
        var agg = new ResultAggregator();
        assertThatThrownBy(() -> agg.toResult(0, "", List.of("java", "-cp", "x", "Main")))
                .isInstanceOfSatisfying(TestLauncherFailure.class, f -> {
                    assertThat(f.exit()).isZero();
                    assertThat(f.phase()).isEqualTo("test runner");
                });
    }

    /** The plan count is a floor: a run that finished with fewer tests than discovery named lost some. */
    @Test
    void a_plan_that_finished_short_of_its_discovered_tests_is_not_green() {
        var agg = new ResultAggregator();
        agg.accept("{\"event\":\"discovery_total\",\"classes\":1,\"tests\":3}");
        agg.accept("{\"event\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"event\":\"finished\",\"id\":\"b\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");

        var result = completed(agg);

        assertThat(result.allPassed()).isFalse();
        assertThat(result.failures()).singleElement().satisfies(row -> {
            assertThat(row.method()).isEqualTo("(test run)");
            assertThat(row.message()).isEqualTo("worker finished its plan having reported 2 of 3 discovered tests");
        });
    }

    /** Every test discovery named was reported, exit 0 after {@code plan_finished}: green, as every normal run is. */
    @Test
    void a_run_that_reports_every_discovered_test_and_finishes_its_plan_is_green() {
        var agg = new ResultAggregator();
        agg.accept("{\"event\":\"discovery_total\",\"classes\":1,\"tests\":2}");
        agg.accept("{\"event\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"event\":\"skipped\",\"id\":\"b\",\"type\":\"TEST\",\"reason\":\"@Disabled\"}");
        // A dynamic invocation is a test the plan did not count; more than discovered is fine.
        agg.accept("{\"event\":\"dynamic_registered\",\"uniqueId\":\"dyn-1\",\"type\":\"TEST\"}");
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"dyn-1\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");

        var result = completed(agg);

        assertThat(result.allPassed()).isTrue();
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.failures()).isEmpty();
    }

    /**
     * JUnit reports a disabled or aborted class once, for the container, and says nothing about
     * the tests under it; an engine whose node is both container and test counts in the plan but
     * never as a test here. Those runs are green: the plan count cannot bound them.
     */
    @Test
    void a_container_that_hid_its_tests_keeps_a_short_count_green() {
        var disabled = new ResultAggregator();
        disabled.accept("{\"event\":\"discovery_total\",\"classes\":2,\"tests\":3}");
        disabled.accept(
                "{\"event\":\"skipped\",\"uniqueId\":\"[engine:junit-jupiter]/[class:A]\",\"type\":\"CONTAINER\","
                        + "\"reason\":\"@Disabled\"}");
        disabled.accept("{\"event\":\"finished\",\"id\":\"b\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        assertThat(completed(disabled).allPassed()).isTrue();

        var aborted = new ResultAggregator();
        aborted.accept("{\"event\":\"discovery_total\",\"classes\":2,\"tests\":3}");
        aborted.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"[engine:junit-jupiter]/[class:A]\",\"type\":\"CONTAINER\","
                        + "\"status\":\"ABORTED\"}");
        aborted.accept("{\"event\":\"finished\",\"id\":\"b\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        assertThat(completed(aborted).allPassed()).isTrue();

        var both = new ResultAggregator();
        both.accept("{\"event\":\"discovery_total\",\"classes\":1,\"tests\":2}");
        both.accept("{\"event\":\"finished\",\"uniqueId\":\"[engine:spock]/[spec:S]/[feature:f]\","
                + "\"type\":\"CONTAINER_AND_TEST\",\"status\":\"SUCCESSFUL\"}");
        both.accept("{\"event\":\"finished\",\"id\":\"b\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        assertThat(completed(both).allPassed()).isTrue();
    }

    @Test
    void unique_id_percent_decode_and_class_extract() {
        assertThat(JUnitUniqueIds.percentDecode("bar(int%5B%5D)")).isEqualTo("bar(int[])");
        assertThat(JUnitUniqueIds.percentDecode("foo%2Fbar%251")).isEqualTo("foo/bar%1");
        assertThat(JUnitUniqueIds.percentDecode("plain")).isEqualTo("plain");
        assertThat(JUnitLauncher.classFromUniqueId(
                        "[engine:junit-jupiter]/[class:demo.FooTest]/[method:bar(int%5B%5D)]"))
                .isEqualTo("demo.FooTest");
        assertThat(JUnitLauncher.classFromUniqueId("[engine:spock]/[spec:demo.FooSpec]/[feature:$spock_feature_0_1]"))
                .isEqualTo("demo.FooSpec");
        assertThat(JUnitLauncher.classFromUniqueId("[engine:cucumber]/[feature:x.feature]/[scenario:1]"))
                .isEqualTo("x.feature");
        assertThat(JUnitLauncher.classFromUniqueId("[engine:junit-jupiter]")).isEmpty();
        assertThat(JUnitLauncher.engineFromUniqueId(
                        "[engine:junit-jupiter]/[class:demo.FooTest]/[method:bar(int%5B%5D)]"))
                .isEqualTo("junit-jupiter");
    }

    @Test
    void malformed_json_does_not_blow_up_the_aggregator() {
        var agg = new ResultAggregator();
        agg.accept("not actually json");
        agg.accept("{\"event\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        assertThat(completed(agg).total()).isEqualTo(1);
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
        var agg = new ResultAggregator(listener, 0);

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

    /**
     * A decoder that throws ends the worker conversation on the parent's own bug, and the report
     * must say so: a {@code (test run)} row carrying the handler's exception, not a worker-exit row
     * that would send the reader hunting for a dead JVM.
     */
    @Test
    void a_throwing_decoder_yields_the_handler_row_not_a_worker_exit_row() {
        var listener = new TestProgressListener() {
            @Override
            public void onWarning(String code, String message) {
                throw new IllegalStateException("decoder choked on " + code);
            }
        };
        var queue = new ConcurrentLinkedDeque<>(List.of("com.acme.BTest"));
        var lastClass = new AtomicReference<>("");
        var handler =
                PullWorkerPool.pullHandler(queue, new ResultAggregator(listener, 2), new AtomicBoolean(), lastClass);
        PluginProcess.Conversation convo = new PluginProcess.Conversation() {
            @Override
            public void send(String line) {}

            @Override
            public void closeInput() {}
        };
        handler.accept("{\"event\":\"ready\"}", convo);
        RuntimeException thrown = catchThrowableOfType(
                RuntimeException.class,
                () -> handler.accept("{\"event\":\"warning\",\"code\":\"jupiter-parallel\"}", convo));
        PluginProcess.HandlerFailure end = new PluginProcess.HandlerFailure(thrown);

        TestFailureInfo row = WorkerFailureRow.of("m", 2, -1, lastClass.get(), "", end.handler());
        assertThat(row.method()).isEqualTo("(test run)");
        assertThat(row.exceptionClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(row.message())
                .contains("handler threw IllegalStateException: decoder choked on jupiter-parallel")
                .contains("com.acme.BTest")
                .doesNotContain("exited");
        assertThat(row.stack()).contains("IllegalStateException").contains("decoder choked");
        assertThat(row.worker()).isEqualTo(2);

        TestFailureInfo died = WorkerFailureRow.of("m", 2, 137, "com.acme.BTest", "killed", null);
        assertThat(died.method()).isEqualTo("(worker 2)");
        assertThat(died.message()).isEqualTo("test worker exited 137 mid-run (last class dispatched: com.acme.BTest)");
        assertThat(died.stack()).isEqualTo("killed");
    }

    /**
     * The parent marks a worker done only when it sends {@code DONE}; a worker that exits 0 while a
     * {@code RUN} is outstanding owes its class, and its row says why it is a failure on a clean exit.
     */
    @Test
    void a_pull_worker_is_done_only_once_the_parent_sent_done() {
        var queue = new ConcurrentLinkedDeque<>(List.of("com.acme.ATest"));
        var done = new AtomicBoolean();
        var lastClass = new AtomicReference<>("");
        var sent = new ArrayList<String>();
        var handler = PullWorkerPool.pullHandler(queue, new ResultAggregator(), done, lastClass);
        PluginProcess.Conversation convo = new PluginProcess.Conversation() {
            @Override
            public void send(String line) {
                sent.add(line);
            }

            @Override
            public void closeInput() {
                sent.add("<eof>");
            }
        };

        handler.accept("{\"event\":\"ready\"}", convo);
        assertThat(done).as("a RUN is outstanding").isFalse();
        assertThat(lastClass).hasValue("com.acme.ATest");
        handler.accept("{\"event\":\"ready\"}", convo);
        assertThat(done).isTrue();
        assertThat(sent).containsExactly("RUN com.acme.ATest", "DONE", "<eof>");

        TestFailureInfo quit = WorkerFailureRow.of("m", 2, 0, "com.acme.ATest", "", null);
        assertThat(quit.method()).isEqualTo("(worker 2)");
        assertThat(quit.className()).isEqualTo("com.acme.ATest");
        assertThat(quit.message())
                .startsWith("test worker exited 0 before it was told DONE (last class dispatched: com.acme.ATest)")
                .contains("System.exit");
    }

    /**
     * The one-worker module's fork hands the aggregator itself to the protocol; a decoder that
     * throws there must end the run with the pool's handler row for worker 0 — the tests reported
     * before the throw kept, the step failed through the summary — never as an IOException out of
     * the launcher with no test row at all.
     */
    @Test
    void a_throwing_decoder_under_the_single_fork_yields_the_handler_row_for_worker_zero() {
        var listener = new TestProgressListener() {
            @Override
            public void onWarning(String code, String message) {
                throw new IllegalStateException("decoder choked on " + code);
            }
        };
        var aggregator = new ResultAggregator(listener, 0);
        aggregator.accept("{\"event\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        RuntimeException thrown = catchThrowableOfType(
                RuntimeException.class,
                () -> aggregator.accept("{\"event\":\"warning\",\"code\":\"jupiter-parallel\"}"));
        PluginProcess.HandlerFailure end = new PluginProcess.HandlerFailure(thrown);

        TestSummary summary = WorkerFailureRow.singleFork(aggregator, "m", end.handler());

        assertThat(summary.allPassed()).isFalse();
        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.succeeded())
                .as("the test reported before the throw is kept")
                .isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.failures()).singleElement().satisfies(row -> {
            assertThat(row.module()).isEqualTo("m");
            assertThat(row.method()).isEqualTo("(test run)");
            assertThat(row.exceptionClass()).isEqualTo("java.lang.IllegalStateException");
            assertThat(row.message())
                    .contains("handler threw IllegalStateException: decoder choked on jupiter-parallel")
                    .doesNotContain("exited");
            assertThat(row.stack()).contains("IllegalStateException").contains("decoder choked");
            assertThat(row.worker()).isEqualTo(0);
        });
    }

    /**
     * The list-only discovery fork (and the serial-tag partition view, which is the same fork with
     * wider excludes) hands its decoder to the protocol the same way. A throw there is a handler
     * row that says discovery, and the classes named before the throw are not a suite.
     */
    @Test
    void a_throwing_decoder_under_discovery_yields_a_handler_row_that_says_discovery() {
        var listener = new TestProgressListener() {
            @Override
            public void onDiscoveryTotal(int classes, int tests) {
                throw new IllegalStateException("decoder choked on totals");
            }
        };
        var listing = new Discovery.Listing();
        Consumer<String> handler = listing.handler(listener);
        handler.accept("{\"event\":\"discovered\",\"class\":\"com.acme.ATest\"}");
        RuntimeException thrown = catchThrowableOfType(
                RuntimeException.class,
                () -> handler.accept("{\"event\":\"discovery_total\",\"classes\":1,\"tests\":3}"));
        PluginProcess.HandlerFailure end = new PluginProcess.HandlerFailure(thrown);

        Discovery discovery = listing.handlerFailed("", end.handler());

        assertThat(discovery.classes()).containsExactly("com.acme.ATest");
        assertThat(discovery.crashed())
                .as("a list the decoder could not finish reading is not a suite")
                .isTrue();
        TestSummary summary = discovery.verdict("m");
        assertThat(summary.allPassed()).isFalse();
        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.failures()).singleElement().satisfies(row -> {
            assertThat(row.module()).isEqualTo("m");
            assertThat(row.method()).isEqualTo("(test run)");
            assertThat(row.exceptionClass()).isEqualTo("java.lang.IllegalStateException");
            assertThat(row.message())
                    .contains("test discovery protocol handler threw IllegalStateException: decoder choked on totals")
                    .doesNotContain("exited");
            assertThat(row.stack()).contains("decoder choked on totals");
        });
        // A clean listing with a shutdown blemish keeps its verdict: the list survives a non-zero exit.
        assertThat(new Discovery(List.of("com.acme.ATest"), true, 1, "", List.of()).crashed())
                .isFalse();
    }

    /**
     * {@code discovery_total} closes the list. A fork that ended before it — killed, or exited 0
     * by a static initializer that called {@code System.exit} — named classes the suite cannot be
     * read from: whatever the exit, nothing runs off that list.
     */
    @Test
    void a_discovery_fork_without_its_total_is_crashed_whatever_its_exit() {
        var listing = new Discovery.Listing();
        Consumer<String> handler = listing.handler(TestProgressListener.noop());
        handler.accept("{\"event\":\"discovered\",\"class\":\"com.acme.ATest\"}");
        handler.accept("{\"event\":\"discovered\",\"class\":\"com.acme.BTest\"}");
        assertThat(listing.complete()).isFalse();

        Discovery cutShort = listing.ended(0, "", List.of("java", "Main"));
        assertThat(cutShort.classes()).containsExactly("com.acme.ATest", "com.acme.BTest");
        assertThat(cutShort.crashed()).isTrue();
        assertThatThrownBy(() -> cutShort.verdict("m")).isInstanceOfSatisfying(TestLauncherFailure.class, f -> {
            assertThat(f.exit()).isZero();
            assertThat(f.phase()).isEqualTo("test discovery");
            assertThat(f.getMessage())
                    .startsWith(
                            "test discovery exited 0 before announcing its total (2 classes named, none of them run)");
        });
        assertThat(listing.ended(137, "", List.of()).crashed()).isTrue();

        handler.accept("{\"event\":\"discovery_total\",\"classes\":2,\"tests\":5}");
        assertThat(listing.complete()).isTrue();
        assertThat(listing.ended(0, "", List.of()).crashed()).isFalse();

        // Nothing named and no total: the existing launcher failure, with the fork's exit.
        Discovery empty = new Discovery.Listing().ended(3, "boom", List.of());
        assertThatThrownBy(() -> empty.verdict("m")).isInstanceOfSatisfying(TestLauncherFailure.class, f -> {
            assertThat(f.exit()).isEqualTo(3);
            assertThat(f.getMessage()).startsWith("test discovery exited 3 before any test ran");
        });
    }

    @Test
    void failed_test_keeps_the_full_stack_trace() {
        var agg = new ResultAggregator();
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"c\",\"type\":\"TEST\",\"status\":\"FAILED\","
                + "\"testMethod\":\"c()\",\"throwable\":{\"class\":\"AssertionError\","
                + "\"message\":\"nope\",\"stack\":\"AssertionError: nope\\n\\tat Foo.c(Foo.java:9)\"}}");
        assertThat(completed(agg).failures()).singleElement().satisfies(f -> assertThat(f.stack())
                .contains("AssertionError: nope")
                .contains("at Foo.c(Foo.java:9)"));
    }

    @Test
    void stack_line_array_is_joined_for_legacy_runners() {
        var agg = new ResultAggregator();
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"c\",\"type\":\"TEST\",\"status\":\"FAILED\","
                + "\"testMethod\":\"c()\",\"throwable\":{\"class\":\"AssertionError\","
                + "\"message\":\"nope\",\"stack\":[\"AssertionError: nope\",\"\\tat Foo.c(Foo.java:9)\"]}}");
        assertThat(completed(agg).failures()).singleElement().satisfies(f -> assertThat(f.stack())
                .contains("AssertionError: nope")
                .contains("at Foo.c(Foo.java:9)"));
    }

    @Test
    void container_failure_is_captured_so_init_errors_are_visible() {
        // A class initializer / @BeforeAll error finishes the CONTAINER as FAILED
        // and fires no TEST event — capture it instead of a silent "runner exited".
        var agg = new ResultAggregator();
        agg.accept(
                "{\"event\":\"finished\",\"uniqueId\":\"cls\",\"testClass\":\"FooTest\",\"type\":\"CONTAINER\","
                        + "\"status\":\"FAILED\",\"throwable\":{\"class\":\"ExceptionInInitializerError\","
                        + "\"message\":\"\",\"stack\":\"ExceptionInInitializerError\\n\\tat FooTest.<clinit>(FooTest.java:3)\"}}");
        var result = completed(agg);
        assertThat(result.allPassed()).isFalse();
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.className()).isEqualTo("FooTest");
            assertThat(f.stack()).contains("ExceptionInInitializerError");
        });
    }

    @Test
    void a_non_zero_exit_after_a_pass_is_not_green() {
        var agg = new ResultAggregator();
        agg.accept("{\"event\":\"finished\",\"id\":\"method\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        var result = agg.toResult(137);
        assertThat(result.allPassed()).isFalse();
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void a_finished_run_and_a_cancel_after_every_test_stay_green() {
        var finished = new ResultAggregator();
        finished.accept("{\"event\":\"finished\",\"id\":\"method\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        finished.accept(PLAN_FINISHED);
        assertThat(finished.toResult(0).allPassed()).isTrue();
        assertThat(finished.toResult(0).failed()).isZero();

        SessionCancel.bind(() -> true);
        try {
            var cancelled = new ResultAggregator();
            cancelled.accept("{\"event\":\"discovery_total\",\"classes\":1,\"tests\":1}");
            cancelled.accept("{\"event\":\"finished\",\"id\":\"method\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
            var result = cancelled.toResult(137);
            assertThat(result.allPassed()).isTrue();
            assertThat(result.failed()).isZero();
        } finally {
            SessionCancel.bind(null);
        }
    }

    @Test
    void a_non_zero_exit_with_no_events_is_a_launcher_failure_carrying_the_crash_output() {
        var agg = new ResultAggregator();
        String crash = "Exception in thread \"main\" java.lang.NoClassDefFoundError: Missing\n"
                + "\tat cc.jumpkick.Boot.main(Boot.java:1)";
        assertThatThrownBy(() -> agg.toResult(1, crash, List.of())) // no events, non-zero exit
                .isInstanceOfSatisfying(TestLauncherFailure.class, f -> {
                    assertThat(f.exit()).isEqualTo(1);
                    assertThat(f.phase()).isEqualTo("test runner");
                    assertThat(f.output()).contains("NoClassDefFoundError").contains("at cc.jumpkick.Boot.main");
                });
    }

    @Test
    void capture_buffer_is_bounded_and_keeps_the_first_and_the_last_lines() {
        var buf = new CaptureBuffer();
        for (int i = 0; i < 1000; i++) buf.add("line " + i);
        String text = buf.text();
        assertThat(text).startsWith("line 0\n").contains("line 999").doesNotContain("line 500\n");
        assertThat(text.split("\n")).hasSizeLessThanOrEqualTo(CaptureBuffer.HEAD_LINES + CaptureBuffer.TAIL_LINES + 1);
    }

    /**
     * A runner-side warning without its {@code code} or {@code message} is still a warning: a
     * decoder that throws on the missing field kills the worker's pump, and the suite then reports
     * the worker as crashed instead of surfacing what it tried to warn about.
     */
    @Test
    void a_warning_event_missing_its_fields_is_still_delivered_as_a_warning() {
        var warnings = new ArrayList<String>();
        var agg = new ResultAggregator(
                new TestProgressListener() {
                    @Override
                    public void onWarning(String code, String message) {
                        warnings.add(code + ":" + message);
                    }
                },
                0);
        agg.accept("{\"event\":\"warning\"}");
        agg.accept("{\"event\":\"warning\",\"message\":\"only a message\"}");
        agg.accept("{\"event\":\"warning\",\"code\":\"jupiter-parallel\",\"message\":\"both\"}");
        assertThat(warnings).containsExactly("warning:", "warning:only a message", "jupiter-parallel:both");
    }
}

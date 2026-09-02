// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** {@link TestSupport#renderFailures} surfaces each failure's name + stack, not just a count. */
class TestFailureRenderingTest {

    @Test
    void no_failures_renders_nothing() {
        var result = new TestSummary(3, 3, 0, 0, List.of());
        assertThat(TestSupport.renderFailures(result)).isEmpty();
    }

    @Test
    void a_failure_renders_short_label_and_stack_when_no_snippet() {
        var f = new TestFailureInfo(
                "",
                "junit-jupiter",
                "cc.jumpkick.FooTest",
                "bar()",
                "org.opentest4j.AssertionFailedError",
                "expected: <1> but was: <2>",
                "org.opentest4j.AssertionFailedError: expected: <1> but was: <2>\n"
                        + "\tat cc.jumpkick.FooTest.bar(FooTest.java:42)");
        var result = new TestSummary(1, 0, 1, 0, List.of(f));

        List<String> lines = TestSupport.renderFailures(result);
        String text = String.join("\n", lines);

        assertThat(lines).anyMatch(l -> l.equals("Test Failure"));
        assertThat(lines).anyMatch(l -> l.equals("1 test failed"));
        assertThat(lines).anyMatch(l -> l.equals("FAILED FooTest.bar()"));
        assertThat(lines.getLast()).isEqualTo("Test Failure end");
        assertThat(text).contains("expected: <1> but was: <2>");
        assertThat(text).contains("at cc.jumpkick.FooTest.bar(FooTest.java:42)");
        // No package FQCN on the FAILED line
        assertThat(text).doesNotContain("FAILED cc.jumpkick.FooTest");
    }

    @Test
    void pluralises_and_falls_back_to_message_when_no_stack() {
        var a = new TestFailureInfo("", "", "A", "x()", "", "boom", "boom\n\tat A.x(A.java:1)");
        var b = new TestFailureInfo("", "", "", "(test run)", "", "runner exited 1", ""); // no stack
        var result = new TestSummary(2, 0, 2, 0, List.of(a, b));

        List<String> lines = TestSupport.renderFailures(result);
        assertThat(lines).anyMatch(l -> l.equals("Test Failure"));
        assertThat(lines).anyMatch(l -> l.equals("2 tests failed"));
        assertThat(lines).anyMatch(l -> l.contains("FAILED "));
        assertThat(String.join("\n", lines)).contains("runner exited 1");
    }

    @Test
    void short_label_uses_simple_class_and_keeps_params() {
        var f = new TestFailureInfo(
                "cc.jumpkick:jk-core",
                "junit-jupiter",
                "cc.jumpkick.runtime.FooTest",
                "freshen_preserves_pins(java.nio.file.Path)",
                "java.lang.AssertionError",
                "nope",
                "java.lang.AssertionError: nope",
                2);
        assertThat(TestSupport.shortTestLabel(f)).isEqualTo("FooTest.freshen_preserves_pins(Path)  [w2]");
        var invoked = new TestFailureInfo("", "", "demo.FooTest", "bar(java.lang.String)[#2]", "", "", "");
        assertThat(TestSupport.shortTestLabel(invoked)).isEqualTo("FooTest.bar(String)[#2]");
        List<String> lines = TestSupport.renderFailures(new TestSummary(1, 0, 1, 0, List.of(f)));
        assertThat(lines).anyMatch(l -> l.equals("module: cc.jumpkick:jk-core"));
        assertThat(lines).anyMatch(l -> l.equals("FAILED FooTest.freshen_preserves_pins(Path)  [w2]"));
        assertThat(String.join("\n", lines)).doesNotContain("class: cc.jumpkick");
        var diag = new BuildPlanResult.Diagnostic("run-tests", "test-failure", f.message(), f);
        assertThat(diag.worker()).isEqualTo(2);
        assertThat(diag.testFailure().worker()).isEqualTo(2);
    }

    /**
     * The point of collapsing {@code TestSummary.Failure} into {@link TestFailureInfo}: a
     * summary failure now <em>is</em> the wire record, so source context reaches a plan diagnostic.
     * The old summary-local record had no {@code file}/{@code line}/{@code snippet} components at
     * all, and its {@code toInfo()} adapter minted them empty — a lossy hop no caller could route
     * around.
     */
    @Test
    void a_summary_failure_carries_source_context_into_a_diagnostic() {
        var f = new TestFailureInfo(
                "cc.jumpkick:jk-core",
                "junit-jupiter",
                "demo.ZTest",
                "d()",
                "java.lang.AssertionError",
                "nope",
                "java.lang.AssertionError: nope\n\tat demo.ZTest.d(ZTest.java:7)",
                3,
                "src/test/java/demo/ZTest.java",
                7,
                5,
                List.of("void d() {", "    fail();", "}"));
        var summary = new TestSummary(1, 0, 1, 0, List.of(f));

        var diag = new BuildPlanResult.Diagnostic(
                "run-tests", "test-failure", f.message(), summary.failures().getFirst());

        assertThat(diag.file()).isEqualTo("src/test/java/demo/ZTest.java");
        assertThat(diag.line()).isEqualTo(7);
        assertThat(diag.snippetStart()).isEqualTo(5);
        assertThat(diag.snippet()).containsExactly("void d() {", "    fail();", "}");
        assertThat(diag.testFailure().worker()).isEqualTo(3);
        assertThat(f.label()).isEqualTo("cc.jumpkick:jk-core :: d()  [w3]");
    }

    @Test
    void progressLabel_formats_module_and_worker() {
        assertThat(TestSupport.progressLabel("cc.jumpkick:core", "Foo > t()", 2, 4))
                .isEqualTo("cc.jumpkick:core :: Foo > t()  [w2]");
        assertThat(TestSupport.progressLabel("", "Foo > t()", 0, 1)).isEqualTo("Foo > t()");
    }

    @Test
    void liveTestDetail_prefers_class_dot_method() {
        String id = "[engine:junit-jupiter]/[class:cc.jumpkick.runtime.FooTest]/[method:bar()]";
        assertThat(TestSupport.liveTestDetail(id, "bar()", true)).isEqualTo("FooTest.bar()");
        assertThat(TestSupport.liveTestDetail(id, "FooTest", false)).isEqualTo("FooTest");
        assertThat(TestSupport.liveTestDetail(id, "FooTest > bar()", true)).isEqualTo("FooTest.bar()");
        assertThat(TestSupport.liveTestDetail(
                        "[engine:junit-jupiter]/[class:cc.jumpkick.runtime.VariantSwitchTest]/[method:switching_variants(java.nio.file.Path)]",
                        "switching_variants(Path)",
                        true))
                .isEqualTo("VariantSwitchTest.switching_variants(Path)");
        // Display may still carry FQCN params from JUnit — never surface them live.
        assertThat(TestSupport.liveTestDetail(
                        "[engine:junit-jupiter]/[class:cc.jumpkick.runtime.VariantSwitchTest]/[method:switching_variants(java.nio.file.Path)]",
                        "switching_variants(java.nio.file.Path)",
                        true))
                .isEqualTo("VariantSwitchTest.switching_variants(Path)");
        assertThat(TestSupport.simpleClassName("cc.jumpkick.runtime.FooTest")).isEqualTo("FooTest");
        assertThat(TestSupport.liveTestDetail(
                        "[engine:junit-jupiter]/[class:demo.FooTest]/[method:bar(java.lang.String%5B%5D)]",
                        "bar(java.lang.String[])", true))
                .isEqualTo("FooTest.bar(String[])");
    }

    @Test
    void short_label_decodes_array_params_to_simple_names() {
        var f = new TestFailureInfo(
                "", "junit-jupiter", "demo.FooTest", "bar(java.lang.String[])", "java.lang.AssertionError", "nope", "");
        assertThat(TestSupport.shortTestLabel(f)).isEqualTo("FooTest.bar(String[])");
    }

    @Test
    void bridgeListener_labels_on_test_start() {
        AtomicReference<String> last = new AtomicReference<>();
        var ctx = new LabelCaptureContext(last);
        var listener = TestSupport.bridgeListener(ctx, 1, false, "cc.jumpkick:core");
        listener.onTestStarted(
                "[engine:junit-jupiter]/[class:cc.jumpkick.runtime.FooTest]/[method:bar()]", "bar()", true, 1);
        assertThat(last.get()).isEqualTo("cc.jumpkick:core :: FooTest.bar()");
    }

    private static final class LabelCaptureContext implements TaskContext {
        private final AtomicReference<String> last;

        LabelCaptureContext(AtomicReference<String> last) {
            this.last = last;
        }

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(String description) {
            last.set(description);
        }

        @Override
        public void output(String line) {}

        @Override
        public void warn(String code, String message) {}

        @Override
        public void error(String code, String message) {}

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public <T> void put(BuildPlanKey<T> key, T value) {}

        @Override
        public <T> Optional<T> get(BuildPlanKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> T require(BuildPlanKey<T> key) {
            throw new IllegalStateException("missing " + key);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
    void a_failure_renders_name_and_indented_stack() {
        var f = new TestSummary.Failure(
                "cc.jumpkick.FooTest > bar()",
                "org.opentest4j.AssertionFailedError",
                "expected: <1> but was: <2>",
                "org.opentest4j.AssertionFailedError: expected: <1> but was: <2>\n"
                        + "\tat cc.jumpkick.FooTest.bar(FooTest.java:42)");
        var result = new TestSummary(1, 0, 1, 0, List.of(f));

        List<String> lines = TestSupport.renderFailures(result);
        String text = String.join("\n", lines);

        assertThat(lines).anyMatch(l -> l.equals("1 test failed:"));
        assertThat(lines).anyMatch(l -> l.contains("FAILED  cc.jumpkick.FooTest > bar()"));
        assertThat(text).contains("AssertionFailedError: expected: <1> but was: <2>");
        assertThat(text).contains("at cc.jumpkick.FooTest.bar(FooTest.java:42)");
    }

    @Test
    void pluralises_and_falls_back_to_message_when_no_stack() {
        var a = new TestSummary.Failure("A > x()", "", "boom", "boom\n\tat A.x(A.java:1)");
        var b = new TestSummary.Failure("(test run)", "", "runner exited 1", ""); // no stack
        var result = new TestSummary(2, 0, 2, 0, List.of(a, b));

        List<String> lines = TestSupport.renderFailures(result);
        assertThat(lines).anyMatch(l -> l.equals("2 tests failed:"));
        // The stack-less failure falls back to its one-line message.
        assertThat(lines).anyMatch(l -> l.contains("FAILED  (test run)"));
        assertThat(String.join("\n", lines)).contains("runner exited 1");
    }

    @Test
    void headline_includes_module_and_worker() {
        var f = new TestSummary.Failure(
                "FooTest > bar()",
                "java.lang.AssertionError",
                "nope",
                "java.lang.AssertionError: nope",
                "cc.jumpkick:jk-core",
                "cc.jumpkick.FooTest",
                2);
        assertThat(f.headline()).isEqualTo("cc.jumpkick:jk-core :: FooTest > bar()  [w2]");
        List<String> lines = TestSupport.renderFailures(new TestSummary(1, 0, 1, 0, List.of(f)));
        assertThat(lines).anyMatch(l -> l.contains("FAILED  cc.jumpkick:jk-core :: FooTest > bar()  [w2]"));
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
        assertThat(TestSupport.simpleClassName("cc.jumpkick.runtime.FooTest")).isEqualTo("FooTest");
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

    private static final class LabelCaptureContext implements cc.jumpkick.run.TaskContext {
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
        public <T> void put(cc.jumpkick.run.BuildPlanKey<T> key, T value) {}

        @Override
        public <T> Optional<T> get(cc.jumpkick.run.BuildPlanKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> T require(cc.jumpkick.run.BuildPlanKey<T> key) {
            throw new IllegalStateException("missing " + key);
        }
    }
}

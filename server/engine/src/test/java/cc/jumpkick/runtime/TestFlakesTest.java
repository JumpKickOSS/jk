// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestHistory;
import cc.jumpkick.config.TestQuarantine;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each class's recent runs, the quarantine they feed, and the failures the quarantine excuses. */
class TestFlakesTest {

    private static final Map<String, TestFlakes.Outcome> RED = Map.of("a.FlakyTest", TestFlakes.Outcome.FAILED);
    private static final Map<String, TestFlakes.Outcome> RERUN_PASS =
            Map.of("a.FlakyTest", TestFlakes.Outcome.PASSED_ON_RERUN);

    @TempDir
    Path tmp;

    private Path root;
    private Path module;
    private Path state;
    private final Recording ctx = new Recording();

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("ws"));
        module = Files.createDirectories(root.resolve("app"));
        state = tmp.resolve("state");
        TomlScan.clearCache();
    }

    private void run(Map<String, TestFlakes.Outcome> outcomes) {
        TestFlakes.record(ctx, state, root, module, outcomes);
    }

    private TestQuarantine quarantine() throws IOException {
        return TestQuarantine.read(root);
    }

    @Test
    void every_run_appends_to_each_class_history_oldest_first() throws IOException {
        run(Map.of("a.FlakyTest", TestFlakes.Outcome.PASSED, "a.OkTest", TestFlakes.Outcome.PASSED));
        run(RED);
        run(RERUN_PASS);

        Path file = TestHistory.file(state, module);
        assertThat(Files.readAllLines(file).getFirst())
                .isEqualTo("# " + module.toAbsolutePath().normalize());
        assertThat(TestHistory.read(file)).containsExactly(Map.entry("a.FlakyTest", ".FR"), Map.entry("a.OkTest", "."));
    }

    @Test
    void the_second_rerun_pass_in_twenty_runs_quarantines_the_class_and_says_so() throws IOException {
        run(RED);
        run(RERUN_PASS);
        assertThat(quarantine().entries())
                .as("one re-run pass is under the threshold")
                .isEmpty();
        assertThat(ctx.warnings).isEmpty();

        run(RED);
        run(RERUN_PASS);

        assertThat(quarantine().entries()).singleElement().satisfies(e -> {
            assertThat(e.className()).isEqualTo("a.FlakyTest");
            assertThat(e.module()).isEqualTo("app");
            assertThat(e.reason()).contains("2 of its last 20 runs");
            assertThat(e.since()).matches("\\d{4}-\\d{2}-\\d{2}");
        });
        assertThat(ctx.warnings)
                .containsExactly(TestFlakes.QUARANTINED
                        + " quarantined a.FlakyTest: passed on a re-run 2 of its last 20" + " runs");

        run(RED);
        run(RERUN_PASS);
        assertThat(quarantine().entries()).as("a class is quarantined once").hasSize(1);
        assertThat(ctx.warnings).hasSize(1);
    }

    @Test
    void a_rerun_pass_older_than_the_window_does_not_count() throws IOException {
        Files.writeString(root.resolve("jk.toml"), "name = \"ws\"\n[test]\nquarantine = { flakes = 2, runs = 3 }\n");
        run(RED);
        run(RERUN_PASS);
        run(Map.of("a.FlakyTest", TestFlakes.Outcome.PASSED));
        run(RED);
        run(RERUN_PASS);
        assertThat(quarantine().entries())
                .as("the first re-run pass is four runs back")
                .isEmpty();

        run(RERUN_PASS);
        assertThat(quarantine().entries()).hasSize(1);
    }

    @Test
    void a_released_class_needs_the_whole_threshold_again_and_keeps_its_history() throws IOException {
        run(RED);
        run(RERUN_PASS);
        run(RED);
        run(RERUN_PASS);
        assertThat(TestQuarantine.release(state, root, "a.FlakyTest")).hasSize(1);

        run(RED);
        run(RERUN_PASS);
        assertThat(quarantine().entries())
                .as("the re-run passes before the release do not count")
                .isEmpty();
        assertThat(TestHistory.read(TestHistory.file(state, module))).containsEntry("a.FlakyTest", "FRFR|FR");

        run(RED);
        run(RERUN_PASS);
        assertThat(quarantine().entries()).hasSize(1);
    }

    @Test
    void the_root_can_turn_it_off_and_the_history_is_still_kept() throws IOException {
        Files.writeString(root.resolve("jk.toml"), "name = \"ws\"\n[test]\nquarantine = false\n");
        for (int i = 0; i < 5; i++) {
            run(RED);
            run(RERUN_PASS);
        }
        assertThat(Files.exists(TestQuarantine.file(root))).isFalse();
        assertThat(TestHistory.read(TestHistory.file(state, module))).containsEntry("a.FlakyTest", "FRFRFRFRFR");
    }

    @Test
    void the_history_keeps_the_last_hundred_runs() throws IOException {
        Files.writeString(root.resolve("jk.toml"), "name = \"ws\"\n[test]\nquarantine = false\n");
        for (int i = 0; i < TestFlakes.KEEP + 5; i++) run(RED);
        assertThat(TestHistory.read(TestHistory.file(state, module)).get("a.FlakyTest"))
                .hasSize(TestFlakes.KEEP);
    }

    private static TestFailureInfo failure(String className) {
        return new TestFailureInfo("g:app", "junit-jupiter", className, "t", "AssertionError", "boom", "");
    }

    private static TestSummary red(TestFailureInfo... failures) {
        return new TestSummary(5, 5 - failures.length, failures.length, 0, List.of(failures));
    }

    @Test
    void failures_only_in_quarantined_classes_are_excused_and_any_other_is_not() throws IOException {
        TestQuarantine.NONE
                .with(new TestQuarantine.Entry("a.FlakyTest", "app", "", "", null))
                .write(root);

        assertThat(TestFlakes.quarantinedFailures(
                        ctx, root, module, red(failure("a.FlakyTest"), failure("a.FlakyTest$Inner"))))
                .containsExactly("a.FlakyTest");
        assertThat(TestFlakes.quarantinedFailures(
                        ctx, root, module, red(failure("a.FlakyTest"), failure("a.RealTest"))))
                .as("a failure outside the quarantine fails the run")
                .isEmpty();
        assertThat(TestFlakes.quarantinedFailures(ctx, root, module, red(failure(""))))
                .as("a failure no class owns")
                .isEmpty();
        assertThat(TestFlakes.quarantinedFailures(
                        ctx, root, Files.createDirectories(root.resolve("other")), red(failure("a.FlakyTest"))))
                .as("another module's class of the same name")
                .isEmpty();
        assertThat(TestFlakes.quarantinedFailures(
                        ctx, root, module, new TestSummary(5, 3, 2, 0, List.of(failure("a.FlakyTest")))))
                .as("more failed tests than failures named")
                .isEmpty();
        assertThat(TestFlakes.quarantinedFailedMessage(List.of("a.FlakyTest")))
                .isEqualTo("quarantined flaky test failed, not your change: a.FlakyTest");
    }

    @Test
    void an_unreadable_quarantine_excuses_nothing_and_says_so() throws IOException {
        Files.createDirectories(TestQuarantine.file(root).getParent());
        Files.writeString(TestQuarantine.file(root), "[[class]\n");
        assertThat(TestFlakes.quarantinedFailures(ctx, root, module, red(failure("a.FlakyTest"))))
                .isEmpty();
        assertThat(ctx.warnings)
                .singleElement()
                .asString()
                .startsWith(TestFlakes.UNREADABLE + " .jk/test/quarantine.toml");
    }

    @Test
    void a_quarantine_is_told_to_the_listener_once_with_the_module_and_its_classes() {
        List<TestFlakes.Quarantined> heard = new CopyOnWriteArrayList<>();
        Path here = root.toAbsolutePath().normalize();
        Consumer<TestFlakes.Quarantined> listener = q -> {
            if (q.root().equals(here)) heard.add(q);
        };
        TestFlakes.listen(listener);
        try {
            run(RED);
            run(RERUN_PASS);
            assertThat(heard).as("under the threshold").isEmpty();
            run(RED);
            run(RERUN_PASS);
            run(RED);
            run(RERUN_PASS);
        } finally {
            TestFlakes.detach(listener);
        }
        assertThat(heard).containsExactly(new TestFlakes.Quarantined(here, "app", List.of("a.FlakyTest")));
    }

    @Test
    void the_report_lists_the_quarantined_first_then_the_classes_that_passed_on_a_rerun_in_the_window()
            throws IOException {
        Path other = Files.createDirectories(root.resolve("lib"));
        Path elsewhere = Files.createDirectories(tmp.resolve("other-ws/app"));
        TestFlakes.record(ctx, state, root, other, Map.of("b.OnceTest", TestFlakes.Outcome.PASSED_ON_RERUN));
        TestFlakes.record(ctx, state, root, elsewhere, Map.of("c.AwayTest", TestFlakes.Outcome.PASSED_ON_RERUN));
        run(Map.of("a.OkTest", TestFlakes.Outcome.PASSED, "a.RedTest", TestFlakes.Outcome.FAILED));
        for (int i = 0; i < 2; i++) {
            run(RED);
            run(RERUN_PASS);
        }
        TestQuarantine.read(root)
                .with(new TestQuarantine.Entry(
                        "a.ByHandTest", "", "hangs on CI", "2026-11-01", new TestQuarantine.Issue(null, "https://x/1")))
                .write(root);

        TestFlakes.Report report = TestFlakes.report(state, root);

        assertThat(report.error()).isNull();
        assertThat(report.policy()).isEqualTo(TestQuarantine.Policy.DEFAULT);
        assertThat(report.rows())
                .extracting(r -> r.module() + " " + r.className() + " " + r.history() + " " + r.flakes())
                .containsExactly("app a.FlakyTest FRFR 2", " a.ByHandTest  0", "lib b.OnceTest R 1");
        assertThat(report.rows().get(0).held()).isNotNull();
        assertThat(report.rows().get(1).held())
                .extracting(TestQuarantine.Entry::issue)
                .isEqualTo(new TestQuarantine.Issue(null, "https://x/1"));
        assertThat(report.rows().get(2).held()).isNull();
    }

    @Test
    void the_report_says_why_an_unreadable_quarantine_lists_nothing() throws IOException {
        Files.createDirectories(TestQuarantine.file(root).getParent());
        Files.writeString(TestQuarantine.file(root), "[[class]\n");
        run(RERUN_PASS);
        TestFlakes.Report report = TestFlakes.report(state, root);
        assertThat(report.error()).startsWith(".jk/test/quarantine.toml unreadable: ");
        assertThat(report.rows()).singleElement().satisfies(r -> assertThat(r.held())
                .isNull());
    }

    /** A step context that keeps its warnings as {@code <code> <message>}. */
    private static final class Recording implements TaskContext {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(@Nullable String description) {}

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void warn(String code, String message) {
            warnings.add(code + " " + message);
        }

        @Override
        public void error(String code, String message) {
            throw new AssertionError(message);
        }

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
            throw new IllegalStateException("no " + key);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestClassResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.test.TestProgressListener;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Build history has a row for every class of a suite the run settled: a class left out because it
 * passed under the same stamp, and every class of a suite served from its marker, each said
 * replayed with when it last ran.
 */
class TestClassReplayRowsTest {

    private static final String STAMP = "b".repeat(64);
    private static final String TASK = "run-tests@0123456789ab";

    @TempDir
    Path tmp;

    private ActionCache cache;

    @BeforeEach
    void setUp() {
        cache = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
    }

    @Test
    void a_class_left_out_under_the_same_stamp_is_said_replayed_with_when_it_ran() throws Exception {
        long before = System.currentTimeMillis();
        TestClassLedger first = load();
        first.enter(SuiteResources.ONE_LAUNCH);
        TestProgressListener l = first.recording(TestProgressListener.noop());
        run(l, "demo.OkTest", "SUCCESSFUL", "SUCCESSFUL");
        run(l, "demo.RedTest", "FAILED");
        first.finish(new Rows(), cache, TASK, STAMP, STAMP, new TestSummary(3, 2, 1, 0, List.of()));

        TestClassLedger second = load();
        assertThat(second.enter(SuiteResources.ONE_LAUNCH)).containsExactly("demo.OkTest");
        run(second.recording(TestProgressListener.noop()), "demo.RedTest", "SUCCESSFUL");
        Rows rows = new Rows();
        second.finish(rows, cache, TASK, STAMP, STAMP, new TestSummary(1, 1, 0, 0, List.of()));

        assertThat(rows.classes).hasSize(1);
        TestClassResult ok = rows.classes.getFirst();
        assertThat(ok.className()).isEqualTo("demo.OkTest");
        assertThat(ok.replayed()).isTrue();
        assertThat(ok.replayedFrom()).as("when it ran").isBetween(before, System.currentTimeMillis());
        assertThat(ok.tests()).isEqualTo(2);
        assertThat(ok.module()).isEqualTo("g:app");
    }

    @Test
    void every_class_of_a_suite_served_whole_is_said_replayed() throws Exception {
        TestClassLedger first = load();
        first.enter(SuiteResources.ONE_LAUNCH);
        TestProgressListener l = first.recording(TestProgressListener.noop());
        run(l, "demo.ATest", "SUCCESSFUL");
        run(l, "demo.BTest", "SUCCESSFUL", "SKIPPED");
        first.finish(new Rows(), cache, TASK, STAMP, STAMP, new TestSummary(3, 2, 0, 1, List.of()));

        Rows rows = new Rows();
        load().replayCovered(rows, cls -> !cls.equals("demo.ATest"), suite -> true);
        assertThat(rows.classes).extracting(TestClassResult::className).containsExactly("demo.BTest");
        assertThat(rows.classes.getFirst().skipped()).isEqualTo(1);
        assertThat(rows.classes.getFirst().replayed()).isTrue();
    }

    @Test
    void an_entry_recorded_before_run_times_were_kept_replays_undated() {
        TestClassLedger.Entry old = TestClassLedger.Entry.decode("3,0,1");
        assertThat(old).isEqualTo(new TestClassLedger.Entry(3, 0, 1, 0, 0));
        TestClassLedger.Entry now = new TestClassLedger.Entry(3, 0, 1, 1_763_250_000_000L, 412);
        assertThat(TestClassLedger.Entry.decode(now.encode())).isEqualTo(now);
    }

    private TestClassLedger load() {
        return TestClassLedger.load(cache, STAMP, "g:app", "fast");
    }

    private static String cls(String name) {
        return "[engine:junit-jupiter]/[class:" + name + "]";
    }

    private static void run(TestProgressListener l, String name, String... statuses) {
        for (int i = 0; i < statuses.length; i++) {
            String id = cls(name) + "/[method:t" + i + "()]";
            if ("SKIPPED".equals(statuses[i])) l.onTestSkipped(id, "t" + i, "off", true, true, 0);
            else l.onTestFinished(id, "t" + i, statuses[i], true, true, 5, 0);
        }
        l.onTestFinished(cls(name), name, "SUCCESSFUL", false, true, 10, 0);
    }

    /** A step context that keeps the class rows it is told. */
    private static final class Rows implements TaskContext {
        final List<TestClassResult> classes = new ArrayList<>();

        @Override
        public void testClass(TestClassResult result) {
            classes.add(result);
        }

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(@Nullable String description) {}

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void waited(Duration blocked) {}

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
            throw new IllegalStateException(key.toString());
        }
    }
}

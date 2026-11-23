// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A suite that ran out of memory runs again until it stops: a memory kill once more at the same
 * heap, a heap exhaustion up the heaps the engine offers, each rung's run replacing what the run so
 * far held for its classes, and the loop over when no heap is left.
 */
class RetryLoopTest {

    private static final RetryPolicy POLICY =
            new RetryPolicy(text -> text.contains("killed for memory"), text -> text.contains("Java heap space"), 3);

    private static final long MIB = 1024 * 1024;

    @Test
    void a_run_with_nothing_to_retry_stands_and_forks_nothing() {
        Engine engine = new Engine(512 * MIB);
        TestSummary first = summary(1, failure("acme.FooTest", "java.lang.AssertionError", "boom"));

        assertThat(engine.loop().afterRun(first, List.of(), List.of(), true, 256 * MIB))
                .isSameAs(first);
        assertThat(engine.forked).isEmpty();
    }

    @Test
    void a_class_that_ran_out_of_heap_climbs_until_it_passes() {
        Engine engine = new Engine(512 * MIB, 1024 * MIB);
        engine.rungs.add(new RetryLoop.Rung.Ran(summary(1, heap("acme.BigTest")), true, () -> {}, ""));
        engine.rungs.add(new RetryLoop.Rung.Ran(new TestSummary(1, 1, 0, 0, List.of()), false, () -> {}, ""));
        TestSummary first = summary(2, heap("acme.BigTest"));

        TestSummary after = engine.loop().afterRun(first, List.of(), List.of(), true, 256 * MIB);

        assertThat(after.failures()).isEmpty();
        assertThat(engine.forked).containsExactly("acme.BigTest@512", "acme.BigTest@1024");
        assertThat(engine.filled).containsExactly(256 * MIB, 512 * MIB);
        assertThat(engine.sufficed).containsExactly(1024 * MIB);
    }

    @Test
    void a_memory_kill_runs_once_more_at_the_same_heap() {
        Engine engine = new Engine();
        engine.rungs.add(new RetryLoop.Rung.Ran(new TestSummary(1, 1, 0, 0, List.of()), false, () -> {}, ""));
        TestSummary first = summary(1, failure("acme.BigTest", "", "worker killed for memory"));

        TestSummary after = engine.loop().afterRun(first, List.of(), List.of(), false, 256 * MIB);

        assertThat(after.failures()).isEmpty();
        assertThat(engine.forked).containsExactly("acme.BigTest@256");
        assertThat(engine.climbs).containsExactly("256<-256 killed");
    }

    @Test
    void a_rung_that_reports_only_its_heap_running_out_is_put_back_and_the_ladder_ends_in_failure() {
        Engine engine = new Engine(512 * MIB);
        List<String> restored = new ArrayList<>();
        engine.rungs.add(
                new RetryLoop.Rung.Ran(summary(1, heap("acme.BigTest")), true, () -> restored.add("x"), "oom"));

        assertThatThrownBy(() -> engine.loop().afterDeath(List.of("acme.BigTest"), false, 256 * MIB))
                .hasMessage("exhausted [268435456, 536870912] oom");
        assertThat(restored).containsExactly("x");
    }

    @Test
    void a_launch_that_died_with_no_heap_left_to_try_has_no_retry() {
        assertThat(new Engine().loop().afterDeath(List.of(), false, 256 * MIB)).isNull();
    }

    /** The engine's side of the loop, with heaps offered and rungs run from a script. */
    private static final class Engine implements RetryLoop.Forks {
        final Deque<Long> heaps = new ArrayDeque<>();
        final Deque<RetryLoop.Rung> rungs = new ArrayDeque<>();
        final List<String> forked = new ArrayList<>();
        final List<Long> filled = new ArrayList<>();
        final List<Long> sufficed = new ArrayList<>();
        final List<String> climbs = new ArrayList<>();

        Engine(long... heaps) {
            for (long heap : heaps) this.heaps.add(heap);
        }

        RetryLoop loop() {
            return new RetryLoop(POLICY, this);
        }

        @Override
        public @Nullable Long nextHeap(List<Long> ranOut) {
            return heaps.poll();
        }

        @Override
        public void filled(long heap) {
            filled.add(heap);
        }

        @Override
        public void sufficed(long heap) {
            sufficed.add(heap);
        }

        @Override
        public void climbing(long next, long failed, boolean killed) {
            climbs.add(next / MIB + "<-" + failed / MIB + (killed ? " killed" : ""));
        }

        @Override
        public RetryPolicy.Share share(List<String> classes) {
            return new RetryPolicy.Share(0, 0, classes.size());
        }

        @Override
        public RetryLoop.Rung fork(List<String> classes, long heap) {
            forked.add(String.join(",", classes) + "@" + heap / MIB);
            return rungs.remove();
        }

        @Override
        public RuntimeException exhausted(List<Long> ranOut, RetryLoop.Rung.Died last) {
            return new IllegalStateException("exhausted " + ranOut + " " + last.output());
        }
    }

    private static TestSummary summary(long total, TestFailureInfo... failures) {
        return new TestSummary(total, total - failures.length, failures.length, 0, List.of(failures));
    }

    private static TestFailureInfo heap(String className) {
        return failure(className, OutOfMemoryError.class.getName(), "Java heap space");
    }

    private static TestFailureInfo failure(String className, String exceptionClass, String message) {
        return new TestFailureInfo("g:app", "", className, "big()", exceptionClass, message, "", 0);
    }
}

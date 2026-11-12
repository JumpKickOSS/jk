// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A fan-out keeps input order and failures, and its work sees the caller's carried values on every thread. */
class ParallelMapTest {

    @Test
    void results_keep_the_input_order_on_both_sides_of_the_threshold() throws IOException {
        for (int n : new int[] {3, ParallelMap.THRESHOLD * 20}) {
            List<Integer> in = IntStream.range(0, n).boxed().toList();
            assertThat(ParallelMap.map(in, i -> i * 2))
                    .isEqualTo(in.stream().map(i -> i * 2).toList());
        }
    }

    @Test
    void an_io_failure_is_rethrown_as_itself() {
        List<Integer> in = IntStream.range(0, ParallelMap.THRESHOLD * 4).boxed().toList();
        assertThatThrownBy(() -> ParallelMap.map(in, i -> {
                    if (i == 100) throw new IOException("item 100");
                    return i;
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("item 100");
    }

    @Test
    void a_null_result_is_kept_in_place() {
        List<Integer> in = IntStream.range(0, ParallelMap.THRESHOLD * 2).boxed().toList();
        List<@Nullable String> out =
                ParallelMap.<Integer, @Nullable String, RuntimeException>map(in, i -> i % 2 == 0 ? null : "odd");
        assertThat(out).hasSize(in.size());
        assertThat(out.get(0)).isNull();
        assertThat(out.get(1)).isEqualTo("odd");
    }

    @Test
    void every_item_sees_the_callers_step_whichever_thread_runs_it() throws Exception {
        TaskContext step = step("test");
        int n = ParallelMap.THRESHOLD * 4;
        List<Integer> in = IntStream.range(0, n).boxed().toList();
        Set<String> threads = ConcurrentHashMap.newKeySet();
        CountDownLatch spread = new CountDownLatch(2);

        List<@Nullable TaskContext> seen = StepScope.call(
                step,
                () -> ParallelMap.<Integer, @Nullable TaskContext, Exception>map(in, i -> {
                    if (threads.add(Thread.currentThread().getName())) spread.countDown();
                    // Hold the first items until a second thread has joined, so the hop is proven, not hoped for.
                    if (i < 2) spread.await(10, TimeUnit.SECONDS);
                    return StepScope.current();
                }));

        assertThat(threads).as("the work left the calling thread").hasSizeGreaterThan(1);
        assertThat(seen).hasSize(n).allSatisfy(s -> assertThat(s).isSameAs(step));
    }

    @Test
    void a_fan_out_started_on_a_pool_thread_finishes_there() throws Exception {
        List<Integer> in = IntStream.range(0, ParallelMap.THRESHOLD * 8).boxed().toList();
        List<Integer> out = JkThreads.cpu()
                .submit(() -> ParallelMap.map(
                        in, i -> ParallelMap.map(List.of(i, i), 2, j -> j).getFirst()))
                .get(30, TimeUnit.SECONDS);
        assertThat(out).isEqualTo(in);
    }

    private static TaskContext step(String name) {
        return (TaskContext) Proxy.newProxyInstance(
                TaskContext.class.getClassLoader(),
                new Class<?>[] {TaskContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    case "cancelled" -> false;
                    default -> null;
                });
    }
}

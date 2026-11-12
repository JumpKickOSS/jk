// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

/** A step's scope follows its work onto every pool and tells its listeners once when it closes. */
class StepScopeTest {

    @Test
    void a_task_on_any_pool_reports_to_the_step_that_submitted_it() throws Exception {
        TaskContext step = step("compile");
        for (ExecutorService pool : List.of(JkThreads.cpu(), JkThreads.io(), JkThreads.platform())) {
            TaskContext seen = StepScope.call(step, () -> sample(pool));
            assertThat(seen).isSameAs(step);
        }
    }

    @Test
    void a_task_submitted_outside_a_step_sees_none_wherever_it_runs() throws Exception {
        Callable<TaskContext> outside = ContextPropagator.wrapCallable(StepScope::current);

        TaskContext seen = StepScope.call(step("test"), () -> call(outside));

        assertThat(seen).isNull();
        assertThat(sample(JkThreads.cpu())).isNull();
    }

    @Test
    void closing_tells_each_listener_which_step_once_the_body_is_done() {
        TaskContext step = step("package");
        List<String> events = new CopyOnWriteArrayList<>();
        StepScope.onClose(closed -> {
            if (closed == step) events.add("closed " + StepScope.current());
        });
        long before = StepScope.closed();

        StepScope.run(step, () -> events.add("body"));

        assertThat(events).containsExactly("body", "closed null");
        assertThat(StepScope.closed()).isGreaterThan(before);
        assertThat(StepScope.current()).isNull();
    }

    private static TaskContext sample(ExecutorService pool) {
        return call(() -> pool.submit(StepScope::current).get());
    }

    private static <T> T call(Callable<T> body) {
        try {
            return body.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.Await;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A CPU step is announced as it is submitted. When every engine CPU thread is busy its body has not
 * begun, and its row hears that it waits for a thread until the body does.
 */
class BuildPlanQueuedStepTest {

    /** Holds every thread of the CPU pool in a plain monitor wait, which the pool does not compensate. */
    private static final class Saturation implements AutoCloseable {
        private final Object gate = new Object();
        private boolean open;

        Saturation() throws InterruptedException {
            AtomicInteger inside = new AtomicInteger();
            for (int i = 0; i < JkThreads.CPU_THREADS; i++) {
                JkThreads.cpu().execute(() -> {
                    inside.incrementAndGet();
                    synchronized (gate) {
                        while (!open) {
                            try {
                                gate.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                });
            }
            Await.until(Duration.ofSeconds(10), () -> inside.get() == JkThreads.CPU_THREADS);
            Await.until(Duration.ofSeconds(10), JkThreads::cpuSaturated);
        }

        @Override
        public void close() {
            synchronized (gate) {
                open = true;
                gate.notifyAll();
            }
        }
    }

    private static BuildPlan plan(List<String> heard) {
        return BuildPlan.builder("queued")
                .addListener(new BuildPlanListener() {
                    @Override
                    public void stepStart(String step, @Nullable String group, int ticks) {
                        heard.add("start " + step);
                    }

                    @Override
                    public void waiting(String step, @Nullable String reason) {
                        heard.add("waiting " + step + " " + reason);
                    }
                })
                .addTask(Task.builder("guard")
                        .kind(TaskKind.CPU)
                        .ticks(1)
                        .execute(ctx -> heard.add("body guard"))
                        .build())
                .build();
    }

    @Test
    void a_step_with_no_free_thread_says_it_waits_for_one_until_its_body_begins() throws Exception {
        List<String> heard = new CopyOnWriteArrayList<>();
        BuildPlan plan = plan(heard);
        Thread runner;
        try (Saturation busy = new Saturation()) {
            runner = Thread.ofPlatform().daemon().start(plan::run);
            Await.until(Duration.ofSeconds(10), () -> heard.contains("waiting guard thread"));
            assertThat(heard).doesNotContain("body guard");
        }
        runner.join(Duration.ofSeconds(10));

        assertThat(heard).containsExactly("start guard", "waiting guard thread", "waiting guard null", "body guard");
    }

    @Test
    void a_step_that_finds_a_free_thread_says_nothing_of_waiting() throws InterruptedException {
        List<String> heard = new CopyOnWriteArrayList<>();
        Await.until(Duration.ofSeconds(10), () -> !JkThreads.cpuSaturated());

        plan(heard).run();

        assertThat(heard).containsExactly("start guard", "body guard");
    }
}

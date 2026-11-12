// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Heap-retry notes reach the step that retried, not whichever module of the request flushes first. */
class HeapNotesTest {

    @Test
    void each_module_of_a_parallel_build_flushes_only_its_own_retries() {
        List<String> appWarnings = new ArrayList<>();
        List<String> libWarnings = new ArrayList<>();
        TaskContext appTests = recording(appWarnings);
        TaskContext libTests = recording(libWarnings);

        // Both suites retry; the app's finishes first and flushes while the library's still runs.
        CountDownLatch bothNoted = new CountDownLatch(2);
        CountDownLatch appFlushed = new CountDownLatch(1);
        Thread app = SessionContext.startVirtual(
                "app-tests",
                () -> StepScope.run(appTests, () -> {
                    HeapNotes.note(HeapNotes.line(256L << 20, 128L << 20, false));
                    bothNoted.countDown();
                    await(bothNoted);
                    HeapNotes.flush(appTests);
                    appFlushed.countDown();
                }));
        Thread lib = SessionContext.startVirtual(
                "lib-tests",
                () -> StepScope.run(libTests, () -> {
                    HeapNotes.note(HeapNotes.line(1L << 30, 512L << 20, false));
                    bothNoted.countDown();
                    await(appFlushed);
                    HeapNotes.flush(libTests);
                }));
        join(app);
        join(lib);

        assertThat(appWarnings).containsExactly("heap-retry retried with 256 MiB heap after running out of 128 MiB");
        assertThat(libWarnings).containsExactly("heap-retry retried with 1.0 GiB heap after running out of 512 MiB");
    }

    @Test
    void a_step_that_ends_without_flushing_leaves_no_notes_behind() {
        TaskContext step = recording(new ArrayList<>());
        StepScope.run(step, () -> HeapNotes.note("retried with 256 MiB heap after running out of 128 MiB"));

        StepScope.run(step, () -> assertThat(HeapNotes.drain()).isEmpty());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void join(Thread t) {
        try {
            t.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertThat(t.isAlive()).isFalse();
    }

    private static TaskContext recording(List<String> warnings) {
        return new TaskContext() {
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
                throw new IllegalStateException("no " + key);
            }
        };
    }
}

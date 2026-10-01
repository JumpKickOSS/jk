// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * A real fork with a 32 MiB heap that touches 1.4 GiB of native memory: the ledger charges its
 * resident set, names it on the step and in status, and holds back a lease that would fit the
 * reservation alone until it exits.
 */
@Tag("integration")
@EnabledOnOs(OS.LINUX)
class WorkerLeasesOffHeapTest {

    private static final long GIB = 1L << 30;

    @Test
    @Timeout(90)
    void a_fork_far_over_its_lease_off_heap_is_charged_named_and_holds_back_the_next_grant() throws Exception {
        long budget = 4 * GIB;
        WorkerLeases.Ledger ledger = new WorkerLeases.Ledger(
                () -> budget, () -> 8, id -> false, OverbookSignals.off(), MemoryProbe::rssBytes);
        List<String> warnings = new CopyOnWriteArrayList<>();
        StepScope.open(recording(warnings));
        Process hog = null;
        CompletableFuture<WorkerLeases.Grant> next = null;
        try {
            hog = JobWorkers.start(
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-Xmx32m",
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    OffHeapHog.class.getName(),
                                    "1400")
                            .redirectErrorStream(true),
                    ledger);
            awaitReady(hog);
            ledger.sample();

            WorkerLeases.Snapshot snap = ledger.snapshot();
            assertThat(snap.leasedBytes()).isGreaterThan(1400L << 20);
            assertThat(snap.overLease()).startsWith("worker JVM pid " + hog.pid() + " using ");
            assertThat(warnings).singleElement().satisfies(w -> assertThat(w)
                    .startsWith("memory-over-lease worker JVM using ")
                    .endsWith(", leased 192 MiB"));

            next = acquireOnThread(() -> ledger.acquireBytes(3 * GIB, true, null));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ledger.queued() < 1 && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(ledger.queued()).isEqualTo(1);

            hog.destroyForcibly();
            try (WorkerLeases.Grant granted = next.get(10, TimeUnit.SECONDS)) {
                assertThat(granted.bytes()).isEqualTo(3 * GIB);
            }
        } finally {
            if (hog != null) {
                hog.destroyForcibly();
                hog.waitFor(5, TimeUnit.SECONDS);
            }
            if (next != null) next.cancel(true);
            StepScope.close();
        }
    }

    private static void awaitReady(Process hog) throws Exception {
        try (BufferedReader out =
                new BufferedReader(new InputStreamReader(hog.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                if (line.equals("ready")) return;
            }
        }
        throw new AssertionError("the hog exited before it was ready: " + hog.waitFor());
    }

    /** Blocks on its own thread: a waiting lease must not sit on the common pool that completes {@code onExit}. */
    private static CompletableFuture<WorkerLeases.Grant> acquireOnThread(Acquire acquire) {
        CompletableFuture<WorkerLeases.Grant> out = new CompletableFuture<>();
        Thread.ofPlatform().daemon().start(() -> {
            try {
                out.complete(acquire.get());
            } catch (InterruptedException | RuntimeException e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    @FunctionalInterface
    private interface Acquire {
        WorkerLeases.Grant get() throws InterruptedException;
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
            public void waited(Duration blocked) {}

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
                throw new IllegalStateException(key.toString());
            }
        };
    }
}

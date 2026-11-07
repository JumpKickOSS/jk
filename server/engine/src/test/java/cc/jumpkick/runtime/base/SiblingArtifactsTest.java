// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.Await;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The wait a module's package and test steps make on its siblings' artifacts, apart from the
 * schedule that admitted it on their classes trees.
 */
class SiblingArtifactsTest {

    private static final Path CORE = Path.of("/ws/core");
    private static final Path LIB = Path.of("/ws/lib");
    private static final Path APP = Path.of("/ws/app");

    /** app names lib, lib names core. */
    private static final Map<Path, Set<Path>> EDGES = Map.of(CORE, Set.of(), LIB, Set.of(CORE), APP, Set.of(LIB));

    @Test
    void the_transitive_closure_reaches_every_module_on_the_classpath() {
        Map<Path, Set<Path>> closed = SiblingArtifacts.transitive(EDGES);

        assertThat(closed.get(APP)).containsExactlyInAnyOrder(LIB, CORE);
        assertThat(closed.get(LIB)).containsExactly(CORE);
        assertThat(closed.get(CORE)).isEmpty();
    }

    @Test
    void a_gate_waits_for_a_prerequisite_of_a_prerequisite_not_only_the_one_named() throws Exception {
        SiblingArtifacts siblings = new SiblingArtifacts(EDGES, List.of(CORE, LIB, APP));
        SiblingArtifacts.Gate app = siblings.gateFor(APP);
        AtomicBoolean released = new AtomicBoolean();
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> {
            try {
                app.awaitArtifacts(() -> false);
                released.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        siblings.published(LIB);
        Thread.sleep(50);
        assertThat(released)
                .as("core is on app's classpath through lib and has not published")
                .isFalse();

        siblings.published(CORE);
        waiting.get(10, TimeUnit.SECONDS);
        assertThat(released).isTrue();
    }

    @Test
    void a_module_this_build_does_not_schedule_is_ready_from_the_start() throws Exception {
        // core is clean: its jar is on disk, and nothing will ever publish for it.
        SiblingArtifacts siblings = new SiblingArtifacts(EDGES, List.of(LIB, APP));
        siblings.published(LIB);

        AtomicBoolean released = new AtomicBoolean();
        siblings.gateFor(APP).awaitArtifacts(() -> false);
        released.set(true);
        assertThat(released).isTrue();
    }

    @Test
    void completion_releases_dependents_of_a_module_that_never_published() throws Exception {
        SiblingArtifacts siblings = new SiblingArtifacts(EDGES, List.of(CORE, LIB, APP));
        siblings.published(CORE);
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> {
            try {
                siblings.gateFor(APP).awaitArtifacts(() -> false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // lib failed before packaging: its completion is what lets app go on and fail accurately.
        siblings.completed(LIB);
        waiting.get(10, TimeUnit.SECONDS);
        assertThat(waiting).isCompleted();
    }

    @Test
    void a_cancel_ends_the_wait_without_a_publish() throws Exception {
        SiblingArtifacts siblings = new SiblingArtifacts(EDGES, List.of(CORE, LIB, APP));
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> {
            try {
                siblings.gateFor(APP).awaitArtifacts(cancelled::get);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        cancelled.set(true);
        waiting.get(10, TimeUnit.SECONDS);
        assertThat(waiting).isCompleted();
    }

    @Test
    void a_module_with_no_prerequisites_has_nothing_to_wait_for() throws Exception {
        SiblingArtifacts siblings = new SiblingArtifacts(EDGES, List.of(CORE, LIB, APP));

        // Nothing has published: a wait on any prerequisite would never return.
        siblings.gateFor(CORE).awaitArtifacts(() -> false);
        assertThat(SiblingArtifacts.none().gateFor(APP)).isSameAs(SiblingArtifacts.NONE);
    }

    @Test
    void a_recorded_failure_is_readable_through_a_dependents_gate_by_coordinate() {
        SiblingArtifacts siblings = new SiblingArtifacts(EDGES, List.of(CORE, LIB, APP));
        siblings.failed("ex:lib", "compile-test-fixtures");

        SiblingArtifacts.Gate app = siblings.gateFor(APP);
        assertThat(app.failedStep("ex:lib")).contains("compile-test-fixtures");
        assertThat(app.failedStep("ex:core"))
                .as("a sibling that did not fail names no step")
                .isEmpty();
        assertThat(SiblingArtifacts.NONE.failedStep("ex:lib"))
                .as("outside a schedule nothing failed")
                .isEmpty();
    }

    @Test
    void a_workspace_guard_on_core_waits_for_the_modules_downstream_of_it_to_index() throws Exception {
        SiblingArtifacts siblings = new SiblingArtifacts(EDGES, List.of(CORE, LIB, APP));
        AtomicBoolean released = new AtomicBoolean();
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> {
            try {
                siblings.gateFor(CORE).awaitIndexed(List.of(LIB, APP), () -> false);
                released.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        siblings.published(LIB);
        siblings.published(APP);
        siblings.indexed(LIB);
        Thread.sleep(50);
        assertThat(released)
                .as("app published its jar but has not indexed its classes")
                .isFalse();

        siblings.completed(APP);
        waiting.get(10, TimeUnit.SECONDS);
        assertThat(released)
                .as("completion stands in for a module that never indexed")
                .isTrue();
    }

    @Test
    void a_module_this_build_does_not_schedule_is_indexed_from_the_start() throws Exception {
        SiblingArtifacts siblings = new SiblingArtifacts(EDGES, List.of(CORE));

        siblings.gateFor(CORE).awaitIndexed(List.of(LIB, APP), () -> false);
    }

    private static final Path INFRA = Path.of("/ws/infra");
    private static final Path ARCH = Path.of("/ws/arch");
    private static final Path SVC = Path.of("/ws/svc");

    /**
     * The fixtures shape: infra's tests read arch's fixtures, arch's fixtures and tests read infra's
     * main, svc's main reads infra. Only svc → infra orders the modules.
     */
    private static SiblingArtifacts fixturesLoop() {
        return new SiblingArtifacts(
                Map.of(INFRA, Set.of(), ARCH, Set.of(), SVC, Set.of(INFRA)),
                new SiblingArtifacts.TestReads(
                        Map.of(INFRA, Set.of(ARCH), ARCH, Set.of(INFRA)), Map.of(INFRA, Set.of(ARCH)), Map.of()),
                List.of(INFRA, ARCH, SVC));
    }

    private static CompletableFuture<Void> waitOn(Waiter waiter, AtomicBoolean released) {
        return CompletableFuture.runAsync(() -> {
            try {
                waiter.await();
                released.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @FunctionalInterface
    private interface Waiter {
        void await() throws InterruptedException;
    }

    @Test
    void a_test_compile_waits_for_the_fixtures_it_selects_and_never_for_a_jar() throws Exception {
        SiblingArtifacts siblings = fixturesLoop();
        AtomicBoolean released = new AtomicBoolean();
        CompletableFuture<Void> waiting = waitOn(() -> siblings.gateFor(INFRA).awaitTestCompile(() -> false), released);

        siblings.compiled(ARCH);
        Thread.sleep(50);
        assertThat(released).as("arch's main is whole, its fixtures are not").isFalse();

        siblings.fixturesCompiled(ARCH);
        waiting.get(10, TimeUnit.SECONDS);
        assertThat(released).as("arch has neither packaged nor tested").isTrue();
    }

    @Test
    void the_other_side_of_the_loop_waits_for_main_classes_only() throws Exception {
        SiblingArtifacts siblings = fixturesLoop();
        AtomicBoolean released = new AtomicBoolean();
        CompletableFuture<Void> waiting = waitOn(() -> siblings.gateFor(ARCH).awaitTestCompile(() -> false), released);

        Thread.sleep(50);
        assertThat(released).isFalse();
        siblings.compiled(INFRA);
        waiting.get(10, TimeUnit.SECONDS);
        assertThat(released)
                .as("infra's tests have not compiled, and arch never waits on them")
                .isTrue();
    }

    @Test
    void a_test_run_waits_for_the_artifacts_of_what_only_its_tests_read() throws Exception {
        SiblingArtifacts siblings = fixturesLoop();
        siblings.compiled(ARCH);
        siblings.fixturesCompiled(ARCH);
        AtomicBoolean released = new AtomicBoolean();
        CompletableFuture<Void> waiting = waitOn(() -> siblings.gateFor(INFRA).awaitTestRuntime(() -> false), released);

        Thread.sleep(50);
        assertThat(released).as("arch's jar is not written").isFalse();
        siblings.published(ARCH);
        waiting.get(10, TimeUnit.SECONDS);
        assertThat(released).isTrue();

        // svc reads infra through a build-order edge: its packaging waits on infra, never on arch.
        siblings.published(INFRA);
        siblings.gateFor(SVC).awaitArtifacts(() -> false);
    }

    @Test
    void a_waiting_test_step_counts_its_module_as_parked_until_it_is_released() throws Exception {
        SiblingArtifacts siblings = fixturesLoop();
        AtomicInteger wakes = new AtomicInteger();
        siblings.onPark(wakes::incrementAndGet);
        AtomicBoolean released = new AtomicBoolean();
        CompletableFuture<Void> waiting = waitOn(() -> siblings.gateFor(INFRA).awaitTestCompile(() -> false), released);

        Await.until(Duration.ofSeconds(10), () -> siblings.parked() == 1);
        assertThat(wakes).hasValue(1);

        siblings.completed(ARCH);
        waiting.get(10, TimeUnit.SECONDS);
        assertThat(siblings.parked()).isZero();

        // Nothing pending: the wait returns without parking.
        siblings.gateFor(INFRA).awaitTestCompile(() -> false);
        assertThat(wakes).hasValue(1);
    }
}

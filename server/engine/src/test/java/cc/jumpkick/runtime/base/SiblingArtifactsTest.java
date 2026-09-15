// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void a_module_with_no_prerequisites_has_nothing_to_wait_for() {
        SiblingArtifacts siblings = new SiblingArtifacts(EDGES, List.of(CORE, LIB, APP));

        assertThat(siblings.gateFor(CORE)).isSameAs(SiblingArtifacts.NONE);
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
}

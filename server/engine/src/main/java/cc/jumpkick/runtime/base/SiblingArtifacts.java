// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NullMarked;

/**
 * When a workspace module's siblings have packaged what its later steps run.
 *
 * <p>The schedule admits a module once the classes trees of its siblings are whole, so its compile
 * starts while they still package and test. Its package, test and native steps read the siblings'
 * jars — and, through a {@code kind = "tests"} edge, their test classes — so before the first of
 * those steps the plan waits here for every sibling it reads to have published its artifacts or
 * finished. The wait covers the transitive closure over the module graph, because the classpath is
 * transitive too: a module two edges away is on the runtime classpath as surely as a direct one.
 *
 * <p>A sibling this build does not schedule has its outputs on disk already and is ready from the
 * start. One that fails publishes on completion, and the waiting step then names the missing jar
 * itself, which is the accurate failure. A cancelled session ends the wait at once.
 */
@NullMarked
public final class SiblingArtifacts {

    /** How often a waiting step re-checks for a cancel while a sibling is still packaging. */
    private static final long POLL_MS = 200;

    /** One module's side of the wait: what its plan calls before reading a sibling's jar. */
    @FunctionalInterface
    public interface Gate {
        /**
         * Returns once every sibling this module reads has published its artifacts or finished,
         * or as soon as {@code cancelled} answers true.
         */
        void awaitArtifacts(BooleanSupplier cancelled) throws InterruptedException;
    }

    /** The gate of a plan outside a workspace schedule: nothing to wait for. */
    public static final Gate NONE = cancelled -> {};

    private final Map<Path, Set<Path>> prereqs;
    private final Map<Path, CompletableFuture<Void>> published = new ConcurrentHashMap<>();

    /**
     * @param edges the module graph, unit dir to the dirs that must build before it
     * @param scheduled the units this build runs; every other unit's outputs already exist
     */
    public SiblingArtifacts(Map<Path, Set<Path>> edges, Collection<Path> scheduled) {
        this.prereqs = transitive(edges);
        for (Path dir : scheduled) published.put(dir, new CompletableFuture<>());
    }

    /** No schedule at all — every gate is {@link #NONE}. */
    public static SiblingArtifacts none() {
        return new SiblingArtifacts(Map.of(), Set.of());
    }

    /**
     * The graph closed under its own edges: each unit maps to every unit reachable through the
     * prerequisite relation, not only the ones it names. A dependent is admitted, and its
     * artifacts awaited, against this closure — a prerequisite's own prerequisite that is still
     * restoring its jar is otherwise invisible to a unit that only names the middle one.
     */
    public static Map<Path, Set<Path>> transitive(Map<Path, Set<Path>> edges) {
        Map<Path, Set<Path>> closed = new LinkedHashMap<>();
        for (Map.Entry<Path, Set<Path>> e : edges.entrySet()) {
            Set<Path> reach = new LinkedHashSet<>();
            Deque<Path> queue = new ArrayDeque<>(e.getValue());
            while (!queue.isEmpty()) {
                Path p = queue.poll();
                if (p.equals(e.getKey()) || !reach.add(p)) continue;
                queue.addAll(edges.getOrDefault(p, Set.of()));
            }
            closed.put(e.getKey(), reach);
        }
        return closed;
    }

    /** The transitive prerequisite map the schedule admits against. */
    public Map<Path, Set<Path>> edges() {
        return prereqs;
    }

    /** {@code dir}'s cross-module artifacts are terminal: its jar, and the test output a sibling selects. */
    public void published(Path dir) {
        CompletableFuture<Void> f = published.get(dir);
        if (f != null) f.complete(null);
    }

    /**
     * {@code dir} has finished, whatever happened to it. Completion always publishes, so a module
     * that failed before packaging never wedges a dependent — the dependent fails on the jar it
     * cannot find, and says so.
     */
    public void completed(Path dir) {
        published(dir);
    }

    /** The gate for the module at {@code dir}. */
    public Gate gateFor(Path dir) {
        Set<Path> waitOn = prereqs.getOrDefault(dir, Set.of());
        if (waitOn.isEmpty()) return NONE;
        return cancelled -> {
            for (Path prereq : waitOn) {
                CompletableFuture<Void> f = published.get(prereq);
                if (f == null) continue; // not scheduled this build: its outputs are on disk
                while (!f.isDone()) {
                    if (cancelled.getAsBoolean()) return;
                    try {
                        f.get(POLL_MS, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException stillPackaging) {
                        // re-check the cancel, then keep waiting
                    } catch (ExecutionException never) {
                        // the future only ever completes normally
                        return;
                    }
                }
            }
        };
    }
}

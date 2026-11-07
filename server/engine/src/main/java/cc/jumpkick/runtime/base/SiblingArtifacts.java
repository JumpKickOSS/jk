// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
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
 * <p>The schedule admits a module once the classes trees of its build-order siblings are whole, so
 * its compile starts while they still package and test. Its package and native steps read those
 * siblings' jars, so before the first of them the plan waits here for every one to have published
 * its artifacts or finished. The wait covers the transitive closure over the module graph, because
 * the classpath is transitive too: a module two edges away is on the runtime classpath as surely as
 * a direct one.
 *
 * <p>A sibling only the module's test scopes name is no admission edge ({@link TestReads}). Its
 * test compiles wait here for that sibling's classes tree, and for the fixtures or test classes they
 * select of any sibling; its test run waits for those siblings' artifacts. Each wait keys on the
 * step that writes what it reads, never on a sibling's tests, so two members whose tests read each
 * other's main or fixtures output build. A step parked on a sibling the schedule has yet to start
 * holds no concurrency slot ({@link WorkspaceScheduler.Parking}).
 *
 * <p>A module's guard suite that judges the whole workspace reads every module's facts index, so it
 * waits here too, for each module it judges to have indexed its classes this build.
 *
 * <p>A sibling this build does not schedule has its outputs on disk already and is ready from the
 * start. One that fails publishes on completion, and the waiting step then names the missing jar
 * itself and the step the sibling failed at, which is the accurate failure. A cancelled session
 * ends the wait at once.
 */
@NullMarked
public final class SiblingArtifacts implements WorkspaceScheduler.Parking {

    /**
     * What each module's tests read of a sibling beyond its build-order edges.
     *
     * @param classes module dir → the siblings only its test scopes name: their classes trees, and
     *     their own build-order closure's, are on its test classpaths
     * @param fixtures module dir → the siblings whose fixtures it selects ({@code fixtures = true})
     * @param testClasses module dir → the siblings whose test classes it selects ({@code kind = "tests"})
     */
    public record TestReads(
            Map<Path, Set<Path>> classes, Map<Path, Set<Path>> fixtures, Map<Path, Set<Path>> testClasses) {
        public static final TestReads NONE = new TestReads(Map.of(), Map.of(), Map.of());
    }

    /** How often a waiting step re-checks for a cancel while a sibling is still packaging. */
    private static final long POLL_MS = 200;

    /** One module's side of the wait: what its plan calls before reading a sibling's jar. */
    @FunctionalInterface
    public interface Gate {
        /**
         * Returns once every sibling of this module's build-order closure has published its
         * artifacts or finished, or as soon as {@code cancelled} answers true.
         */
        void awaitArtifacts(BooleanSupplier cancelled) throws InterruptedException;

        /**
         * Returns once every sibling this module's test compiles read has its classes tree whole,
         * and every sibling whose fixtures or test classes they select has compiled those, or as
         * soon as {@code cancelled} answers true.
         */
        default void awaitTestCompile(BooleanSupplier cancelled) throws InterruptedException {}

        /**
         * Returns once every sibling this module's test run reads — its build-order closure and its
         * tests' reads — has published its artifacts or finished, or as soon as {@code cancelled}
         * answers true.
         */
        default void awaitTestRuntime(BooleanSupplier cancelled) throws InterruptedException {
            awaitArtifacts(cancelled);
        }

        /**
         * The step the sibling with coordinate {@code coord} failed at this build, when its plan
         * failed; empty for a sibling that succeeded, was not scheduled, or is unknown to the
         * schedule. What lets a waiting step say "failed at compile-test-fixtures" instead of
         * "not built".
         */
        default Optional<String> failedStep(String coord) {
            return Optional.empty();
        }

        /**
         * Returns once every module of {@code dirs} this build schedules has indexed its classes or
         * finished, or as soon as {@code cancelled} answers true.
         */
        default void awaitIndexed(Collection<Path> dirs, BooleanSupplier cancelled) throws InterruptedException {}

        /** This module's facts indexes describe the classes this build compiled. */
        default void indexed() {}

        /**
         * Returns once every module of {@code dirs} this build schedules has published its artifacts
         * or finished, or as soon as {@code cancelled} answers true: for a jar a step runs that is
         * no prerequisite of its module.
         */
        default void awaitPublished(Collection<Path> dirs, BooleanSupplier cancelled) throws InterruptedException {}
    }

    /** The gate of a plan outside a workspace schedule: nothing to wait for. */
    public static final Gate NONE = cancelled -> {};

    private final Map<Path, Set<Path>> prereqs;
    private final Map<Path, Set<Path>> testClasspath = new LinkedHashMap<>();
    private final TestReads tests;
    private final Map<Path, CompletableFuture<Void>> compiled = new ConcurrentHashMap<>();
    private final Map<Path, CompletableFuture<Void>> published = new ConcurrentHashMap<>();
    private final Map<Path, CompletableFuture<Void>> fixturesCompiled = new ConcurrentHashMap<>();
    private final Map<Path, CompletableFuture<Void>> testsCompiled = new ConcurrentHashMap<>();
    private final Map<Path, CompletableFuture<Void>> indexed = new ConcurrentHashMap<>();
    private final Map<String, String> failedSteps = new ConcurrentHashMap<>();
    private final Map<Path, Integer> parkedSteps = new ConcurrentHashMap<>();
    private volatile Runnable onPark = () -> {};

    /** As {@link #SiblingArtifacts(Map, TestReads, Collection)} for a graph whose tests read nothing more. */
    public SiblingArtifacts(Map<Path, Set<Path>> edges, Collection<Path> scheduled) {
        this(edges, TestReads.NONE, scheduled);
    }

    /**
     * @param edges the module graph, unit dir to the dirs that must build before it
     * @param tests what each module's tests read beyond {@code edges}
     * @param scheduled the units this build runs; every other unit's outputs already exist
     */
    public SiblingArtifacts(Map<Path, Set<Path>> edges, TestReads tests, Collection<Path> scheduled) {
        this.prereqs = transitive(edges);
        this.tests = tests;
        for (var e : tests.classes().entrySet()) {
            Set<Path> reach = new LinkedHashSet<>();
            for (Path sibling : e.getValue()) {
                reach.add(sibling);
                reach.addAll(prereqs.getOrDefault(sibling, Set.of()));
            }
            reach.remove(e.getKey());
            reach.removeAll(prereqs.getOrDefault(e.getKey(), Set.of()));
            testClasspath.put(e.getKey(), reach);
        }
        for (Path dir : scheduled) {
            compiled.put(dir, new CompletableFuture<>());
            published.put(dir, new CompletableFuture<>());
            fixturesCompiled.put(dir, new CompletableFuture<>());
            testsCompiled.put(dir, new CompletableFuture<>());
            indexed.put(dir, new CompletableFuture<>());
        }
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

    /** Whether any module's tests read a sibling beyond its build-order edges. */
    public boolean hasTestReads() {
        return !tests.classes().isEmpty()
                || !tests.fixtures().isEmpty()
                || !tests.testClasses().isEmpty();
    }

    @Override
    public int parked() {
        return parkedSteps.size();
    }

    @Override
    public void onPark(Runnable wake) {
        this.onPark = wake;
    }

    /** {@code dir}'s classes tree is whole: what a sibling's test compile reads of it. */
    public void compiled(Path dir) {
        complete(compiled, dir);
    }

    /** {@code dir}'s fixtures are compiled, or it has none to compile. */
    public void fixturesCompiled(Path dir) {
        complete(fixturesCompiled, dir);
    }

    /** {@code dir}'s test classes are compiled, or it has none to compile. */
    public void testsCompiled(Path dir) {
        complete(testsCompiled, dir);
    }

    /** {@code dir}'s cross-module artifacts are terminal: its jar, and the test output a sibling selects. */
    public void published(Path dir) {
        complete(published, dir);
    }

    private static void complete(Map<Path, CompletableFuture<Void>> signals, Path dir) {
        CompletableFuture<Void> f = signals.get(dir);
        if (f != null) f.complete(null);
    }

    /**
     * {@code dir} has finished, whatever happened to it. Completion always publishes, so a module
     * that failed before packaging never wedges a dependent — the dependent fails on the jar it
     * cannot find, and says so.
     */
    public void completed(Path dir) {
        compiled(dir);
        fixturesCompiled(dir);
        testsCompiled(dir);
        published(dir);
        indexed(dir);
    }

    /** {@code dir}'s facts indexes describe the classes this build compiled. */
    public void indexed(Path dir) {
        complete(indexed, dir);
    }

    /**
     * The module with coordinate {@code coord} failed at {@code step}. Recorded before completion
     * publishes, so a dependent that then finds the output absent names the step rather than
     * "not built".
     */
    public void failed(String coord, String step) {
        failedSteps.put(coord, step);
    }

    /** The gate for the module at {@code dir}. */
    public Gate gateFor(Path dir) {
        Set<Path> waitOn = prereqs.getOrDefault(dir, Set.of());
        Set<Path> testWaitOn = testClasspath.getOrDefault(dir, Set.of());
        Set<Path> fixtures = tests.fixtures().getOrDefault(dir, Set.of());
        Set<Path> testClasses = tests.testClasses().getOrDefault(dir, Set.of());
        Set<Path> testRuntime = new LinkedHashSet<>(waitOn);
        testRuntime.addAll(testWaitOn);
        testRuntime.addAll(fixtures);
        testRuntime.addAll(testClasses);
        if (testRuntime.isEmpty() && indexed.isEmpty()) return NONE;
        return new Gate() {
            @Override
            public void awaitArtifacts(BooleanSupplier cancelled) throws InterruptedException {
                await(published, waitOn, cancelled);
            }

            @Override
            public void awaitTestCompile(BooleanSupplier cancelled) throws InterruptedException {
                boolean pending = pending(compiled, testWaitOn)
                        || pending(fixturesCompiled, fixtures)
                        || pending(testsCompiled, testClasses);
                parked(dir, pending, () -> {
                    await(compiled, testWaitOn, cancelled);
                    await(fixturesCompiled, fixtures, cancelled);
                    await(testsCompiled, testClasses, cancelled);
                });
            }

            @Override
            public void awaitTestRuntime(BooleanSupplier cancelled) throws InterruptedException {
                parked(dir, pending(published, testRuntime), () -> await(published, testRuntime, cancelled));
            }

            @Override
            public Optional<String> failedStep(String coord) {
                return Optional.ofNullable(failedSteps.get(coord));
            }

            @Override
            public void awaitIndexed(Collection<Path> dirs, BooleanSupplier cancelled) throws InterruptedException {
                await(indexed, dirs, cancelled);
            }

            @Override
            public void indexed() {
                SiblingArtifacts.this.indexed(dir);
            }

            @Override
            public void awaitPublished(Collection<Path> dirs, BooleanSupplier cancelled) throws InterruptedException {
                parked(dir, pending(published, dirs), () -> await(published, dirs, cancelled));
            }
        };
    }

    /** A wait that may block on a sibling the schedule has not started. */
    @FunctionalInterface
    private interface Wait {
        void run() throws InterruptedException;
    }

    /**
     * Run {@code wait}, with {@code dir} counted as parked while it blocks when anything it waits on
     * is {@code pending}, so the schedule admits another unit in its place: the sibling it waits on
     * may be one the cap has not let start yet.
     */
    private void parked(Path dir, boolean pending, Wait wait) throws InterruptedException {
        if (!pending) {
            wait.run();
            return;
        }
        parkedSteps.merge(dir, 1, Integer::sum);
        onPark.run();
        try {
            wait.run();
        } finally {
            parkedSteps.computeIfPresent(dir, (k, n) -> n <= 1 ? null : n - 1);
        }
    }

    private static boolean pending(Map<Path, CompletableFuture<Void>> signals, Collection<Path> dirs) {
        for (Path dir : dirs) {
            CompletableFuture<Void> f = signals.get(dir);
            if (f != null && !f.isDone()) return true;
        }
        return false;
    }

    private static void await(
            Map<Path, CompletableFuture<Void>> signals, Collection<Path> dirs, BooleanSupplier cancelled)
            throws InterruptedException {
        for (Path dir : dirs) {
            CompletableFuture<Void> f = signals.get(dir);
            if (f == null) continue; // not scheduled this build: its outputs are on disk
            while (!f.isDone()) {
                if (cancelled.getAsBoolean()) return;
                try {
                    f.get(POLL_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException stillRunning) {
                    // re-check the cancel, then keep waiting
                } catch (ExecutionException never) {
                    // the future only ever completes normally
                    return;
                }
            }
        }
    }
}

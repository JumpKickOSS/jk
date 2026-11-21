// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.guard.schema.Lane;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * What a lane hands its evaluators. The module lane supplies one module's facts; the model lane the
 * workspace root and its module directories; the tree lane the root. Suppliers are lazy so a lane
 * with no rule of a kind never loads what that kind reads.
 *
 * @param module the module's workspace-relative path, or {@code ""} at the root
 * @param modules every module directory of the workspace (root lanes), else just this module's
 * @param classpath the main source set's compile classpath, which {@link #hierarchy()} reads
 * @param testClasspath where a test's supertypes and annotations are read from beyond the module's
 *     own classes, the one the engine routes test tags through ({@link #testHierarchy()})
 * @param rules every loaded rule, for kinds that refer to another rule ({@code metric matches:<id>})
 */
public record EvalContext(
        Lane lane,
        Path root,
        String module,
        @Nullable Path moduleDir,
        List<Path> modules,
        Supplier<FactsIndex> factsSupplier,
        Supplier<@Nullable FactsIndex> testFactsSupplier,
        Supplier<List<Path>> classpath,
        Supplier<List<Path>> testClasspath,
        RuleSet rules) {

    /**
     * Without a rule set, the tests' types read from {@code classpath}: for lanes that judge no test
     * classes, and for tests.
     */
    public EvalContext(
            Lane lane,
            Path root,
            String module,
            @Nullable Path moduleDir,
            List<Path> modules,
            Supplier<FactsIndex> factsSupplier,
            Supplier<@Nullable FactsIndex> testFactsSupplier,
            Supplier<List<Path>> classpath) {
        this(
                lane,
                root,
                module,
                moduleDir,
                modules,
                factsSupplier,
                testFactsSupplier,
                classpath,
                classpath,
                RuleSet.EMPTY);
    }

    public EvalContext withRules(RuleSet set) {
        return new EvalContext(
                lane,
                root,
                module,
                moduleDir,
                modules,
                factsSupplier,
                testFactsSupplier,
                classpath,
                testClasspath,
                set);
    }

    /** A context is one lane run: two runs over equal inputs still keep their own memos. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    private static final Map<EvalContext, TypeHierarchy> HIERARCHIES = new WeakHashMap<>();

    /** The memo key of the hierarchy {@link #testHierarchy()} builds. */
    private static final Object TEST_HIERARCHY = new Object();

    private static final Map<EvalContext, Map<Object, Object>> MEMOS = new WeakHashMap<>();

    /** A value derived once per lane run under {@code key}; released with the hierarchy. */
    @SuppressWarnings("unchecked")
    public <T> T memo(Object key, Function<EvalContext, T> compute) {
        Map<Object, Object> mine;
        synchronized (MEMOS) {
            mine = MEMOS.computeIfAbsent(this, c -> new ConcurrentHashMap<>());
        }
        return (T) mine.computeIfAbsent(key, k -> compute.apply(this));
    }

    /** The module's type hierarchy (facts, then classpath, then JDK), built once per lane run. */
    public TypeHierarchy hierarchy() {
        synchronized (HIERARCHIES) {
            return HIERARCHIES.computeIfAbsent(this, c -> new TypeHierarchy(c.facts(), c.classpath()));
        }
    }

    /**
     * The module's type hierarchy as its tests see it (facts, then the test classpath, then JDK),
     * built once per lane run: a composed annotation or base class a test-only library declares is
     * read here.
     */
    public TypeHierarchy testHierarchy() {
        return memo(TEST_HIERARCHY, c -> new TypeHierarchy(c.facts(), c.testClasspath()));
    }

    /**
     * Release the hierarchies built for this context, closing the classpath jars they opened; the lane
     * run calls this once its rules are evaluated, and the next {@link #hierarchy()} builds afresh.
     */
    public void closeHierarchy() {
        TypeHierarchy built;
        synchronized (HIERARCHIES) {
            built = HIERARCHIES.remove(this);
        }
        if (built != null) built.close();
        Map<Object, Object> memos;
        synchronized (MEMOS) {
            memos = MEMOS.remove(this);
        }
        if (memos != null && memos.get(TEST_HIERARCHY) instanceof TypeHierarchy test) test.close();
    }

    /** Contexts holding a hierarchy or memos that {@link #closeHierarchy} has not released. Test seam. */
    static int heldContexts() {
        int held;
        synchronized (HIERARCHIES) {
            held = HIERARCHIES.size();
        }
        synchronized (MEMOS) {
            return held + MEMOS.size();
        }
    }

    /** This module's main-source-set facts; loaded on first use. */
    public FactsIndex facts() {
        return factsSupplier.get();
    }

    /** The test source set's facts, or {@code null} when the module has no compiled tests. */
    public @Nullable FactsIndex testFacts() {
        return testFactsSupplier.get();
    }

    /** Lift a checked reader into a supplier; the failure surfaces as {@code scanner-failed}. */
    public static <T> Supplier<T> lazy(IoSupplier<T> body) {
        return new Supplier<>() {
            private @Nullable T value;
            private boolean done;

            @Override
            public synchronized T get() {
                if (!done) {
                    try {
                        value = body.get();
                    } catch (IOException e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                    done = true;
                }
                return Objects.requireNonNull(value, "lazy value");
            }
        };
    }

    public interface IoSupplier<T> {
        T get() throws IOException;
    }

    /** A step that may fail with I/O. */
    @FunctionalInterface
    public interface IoRunnable {
        void run() throws IOException;
    }
}

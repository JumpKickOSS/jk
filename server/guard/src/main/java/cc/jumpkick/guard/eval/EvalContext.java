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
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * What a lane hands its evaluators. The module lane supplies one module's facts; the model lane the
 * workspace root and its module directories; the tree lane the root. Suppliers are lazy so a lane
 * with no rule of a kind never loads what that kind reads.
 *
 * @param module the module's workspace-relative path, or {@code ""} at the root
 * @param modules every module directory of the workspace (root lanes), else just this module's
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
        RuleSet rules) {

    /** Without a rule set: for tests and callers whose kinds never refer to another rule. */
    public EvalContext(
            Lane lane,
            Path root,
            String module,
            @Nullable Path moduleDir,
            List<Path> modules,
            Supplier<FactsIndex> factsSupplier,
            Supplier<@Nullable FactsIndex> testFactsSupplier,
            Supplier<List<Path>> classpath) {
        this(lane, root, module, moduleDir, modules, factsSupplier, testFactsSupplier, classpath, RuleSet.EMPTY);
    }

    public EvalContext withRules(RuleSet set) {
        return new EvalContext(
                lane, root, module, moduleDir, modules, factsSupplier, testFactsSupplier, classpath, set);
    }

    private static final Map<EvalContext, TypeHierarchy> HIERARCHIES = new WeakHashMap<>();

    /** The module's type hierarchy (facts, then classpath, then JDK), built once per lane run. */
    public TypeHierarchy hierarchy() {
        synchronized (HIERARCHIES) {
            return HIERARCHIES.computeIfAbsent(this, c -> new TypeHierarchy(c.facts(), c.classpath()));
        }
    }

    /**
     * Release the hierarchy built for this context, closing the classpath jars it opened; the lane
     * run calls this once its rules are evaluated, and the next {@link #hierarchy()} builds afresh.
     */
    public void closeHierarchy() {
        TypeHierarchy built;
        synchronized (HIERARCHIES) {
            built = HIERARCHIES.remove(this);
        }
        if (built != null) built.close();
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

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Puts a boundary around the process-globals a test may not leak, and says who moved one.
 *
 * <p>Installing resolved state on a static is the right design for a real {@code jk} invocation —
 * one process, one command. Under a shared test JVM the same write has no owner and outlives the
 * class that made it: worker JVMs pull test classes off a shared queue, so which class inherits a
 * leak, and therefore whether anything fails, changes run to run. The failure surfaces in the
 * victim carrying no trace of the culprit, which is the archaeology was filed to end.
 *
 * <p>Globals are registered by their owners through {@link BoundedGlobal} and discovered with
 * {@link ServiceLoader}, so each lives beside the code that can actually reach it.
 *
 * <h2>Restore, and where possible attribute</h2>
 *
 * Everything registered is restored. Where a global can also be <em>read</em>, a change that
 * outlives a test is reported with the class that made it — the "name the culprit" bar. Where it
 * can only be reset ({@code TerminalReflow}, {@code Size}: a memo with no reader), it is bounded
 * silently, and this class says so in its summary rather than implying coverage it does not have.
 *
 * <p>Restores put back the <em>prior value</em> rather than resetting to a default: a reset is not
 * a restore, and would clobber state a legitimate ambient setup had installed.
 *
 * <p>Containers are bounded as well as tests. An install from a {@code @BeforeAll} would otherwise
 * outlive its class and reach every class scheduled after it in the same worker — which is what the
 * per-class {@code @AfterEach reset} hooks deleted were papering over.
 *
 * <p>Deliberately a platform {@link TestExecutionListener} rather than a Jupiter {@code Extension}:
 * extension autodetection is a single per-task switch and the unit tier keeps it off on purpose so
 * {@code EngineTestExtension} stays unloaded. A listener is discovered unconditionally, so
 * every tier gets the boundary and no tier gains an engine. The cost is that a listener cannot fail
 * a test — the platform isolates listener exceptions — so this reports rather than fails. Turning
 * the report into a build failure needs a mechanism decision and is left to its own change.
 */
public final class SessionBoundary implements TestExecutionListener {

    private final List<BoundedGlobal> globals = ServiceLoader.load(BoundedGlobal.class).stream()
            .map(ServiceLoader.Provider::get)
            .sorted(Comparator.comparing(BoundedGlobal::name))
            .toList();

    /** Keyed by unique id so nesting works: a container's snapshot outlives the tests inside it. */
    private final Map<String, Map<String, Optional<Object>>> taken = new ConcurrentHashMap<>();

    private final List<String> leaks = new ArrayList<>();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        taken.clear();
        leaks.clear();
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        Map<String, Optional<Object>> snapshot = new ConcurrentHashMap<>();
        for (BoundedGlobal g : globals) {
            snapshot.put(g.name(), g.capture());
        }
        taken.put(id.getUniqueId(), snapshot);
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        Map<String, Optional<Object>> before = taken.remove(id.getUniqueId());
        if (before == null) return;
        Set<String> declared = declaredBy(id);
        for (BoundedGlobal g : globals) {
            Optional<Object> was = before.getOrDefault(g.name(), Optional.empty());
            if (was.isPresent()) {
                Optional<Object> now = g.capture();
                if (g.attributable()
                        && now.isPresent()
                        && !Objects.equals(was.get(), now.get())
                        && !declared.contains(g.name())) {
                    leaks.add("  " + g.name() + " changed by " + display(id));
                }
            }
            g.restore(was.orElse(null));
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        String readable = globals.stream()
                .filter(g -> g.attributable() && g.capture().isPresent())
                .map(BoundedGlobal::name)
                .collect(Collectors.joining(", "));
        String resetOnly = globals.stream()
                .filter(g -> !g.attributable() || g.capture().isEmpty())
                .map(BoundedGlobal::name)
                .collect(Collectors.joining(", "));
        if (globals.isEmpty()) return;
        StringBuilder out = new StringBuilder("jk global boundary — bounded: " + globals.size()
                + (readable.isEmpty() ? "" : "; attributable: " + readable)
                + (resetOnly.isEmpty() ? "" : "; bounded only (no reader, or written by design): " + resetOnly)
                + "\n");
        if (!leaks.isEmpty()) {
            out.append("  left a global changed (restored anyway; declare @InstallsGlobal if deliberate):\n");
            leaks.stream().distinct().sorted().forEach(l -> out.append(l).append('\n'));
        }
        System.out.println(out);
        report(out.toString());
    }

    /**
     * Also write the summary to a file. The listener runs inside the test JVM, whose stdout a runner
     * may discard — an attribution report nobody reads is not a guard. The path is relative to the
     * JVM's working directory, so it lands beside wherever the worker was started, which is fine
     * because the file is looked up, not watched.
     */
    private static void report(String text) {
        try {
            Path f = Path.of("build", "reports", "jk-global-boundary.txt");
            Files.createDirectories(f.getParent());
            Files.writeString(f, text);
        } catch (Exception ignored) {
            // A report that cannot be written is not a reason to disturb a green suite.
        }
    }

    /** Globals the owning class declared it writes on purpose. */
    private static Set<String> declaredBy(TestIdentifier id) {
        String className = id.getSource()
                .map(s -> s instanceof ClassSource c
                        ? c.getClassName()
                        : (s instanceof MethodSource m ? m.getClassName() : null))
                .orElse(null);
        if (className == null) return Set.of();
        try {
            InstallsGlobal ann = Class.forName(className).getAnnotation(InstallsGlobal.class);
            return ann == null ? Set.of() : Set.of(ann.value());
        } catch (Throwable ignored) {
            // A class the listener's loader cannot see is not a reason to fail anyone's test.
            return Set.of();
        }
    }

    private static String display(TestIdentifier id) {
        return id.getSource()
                .map(s -> s instanceof MethodSource m
                        ? m.getClassName() + "." + m.getMethodName() + "()"
                        : (s instanceof ClassSource c ? c.getClassName() : id.getDisplayName()))
                .orElse(id.getDisplayName());
    }
}

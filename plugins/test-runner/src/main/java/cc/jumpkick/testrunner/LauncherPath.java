// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * JUnit Platform <em>Launcher</em> path for {@code jk test}.
 *
 * <p>Unlike raw {@code TestEngine.discover/execute}, the Launcher opens a {@code LauncherSession}
 * and fires {@code LauncherSessionListener} / {@code LauncherDiscoveryListener} SPIs. Quarkus
 * ({@code CustomLauncherInterceptor}) needs those to install {@code FacadeClassLoader} and register
 * {@code TestConfig} before {@code @QuarkusTest} runs.
 *
 * <p>Compile-only against launcher; the forked test JVM must put a matching launcher jar on the CP
 * (projects with junit-jupiter / Quarkus already do).
 */
final class LauncherPath {

    private LauncherPath() {}

    /** {@code true} when the launcher core is loadable on the current classpath. */
    static boolean available() {
        try {
            Class.forName("org.junit.platform.launcher.core.LauncherFactory");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static int runOneShot(
            Path scanClasspath,
            String filter,
            List<String> includeTags,
            List<String> excludeTags,
            int workerId,
            JsonEventWriter writer) {
        Adapter adapter = new Adapter(writer, workerId);

        LauncherDiscoveryRequestBuilder b = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(scanClasspath)));
        if (filter != null && !filter.isBlank()) {
            b.filters(ClassNameFilter.includeClassNamePatterns(TestRunner.classNamePattern(filter)));
        }
        applyTagFilters(b, includeTags, excludeTags);

        LauncherDiscoveryRequest request = b.build();
        Launcher launcher = LauncherFactory.create();

        long planStart = System.nanoTime();
        TestPlan plan;
        try {
            plan = launcher.discover(request);
        } catch (RuntimeException e) {
            reportDiscoveryFailure(scanClasspath, e);
            throw e;
        }
        emitDiscovery(plan, adapter);
        warnIfEmptyPlan(scanClasspath, plan, adapter);
        launcher.execute(request, adapter);
        long planMs = Math.max(0, (System.nanoTime() - planStart) / 1_000_000);
        adapter.emitPlanFinished(planMs);
        return adapter.hasFailures() ? 1 : 0;
    }

    static void runListOnly(
            Path scanClasspath,
            String filter,
            List<String> includeTags,
            List<String> excludeTags,
            int workerId,
            JsonEventWriter writer) {
        Adapter adapter = new Adapter(writer, workerId);
        LauncherDiscoveryRequestBuilder b = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(scanClasspath)));
        if (filter != null && !filter.isBlank()) {
            b.filters(ClassNameFilter.includeClassNamePatterns(TestRunner.classNamePattern(filter)));
        }
        applyTagFilters(b, includeTags, excludeTags);
        TestPlan plan;
        try {
            plan = LauncherFactory.create().discover(b.build());
        } catch (RuntimeException e) {
            reportDiscoveryFailure(scanClasspath, e);
            throw e;
        }
        emitDiscovery(plan, adapter);
        warnIfEmptyPlan(scanClasspath, plan, adapter);
    }

    /**
     * Classpath-root discovery can drop classes when framework SPI (e.g. Quarkus
     * {@code FacadeClassLoader}) fails to load them — the plan is simply empty. Surface a
     * pointer so "No tests" is not a silent dead-end.
     */
    private static void warnIfEmptyPlan(Path scanClasspath, TestPlan plan, Adapter adapter) {
        if (plan == null) return;
        boolean anyTest = false;
        for (TestIdentifier root : plan.getRoots()) {
            if (hasTest(plan, root)) {
                anyTest = true;
                break;
            }
        }
        if (anyTest) return;
        long classFiles = countClassFiles(scanClasspath);
        if (classFiles <= 0) return;
        // Protocol warning, not stderr: passthrough stderr is muted unless --verbose and the
        // crash buffer only surfaces on non-zero exit — an empty plan exits 0, so the one
        // diagnostic that explains "No tests" was invisible exactly when needed.
        adapter.emitWarning(
                "empty-plan",
                "discovery found 0 tests under " + scanClasspath + " (" + classFiles
                        + " .class file(s) present). If you expected @QuarkusTest / framework tests,"
                        + " check classloader bootstrap errors (e.g. maven-resolver named-locks"
                        + " version skew) — rerun with --verbose for the runner's own output.");
    }

    private static boolean hasTest(TestPlan plan, TestIdentifier node) {
        if (node.isTest()) return true;
        for (TestIdentifier child : plan.getChildren(node)) {
            if (hasTest(plan, child)) return true;
        }
        return false;
    }

    private static long countClassFiles(Path root) {
        if (root == null || !Files.isDirectory(root)) return 0;
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.getFileName() != null
                            && p.getFileName().toString().endsWith(".class"))
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void reportDiscoveryFailure(Path scanClasspath, RuntimeException e) {
        System.err.println("jk-test-runner: test discovery failed under " + scanClasspath + ": "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
        Throwable c = e.getCause();
        int depth = 0;
        while (c != null && depth++ < 6) {
            System.err.println("  caused by: " + c.getClass().getName() + ": " + c.getMessage());
            c = c.getCause();
        }
    }

    /**
     * Pull-mode: run one class through the Launcher. Returns whether any test failed; the caller
     * merges that into its session failure bit.
     */
    static boolean runClass(
            String className, List<String> includeTags, List<String> excludeTags, int workerId, EventWriter writer) {
        Adapter adapter = new Adapter(writer, workerId);
        LauncherDiscoveryRequestBuilder b =
                LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(className));
        applyTagFilters(b, includeTags, excludeTags);
        LauncherFactory.create().execute(b.build(), adapter);
        return adapter.hasFailures();
    }

    private static void applyTagFilters(
            LauncherDiscoveryRequestBuilder b, List<String> includeTags, List<String> excludeTags) {
        if (includeTags != null && !includeTags.isEmpty()) {
            List<String> inc = includeTags.stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!inc.isEmpty()) {
                b.filters(org.junit.platform.launcher.TagFilter.includeTags(inc));
            }
        }
        if (excludeTags != null && !excludeTags.isEmpty()) {
            List<String> exc = excludeTags.stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!exc.isEmpty()) {
                b.filters(org.junit.platform.launcher.TagFilter.excludeTags(exc));
            }
        }
    }

    private static void emitDiscovery(TestPlan plan, Adapter adapter) {
        int[] counts = new int[] {0, 0};
        for (TestIdentifier root : plan.getRoots()) {
            walkPlan(plan, root, adapter, counts);
        }
        adapter.emitDiscoveryTotal(counts[0], counts[1]);
    }

    private static void walkPlan(TestPlan plan, TestIdentifier node, Adapter adapter, int[] counts) {
        if (node.isContainer()
                && node.getSource().isPresent()
                && node.getSource().get() instanceof ClassSource cs) {
            adapter.emitDiscovered(cs.getClassName());
            counts[0]++;
        }
        if (node.isTest()) counts[1]++;
        for (TestIdentifier child : plan.getChildren(node)) {
            walkPlan(plan, child, adapter, counts);
        }
    }

    /** Launcher listener that emits the same JSONL events as {@link StreamingListener}. */
    private static final class Adapter implements TestExecutionListener {
        private final EventWriter out;
        private final int workerId;
        private final ConcurrentHashMap<String, Long> startNanos = new ConcurrentHashMap<>();
        private final AtomicBoolean failed = new AtomicBoolean(false);

        Adapter(EventWriter out, int workerId) {
            this.out = out;
            this.workerId = workerId;
        }

        boolean hasFailures() {
            return failed.get();
        }

        void emitDiscovered(String className) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("class", className);
            emit(EventType.DISCOVERED, p);
        }

        void emitDiscoveryTotal(int classes, int tests) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("classes", classes);
            p.put("tests", tests);
            emit(EventType.DISCOVERY_TOTAL, p);
        }

        void emitWarning(String code, String message) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("code", code);
            p.put("message", message);
            emit(EventType.WARNING, p);
        }

        @Override
        public void dynamicTestRegistered(TestIdentifier id) {
            // Without this, every @ParameterizedTest/@TestFactory invocation counts as static
            // and the progress numerator blows past the static-plan denominator.
            Map<String, Object> p = new LinkedHashMap<>();
            JUnitUniqueId.parse(id.getUniqueId()).putIdentity(p);
            p.put("parent", id.getParentId().orElse(null));
            p.put("type", typeName(id));
            emit(EventType.DYNAMIC_REGISTERED, p);
        }

        void emitPlanFinished(long durationMs) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("duration_ms", durationMs);
            p.put("failed", failed.get());
            emit(EventType.PLAN_FINISHED, p);
        }

        @Override
        public void executionSkipped(TestIdentifier id, String reason) {
            Map<String, Object> p = new LinkedHashMap<>();
            JUnitUniqueId.parse(id.getUniqueId()).putIdentity(p);
            p.put("type", typeName(id));
            p.put("reason", reason == null ? "" : reason);
            emit(EventType.SKIPPED, p);
        }

        @Override
        public void executionStarted(TestIdentifier id) {
            startNanos.put(id.getUniqueId(), System.nanoTime());
            Map<String, Object> p = new LinkedHashMap<>();
            JUnitUniqueId.parse(id.getUniqueId()).putIdentity(p);
            p.put("parent", id.getParentId().orElse(null));
            p.put("type", typeName(id));
            id.getSource().ifPresent(src -> p.put("source", src.toString()));
            emit(EventType.STARTED, p);
        }

        @Override
        public void executionFinished(TestIdentifier id, TestExecutionResult result) {
            long started = startNanos.getOrDefault(id.getUniqueId(), System.nanoTime());
            long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            if (result.getStatus() == TestExecutionResult.Status.FAILED) {
                failed.set(true);
            }
            Map<String, Object> p = new LinkedHashMap<>();
            JUnitUniqueId.parse(id.getUniqueId()).putIdentity(p);
            p.put("type", typeName(id));
            p.put("status", result.getStatus().name());
            p.put("duration_ms", durationMs);
            result.getThrowable().ifPresent(t -> p.put("throwable", throwableMap(t)));
            emit(EventType.FINISHED, p);
        }

        private void emit(EventType type, Map<String, Object> payload) {
            try {
                if (workerId > 0) payload.put("worker", workerId);
                out.write(type, payload);
                out.flush();
            } catch (Exception e) {
                System.err.println("jk-test-runner: emit failed: " + e.getMessage());
            }
        }

        private static String typeName(TestIdentifier id) {
            if (id.isTest() && id.isContainer()) return "CONTAINER_AND_TEST";
            if (id.isTest()) return "TEST";
            return "CONTAINER";
        }

        /** Single-string stack ({@code printStackTrace}); not a line array. */
        private static Map<String, Object> throwableMap(Throwable t) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("class", t.getClass().getName());
            m.put("message", t.getMessage() == null ? "" : t.getMessage());
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            m.put("stack", sw.toString());
            return m;
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * JUnit Platform <em>Launcher</em> path for {@code jk test} — the only way this runner discovers or
 * executes tests.
 *
 * <p>Unlike raw {@code TestEngine.discover/execute}, the Launcher opens a {@code LauncherSession}
 * and fires {@code LauncherSessionListener} / {@code LauncherDiscoveryListener} SPIs. Quarkus
 * ({@code CustomLauncherInterceptor}) needs those to install {@code FacadeClassLoader} and register
 * {@code TestConfig} before {@code @QuarkusTest} runs. It is also the sole owner of tag filtering
 * and of the wire payloads ({@link Adapter}); nothing in this plugin re-decides either.
 *
 * <p>Compile-only against launcher; the forked test JVM must put a matching launcher jar on the CP.
 * {@code jk lock} injects {@code org.junit.platform:junit-platform-launcher} into every project's
 * test classpath, so this holds regardless of which test framework the project chose.
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
            @Nullable String filter,
            List<String> includeTags,
            List<String> excludeTags,
            int workerId,
            EventWriter writer) {
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
            @Nullable String filter,
            List<String> includeTags,
            List<String> excludeTags,
            int workerId,
            EventWriter writer) {
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

    /** Pull-mode handshake: "send me the next class". Same emit path as every other event. */
    static void emitReady(EventWriter writer, int workerId) {
        new Adapter(writer, workerId).emit(EventType.READY, new LinkedHashMap<>());
    }

    /**
     * The one tag filter in this plugin. Entries are JUnit Platform <em>tag expressions</em>, not
     * bare names: {@code slow}, {@code !slow}, {@code slow | bench} and {@code any()} all mean what
     * JUnit says they mean, because {@link TagFilter} is the parser of record.
     *
     * <p>Discovery ({@code --list-only}) and execution (one-shot and pull) both come through here,
     * so a filter can never select one set of tests to list and a different set to run.
     */
    private static void applyTagFilters(
            LauncherDiscoveryRequestBuilder b, List<String> includeTags, List<String> excludeTags) {
        List<String> inc = expressions(includeTags);
        if (!inc.isEmpty()) b.filters(TagFilter.includeTags(inc));
        List<String> exc = expressions(excludeTags);
        if (!exc.isEmpty()) b.filters(TagFilter.excludeTags(exc));
    }

    private static List<String> expressions(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        return raw.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static void emitDiscovery(TestPlan plan, Adapter adapter) {
        List<String> classes = discoveredClasses(plan);
        for (String className : classes) adapter.emitDiscovered(className);
        adapter.emitDiscoveryTotal(classes.size(), countTests(plan));
    }

    /**
     * The test classes a plan runs, top-level classes only, in plan order. A {@code @Nested} class
     * is a child of its enclosing class and runs as part of it; announcing it as a class of its own
     * had the pull workers run its tests twice — once inside the outer class, once on their own —
     * and report both.
     */
    /** {@link #discoveredClasses(TestPlan)} over the plan of one selected class. */
    static List<String> discoveredClassesOf(Class<?> testClass) {
        return discoveredClasses(LauncherFactory.create()
                .discover(LauncherDiscoveryRequestBuilder.request()
                        .selectors(DiscoverySelectors.selectClass(testClass))
                        .build()));
    }

    static List<String> discoveredClasses(TestPlan plan) {
        List<String> out = new ArrayList<>();
        for (TestIdentifier root : plan.getRoots()) collectClasses(plan, root, out);
        return out;
    }

    private static void collectClasses(TestPlan plan, TestIdentifier node, List<String> out) {
        if (isClassContainer(node)
                && !plan.getParent(node).map(LauncherPath::isClassContainer).orElse(false)) {
            out.add(((ClassSource) node.getSource().orElseThrow()).getClassName());
        }
        for (TestIdentifier child : plan.getChildren(node)) collectClasses(plan, child, out);
    }

    private static int countTests(TestPlan plan) {
        int[] tests = {0};
        for (TestIdentifier root : plan.getRoots()) countTests(plan, root, tests);
        return tests[0];
    }

    private static void countTests(TestPlan plan, TestIdentifier node, int[] tests) {
        if (node.isTest()) tests[0]++;
        for (TestIdentifier child : plan.getChildren(node)) countTests(plan, child, tests);
    }

    private static boolean isClassContainer(TestIdentifier node) {
        return node.isContainer()
                && node.getSource().isPresent()
                && node.getSource().get() instanceof ClassSource;
    }

    /**
     * Bound for a failure message on the wire — keep in lock-step with the engine aggregator's
     * {@code JUnitLauncher.ResultAggregator.MAX_MESSAGE_CHARS} and its {@code
     * MESSAGE_TRUNCATION_MARKER}. Capping here bounds the JSONL line itself; the engine re-caps and
     * recognises this exact form so a worker-capped message passes through with its own remainder
     * count intact.
     */
    private static final int MAX_MESSAGE_CHARS = 8_192;

    private static final String MESSAGE_TRUNCATION_MARKER = " ... message truncated (";

    /**
     * Render a throwable in a form the parent process can display without needing the failure's
     * classes on its own classpath. {@code stack} is a single string ({@code printStackTrace} text).
     *
     * <p>The message is worker-controlled input that rides every downstream copy — wire, SSE,
     * journal, web card — so an {@code assertEquals} diff of two multi-MB strings must not put
     * hundreds of MB of transients through the engine for one bad suite.
     */
    static Map<String, Object> throwableMap(Throwable t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", t.getClass().getName());
        m.put("message", capMessage(t.getMessage() == null ? "" : t.getMessage()));
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        m.put("stack", sw.toString()); // single string — not a line array
        return m;
    }

    private static String capMessage(String message) {
        if (message.length() <= MAX_MESSAGE_CHARS) return message;
        int cut = MAX_MESSAGE_CHARS;
        // Never leave a lone high surrogate at the cut — the JSON encoder would emit an
        // unpaired code unit and the parent's decoder would see a replacement char.
        if (Character.isHighSurrogate(message.charAt(cut - 1))) cut--;
        return message.substring(0, cut) + MESSAGE_TRUNCATION_MARKER + (message.length() - cut) + " more chars)";
    }

    /**
     * Structured identity from the uniqueId, plus the human display name when the engine has no
     * class/method segments (Spock spec/feature, Cucumber feature/scenario) — without it, progress
     * and FAILED labels regress to the raw bracketed uniqueId.
     */
    static void putIdentity(String uniqueId, @Nullable String displayName, Map<String, Object> payload) {
        JUnitUniqueId.parse(uniqueId).putIdentity(payload);
        if (payload.containsKey("testClass") || payload.containsKey("testMethod")) return;
        if (displayName != null && !displayName.isBlank()) payload.put("display", displayName);
    }

    /** The one listener: every event the parent sees is written here. */
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
            Map<String, Object> p = identity(id);
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
            Map<String, Object> p = identity(id);
            p.put("type", typeName(id));
            p.put("reason", reason == null ? "" : reason);
            emit(EventType.SKIPPED, p);
        }

        @Override
        public void executionStarted(TestIdentifier id) {
            startNanos.put(id.getUniqueId(), System.nanoTime());
            Map<String, Object> p = identity(id);
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
            Map<String, Object> p = identity(id);
            p.put("type", typeName(id));
            p.put("status", result.getStatus().name());
            p.put("duration_ms", durationMs);
            result.getThrowable().ifPresent(t -> p.put("throwable", throwableMap(t)));
            emit(EventType.FINISHED, p);
        }

        private static Map<String, Object> identity(TestIdentifier id) {
            Map<String, Object> p = new LinkedHashMap<>();
            putIdentity(id.getUniqueId(), id.getDisplayName(), p);
            return p;
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
    }
}

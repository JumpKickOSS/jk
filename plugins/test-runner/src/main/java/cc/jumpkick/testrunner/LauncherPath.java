// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.Exit;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * JUnit Platform <em>Launcher</em> path for {@code jk test} — the only way this runner discovers or
 * executes tests.
 *
 * <p>Unlike raw {@code TestEngine.discover/execute}, the Launcher opens a {@code LauncherSession}
 * and fires {@code LauncherSessionListener} / {@code LauncherDiscoveryListener} SPIs. Quarkus
 * ({@code CustomLauncherInterceptor}) needs those to install {@code FacadeClassLoader} and register
 * {@code TestConfig} before {@code @QuarkusTest} runs, so every launcher that executes fires them.
 * A discovery that only names classes runs on {@link #listingLauncher()}, which fires none. It is
 * also the sole owner of tag filtering and of the wire payloads ({@link Adapter}); nothing in this
 * plugin re-decides either.
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
            List<MethodSelection> methods,
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
        applyMethodFilter(b, methods);
        applyTagFilters(b, includeTags, excludeTags);
        DiscoveryFailures dropped = new DiscoveryFailures(scanClasspath, filter);
        b.listeners(dropped);

        LauncherDiscoveryRequest request = b.build();
        Clock clock = Clock.SYSTEM;
        long planStart = clock.nanos();
        TestPlan plan;
        try {
            plan = listingLauncher().discover(request);
        } catch (RuntimeException e) {
            if (emptyRunWithoutEngine(e, scanClasspath, filter, adapter)) {
                adapter.emitPlanFinished(0);
                return 0;
            }
            reportDiscoveryFailure(scanClasspath, e);
            throw e;
        }
        // A class discovery dropped is a suite that cannot run as written: fail before executing,
        // so the engine sees the header and no event, never a green run minus the class.
        if (dropped.report(System.err)) return Exit.SOFTWARE;
        emitDiscovery(plan, adapter);
        warnIfEmptyPlan(scanClasspath, filter, plan, adapter);
        warnTagExcluded(() -> named(scanClasspath, filter, methods), includeTags, excludeTags, plan, adapter);
        // Executed class by class on a launcher that fires the session and discovery listeners, as
        // the pull workers execute: a framework readies the JVM for the class about to run, not for
        // every class the root holds — Quarkus augments one application per test profile as the
        // classes load, which over a root of hundreds of @QuarkusTest classes is more heap than a
        // JVM has before the first test is named. A plan with a test under no class — an engine
        // that runs features or scripts — is executed whole, as the one request it was listed from.
        Launcher launcher = LauncherFactory.create();
        if (everyTestUnderAClass(plan)) {
            for (String className : discoveredClasses(plan)) {
                LauncherDiscoveryRequestBuilder one =
                        LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(className));
                if (filter != null && !filter.isBlank()) {
                    one.filters(ClassNameFilter.includeClassNamePatterns(TestRunner.classNamePattern(filter)));
                }
                applyTagFilters(one, includeTags, excludeTags);
                applyMethodFilter(one, methods);
                launcher.execute(one.build(), adapter);
            }
        } else {
            launcher.execute(request, adapter);
        }
        long planMs = Math.max(0, (clock.nanos() - planStart) / 1_000_000);
        adapter.emitPlanFinished(planMs);
        return adapter.hasFailures() ? 1 : 0;
    }

    /** True when every test of {@code plan} sits under a class container, so selecting the classes selects the tests. */
    private static boolean everyTestUnderAClass(TestPlan plan) {
        for (TestIdentifier root : plan.getRoots()) if (!everyTestUnderAClass(plan, root)) return false;
        return true;
    }

    private static boolean everyTestUnderAClass(TestPlan plan, TestIdentifier node) {
        if (isClassContainer(node)) return true;
        if (node.isTest()) return false;
        for (TestIdentifier child : plan.getChildren(node)) if (!everyTestUnderAClass(plan, child)) return false;
        return true;
    }

    /**
     * The launcher for a discovery that names classes and runs nothing: the {@code --list-only}
     * fork, and the second looks a run takes to explain an empty or tag-filtered plan. No
     * auto-registered {@code LauncherSessionListener}, {@code LauncherDiscoveryListener} or {@code
     * TestExecutionListener} fires on it — those are how a framework readies a JVM for the tests it
     * is about to run, and Quarkus's augments one application per test profile as the classes load
     * through its {@code FacadeClassLoader}, which a JVM that runs no test has no use for and, over
     * hundreds of {@code @QuarkusTest} classes, no heap for. Engines and post-discovery filters stay
     * auto-registered: they decide what the list holds.
     */
    static Launcher listingLauncher() {
        return LauncherFactory.create(LauncherConfig.builder()
                .enableLauncherSessionListenerAutoRegistration(false)
                .enableLauncherDiscoveryListenerAutoRegistration(false)
                .enableTestExecutionListenerAutoRegistration(false)
                .build());
    }

    /** Exit 0 with the plan's classes announced, or {@link Exit#SOFTWARE} after a discovery failure was printed. */
    static int runListOnly(
            Path scanClasspath,
            @Nullable String filter,
            List<MethodSelection> methods,
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
        applyMethodFilter(b, methods);
        applyTagFilters(b, includeTags, excludeTags);
        DiscoveryFailures dropped = new DiscoveryFailures(scanClasspath, filter);
        b.listeners(dropped);
        TestPlan plan;
        try {
            plan = listingLauncher().discover(b.build());
        } catch (RuntimeException e) {
            if (emptyRunWithoutEngine(e, scanClasspath, filter, adapter)) return 0;
            reportDiscoveryFailure(scanClasspath, e);
            throw e;
        }
        // Printed before any class is announced: a list the engine cannot trust is no list.
        if (dropped.report(System.err)) return Exit.SOFTWARE;
        emitDiscovery(plan, adapter);
        warnIfEmptyPlan(scanClasspath, filter, plan, adapter);
        warnTagExcluded(() -> named(scanClasspath, filter, methods), includeTags, excludeTags, plan, adapter);
        return 0;
    }

    /**
     * The classes and methods {@code --class} names under {@code scanClasspath}, before any tag
     * filter: the selection the tag filter is judged against. Null when no class filter is in force.
     */
    private static @Nullable LauncherDiscoveryRequestBuilder named(
            Path scanClasspath, @Nullable String filter, List<MethodSelection> methods) {
        if (filter == null || filter.isBlank()) return null;
        LauncherDiscoveryRequestBuilder b = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(scanClasspath)))
                .filters(ClassNameFilter.includeClassNamePatterns(TestRunner.classNamePattern(filter)));
        applyMethodFilter(b, methods);
        return b;
    }

    /** {@code --class Foo#bar}: the post-discovery filter that keeps only the named methods of the named classes. */
    private static void applyMethodFilter(LauncherDiscoveryRequestBuilder b, List<MethodSelection> methods) {
        PostDiscoveryFilter filter = MethodSelection.filter(methods);
        if (filter != null) b.filters(filter);
    }

    /** {@link #warnTagExcluded(Supplier, List, List, TestPlan, Adapter)} writing to {@code writer}. */
    static void warnTagExcluded(
            Supplier<@Nullable LauncherDiscoveryRequestBuilder> named,
            List<String> includeTags,
            List<String> excludeTags,
            TestPlan filtered,
            EventWriter writer,
            int workerId) {
        warnTagExcluded(named, includeTags, excludeTags, filtered, new Adapter(writer, workerId));
    }

    /**
     * Naming a class is the strongest selection {@code jk test} has, so a class the {@code --class}
     * filter matched and the tag filter then dropped is reported by name — with the tags that
     * dropped it and the flag that runs it — instead of vanishing into a green run that ran
     * nothing of what was named. Found by discovering the named classes once more without the
     * tag filter and taking the difference; only when both a class filter and a tag filter are
     * in force, so an ordinary run costs nothing.
     */
    private static void warnTagExcluded(
            Supplier<@Nullable LauncherDiscoveryRequestBuilder> named,
            List<String> includeTags,
            List<String> excludeTags,
            TestPlan filtered,
            Adapter adapter) {
        List<String> inc = expressions(includeTags);
        List<String> exc = expressions(excludeTags);
        if (inc.isEmpty() && exc.isEmpty()) return;
        LauncherDiscoveryRequestBuilder selection = named.get();
        if (selection == null) return;
        TestPlan unfiltered;
        try {
            unfiltered = listingLauncher().discover(selection.build());
        } catch (RuntimeException e) {
            return;
        }
        Set<String> kept = new HashSet<>(discoveredClasses(filtered));
        Map<String, Set<String>> dropped = new LinkedHashMap<>();
        for (TestIdentifier root : unfiltered.getRoots()) collectDropped(unfiltered, root, kept, dropped);
        if (dropped.isEmpty()) return;
        adapter.emitWarning("tag-excluded", tagExcludedMessage(dropped, inc, exc));
    }

    /** Top-level classes of {@code plan} absent from {@code kept}, each with every tag it or its tests carry. */
    private static void collectDropped(
            TestPlan plan, TestIdentifier node, Set<String> kept, Map<String, Set<String>> dropped) {
        if (isClassContainer(node)
                && !plan.getParent(node).map(LauncherPath::isClassContainer).orElse(false)) {
            String className = ((ClassSource) node.getSource().orElseThrow()).getClassName();
            if (!kept.contains(className)) {
                Set<String> tags = new TreeSet<>();
                collectTags(plan, node, tags);
                dropped.put(className, tags);
            }
            return;
        }
        for (TestIdentifier child : plan.getChildren(node)) collectDropped(plan, child, kept, dropped);
    }

    private static void collectTags(TestPlan plan, TestIdentifier node, Set<String> tags) {
        node.getTags().forEach(t -> tags.add(t.getName()));
        for (TestIdentifier child : plan.getChildren(node)) collectTags(plan, child, tags);
    }

    /** The warning's text: each dropped class with its tags, then the flag that would run them. */
    static String tagExcludedMessage(
            Map<String, Set<String>> dropped, List<String> includeTags, List<String> excludeTags) {
        StringBuilder sb = new StringBuilder("--class named ")
                .append(dropped.size())
                .append(dropped.size() == 1 ? " class" : " classes")
                .append(" the tag filter excluded: ");
        Set<String> allTags = new TreeSet<>();
        boolean first = true;
        for (Map.Entry<String, Set<String>> e : dropped.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append(e.getValue().isEmpty() ? " (untagged)" : " " + e.getValue());
            allTags.addAll(e.getValue());
        }
        if (!excludeTags.isEmpty() && !allTags.isEmpty()) {
            sb.append("; pass --include-tags ")
                    .append(String.join(",", allTags))
                    .append(" (or a --profile that includes ")
                    .append(allTags.size() == 1 ? "it" : "them")
                    .append(") to run ")
                    .append(dropped.size() == 1 ? "it" : "them");
        } else {
            sb.append("; --include-tags ")
                    .append(String.join(",", includeTags))
                    .append(" admits only classes carrying ")
                    .append(includeTags.size() == 1 ? "that tag" : "those tags")
                    .append(" — drop it, or --no-profile, to run ")
                    .append(dropped.size() == 1 ? "it" : "them");
        }
        return sb.toString();
    }

    /** The warning code the engine turns into a failed run-tests step. */
    static final String NO_TESTS_DISCOVERED = "no-tests-discovered";

    /**
     * The warning code of a test root whose classes declare no test framework at all: an empty run
     * the engine leaves green, as Maven's surefire reports {@code No tests to run}.
     */
    static final String NO_TEST_CLASSES = "no-test-classes";

    /** How many of the classes a {@link #NO_TEST_CLASSES} warning names before counting the rest. */
    private static final int NAMED_CLASSES = 8;

    /**
     * A plan with no test where test classes exist is a run that would report success having run
     * nothing: an engine the Platform dropped, a class filter that admits no test. A class the
     * loader could not produce is reported before this by {@link DiscoveryFailures}. Judged against a second discovery without the tag filters,
     * so a plan the filters emptied stays what it is — a tier with nothing in it. Not under a
     * class filter: in a workspace every module but the one holding the named class is empty, and
     * the engine judges an unmatched {@code --class} across the run.
     *
     * <p>When no class under the root is test-shaped by its bytes ({@link TestClassShape}: no test
     * annotation, no specification base) there is no framework whose engine could be missing — the
     * root holds simulators, fixtures, a {@code main} — and the run is empty rather than broken:
     * {@link #NO_TEST_CLASSES} names the classes it skipped. One class that does declare a test
     * keeps the failure, since a declared framework whose engine is absent is exactly the case it
     * exists for.
     */
    private static void warnIfEmptyPlan(Path scanClasspath, @Nullable String filter, TestPlan plan, Adapter adapter) {
        if (plan == null || (filter != null && !filter.isBlank())) return;
        if (hasTest(plan)) return;
        long classes = countClassFiles(scanClasspath);
        if (classes <= 0) return;
        TestPlan unfiltered;
        try {
            unfiltered = listingLauncher()
                    .discover(LauncherDiscoveryRequestBuilder.request()
                            .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(scanClasspath)))
                            .build());
        } catch (RuntimeException e) {
            return;
        }
        if (hasTest(unfiltered)) return;
        // Protocol warning, not stderr: passthrough stderr is muted unless --verbose and the
        // crash buffer only surfaces on non-zero exit — an empty plan exits 0.
        List<String> plain = classesDeclaringNoTest(scanClasspath);
        if (plain != null) {
            adapter.emitWarning(NO_TEST_CLASSES, noTestClassesMessage(plain, scanClasspath));
            return;
        }
        adapter.emitWarning(NO_TESTS_DISCOVERED, noTestsMessage(classes, scanClasspath));
    }

    /**
     * The top-level classes under {@code root} when none of them is test-shaped; {@code null} as
     * soon as one is, or when there is none to judge. A class whose bytes cannot be read counts as
     * test-shaped, so an unreadable root keeps the failure.
     */
    private static @Nullable List<String> classesDeclaringNoTest(Path root) {
        List<String> names = DiscoveryFailures.topLevelClassNames(root);
        if (names.isEmpty()) return null;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = ClassLoader.getSystemClassLoader();
        TestClassShape shape = new TestClassShape(root, loader);
        for (String name : names) {
            if (shape.isTestClass(name)) return null;
        }
        return names;
    }

    /** What the Platform says when no engine is on the classpath; discovery cannot begin at all. */
    static final String NO_ENGINE = "without at least one TestEngine";

    /**
     * True when {@code noLauncher} is the Platform's no-engine refusal and the root's classes declare
     * no test, in which case the empty run — a discovery of nothing and the {@link #NO_TEST_CLASSES}
     * warning — has been announced through {@code adapter}: with no framework declared there was
     * none the engine could have served, so the run is empty, not broken. A root with a declared
     * test, or a class filter, is left to fail: there the missing engine is the failure.
     */
    static boolean emptyRunWithoutEngine(
            RuntimeException noLauncher,
            Path scanClasspath,
            @Nullable String filter,
            EventWriter writer,
            int workerId) {
        return emptyRunWithoutEngine(noLauncher, scanClasspath, filter, new Adapter(writer, workerId));
    }

    private static boolean emptyRunWithoutEngine(
            RuntimeException noLauncher, Path scanClasspath, @Nullable String filter, Adapter adapter) {
        if (!String.valueOf(noLauncher.getMessage()).contains(NO_ENGINE)) return false;
        if (filter != null && !filter.isBlank()) return false;
        List<String> plain = classesDeclaringNoTest(scanClasspath);
        if (plain == null) return false;
        adapter.emitDiscoveryTotal(0, 0);
        adapter.emitWarning(NO_TEST_CLASSES, noTestClassesMessage(plain, scanClasspath));
        return true;
    }

    /** The empty-run line: how many classes, where, and which — none of them a test. */
    static String noTestClassesMessage(List<String> classes, Path scanClasspath) {
        int n = classes.size();
        StringBuilder sb = new StringBuilder("no test classes: ")
                .append(n == 1 ? "the one class" : "none of the " + n + " classes")
                .append(" under ")
                .append(scanClasspath)
                .append(n == 1 ? " declares" : " declare")
                .append(" a test framework (no test annotation, no specification base), so nothing ran — ")
                .append(String.join(", ", classes.subList(0, Math.min(n, NAMED_CLASSES))));
        if (n > NAMED_CLASSES) sb.append(" and ").append(n - NAMED_CLASSES).append(" more");
        return sb.toString();
    }

    /** The failure line: the class count, then where to look. */
    static String noTestsMessage(long classes, Path scanClasspath) {
        return "no tests discovered in " + classes + (classes == 1 ? " class" : " classes") + " under "
                + scanClasspath
                + " — the test framework's engine is not on the classpath or not on the launcher's"
                + " Platform line (`jk why org.junit.platform:junit-platform-launcher`), or a framework"
                + " classloader failed to load the classes; rerun with --verbose for the runner's own output.";
    }

    private static boolean hasTest(TestPlan plan) {
        for (TestIdentifier root : plan.getRoots()) {
            if (hasTest(plan, root)) return true;
        }
        return false;
    }

    private static boolean hasTest(TestPlan plan, TestIdentifier node) {
        if (node.isTest()) return true;
        for (TestIdentifier child : plan.getChildren(node)) {
            if (hasTest(plan, child)) return true;
        }
        return false;
    }

    /** Top-level class files under {@code root}; a nested or anonymous class is part of its outer one. */
    private static long countClassFiles(Path root) {
        if (root == null || !Files.isDirectory(root)) return 0;
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.getFileName() != null
                            && p.getFileName().toString().endsWith(".class")
                            && !p.getFileName().toString().contains("$"))
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * One header line the engine reads back — {@code jk-test-runner: test discovery failed:
     * <exception class>: <message>} — then the classpath root and the cause chain.
     */
    private static void reportDiscoveryFailure(Path scanClasspath, RuntimeException e) {
        System.err.println(
                "jk-test-runner: test discovery failed: " + e.getClass().getName() + ": " + e.getMessage());
        System.err.println("  under " + scanClasspath);
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
            String className,
            List<MethodSelection> methods,
            List<String> includeTags,
            List<String> excludeTags,
            int workerId,
            EventWriter writer) {
        Adapter adapter = new Adapter(writer, workerId);
        LauncherDiscoveryRequestBuilder b =
                LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(className));
        applyMethodFilter(b, methods);
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
     * and FAILED labels regress to the raw bracketed uniqueId — and for every invocation of a
     * parameterized or dynamic test, whose display name is its only distinct name.
     */
    static void putIdentity(String uniqueId, @Nullable String displayName, Map<String, Object> payload) {
        JUnitUniqueId id = JUnitUniqueId.parse(uniqueId);
        id.putIdentity(payload);
        if (displayName == null || displayName.isBlank()) return;
        boolean unnamed = !payload.containsKey("testClass") && !payload.containsKey("testMethod");
        // An invocation of a parameterized or dynamic test is one of several under one method; its
        // display name ("[1] "build"") is what tells them apart, and what every other JUnit XML
        // writer records for it.
        boolean invocation = id.testMethod.endsWith("]");
        if (unnamed || invocation) payload.put("display", displayName);
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

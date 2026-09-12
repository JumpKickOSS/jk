// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static cc.jumpkick.test.TestEventFields.classNameOf;
import static cc.jumpkick.test.TestEventFields.engineOf;
import static cc.jumpkick.test.TestEventFields.identityKey;
import static cc.jumpkick.test.TestEventFields.methodOf;
import static cc.jumpkick.test.TestEventFields.progressLabel;
import static cc.jumpkick.test.TestEventFields.xmlName;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.plugin.protocol.JUnitUniqueIds;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.util.JkDirs;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Forks {@code TestRunner} child JVM(s): one-shot when {@code workers=1}, else discovery + N
 * pull-queue workers (RUN/DONE on stdin). Protocol lines are {@code ##JKT:}-prefixed JSONL.
 */
public final class JUnitLauncher {

    /** Marker appended by {@code ResultAggregator.truncateStack}; {@code EventRedaction} keys the cut-seam masking off it. */
    public static final String STACK_TRUNCATION_MARKER = "\n\t... stack truncated (";

    /** Marker appended by {@code ResultAggregator.truncateMessage}. */
    public static final String MESSAGE_TRUNCATION_MARKER = " ... message truncated (";

    /** Marker prefix every protocol line carries. Must match {@code JsonEventWriter.PREFIX}. */
    private static final String PROTOCOL_PREFIX = "##JKT:";

    /**
     * The plugin the plugin host must run. The test-runner jar is launched with the module-under-test
     * on its classpath (to discover its tests); when that module is itself a plugin, the host would
     * otherwise see two {@code Plugin} services. Naming the runner explicitly via {@code
     * -Djk.plugin.class} keeps the host on {@code TestRunner} regardless of what the module registers.
     */
    private static final String RUNNER_PLUGIN_CLASS = "cc.jumpkick.testrunner.TestRunner";

    /**
     * {@code jk.<worker>.plugin.jar} (and {@code jk.engine.jar}) overrides handed to the test JVM so
     * tests that fork a first-party plugin or materialize the engine locate jars by path. Mirrors
     * what Gradle's test config provides; under {@code jk build} the {@code run-tests} step resolves
     * the freshly-built sibling jars and passes them here. Empty when none are built (e.g. a scoped
     * single-module build) — tests then fall back to CAS-by-sha.
     */
    private Map<String, String> workerJarProps = Map.of();

    /** JUnit include tags; empty = no include filter. */
    private List<String> includeTags = List.of();

    /** JUnit exclude tags. */
    private List<String> excludeTags = List.of();

    /** {@code [test] serial-tags} — see {@link #withSerialTags}. */
    private List<String> serialTags = List.of();

    /** When non-empty, run only these class FQCNs ({@code --affected}). */
    private List<String> classNames = List.of();

    public JUnitLauncher withClassNames(List<String> names) {
        this.classNames = names == null ? List.of() : List.copyOf(names);
        return this;
    }

    /** {@code --class} patterns as one class-name regex for the runner, or null for every class. */
    private @Nullable String classFilter;

    /**
     * {@code --class}: run only classes matching these names (fully qualified, simple, or with
     * {@code *} wildcards). Discovery and the one-shot runner both apply the filter, so a sharded
     * run dispatches exactly the classes a single JVM would have run. Ignored when {@link
     * #withClassNames} named exact classes. A filter that matches nothing comes back as an empty
     * summary; whether that is a skip or a failure is the caller's call, not the launcher's.
     */
    public JUnitLauncher withClassPatterns(List<String> patterns) {
        this.classFilter = patterns == null || patterns.isEmpty() ? null : JUnitClassFilter.patternRegex(patterns);
        return this;
    }

    /** JDWP listener for the suite JVM ({@code --debug-jvm}); null for an ordinary run. */
    private @Nullable DebugJvm debug;

    /**
     * Start the suite JVM with {@link DebugJvm#agentArg()}. A debugger attaches to one process, so
     * the run is pinned to a single worker: no discovery JVM, no pull-mode pool — the listener
     * belongs to the JVM that runs the tests and to nothing else jk forks.
     */
    public JUnitLauncher withDebug(@Nullable DebugJvm debug) {
        this.debug = debug;
        return this;
    }

    /** The JaCoCo agent every test-running JVM starts with ({@code --coverage}); null for a plain run. */
    private @Nullable CoverageAgent coverage;

    /**
     * Start every JVM that runs tests — the single suite runner and each pull-mode shard — with the
     * JaCoCo agent. Discovery only lists classes and stays uninstrumented.
     */
    public JUnitLauncher withCoverage(@Nullable CoverageAgent coverage) {
        this.coverage = coverage;
        return this;
    }

    /**
     * {@link #runnerFlags} for {@code role}, plus the JDWP agent when the suite JVM is under debug
     * and the JaCoCo agent on every test-running JVM of a coverage run.
     */
    List<String> jvmFlags(JvmRole role, int concurrency, @Nullable Path tmpDir) {
        List<String> flags = new ArrayList<>(runnerFlags(concurrency, tmpDir));
        if (role == JvmRole.SUITE && debug != null) flags.add(debug.agentArg());
        if (role != JvmRole.DISCOVERY && coverage != null) flags.add(coverage.agentArg());
        return flags;
    }

    /**
     * The test JVM's environment: the module's {@link WorkerEnv} policy plus the sandbox the planner
     * hands over ({@code JK_HOME}, {@code JK_STATE_DIR} for nested-engine suites, the temp root).
     */
    private WorkerEnv testEnv = WorkerEnv.strict();

    /** Module coord for failure lines (e.g. {@code cc.jumpkick:jk-core}); empty when unknown. */
    private String moduleLabel = "";

    /**prefix failure / progress labels with this module coordinate. */
    public JUnitLauncher withModuleLabel(String moduleLabel) {
        this.moduleLabel = moduleLabel == null ? "" : moduleLabel.trim();
        return this;
    }

    /**
     * Worker JVM flags: the heap/GC tuning, the {@code jk.plugin.class} selector for the runner, and
     * any {@code jk.<worker>.plugin.jar} / {@code jk.engine.jar} overrides.
     */
    private List<String> runnerFlags(int concurrency, @Nullable Path tmpDir) {
        List<String> flags = new ArrayList<>(JvmOptions.workerFlags(concurrency));
        flags.add("-Djk.plugin.class=" + RUNNER_PLUGIN_CLASS);
        // The Java half of the TMPDIR TestEnv sandboxes: @TempDir reads the property, not the
        // environment. Passed in, not read off testEnv — with W>1 it is the worker's own subdir.
        if (tmpDir != null) flags.add("-Djava.io.tmpdir=" + tmpDir);
        // Suite JVMs: no AOT train-on-miss (nested engines / compiler workers); still map caches.
        flags.add("-Djk.aot.train=off");
        // CLI integration tests use FFM (EngineClient / MemoryProbe) and JUnit autodetection of
        // EngineTestExtension — match Gradle's:cli:test jvmArgs / systemProperty setup.
        if (!testEnv.extras().isEmpty()) {
            flags.add("--enable-native-access=ALL-UNNAMED");
            flags.add("-Djunit.jupiter.extensions.autodetection.enabled=true");
            // Match Gradle :cli:test — short /tmp factory + soft-fail delete. Nested engines
            // hardlink into @TempDir caches; macOS can fail Standard delete. The strategy
            // reports success anyway. Cleanup stays ALWAYS: NEVER left tens of thousands of
            // dirs on tmpfs /tmp until the next @TempDir could not allocate an inode.
            //
            // Only where the classes exist. These two live in jk-cli's TEST tree, and a
            // non-empty [test] env is not a test for "this is jk-cli" — server/engine sets one
            // too, and named a factory it cannot load JUnit logs a stack trace per @TempDir and
            // silently falls back, which costs the soft-fail delete this exists to provide.
            if (cliTempDirSupport) {
                flags.add("-Djunit.jupiter.tempdir.deletion.strategy.default="
                        + "cc.jumpkick.cli.engine.JkTempDirDeletionStrategy");
                flags.add("-Djunit.jupiter.tempdir.factory.default=cc.jumpkick.cli.engine.JkTempDirFactory");
            }
            flags.add("-Djunit.jupiter.tempdir.cleanup.mode.default=always");
            String jkHome = testEnv.extras().get("JK_HOME");
            if (jkHome != null && !jkHome.isBlank()) {
                // Sibling of the sandbox home: <slot>/test-shared-cache (SharedTestCache).
                Path jkHomeParent = Path.of(jkHome).getParent();
                if (jkHomeParent != null) {
                    flags.add("-Djk.test.cache.dir=" + jkHomeParent.resolve("test-shared-cache"));
                }
                // Sandbox JK_HOME hides the product store; first-party workers still resolve
                // Zinc from the host engine's store.
                flags.add("-D"
                        + PomRuntimeClasspath.HOST_STORE_PROPERTY
                        + "="
                        + JkDirs.store().toAbsolutePath().normalize());
            }
        }
        workerJarProps.forEach((prop, jar) -> flags.add("-D" + prop + "=" + jar));
        // Quarkus BuildToolHelper / path resolution walk from user.dir; point at the module root
        // when we can infer it from the standard jk layout (.../target/classes/test).
        if (inferredModuleDir != null) {
            flags.add("-Duser.dir=" + inferredModuleDir.toAbsolutePath().normalize());
        }
        return flags;
    }

    /** Set when {@link #run} starts — module root inferred from testClassesDir layout. */
    private @Nullable Path inferredModuleDir;

    /** Set when {@link #run} starts — the sandboxed temp root; see {@link TestTmpDir}. */
    private @Nullable Path testTmpDir;

    /**
     * Set when {@link #run} starts — whether jk-cli's {@code @TempDir} factory and deletion
     * strategy are on this module's test classpath. Naming them for a module that cannot load
     * them is not a no-op: JUnit logs a stack trace per {@code @TempDir} and falls back to the
     * default strategy, so the soft-fail delete the flag exists to install is not installed.
     */
    private boolean cliTempDirSupport;

    /**
     * {@code .../target/classes/test} → module root ({@code .../}). Null when layout is nonstandard.
     */
    static @Nullable Path inferModuleDir(@Nullable Path testClassesDir) {
        if (testClassesDir == null) return null;
        Path p = testClassesDir.toAbsolutePath().normalize();
        // .../target/classes/test
        if (!"test".equals(name(p))) return null;
        p = p.getParent(); // classes
        if (p == null || !"classes".equals(name(p))) return null;
        p = p.getParent(); // target
        if (p == null || !BuildLayout.TARGET.equals(name(p))) return null;
        return p.getParent();
    }

    /** The XML report exists exactly when there is a directory to write it into. */
    private static void writeXml(@Nullable XmlTestReport xml, @Nullable Path testResultsDir) {
        if (testResultsDir == null || xml == null) return;
        try {
            xml.writeAll(testResultsDir);
        } catch (IOException e) {
            // Non-fatal: the tests ran; only the report failed to land.
        }
    }

    private static String name(Path p) {
        Path f = p.getFileName();
        return f == null ? "" : f.toString();
    }

    /** JUnit Platform tag filters forwarded to the runner. */
    public JUnitLauncher withTagFilters(List<String> include, List<String> exclude) {
        this.includeTags = include == null ? List.of() : List.copyOf(include);
        this.excludeTags = exclude == null ? List.of() : List.copyOf(exclude);
        return this;
    }

    /**
     * {@code [test] serial-tags}: class-level tags whose classes run on a single trailing worker
     * instead of the sharded pool. Partitioning is per class — a method-level serial
     * tag inside an otherwise-untagged class still shards with its class.
     */
    public JUnitLauncher withSerialTags(List<String> tags) {
        this.serialTags = tags == null ? List.of() : List.copyOf(tags);
        return this;
    }

    /** List-only discovery with {@code extraExcludes} folded in — the serial-tag partition view. */
    private List<String> discoverWithExtraExcludes(
            Path javaBinary, String classpath, Path testClassesDir, List<String> extraExcludes)
            throws IOException, InterruptedException {
        List<String> saved = excludeTags;
        var widened = new ArrayList<>(saved);
        for (String t : extraExcludes) {
            if (!widened.contains(t)) widened.add(t);
        }
        excludeTags = List.copyOf(widened);
        try {
            // noop listener: the full discovery already reported totals; this view must not
            // grow the denominator a second time.
            return discoverClasses(javaBinary, classpath, testClassesDir, TestProgressListener.noop());
        } finally {
            excludeTags = saved;
        }
    }

    private List<String> withTagArgs(List<String> base) {
        var out = new ArrayList<>(base);
        if (classFilter != null && classNames.isEmpty()) out.add("--filter=" + classFilter);
        if (!includeTags.isEmpty()) out.add("--include-tags=" + String.join(",", includeTags));
        if (!excludeTags.isEmpty()) out.add("--exclude-tags=" + String.join(",", excludeTags));
        return out;
    }

    /**
     * Run the project's tests. {@code workers} of 1 (today's default) takes the one-shot path;
     * anything higher fans out across pull-mode workers.
     *
     * <p>{@code listener} receives a stream of progress callbacks as events arrive — pass {@link
     * TestProgressListener#noop} when no UI is wired.
     *
     * <p>{@code workerJarProps} maps {@code jk.<worker>.plugin.jar} property names to built
     * plugin-jar paths, forwarded to the test JVM so plugin-forking tests can locate their plugin by
     * path (see {@link #workerJarProps}).
     */
    public TestSummary run(
            Path javaHome,
            Path testClassesDir,
            List<Path> runtimeClasspath,
            Path cacheRoot,
            int workers,
            Map<String, String> workerJarProps,
            TestProgressListener listener)
            throws IOException, InterruptedException {
        return run(
                javaHome,
                testClassesDir,
                runtimeClasspath,
                cacheRoot,
                workers,
                workerJarProps,
                WorkerEnv.strict(),
                listener,
                null);
    }

    /**
     * As {@link #run(Path, Path, List, Path, int, Map, TestProgressListener)} but also writes
     * Gradle-compatible {@code TEST-<classname>.xml} files into {@code testResultsDir} after the run
     * completes. {@code testResultsDir} may be {@code null} to skip XML output.
     */
    public TestSummary run(
            Path javaHome,
            Path testClassesDir,
            List<Path> runtimeClasspath,
            Path cacheRoot,
            int workers,
            Map<String, String> workerJarProps,
            TestProgressListener listener,
            @Nullable Path testResultsDir)
            throws IOException, InterruptedException {
        return run(
                javaHome,
                testClassesDir,
                runtimeClasspath,
                cacheRoot,
                workers,
                workerJarProps,
                WorkerEnv.strict(),
                listener,
                testResultsDir);
    }

    /**
     * As {@link #run(Path, Path, List, Path, int, Map, TestProgressListener, Path)} with {@code
     * testEnv} merged into the test JVM environment (isolated {@code JK_HOME}/{@code JK_STATE_DIR}
     * for nested-engine suites).
     */
    public TestSummary run(
            Path javaHome,
            Path testClassesDir,
            List<Path> runtimeClasspath,
            Path cacheRoot,
            int workers,
            Map<String, String> workerJarProps,
            WorkerEnv testEnv,
            TestProgressListener listener,
            @Nullable Path testResultsDir)
            throws IOException, InterruptedException {
        Objects.requireNonNull(javaHome, "javaHome");
        Objects.requireNonNull(testClassesDir, "testClassesDir");
        Objects.requireNonNull(runtimeClasspath, "runtimeClasspath");
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(listener, "listener");
        // workers: 0 = auto (Mill-like min(jobs, classes) + heap clamp); ≥1 = explicit.
        if (workers < 0) throw new IllegalArgumentException("workers must be >= 0 (0 = auto)");
        // One debugger, one JVM: a pool would have every shard contend for the same port.
        int wanted = debug != null ? 1 : workers;
        this.workerJarProps = workerJarProps == null ? Map.of() : Map.copyOf(workerJarProps);
        // Quarkus PathTestHelper only recognizes Maven/Gradle/IDE test-dir fragments. JK uses
        // target/classes/test + target/classes/main — register via TEST_TO_MAIN_MAPPINGS (BootstrapConstants).
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put(
                "TEST_TO_MAIN_MAPPINGS", "classes" + File.separator + "test" + ":classes" + File.separator + "main");
        // Suite JVMs must never prompt on the developer's controlling TTY (Confirm/Wizard via JLine
        // system terminal) or hang waiting for a keystroke during `jk build` / `jk test`.
        defaults.put("JK_NONINTERACTIVE", "1");
        this.testEnv = Objects.requireNonNull(testEnv, "testEnv").withDefaults(defaults);
        this.inferredModuleDir = inferModuleDir(testClassesDir);
        this.testTmpDir = TestTmpDir.ensure(this.testEnv.extras().get("TMPDIR"));
        QuarkusToolingPom.ensure(this.inferredModuleDir);

        Path runnerJar = locateRunner(cacheRoot);
        var classpathBase = new LinkedHashSet<Path>();
        classpathBase.add(testClassesDir);
        classpathBase.addAll(runtimeClasspath);
        // Thin workers: jar + Maven runtime closure from the POM. Gradle-vendored runners
        // already contain PluginMain; extra entries are harmless.
        classpathBase.addAll(WorkerLaunchClasspath.paths(runnerJar));
        String classpath = Classpaths.join(classpathBase);
        this.cliTempDirSupport = CliTempDirSupport.onClasspath(classpathBase);
        Path javaBinary = javaBinary(javaHome);

        int resolvedWorkers = wanted;
        List<String> preDiscovered = null;
        if (!classNames.isEmpty()) {
            preDiscovered = classNames;
            resolvedWorkers = TestWorkers.resolve(wanted, preDiscovered.size(), TestWorkers.effectiveJobs());
            if (preDiscovered.size() <= 1) resolvedWorkers = 1;
        } else if (wanted == 0) {
            // Discover once so auto can size the pool; reuse the list when W>1.
            preDiscovered = discoverClasses(javaBinary, classpath, testClassesDir, listener);
            resolvedWorkers = TestWorkers.resolve(0, preDiscovered.size(), TestWorkers.effectiveJobs());
        } else if (wanted > 1) {
            resolvedWorkers = TestWorkers.resolve(wanted, Integer.MAX_VALUE, TestWorkers.effectiveJobs());
        }

        // Tag every summary with the runner count that produced it, at the one place that count is
        // authoritative. The inner paths cannot: `runSingle` returns from two places and the
        // parallel pool merges per-worker aggregators built by a constructor that has no idea. Doing
        // it per-path recorded the concurrency for 2 modules out of 29, which is worse than not at
        // all — a forecast rescales the modules it has a count for and not the rest.
        if (resolvedWorkers <= 1) {
            return runSingle(javaBinary, classpath, testClassesDir, listener, testResultsDir)
                    .withWorkers(1);
        }
        // W>1 + Jupiter in-process parallel is a known double-parallelism footgun.
        List<Path> cpForDetect = new ArrayList<>();
        cpForDetect.add(testClassesDir);
        if (runtimeClasspath != null) cpForDetect.addAll(runtimeClasspath);
        if (JupiterParallelDetect.enabled(cpForDetect)) {
            listener.onWarning("jupiter-parallel", JupiterParallelDetect.stackWarning(resolvedWorkers));
        }
        return runParallel(
                        javaBinary, classpath, testClassesDir, resolvedWorkers, listener, testResultsDir, preDiscovered)
                .withWorkers(resolvedWorkers);
    }

    // -------- single-worker ---------------------------------------------

    private TestSummary runSingle(
            Path javaBinary,
            String classpath,
            Path testClassesDir,
            TestProgressListener listener,
            @Nullable Path testResultsDir)
            throws IOException, InterruptedException {
        XmlTestReport xml = testResultsDir != null ? new XmlTestReport() : null;
        MarkdownTestReport md = new MarkdownTestReport();
        var aggregator = new ResultAggregator(listener, /* workerId */ 0, xml, md, moduleLabel);
        // Capture the worker's non-protocol output so a hard crash (uncaught
        // throwable / System.exit before any test event) can be explained instead
        // of surfacing only as "runner exited N".
        var crash = new CaptureBuffer();
        int exit = PluginLoader.run(
                javaBinary,
                classpath,
                jvmFlags(JvmRole.SUITE, 1, testTmpDir),
                PROTOCOL_PREFIX,
                withTagArgs(JUnitClassFilter.singleWorkerArgs(testClassesDir, classNames)),
                testEnv,
                inferredModuleDir,
                aggregator::accept,
                line -> {
                    crash.add(line);
                    listener.onUserOutput(0, line);
                });
        TestSummary result = aggregator.toResult(exit, crash.text());
        writeXml(xml, testResultsDir);
        publishTests(md, testClassesDir);
        return result;
    }

    // -------- parallel pull-queue ---------------------------------------

    private TestSummary runParallel(
            Path javaBinary,
            String classpath,
            Path testClassesDir,
            int workers,
            TestProgressListener listener,
            @Nullable Path testResultsDir)
            throws IOException, InterruptedException {
        return runParallel(javaBinary, classpath, testClassesDir, workers, listener, testResultsDir, null);
    }

    private TestSummary runParallel(
            Path javaBinary,
            String classpath,
            Path testClassesDir,
            int workers,
            TestProgressListener listener,
            @Nullable Path testResultsDir,
            @Nullable List<String> preDiscovered)
            throws IOException, InterruptedException {
        // 1. Discovery — one fork, list-only mode, harvest class FQCNs (skip if auto already did).
        List<String> classes = preDiscovered != null
                ? preDiscovered
                : discoverClasses(javaBinary, classpath, testClassesDir, listener);
        if (classes.isEmpty()) {
            return new TestSummary(0, 0, 0, 0, List.of());
        }

        // [test] serial-tags partition (class-level): classes bearing a serial tag leave the
        // sharded pool and run on one trailing worker. Partitioned by class-list subtraction —
        // a second list-only discovery with the serial tags excluded — so tag expressions
        // never enter the picture.
        List<String> serialClasses = List.of();
        if (!serialTags.isEmpty()) {
            Set<String> parallelView =
                    new HashSet<>(discoverWithExtraExcludes(javaBinary, classpath, testClassesDir, serialTags));
            List<String> par = new ArrayList<>();
            List<String> ser = new ArrayList<>();
            for (String c : classes) (parallelView.contains(c) ? par : ser).add(c);
            classes = par;
            serialClasses = ser;
        }

        // One shared report per format — all worker threads write into them (both are thread-safe).
        XmlTestReport xml = testResultsDir != null ? new XmlTestReport() : null;
        MarkdownTestReport md = new MarkdownTestReport();

        TestSummary summary = classes.isEmpty()
                ? new TestSummary(0, 0, 0, 0, List.of())
                : runPool(javaBinary, classpath, testClassesDir, workers, listener, classes, xml, md, 0);
        // A runner-crash sentinel means the fork itself is broken — don't fork it again.
        boolean crashed = summary.failures().stream().anyMatch(f -> "(test run)".equals(f.method()));
        if (!serialClasses.isEmpty() && !crashed) {
            TestSummary serial =
                    runPool(javaBinary, classpath, testClassesDir, 1, listener, serialClasses, xml, md, workers);
            summary = merge(summary, serial);
        }
        writeXml(xml, testResultsDir);
        publishTests(md, testClassesDir);
        return summary;
    }

    /** Fold this launch's JUnit events into the store {@code jk-results.md} drains. */
    private void publishTests(MarkdownTestReport md, Path testClassesDir) {
        if (md == null) return;
        Path scope = inferredModuleDir != null ? inferredModuleDir : testClassesDir;
        String key = "";
        if (scope != null) {
            try {
                key = scope.toAbsolutePath().normalize().toString();
            } catch (RuntimeException ignored) {
                key = scope.toString();
            }
        }
        String label = !moduleLabel.isBlank() ? moduleLabel : leafName(key);
        md.publish(key, label);
    }

    private static String leafName(String path) {
        if (path == null || path.isBlank()) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The sharded pool's summary and the serial-tag pool's as one suite result. */
    static TestSummary merge(TestSummary a, TestSummary b) {
        var failures = new ArrayList<>(a.failures());
        failures.addAll(b.failures());
        var walls = new LinkedHashMap<>(a.classWallMs());
        b.classWallMs().forEach((k, v) -> walls.merge(k, v, Long::sum));
        return new TestSummary(
                a.total() + b.total(),
                a.succeeded() + b.succeeded(),
                a.failed() + b.failed(),
                a.skipped() + b.skipped(),
                a.classes() + b.classes(),
                failures,
                walls,
                // Sharded pool + serial-tag pool: the suite's concurrency is the wider of the two,
                // since that is what shaped its wall.
                Math.max(a.workers(), b.workers()));
    }

    /** One pull-queue pool over {@code classes}; report accumulation stays with the caller. */
    private TestSummary runPool(
            Path javaBinary,
            String classpath,
            Path testClassesDir,
            int workers,
            TestProgressListener listener,
            List<String> classes,
            @Nullable XmlTestReport xml,
            MarkdownTestReport md,
            int workerIdBase)
            throws IOException, InterruptedException {
        // Don't waste workers on small suites — N workers > N classes leaves
        // some idle waiting for a class that'll never come.
        int actualWorkers = Math.min(workers, classes.size());
        actualWorkers = TestWorkers.clampByHeap(actualWorkers);

        var queue = new ConcurrentLinkedDeque<>(classes);
        var aggregators = new ArrayList<ResultAggregator>();
        var workerThreads = new ArrayList<Thread>();
        int[] exits = new int[actualWorkers];
        var captures = new ArrayList<CaptureBuffer>();
        var lastClasses = new ArrayList<AtomicReference<String>>();

        for (int w = 0; w < actualWorkers; w++) {
            // workerIdBase keeps ids unique across the sharded and serial-tag pools, so the
            // per-worker temp/state suffixes and failure attributions never collide.
            final int workerId = workerIdBase + w + 1;
            final int idx = w;
            List<String> args =
                    withTagArgs(List.of("--pull", "--worker=" + workerId, "--scan-classpath=" + testClassesDir));
            var agg = new ResultAggregator(listener, workerId, xml, md, moduleLabel);
            aggregators.add(agg);
            final var crash = new CaptureBuffer();
            captures.add(crash);
            final var last = new AtomicReference<String>("");
            lastClasses.add(last);
            final int totalWorkers = actualWorkers;
            // Virtual: the thread blocks on the child's stdout for the worker's whole life —
            // exactly the shape VT is for.
            Thread t = Thread.ofVirtual()
                    .name("jk-test-worker-" + workerId)
                    .start(() -> exits[idx] = driveWorker(
                            javaBinary, classpath, workerId, totalWorkers, args, queue, agg, listener, crash, last));
            workerThreads.add(t);
        }
        // Each worker thread owns its process (via PluginProcess.converse) and
        // returns its exit code once stdout is fully drained and the process
        // has exited. Join them and take the worst exit.
        for (Thread t : workerThreads) t.join();
        int worstExit = 0;
        for (int e : exits) {
            if (e != 0) worstExit = e;
        }
        // Merge per-worker aggregators into one TestSummary.
        long total = 0, succeeded = 0, failed = 0, skipped = 0, classCount = 0;
        var allFailures = new ArrayList<TestFailureInfo>();
        var walls = new LinkedHashMap<String, Long>();
        for (var agg : aggregators) {
            var r = agg.snapshot();
            total += r.total();
            succeeded += r.succeeded();
            failed += r.failed();
            skipped += r.skipped();
            classCount += r.classes();
            allFailures.addAll(r.failures());
            r.classWallMs().forEach((k, v) -> walls.merge(k, v, Long::sum));
        }
        if (total == 0 && worstExit != 0) {
            // No test events but a worker died — surface what the crashed worker(s)
            // printed (the dropped stderr) instead of a bare "runner exited N".
            StringBuilder crash = new StringBuilder();
            for (int i = 0; i < actualWorkers; i++) {
                if (exits[i] != 0 && !captures.get(i).isEmpty()) {
                    if (crash.length() > 0) crash.append('\n');
                    crash.append(captures.get(i).text());
                }
            }
            return new TestSummary(
                    1,
                    0,
                    1,
                    0,
                    List.of(new TestFailureInfo(
                            moduleLabel, "", "", "(test run)", "", "runner exited " + worstExit, crash.toString())));
        }
        // A worker that dies mid-suite while its siblings keep going must not vanish silently:
        // its in-flight class is neither run nor reported, and the suite would go green with a
        // shortfall. Surface every abnormal exit as a failure naming the worker's last class
        // (idle-watchdog kills land here too). Skipped on user cancel: those exits
        // are the kill we asked for.
        if (worstExit != 0 && !SessionCancel.cancelled()) {
            for (int i = 0; i < actualWorkers; i++) {
                if (exits[i] == 0) continue;
                total += 1;
                failed += 1;
                String cls = lastClasses.get(i).get();
                String why = "test worker exited " + exits[i] + " mid-run"
                        + (cls.isBlank() ? "" : " (last class dispatched: " + cls + ")");
                String who = "(worker " + (workerIdBase + i + 1) + ")";
                allFailures.add(new TestFailureInfo(
                        moduleLabel, "", cls, who, "", why, captures.get(i).text(), workerIdBase + i + 1));
            }
        }
        String cancelledWhy = CancelledShortfall.of(SessionCancel.cancelled(), worstExit, queue.size());
        if (cancelledWhy != null) {
            total += 1;
            failed += 1;
            allFailures.add(new TestFailureInfo(moduleLabel, "", "", "(test run)", "", cancelledWhy, "", 0));
        }
        return new TestSummary(total, succeeded, failed, skipped, classCount, allFailures, walls, actualWorkers);
    }

    /**
     * Per-worker reader thread. Reads the child's stdout line-by-line. On each {@code ready} event,
     * dispatch the next class from the shared queue (or {@code DONE} when the queue is empty) by
     * writing one line to the child's stdin. Non-protocol lines are user test output — passed through
     * to the parent's stdout, tagged with the worker id.
     */
    private int driveWorker(
            Path javaBinary,
            String classpath,
            int workerId,
            int totalWorkers,
            List<String> args,
            ConcurrentLinkedDeque<String> queue,
            ResultAggregator aggregator,
            TestProgressListener listener,
            CaptureBuffer crash,
            AtomicReference<String> lastClass) {
        // Pull protocol: each "ready" pulls the next class from the shared queue.
        BiConsumer<String, PluginProcess.Conversation> handler = (json, convo) -> {
            String event = Jsonl.str(json, "event");
            if ("ready".equals(event)) {
                String next = queue.pollFirst();
                if (next != null) {
                    lastClass.set(next);
                    convo.send("RUN " + next);
                } else {
                    convo.send("DONE");
                    convo.closeInput();
                }
            } else {
                aggregator.accept(json);
            }
        };
        Consumer<String> passthrough = line -> {
            crash.add(line);
            listener.onUserOutput(workerId, line);
        };

        try {
            Path tmp = TestTmpDir.forWorker(testTmpDir, workerId, totalWorkers);
            WorkerEnv env = totalWorkers > 1 && tmp != null ? TestWorkerEnv.forWorker(testEnv, workerId, tmp) : testEnv;
            List<String> flags = jvmFlags(JvmRole.PULL_WORKER, totalWorkers, tmp);
            return PluginLoader.converse(
                    javaBinary,
                    classpath,
                    // N test JVMs run at once → divide the heap cap by N so they fit.
                    flags,
                    PROTOCOL_PREFIX,
                    args,
                    env,
                    inferredModuleDir,
                    handler,
                    passthrough,
                    TestWorkerEnv.idleTimeoutMs());
        } catch (IOException e) {
            listener.onUserOutput(workerId, "reader error: " + e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Step one of parallel mode: list every top-level test class without running anything. Uses
     * {@code Launcher.discover} (not {@code execute}) so this completes in 100–300 ms even for big
     * suites.
     */
    private List<String> discoverClasses(
            Path javaBinary, String classpath, Path testClassesDir, TestProgressListener listener)
            throws IOException, InterruptedException {
        var classes = new ArrayList<String>();
        PluginLoader.run(
                javaBinary,
                classpath,
                jvmFlags(JvmRole.DISCOVERY, 1, testTmpDir),
                PROTOCOL_PREFIX,
                withTagArgs(List.of("--list-only", "--scan-classpath=" + testClassesDir)),
                testEnv,
                inferredModuleDir,
                json -> {
                    String event = Jsonl.str(json, "event");
                    if ("discovered".equals(event)) {
                        classes.add(Jsonl.str(json, "class"));
                    } else if ("discovery_total".equals(event)) {
                        listener.onDiscoveryTotal(Jsonl.intValue(json, "classes", 0), Jsonl.intValue(json, "tests", 0));
                    }
                },
                null);
        return classes;
    }

    // -------- shared helpers --------------------------------------------

    /**
     * Look up the jk-test-runner jar in the local CAS, keyed by its SHA-256 (the hash this build of
     * engine was paired against — embedded as a resource at {@link #RUNNER_SHA_RESOURCE} by Gradle's
     * {@code writeRunnerSha} task).
     *
     * <p>Until jk-test-runner ships to Maven Central, the user is responsible for side-loading the
     * jar into the CAS — typically by running {@code ./gradlew :test-runner:installLocalCas} in jk's
     * own tree. Once the runner is published, {@code jk sync} will populate the CAS automatically.
     *
     * <p>Throws {@link IOException} with side-load instructions if the jar isn't in the CAS at the
     * expected hash. The error message spells out the exact destination path the user needs to
     * populate.
     */
    private static Path locateRunner(Path cacheRoot) throws IOException {
        // Location (override → CAS-by-SHA) is shared with every other worker via
        // PluginJar; adapt its IllegalStateException to this method's IOException.
        try {
            return PluginJar.TEST_RUNNER.locate(JkStores.storeCas());
        } catch (IllegalStateException e) {
            throw new IOException("jk test: " + e.getMessage(), e);
        }
    }

    private static Path javaBinary(Path javaHome) {
        return JdkFingerprint.java(javaHome);
    }

    // -------- event aggregation -----------------------------------------

    /**
     * Thread-safe accumulator. Multiple driveWorker threads call into {@link #accept} concurrently.
     * Counts come from FINISHED / SKIPPED events for {@code type == TEST} — we ignore CONTAINER nodes
     * (the engine root, classes themselves) so totals match {@code SummaryGeneratingListener}
     * semantics. Failure detail is pulled from FINISHED[status=FAILED] events.
     *
     * <p>Counting per-event (not summing per-worker plan totals) sidesteps a {@link
     * org.junit.platform.launcher.listeners.SummaryGeneratingListener} quirk: it resets its
     * accumulator on every {@code testPlanExecutionStarted} — which fires per {@code
     * Launcher.execute} call — so in pull mode a worker's final summary reflects only its last
     * class.
     */
    static final class ResultAggregator {

        private final TestProgressListener listener;
        private final int workerId;
        private final @Nullable XmlTestReport xmlReport;
        private final @Nullable MarkdownTestReport mdReport;
        private final String moduleLabel;
        private long succeeded;
        private long failed;
        private long skipped;
        private final List<TestFailureInfo> failures = new ArrayList<>();
        // Tests whose `dynamic_registered` event we observed at execute-time
        // — i.e., @ParameterizedTest / @TestFactory / @TestTemplate /
        // @RepeatedTest invocations that weren't in the static plan. Used
        // to mark their later `finished`/`skipped` events as wasStatic=false
        // so progress UIs can keep a stable static-plan denominator.
        private final Set<String> dynamicIds = new HashSet<>();
        // Distinct classes with at least one executed (finished/skipped) test — the
        // class-rate ETA prior's denominator. Workers partition by class, so
        // per-worker counts sum without overlap.
        private final Set<String> executedClasses = new HashSet<>();
        /** FQCN → wall-ms for CONTAINER finished events (class-level timing for ETA). */
        private final Map<String, Long> classWallMs = new LinkedHashMap<>();

        /** Test-friendly ctor: no listener, no worker id, no reports. */
        ResultAggregator() {
            this(TestProgressListener.noop(), 0, null, null, "");
        }

        /** For tests of the XML naming. */
        ResultAggregator(XmlTestReport xml) {
            this(TestProgressListener.noop(), 0, xml, null, "");
        }

        ResultAggregator(TestProgressListener listener, int workerId) {
            this(listener, workerId, null, null, "");
        }

        ResultAggregator(
                TestProgressListener listener,
                int workerId,
                @Nullable XmlTestReport xmlReport,
                @Nullable MarkdownTestReport mdReport) {
            this(listener, workerId, xmlReport, mdReport, "");
        }

        ResultAggregator(
                TestProgressListener listener,
                int workerId,
                @Nullable XmlTestReport xmlReport,
                @Nullable MarkdownTestReport mdReport,
                @Nullable String moduleLabel) {
            this.listener = listener;
            this.workerId = workerId;
            this.xmlReport = xmlReport;
            this.mdReport = mdReport;
            this.moduleLabel = moduleLabel == null ? "" : moduleLabel;
        }

        synchronized void accept(String json) {
            // A non-protocol line shows up here only in tests that call accept
            // directly. In production the caller already stripped the prefix, so
            // any line that doesn't look like a JSON object is user output.
            if (json == null || !json.startsWith("{")) {
                listener.onUserOutput(workerId, json);
                return;
            }
            acceptJson(json);
        }

        private void acceptJson(String json) {
            String event = Jsonl.str(json, "event");
            if (event == null) return;
            switch (event) {
                case "discovery_total" ->
                    listener.onDiscoveryTotal(Jsonl.intValue(json, "classes", 0), Jsonl.intValue(json, "tests", 0));
                case "dynamic_registered" -> {
                    if ("TEST".equals(Jsonl.str(json, "type"))) {
                        String uid = identityKey(json);
                        if (!uid.isEmpty()) dynamicIds.add(uid);
                    }
                }
                case "warning" ->
                    // Both fields are optional on arrival: a warning the runner half-filled is
                    // still worth surfacing, and a decoder that throws here ends the worker's pump.
                    listener.onWarning(
                            Objects.requireNonNullElse(Jsonl.str(json, "code"), "warning"),
                            Objects.requireNonNullElse(Jsonl.str(json, "message"), ""));
                case "started" -> onStarted(json);
                case "finished" -> onFinished(json);
                case "skipped" -> onSkipped(json);
                default -> {}
            }
        }

        private void onStarted(String json) {
            boolean isTest = "TEST".equals(Jsonl.str(json, "type"));
            String id = identityKey(json);
            String label = progressLabel(json);
            listener.onTestStarted(id, label, isTest, eventWorker(json));
        }

        private void onFinished(String json) {
            boolean isTest = "TEST".equals(Jsonl.str(json, "type"));
            String id = identityKey(json);
            String status = Objects.requireNonNullElse(Jsonl.str(json, "status"), "");
            String label = progressLabel(json);
            long duration = Jsonl.intValue(json, "duration_ms", 0);
            int w = eventWorker(json);
            boolean wasStatic = isTest && (id.isEmpty() || !dynamicIds.contains(id));
            String cls = classNameOf(json);
            if (isTest) {
                if (!cls.isEmpty()) executedClasses.add(cls);
                switch (status) {
                    case "SUCCESSFUL" -> succeeded++;
                    case "FAILED" -> captureFailure(json, label, false);
                    case "ABORTED" -> skipped++;
                    default -> {}
                }
            } else {
                // Class (or suite) container wall — free duration_ms from the runner; no method walk.
                if (!cls.isEmpty() && duration > 0 && "SUCCESSFUL".equals(status)) {
                    classWallMs.merge(cls, duration, Long::sum);
                }
                if ("FAILED".equals(status)) {
                    // A container-level failure (class initializer / @BeforeAll / engine):
                    // no per-test event follows, so without capturing it the run would
                    // surface only as a bare "runner exited N". Record it with its stack.
                    captureFailure(json, label.isEmpty() ? "container" : label + " (container)", true);
                }
            }
            listener.onTestFinished(id, label, status, isTest, wasStatic, duration, w);
            if (isTest) {
                String throwable = Jsonl.nested(json, "throwable");
                String xmlName = xmlName(json, label);
                if ("ABORTED".equals(status)) {
                    if (xmlReport != null) xmlReport.recordSkipped(id, xmlName, "aborted");
                    if (mdReport != null) mdReport.recordSkipped(id, label, "aborted");
                } else {
                    if (xmlReport != null) xmlReport.recordFinished(id, xmlName, duration, throwable);
                    if (mdReport != null) mdReport.recordFinished(id, label, duration, throwable);
                }
            }
        }

        /** Record a FAILED test/container: count it and keep identity + full stack. */
        private void captureFailure(String json, String label, boolean container) {
            failed++;
            String throwableJson = Jsonl.nested(json, "throwable");
            String exClass = throwableJson != null ? Jsonl.str(throwableJson, "class") : null;
            if (exClass == null) exClass = "?";
            String message = throwableJson != null ? Jsonl.str(throwableJson, "message") : null;
            if (message == null) message = "";
            message = truncateMessage(message);
            String stack = readStack(throwableJson);
            String className = classNameOf(json);
            String method = methodOf(json);
            String engine = engineOf(json);
            String testName = !method.isEmpty() ? method : label;
            if (container && !testName.endsWith("(container)")) {
                testName = testName + " (container)";
            }
            int w = eventWorker(json);
            // TestFailureInfo.method is the display identity: the method when the runner named one,
            // else the container/run label — there is no second short-name component to drift from it.
            String name = method.isEmpty() ? testName : method;
            failures.add(new TestFailureInfo(moduleLabel, engine, className, name, exClass, message, stack, w));
            listener.onFailure(identityKey(json), testName, exClass, message, stack, engine, className, method, w);
        }

        private void onSkipped(String json) {
            boolean isTest = "TEST".equals(Jsonl.str(json, "type"));
            String id = identityKey(json);
            boolean wasStatic = isTest && (id.isEmpty() || !dynamicIds.contains(id));
            if (isTest) {
                skipped++;
                String cls = classNameOf(json);
                if (!cls.isEmpty()) executedClasses.add(cls);
            }
            String reason = Jsonl.str(json, "reason");
            String label = progressLabel(json);
            int w = eventWorker(json);
            listener.onTestSkipped(id, label, reason != null ? reason : "", isTest, wasStatic, w);
            if (isTest) {
                if (xmlReport != null) xmlReport.recordSkipped(id, xmlName(json, label), reason);
                if (mdReport != null) mdReport.recordSkipped(id, label, reason);
            }
        }

        /** Event {@code worker} field, else this aggregator's id. */
        private int eventWorker(String json) {
            int w = Jsonl.intValue(json, "worker", -1);
            return w > 0 ? w : workerId;
        }

        /**
         * {@code throwable.stack} as a single string. Accepts a string (preferred) or a legacy line
         * array and joins it. Truncated to {@link #MAX_STACK_CHARS}: the stack is worker-controlled
         * input that rides every downstream copy (wire, SSE, journal), and a deep-recursion failure
         * can produce megabytes of frames that no reader wants.
         */
        static String readStack(@Nullable String throwableJson) {
            if (throwableJson == null) return "";
            String s = Jsonl.str(throwableJson, "stack");
            if (s == null) {
                List<String> lines = Jsonl.strArray(throwableJson, "stack");
                if (lines.isEmpty()) return "";
                s = String.join("\n", lines);
            }
            return truncateStack(s);
        }

        /** Bound for a single failure's stack text; ~400 frames — far past any useful depth. */
        static final int MAX_STACK_CHARS = 32_768;

        static String truncateStack(String stack) {
            if (stack == null || stack.length() <= MAX_STACK_CHARS) return stack;
            int cut = stack.lastIndexOf('\n', MAX_STACK_CHARS);
            if (cut <= 0) {
                cut = MAX_STACK_CHARS;
                // Hard cut (a single >32KB line): never leave a lone high surrogate.
                if (Character.isHighSurrogate(stack.charAt(cut - 1))) cut--;
            }
            return stack.substring(0, cut) + STACK_TRUNCATION_MARKER + (stack.length() - cut) + " more chars)";
        }

        /**
         * Bound for a single failure's message. Same rationale as {@link #MAX_STACK_CHARS}
         *: the message is worker-controlled input that rides every downstream copy —
         * wire, SSE, journal, web card — and an {@code assertEquals} diff of two multi-MB strings
         * otherwise puts hundreds of MB of transients through the engine for one bad suite. The
         * copy of the message inside the stack's first line was already bounded; the field itself
         * was not. {@code LauncherPath} applies the same cap worker-side so the JSONL line is
         * bounded on the wire too; this cap covers workers that predate it.
         */
        static final int MAX_MESSAGE_CHARS = 8_192;

        static String truncateMessage(String message) {
            if (message == null || message.length() <= MAX_MESSAGE_CHARS) return message;
            if (workerCapped(message)) return message;
            int cut = MAX_MESSAGE_CHARS;
            if (Character.isHighSurrogate(message.charAt(cut - 1))) cut--;
            return message.substring(0, cut) + MESSAGE_TRUNCATION_MARKER + (message.length() - cut) + " more chars)";
        }

        /**
         * A worker-capped message is cap-sized content + marker + remainder count. It exceeds the
         * cap only by the marker's own tail, and re-cutting would replace the worker's accurate
         * remainder count with the marker's length — so it passes through verbatim. The marker
         * position is bounded by the cap, keeping the accepted form itself bounded.
         */
        private static boolean workerCapped(String message) {
            String tail = " more chars)";
            if (!message.endsWith(tail)) return false;
            int at = message.lastIndexOf(MESSAGE_TRUNCATION_MARKER);
            if (at < 0 || at > MAX_MESSAGE_CHARS) return false;
            int digitsFrom = at + MESSAGE_TRUNCATION_MARKER.length();
            int digitsTo = message.length() - tail.length();
            if (digitsTo <= digitsFrom) return false;
            for (int i = digitsFrom; i < digitsTo; i++) {
                char c = message.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            return true;
        }

        synchronized TestSummary toResult(int exitCode) {
            return toResult(exitCode, "");
        }

        /**
         * As {@link #toResult(int)}, attaching {@code crashOutput} (the worker's captured
         * stdout/stderr) to the synthetic "runner exited" failure so a hard crash with no test events
         * still explains itself.
         */
        synchronized TestSummary toResult(int exitCode, String crashOutput) {
            long total = succeeded + failed + skipped;
            if (total == 0 && exitCode != 0) {
                return new TestSummary(
                        1,
                        0,
                        1,
                        0,
                        List.of(new TestFailureInfo(
                                moduleLabel,
                                "",
                                "",
                                "(test run)",
                                "",
                                "runner exited " + exitCode,
                                crashOutput == null ? "" : crashOutput,
                                workerId)));
            }
            return new TestSummary(
                    total,
                    succeeded,
                    failed,
                    skipped,
                    executedClasses.size(),
                    List.copyOf(failures),
                    Map.copyOf(classWallMs));
        }

        /** Snapshot of just the counters — used by the parallel-merge path. */
        synchronized TestSummary snapshot() {
            long total = succeeded + failed + skipped;
            return new TestSummary(
                    total,
                    succeeded,
                    failed,
                    skipped,
                    executedClasses.size(),
                    List.copyOf(failures),
                    Map.copyOf(classWallMs));
        }

        /** Class walls collected this worker (for parallel merge). */
        synchronized Map<String, Long> classWallMs() {
            return Map.copyOf(classWallMs);
        }
    }

    /**
     * Binary class name from a JUnit Platform unique id ({@code [class:…]} plus the
     * {@code [nested-class:…]} join) — the shared {@link JUnitUniqueIds} walk, same as the runner's
     * malformed-id fallback on the other side of the fork.
     */
    public static String classFromUniqueId(String id) {
        return id == null ? "" : JUnitUniqueIds.classOf(id);
    }

    /** Engine id from {@code [engine:junit-jupiter]} — the shared {@link JUnitUniqueIds} walk. */
    public static String engineFromUniqueId(String id) {
        return id == null ? "" : JUnitUniqueIds.engineOf(id);
    }
}

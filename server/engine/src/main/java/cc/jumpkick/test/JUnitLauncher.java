// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

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
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.plugin.protocol.JUnitUniqueIds;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.util.JkDirs;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    static final String PROTOCOL_PREFIX = "##JKT:";

    /**
     * The plugin the plugin host must run. The test-runner jar is launched with the module-under-test
     * on its classpath (to discover its tests); when that module is itself a plugin, the host would
     * otherwise see two {@code Plugin} services. Naming the runner explicitly via {@code
     * -Djk.plugin.class} keeps the host on {@code TestRunner} regardless of what the module registers.
     */
    private static final String RUNNER_PLUGIN_CLASS = "cc.jumpkick.testrunner.TestRunner";

    /**
     * {@code jk.<worker>.plugin.jar} (and {@code jk.engine.jar}) overrides handed to the test JVM so
     * tests that fork a first-party plugin or materialize the engine locate jars by path. The
     * {@code run-tests} step resolves the freshly-built sibling jars and passes them here. Empty
     * when none are built (e.g. a scoped
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

    /** Prefix failure / progress labels with this module coordinate. */
    public JUnitLauncher withModuleLabel(String moduleLabel) {
        this.moduleLabel = moduleLabel == null ? "" : moduleLabel.trim();
        return this;
    }

    /** {@code [test] assertions}: whether every test JVM this launcher forks runs with {@code -ea}. */
    private boolean assertions = true;

    /** Run the forked test JVMs with ({@code true}, the default) or without {@code -ea}. */
    public JUnitLauncher withAssertions(boolean enabled) {
        this.assertions = enabled;
        return this;
    }

    /** {@code [test] jvm-args}, its system properties and the active profile's {@code jvm-args}. */
    private List<String> jvmArgs = List.of();

    /**
     * Extra flags for every JVM this launcher forks, after jk's own tuning so the module's or a
     * profile's {@code -Xmx}, {@code -Xss} or {@code -D} wins over the default.
     */
    public JUnitLauncher withJvmArgs(List<String> args) {
        this.jvmArgs = args == null ? List.of() : List.copyOf(args);
        return this;
    }

    String moduleLabel() {
        return moduleLabel;
    }

    /** The test JVM environment of this run, sandbox defaults applied; set by {@link #run}. */
    WorkerEnv testEnv() {
        return testEnv;
    }

    @Nullable
    Path testTmpDir() {
        return testTmpDir;
    }

    @Nullable
    Path inferredModuleDir() {
        return inferredModuleDir;
    }

    /**
     * Worker JVM flags: the heap/GC tuning, {@code -ea} unless the module opted out, the test
     * table's and the active profile's {@code jvm-args}, the {@code jk.plugin.class} selector for the runner, and any
     * {@code jk.<worker>.plugin.jar} / {@code jk.engine.jar} overrides.
     */
    private List<String> runnerFlags(int concurrency, @Nullable Path tmpDir) {
        List<String> flags = new ArrayList<>(JvmOptions.suiteFlags(concurrency));
        // Surefire and Gradle fork test JVMs with assertions on; a Java or Kotlin `assert` in a
        // test is a check the author wrote to run.
        if (assertions) flags.add("-ea");
        flags.addAll(jvmArgs);
        flags.add("-Djk.plugin.class=" + RUNNER_PLUGIN_CLASS);
        // The Java half of the TMPDIR TestEnv sandboxes: @TempDir reads the property, not the
        // environment. Passed in, not read off testEnv — with W>1 it is the worker's own subdir.
        if (tmpDir != null) flags.add("-Djava.io.tmpdir=" + tmpDir);
        // Suite JVMs: no AOT train-on-miss (nested engines / compiler workers); still map caches.
        flags.add("-Djk.aot.train=off");
        // CLI integration tests use FFM (EngineClient / MemoryProbe) and JUnit autodetection of
        // EngineTestExtension.
        if (!testEnv.extras().isEmpty()) {
            flags.add("--enable-native-access=ALL-UNNAMED");
            flags.add("-Djunit.jupiter.extensions.autodetection.enabled=true");
            // Short /tmp factory + soft-fail delete. Nested engines
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
        // Quarkus BuildToolHelper / path resolution walk from user.dir; the same directory the
        // fork starts in, so Paths.get("") and user.dir agree.
        Path cwd = workDir();
        if (cwd != null) flags.add("-Duser.dir=" + cwd.toAbsolutePath().normalize());
        return flags;
    }

    /**
     * The test JVM's working directory: the module root when the classes dir follows a jk layout,
     * else the sandboxed temp root, else the classes dir itself. Never the engine daemon's own cwd
     * — that is the product home's state directory, and a test that roots anything at {@code
     * user.dir} would write into it. Null only before {@link #run} has bound a classes dir.
     */
    @Nullable
    Path workDir() {
        return workDir(inferredModuleDir, testTmpDir, testClassesDir);
    }

    /** {@link #workDir()} as a function of the three candidates, first known wins. */
    static @Nullable Path workDir(@Nullable Path moduleDir, @Nullable Path tmpDir, @Nullable Path testClassesDir) {
        if (moduleDir != null) return moduleDir;
        if (tmpDir != null) return tmpDir;
        return testClassesDir;
    }

    /** Set when {@link #run} starts — module root inferred from testClassesDir layout. */
    private @Nullable Path inferredModuleDir;

    /** Set when {@link #run} starts — the classes dir under test, the work dir of last resort. */
    private @Nullable Path testClassesDir;

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
     * The module root a test classes dir belongs to, under either jk layout: {@code
     * <module>/target/classes/test} for a standalone module, {@code
     * <workspace>/target/<module-rel>/classes/test} for a workspace member — the central out tree
     * {@link BuildLayout#moduleTargetDir} lays down, inverted. A member whose {@code module-rel}
     * names no directory under the workspace is not a module, and a layout with no {@code target}
     * ancestor is not jk's; both are null.
     */
    static @Nullable Path inferModuleDir(@Nullable Path testClassesDir) {
        if (testClassesDir == null) return null;
        Path p = testClassesDir.toAbsolutePath().normalize();
        if (!"test".equals(name(p))) return null;
        Path classes = p.getParent();
        if (classes == null || !"classes".equals(name(classes))) return null;
        // Everything between `target` and `classes` is the member's path relative to the root.
        List<String> rel = new ArrayList<>();
        Path cursor = classes.getParent();
        while (cursor != null && !BuildLayout.TARGET.equals(name(cursor))) {
            rel.add(0, name(cursor));
            cursor = cursor.getParent();
        }
        if (cursor == null) return null;
        Path root = cursor.getParent();
        if (root == null) return null;
        if (rel.isEmpty()) return root;
        Path module = root;
        for (String segment : rel) module = module.resolve(segment);
        return Files.isDirectory(module) ? module : null;
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
    private Discovery discoverWithExtraExcludes(
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

    /** The runner arguments of pull-mode shard worker {@code workerId}: the pull protocol plus this run's tag filters. */
    List<String> pullWorkerArgs(int workerId, Path testClassesDir) {
        return withTagArgs(List.of("--pull", "--worker=" + workerId, "--scan-classpath=" + testClassesDir));
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
        this.testClassesDir = testClassesDir.toAbsolutePath().normalize();
        this.inferredModuleDir = inferModuleDir(testClassesDir);
        this.testTmpDir = TestTmpDir.ensure(this.testEnv.extras().get("TMPDIR"));
        QuarkusToolingPom.ensure(this.inferredModuleDir);

        Path runnerJar = locateRunner(cacheRoot);
        var classpathBase = new LinkedHashSet<Path>();
        classpathBase.add(testClassesDir);
        classpathBase.addAll(runtimeClasspath);
        // Thin workers: jar + Maven runtime closure from the POM. A runner that vendors
        // PluginMain already has it; extra entries are harmless.
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
            Discovery discovery = discoverClasses(javaBinary, classpath, testClassesDir, listener);
            if (discovery.crashed()) return discovery.verdict(moduleLabel).withWorkers(1);
            preDiscovered = discovery.classes();
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
        TestSummary result;
        try {
            int exit = PluginLoader.run(
                    javaBinary,
                    classpath,
                    jvmFlags(JvmRole.SUITE, 1, testTmpDir),
                    PROTOCOL_PREFIX,
                    withTagArgs(JUnitClassFilter.singleWorkerArgs(testClassesDir, classNames)),
                    testEnv,
                    workDir(),
                    aggregator::accept,
                    line -> {
                        crash.add(line);
                        aggregator.userOutput(line);
                    });
            result = aggregator.toResult(exit, crash.text());
        } catch (PluginProcess.HandlerFailure e) {
            // The parent's own decoder ended the fork: the pool's handler row, not an IOException.
            listener.onUserOutput(WorkerFailureRow.SINGLE_WORKER, Objects.requireNonNull(e.getMessage()));
            result = WorkerFailureRow.singleFork(aggregator, moduleLabel, e.handler());
        }
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
            @Nullable Path testResultsDir,
            @Nullable List<String> preDiscovered)
            throws IOException, InterruptedException {
        // 1. Discovery — one fork, list-only mode, harvest class FQCNs (skip if auto already did).
        List<String> classes;
        if (preDiscovered != null) {
            classes = preDiscovered;
        } else {
            Discovery discovery = discoverClasses(javaBinary, classpath, testClassesDir, listener);
            if (discovery.crashed()) return discovery.verdict(moduleLabel);
            classes = discovery.classes();
        }
        if (classes.isEmpty()) {
            return new TestSummary(0, 0, 0, 0, List.of());
        }

        // [test] serial-tags partition (class-level): classes bearing a serial tag leave the
        // sharded pool and run on one trailing worker. Partitioned by class-list subtraction —
        // a second list-only discovery with the serial tags excluded — so tag expressions
        // never enter the picture.
        List<String> serialClasses = List.of();
        if (!serialTags.isEmpty()) {
            Discovery view = discoverWithExtraExcludes(javaBinary, classpath, testClassesDir, serialTags);
            if (view.crashed()) return view.verdict(moduleLabel);
            Set<String> parallelView = new HashSet<>(view.classes());
            List<String> par = new ArrayList<>();
            List<String> ser = new ArrayList<>();
            for (String c : classes) (parallelView.contains(c) ? par : ser).add(c);
            classes = par;
            serialClasses = ser;
        }

        // One shared report per format — all worker threads write into them (both are thread-safe).
        XmlTestReport xml = testResultsDir != null ? new XmlTestReport() : null;
        MarkdownTestReport md = new MarkdownTestReport();

        PullWorkerPool pool = new PullWorkerPool(this, javaBinary, classpath, testClassesDir, listener);
        TestSummary summary =
                classes.isEmpty() ? new TestSummary(0, 0, 0, 0, List.of()) : pool.run(workers, classes, xml, md, 0);
        // A runner-crash sentinel means the fork itself is broken — don't fork it again.
        boolean crashed = summary.failures().stream().anyMatch(f -> "(test run)".equals(f.method()));
        if (!serialClasses.isEmpty() && !crashed) {
            TestSummary serial = pool.run(1, serialClasses, xml, md, workers);
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

    /**
     * Step one of parallel mode: list every top-level test class without running anything. Uses
     * {@code Launcher.discover} (not {@code execute}) so this completes in 100–300 ms even for big
     * suites.
     */
    private Discovery discoverClasses(
            Path javaBinary, String classpath, Path testClassesDir, TestProgressListener listener)
            throws IOException, InterruptedException {
        var classes = new ArrayList<String>();
        var crash = new CaptureBuffer();
        int exit;
        try {
            exit = PluginLoader.run(
                    javaBinary,
                    classpath,
                    jvmFlags(JvmRole.DISCOVERY, 1, testTmpDir),
                    PROTOCOL_PREFIX,
                    withTagArgs(List.of("--list-only", "--scan-classpath=" + testClassesDir)),
                    testEnv,
                    workDir(),
                    Discovery.handler(classes, listener),
                    crash::add);
        } catch (PluginProcess.HandlerFailure e) {
            // The parent's own decoder ended the listing: a handler row, and no trust in the list.
            return Discovery.handlerFailed(List.copyOf(classes), crash.text(), e.handler());
        }
        return new Discovery(List.copyOf(classes), exit, crash.text());
    }

    // -------- shared helpers --------------------------------------------

    /**
     * Look up the jk-test-runner jar in the local CAS, keyed by its SHA-256 (the hash this build of
     * engine was paired against — embedded as a resource at {@link #RUNNER_SHA_RESOURCE} by the
     * engine's build).
     *
     * <p>A runner absent from the CAS is side-loaded by {@code jk install} in jk's own tree or
     * fetched from the official repository; {@code jk sync} populates the CAS from the local Maven
     * repository.
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

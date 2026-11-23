// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.engine.plugin.HeapFlags;
import cc.jumpkick.engine.plugin.HeapLadder;
import cc.jumpkick.engine.plugin.HeapNotes;
import cc.jumpkick.engine.plugin.HeapScope;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.LearnedHeaps;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.engine.plugin.WorkerExit;
import cc.jumpkick.engine.plugin.WorkerFate;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.engine.plugin.WorkerLeases;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.orchestrator.DiscoveryListing;
import cc.jumpkick.orchestrator.NoTestsGuard;
import cc.jumpkick.orchestrator.ResultAggregator;
import cc.jumpkick.orchestrator.RetryLoop;
import cc.jumpkick.orchestrator.RetryPolicy;
import cc.jumpkick.orchestrator.RunShortfall;
import cc.jumpkick.orchestrator.SuiteReport;
import cc.jumpkick.orchestrator.TestProgressListener;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.RecentClassWalls;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Forks {@code TestRunner} child JVM(s): one-shot when {@code workers=1}, else discovery + N
 * pull-queue workers (RUN/DONE over a {@link cc.jumpkick.engine.plugin.WorkerCommands} channel).
 * Every test JVM starts with its stdin closed. Protocol lines are {@code ##JKT:}-prefixed JSONL.
 */
public final class JUnitLauncher {

    /** Marker prefix every protocol line carries. Must match {@code JsonEventWriter.PREFIX}. */
    static final String PROTOCOL_PREFIX = "##JKT:";

    /**
     * {@code jk.<worker>.plugin.jar} (and {@code jk.engine.jar}) overrides handed to the test JVM so
     * tests that fork a first-party plugin or materialize the engine locate jars by path. The
     * {@code run-tests} step resolves the freshly-built sibling jars and passes them here. Empty
     * when none are built (e.g. a scoped
     * single-module build) — tests then fall back to CAS-by-sha.
     */
    private Map<String, String> workerJarProps = Map.of();

    /** What every JVM of the launch starts with that a run-tests stamp keys; see {@link #withFork}. */
    private ForkSpec fork = ForkSpec.DEFAULT;

    /** Classes that passed earlier under the same stamp and are not run again. */
    private List<String> settled = List.of();

    /** When non-empty, run only these class FQCNs ({@code --affected}). */
    private List<String> classNames = List.of();

    /** Set while a retry runs a subset of classes; {@code null} uses {@link #classNames}. */
    private @Nullable List<String> onlyClasses;

    public JUnitLauncher withClassNames(List<String> names) {
        this.classNames = names == null ? List.of() : List.copyOf(names);
        return this;
    }

    /**
     * Launch from {@code fork}: its flags around the JVM arguments, its tag and class filters, and
     * its environment defaults under every JVM's environment. The {@code --class} patterns run only
     * the classes they name, or only the methods a {@code Class#method} pattern names within them,
     * in discovery and every JVM that runs tests alike, so a sharded run dispatches exactly the
     * classes a single JVM would have run; they are ignored when {@link #withClassNames} named exact
     * classes. A filter that matches nothing comes back as an empty summary; whether that is a skip
     * or a failure is the caller's call, not the launcher's.
     */
    public JUnitLauncher withFork(ForkSpec fork) {
        this.fork = fork;
        return this;
    }

    /** Leave out {@code classNames} by exact binary name: the classes that passed earlier under the same stamp. */
    public JUnitLauncher withSettled(List<String> classNames) {
        this.settled = List.copyOf(classNames);
        return this;
    }

    /** The classes this launch leaves out ({@link #withSettled}), top-level binary names. */
    public List<String> settled() {
        return settled;
    }

    /**
     * The run's suites over a test-classes tree shared with other suites: no JVM of the run
     * discovers or executes a test another suite owns, whatever the other filters select.
     */
    public JUnitLauncher withSuites(SuiteFilter suites) {
        this.suites = suites;
        return this;
    }

    private SuiteFilter suites = SuiteFilter.NONE;

    /** Where the {@code integration} suite's JUnit XML goes, as Failsafe's does; null keeps it with the rest. */
    private @Nullable Path integrationResultsDir;

    /** Write the {@code integration} suite's JUnit XML to {@code dir} rather than beside every other suite's. */
    public JUnitLauncher withIntegrationResults(Path dir) {
        this.integrationResultsDir = dir;
        return this;
    }

    /** Whether the published results keep the last lines each class printed, for the agent report. */
    private boolean keepOutput;

    /** Keep the last lines each class printed in the published results ({@code -v}). */
    public JUnitLauncher withKeepOutput(boolean keep) {
        this.keepOutput = keep;
        return this;
    }

    /** Whether this run's tests join the request's published results ({@code jk-results.md}). */
    private boolean publishResults = true;

    /** Keep this run's rows out of the request's published results: a guard suite's methods are no tests. */
    public JUnitLauncher withoutPublishedResults() {
        this.publishResults = false;
        return this;
    }

    /**
     * When set, the worker count passed to {@link #run} is the auto share ({@link TestWorkers#autoShare}),
     * not an explicit pin. The launcher then sizes the pool from recorded class walls.
     */
    private boolean autoShare;

    public JUnitLauncher withAutoShare(boolean auto) {
        this.autoShare = auto;
        return this;
    }

    /** The auto share as it stands now; null when a running pool keeps the count it started with. */
    private @Nullable IntSupplier widening;

    /**
     * Let an auto-sized pool widen while it runs: {@code share} answers the share the suite would
     * start with now, and the pool grows to what the recorded class walls make of it ({@link
     * PullWorkerPool#run}). Applies only with {@link #withAutoShare}.
     */
    public JUnitLauncher withWidening(@Nullable IntSupplier share) {
        this.widening = share;
        return this;
    }

    /** Whether the module under test is the engine, whose tests run engines in the test JVM itself. */
    private boolean runsEngines;

    /**
     * Mark the suite as the engine's own: outside the fast tier its test JVMs run engines in-process,
     * whose compilers and test JVMs are forks of the test JVM, so it is leased at its process tree.
     */
    public JUnitLauncher withRunsEngines(boolean runs) {
        this.runsEngines = runs;
        return this;
    }

    /** Workers the run's sharded pool started, the ones it widened to included. */
    private int pooled;

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
     * The flags that record what each test-running JVM does: the files it reads, the processes it
     * starts and its sockets ({@link SuiteReads}), the environment variables it looks up ({@link
     * EnvProbeJar}).
     */
    private final List<String> recordings = new ArrayList<>();

    /** Start every JVM that runs tests with {@code flag}, a recording; null adds nothing. */
    public JUnitLauncher withRecording(@Nullable String flag) {
        if (flag != null) recordings.add(flag);
        return this;
    }

    /**
     * {@link #runnerFlags} for {@code role}, plus the JDWP agent when the suite JVM is under debug,
     * the snapshot agent ({@link FailureSnapshotFlags}) when failures carry one and no debugger owns
     * the JVM, the JaCoCo agent on every test-running JVM of a coverage run, and the recordings.
     */
    List<String> jvmFlags(JvmRole role, int concurrency, @Nullable Path tmpDir) {
        List<String> flags = new ArrayList<>(runnerFlags(concurrency, tmpDir, role));
        if (role == JvmRole.SUITE && debug != null) flags.add(debug.agentArg());
        if (role != JvmRole.DISCOVERY && fork.failureSnapshot() && debug == null) {
            flags.addAll(FailureSnapshotFlags.flags(launchJavaHome, jvmArgs));
        }
        if (role != JvmRole.DISCOVERY && coverage != null) flags.add(coverage.agentArg());
        if (role != JvmRole.DISCOVERY) flags.addAll(recordings);
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

    /** One fork's redactor ({@link BuildEnv#secretsFor}), built as it starts: after its environment filed its credentials. */
    SecretRedactor secrets() {
        return BuildEnv.secretsFor(inferredModuleDir);
    }

    /** {@code [test] idle-timeout} in milliseconds; 0 when the module sets none ({@link TestWorkerEnv}). */
    long idleTimeoutMs() {
        return fork.idleTimeoutMs();
    }

    /** {@code [test] jvm-args}, its system properties and the active profile's {@code jvm-args}. */
    private List<String> jvmArgs = List.of();

    /** JDK the suite JVM is launched with; set for the duration of {@link #run}. */
    private @Nullable Path launchJavaHome;

    /** The XML report of the run in progress, which a heap retry's suite JVM records into too. */
    private @Nullable XmlTestReport runXml;

    /** The Markdown report of the run in progress; a heap retry replaces its classes' entries. */
    private @Nullable MarkdownTestReport runMd;

    /** Non-null only while a heap retry is re-running an item at this {@code -Xmx}. */
    private @Nullable Long heapOverride;

    /** Where this launcher reads and records learned heaps. */
    private LearnedHeaps heaps = LearnedHeaps.engine();

    /** Record and size from {@code heaps} instead of {@link LearnedHeaps#engine()}. */
    public JUnitLauncher withHeaps(LearnedHeaps heaps) {
        this.heaps = heaps == null ? LearnedHeaps.engine() : heaps;
        return this;
    }

    LearnedHeaps heaps() {
        return heaps;
    }

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
     * Worker JVM flags: the {@linkplain JvmOptions#suiteFlags suite tuning}, the fork's leading flags, the test
     * table's and the active profile's {@code jvm-args}, the fork's trailing flags, and the host's own: the temp
     * root, the sandbox flags, any {@code jk.<worker>.plugin.jar} / {@code jk.engine.jar} overrides and the
     * working directory.
     */
    private List<String> runnerFlags(int concurrency, @Nullable Path tmpDir, JvmRole role) {
        List<String> planned = new ArrayList<>(JvmOptions.suiteFlags(concurrency, jvmArgs));
        // The listing JVM takes the suite's heap too: on the plan's alone it asked a 2 vCPU / 7 GiB
        // runner for 3.5 GiB, past the most a test JVM is given, to list classes.
        if (SuiteRetry.planned(jvmArgs)) {
            int jdk = launchJavaHome == null ? Runtime.version().feature() : JvmOptions.hostFeature(launchJavaHome);
            planned = SuiteRetry.tune(
                    planned, SuiteRetry.project(inferredModuleDir), moduleLabel, jdk, heapOverride, heaps);
            String onOom = role == JvmRole.DISCOVERY ? null : heapRun.flag();
            if (onOom != null) planned.add(onOom);
        }
        List<String> flags = new ArrayList<>(planned);
        flags.addAll(fork.leading());
        flags.addAll(jvmArgs);
        flags.addAll(fork.trailing());
        // The Java half of the TMPDIR TestEnv sandboxes: @TempDir reads the property, not the
        // environment. Passed in, not read off testEnv — with W>1 it is the worker's own subdir.
        if (tmpDir != null) {
            flags.add("-Djava.io.tmpdir=" + tmpDir);
            // jqwik's failure-replay database, otherwise `.jqwik-database` in the working directory:
            // with W>1 every worker of the module would write one file at once, and a torn copy ends
            // discovery with an EOFException. The temp root is already the worker's own.
            flags.add("-Djqwik.database=" + tmpDir.resolve(".jqwik-database"));
        }
        flags.addAll(SandboxJvmFlags.of(testEnv.extras(), shortTempDirFactory, cliTempDirSupport));
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

    /** This run's heap-retry state: where its JVMs mark running out, and whether it climbed. */
    private final SuiteHeapRun heapRun = new SuiteHeapRun();

    /**
     * Set when {@link #run} starts — whether jk-cli's {@code @TempDir} factory and deletion
     * strategy are on this module's test classpath. Naming them for a module that cannot load
     * them is not a no-op: JUnit logs a stack trace per {@code @TempDir} and falls back to the
     * default strategy, so the soft-fail delete the flag exists to install is not installed.
     */
    private boolean cliTempDirSupport;

    private boolean shortTempDirFactory;

    /**
     * The module a test classes dir belongs to: {@code <module>} for {@code
     * <module>/target/test-classes}, and null for a directory jk did not lay out.
     */
    static @Nullable Path inferModuleDir(@Nullable Path testClassesDir) {
        if (testClassesDir == null) return null;
        Path p = testClassesDir.toAbsolutePath().normalize();
        if (!"test-classes".equals(name(p))) return null;
        Path target = p.getParent();
        if (target == null || !BuildLayout.TARGET.equals(name(target))) return null;
        return target.getParent();
    }

    /**
     * The XML report exists exactly when there is a directory to write it into: each class's file in
     * its suite's directory, the {@code integration} suite's in {@link #integrationResultsDir}; no
     * report of a class gone from the test classes stays in either.
     */
    private void writeXml(@Nullable XmlTestReport xml, @Nullable Path testResultsDir) {
        if (testResultsDir == null || xml == null) return;
        Function<String, Path> dirFor = className -> xmlDir(className, testResultsDir);
        try {
            xml.writeAll(dirFor);
        } catch (IOException e) {
            // Non-fatal: the tests ran; only the report failed to land.
        }
        StaleTestReports.prune(testClassesDir, xml.classNames(), dirFor, testResultsDir, integrationResultsDir);
    }

    private Path xmlDir(String className, Path testResultsDir) {
        Path integration = integrationResultsDir;
        return integration != null && TestSuites.INTEGRATION.equals(suites.suiteOf(className))
                ? integration
                : testResultsDir;
    }

    /** The XML file holding {@code className}'s results; empty when no XML is written. */
    private String xmlFile(String className, @Nullable Path testResultsDir) {
        if (testResultsDir == null) return "";
        return xmlDir(className, testResultsDir)
                .resolve(XmlTestReport.fileName(className))
                .toAbsolutePath()
                .toString();
    }

    private static String name(Path p) {
        Path f = p.getFileName();
        return f == null ? "" : f.toString();
    }

    /**
     * Whether each test JVM of a pool of more than one gets its own {@code JK_HOME} under the suite's,
     * for a suite whose tests start engines and write the product's home ({@link TestWorkerEnv}).
     */
    boolean workerHomes() {
        return fork.workerHomes();
    }

    /**
     * How a pooled test JVM is forked and leased: with no JVM slot when it {@linkplain
     * ForkSpec#drivesEngines drives engines}, so the slot stays free for the forks of the engine it
     * waits on; at its process tree, slot kept, when it {@linkplain #withRunsEngines runs engines}
     * in-process outside the fast tier; otherwise as every worker is.
     */
    PluginProcess.Starter poolStarter() {
        LearnedHeaps heaps = heaps();
        JobWorkers.Lease lease = poolLease();
        return (pb, heap) -> JobWorkers.start(pb, heap, heaps, lease);
    }

    /**
     * The share an auto-sized pool runs at out of {@code share}: half of it, at least one, when its
     * test JVMs {@linkplain ForkSpec#drivesEngines drive engines}. Such a JVM mostly waits while the
     * engine it started compiles and tests on other cores, so a pool as wide as the share leaves that
     * work no cores and no memory: on a seven-core host six test JVMs sat idle while their engines'
     * builds waited for room, one gave up after ten minutes and the memory limit killed two.
     */
    int poolShare(int share) {
        return fork.drivesEngines() ? Math.max(1, share / 2) : share;
    }

    /** How {@link #poolStarter} leases a pooled test JVM. */
    JobWorkers.Lease poolLease() {
        if (fork.drivesEngines()) return JobWorkers.Lease.DRIVES_ENGINES;
        if (runsEngines && !fork.walled()) return JobWorkers.Lease.RUNS_ENGINES;
        return JobWorkers.Lease.SLOT;
    }

    /** The runner arguments of pull-mode shard worker {@code workerId}: the pull protocol plus this run's filters. */
    List<String> pullWorkerArgs(int workerId, Path testClassesDir) {
        return withTagArgs(
                List.of("--pull", "--worker=" + workerId, "--scan-classpath=" + testClassesDir), List.of(), List.of());
    }

    /**
     * {@code base} plus the class filter, the tag filters ({@code extraExcludes} excluded too) and
     * the run's suites. The class filter is {@code names} exactly when given, else the {@code
     * --class} patterns, within the run's suites and less the excluded and settled classes either
     * way.
     */
    private List<String> withTagArgs(List<String> base, List<String> names, List<String> extraExcludes) {
        var out = new ArrayList<>(base);
        String include = names.isEmpty() ? fork.classFilter() : JUnitClassFilter.exactRegex(names);
        String filter = JUnitClassFilter.filter(include, fork.excludeBody(settled), suites.body());
        if (filter != null) out.add("--filter=" + filter);
        if (classNames.isEmpty()) out.addAll(fork.methodArgs());
        out.addAll(fork.tagArgs(extraExcludes));
        for (String member : suites.members()) out.add("--member=" + member);
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
     * for nested-engine suites). A suite whose classes hold no test the runner could discover is a
     * failed run, never an empty green one ({@link NoTestsGuard}).
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
        NoTestsGuard guard = new NoTestsGuard(Objects.requireNonNull(listener, "listener"));
        TestSummary summary;
        try {
            summary = runUnguarded(
                    javaHome,
                    testClassesDir,
                    runtimeClasspath,
                    cacheRoot,
                    workers,
                    workerJarProps,
                    testEnv,
                    guard,
                    testResultsDir);
        } finally {
            heapRun.close();
        }
        return guard.verdict(summary, moduleLabel);
    }

    private TestSummary runUnguarded(
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
        this.launchJavaHome = javaHome;
        // workers: 0 = auto (Mill-like min(jobs, classes) + heap clamp); ≥1 = explicit.
        if (workers < 0) throw new IllegalArgumentException("workers must be >= 0 (0 = auto)");
        // One debugger, one JVM: a pool would have every shard contend for the same port.
        int wanted = debug != null ? 1 : workers;
        this.workerJarProps = workerJarProps == null ? Map.of() : Map.copyOf(workerJarProps);
        this.testEnv = Objects.requireNonNull(testEnv, "testEnv").withDefaults(fork.env());
        this.testClassesDir = testClassesDir.toAbsolutePath().normalize();
        this.inferredModuleDir = inferModuleDir(testClassesDir);
        this.testTmpDir = TestTmpDir.fresh(this.testEnv.extras().get("TMPDIR"));
        heapRun.start(testTmpDir);

        Path runnerJar = locateRunner(cacheRoot);
        var classpathBase = new LinkedHashSet<Path>();
        classpathBase.add(testClassesDir);
        classpathBase.addAll(runtimeClasspath);
        // Thin workers: jar + Maven runtime closure from the POM. A runner that vendors
        // PluginMain already has it; extra entries are harmless.
        classpathBase.addAll(WorkerLaunchClasspath.paths(runnerJar));
        String classpath = Classpaths.join(classpathBase);
        this.cliTempDirSupport = CliTempDirSupport.onClasspath(classpathBase, CliTempDirSupport.STRATEGY_RESOURCE);
        this.shortTempDirFactory = CliTempDirSupport.onClasspath(classpathBase, CliTempDirSupport.FACTORY_RESOURCE);

        int resolvedWorkers = wanted;
        List<String> preDiscovered = null;
        IntSupplier widen = null;
        // Auto (the share, or a bare 0) sizes from this selection's recorded walls. An explicit
        // count is capped by class count the way it always was.
        boolean auto = debug == null && (autoShare || wanted == 0);
        if (auto) {
            if (!classNames.isEmpty()) {
                preDiscovered = classNames;
            } else {
                Discovery discovery = discoverClasses(javaHome, classpath, testClassesDir, listener, List.of());
                if (discovery.crashed()) return discovery.verdict(moduleLabel).withWorkers(1);
                preDiscovered = discovery.classes();
            }
            int share = poolShare(wanted > 0 ? wanted : TestWorkers.effectiveJobs());
            Map<String, Long> recorded = RecentClassWalls.forModule(inferredModuleDir);
            List<String> selection = preDiscovered;
            resolvedWorkers =
                    selection.isEmpty() ? 1 : TestWorkers.autoCount(share, recorded, selection, selection.size());
            IntSupplier live = widening;
            // Worth a pool only when the whole jobs budget would make more of the suite than now.
            if (live != null
                    && selection.size() > 1
                    && TestWorkers.autoCount(
                                    poolShare(TestWorkers.effectiveJobs()), recorded, selection, selection.size())
                            > resolvedWorkers) {
                widen = () -> TestWorkers.autoCount(poolShare(live.getAsInt()), recorded, selection, selection.size());
            }
        } else if (!classNames.isEmpty()) {
            preDiscovered = classNames;
            resolvedWorkers = TestWorkers.resolve(wanted, preDiscovered.size(), TestWorkers.effectiveJobs());
        } else if (wanted > 1) {
            resolvedWorkers = TestWorkers.resolve(wanted, Integer.MAX_VALUE, TestWorkers.effectiveJobs());
        }
        if (preDiscovered != null && preDiscovered.size() <= 1) resolvedWorkers = 1;

        // Tag every summary with the runner count that produced it, at the one place that count is
        // authoritative. The inner paths cannot: `runSingle` returns from two places and the
        // parallel pool merges per-worker aggregators built by a constructor that has no idea. Doing
        // it per-path recorded the concurrency for 2 modules out of 29, which is worse than not at
        // all — a forecast rescales the modules it has a count for and not the rest.
        if (resolvedWorkers <= 1 && widen == null) {
            listener.onWorkers(1);
            return runSingle(javaHome, classpath, testClassesDir, listener, testResultsDir)
                    .withWorkers(1);
        }
        // W>1 + Jupiter in-process parallel is a known double-parallelism footgun.
        List<Path> cpForDetect = new ArrayList<>();
        cpForDetect.add(testClassesDir);
        if (runtimeClasspath != null) cpForDetect.addAll(runtimeClasspath);
        if (resolvedWorkers > 1 && JupiterParallelDetect.enabled(cpForDetect)) {
            listener.onWarning("jupiter-parallel", JupiterParallelDetect.stackWarning(resolvedWorkers));
        }
        pooled = 0;
        TestSummary summary = runParallel(
                javaHome, classpath, testClassesDir, resolvedWorkers, widen, listener, testResultsDir, preDiscovered);
        return summary.withWorkers(Math.max(resolvedWorkers, pooled));
    }

    // -------- single-worker ---------------------------------------------

    private TestSummary runSingle(
            Path javaHome,
            String classpath,
            Path testClassesDir,
            TestProgressListener listener,
            @Nullable Path testResultsDir)
            throws IOException, InterruptedException {
        XmlTestReport xml = testResultsDir != null ? new XmlTestReport() : null;
        runXml = xml;
        MarkdownTestReport md = new MarkdownTestReport(keepOutput);
        runMd = md;
        var aggregator =
                new ResultAggregator(listener, /* workerId */ 0, SuiteReport.all(xml, md), moduleLabel, secrets());
        // Capture the worker's non-protocol output so a hard crash (uncaught
        // throwable / System.exit before any test event) can be explained instead
        // of surfacing only as "runner exited N".
        var crash = new CaptureBuffer();
        List<String> flags = jvmFlags(JvmRole.SUITE, 1, testTmpDir);
        List<String> names = onlyClasses != null ? onlyClasses : classNames;
        List<String> args = withTagArgs(List.of("--scan-classpath=" + testClassesDir), names, List.of());
        TestSummary result;
        try {
            result = forkSuite(javaHome, classpath, flags, args, aggregator, crash, listener);
        } catch (TestLauncherFailure e) {
            if (heapOverride != null) throw e;
            result = retryLaunch(javaHome, classpath, HeapFlags.parseXmx(flags), listener, e);
        }
        if (heapOverride == null) {
            result = retrySuite(javaHome, classpath, HeapFlags.parseXmx(flags), listener, result, List.of());
        }
        writeXml(xml, testResultsDir);
        publishTests(md, testClassesDir, testResultsDir);
        return result;
    }

    /** One suite JVM. A launcher failure propagates; a handler failure is a summary row. */
    private TestSummary forkSuite(
            Path javaHome,
            String classpath,
            List<String> flags,
            List<String> args,
            ResultAggregator aggregator,
            CaptureBuffer crash,
            TestProgressListener listener)
            throws IOException, InterruptedException {
        HeapScope.Key key = filledKey(javaHome);
        try {
            WorkerExit exit = key == null
                    ? launch(javaHome, classpath, flags, args, aggregator, crash)
                    : HeapScope.call(key, () -> launch(javaHome, classpath, flags, args, aggregator, crash));
            return aggregator.toResult(
                    TestLauncherFailure.ending(exit, PluginLoader.command(javaHome, classpath, flags, args)),
                    crash.text());
        } catch (PluginProcess.HandlerFailure e) {
            listener.onUserOutput(WorkerFailureRow.SINGLE_WORKER, Objects.requireNonNull(e.getMessage()));
            return WorkerFailureRow.singleFork(aggregator, moduleLabel, e.handler());
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private WorkerExit launch(
            Path javaHome,
            String classpath,
            List<String> flags,
            List<String> args,
            ResultAggregator aggregator,
            CaptureBuffer crash)
            throws IOException, InterruptedException {
        return PluginLoader.run(
                javaHome,
                classpath,
                flags,
                PROTOCOL_PREFIX,
                args,
                testEnv,
                workDir(),
                aggregator::accept,
                line -> crash.add(aggregator.userOutput(line)),
                TestWorkerEnv.suiteIdleTimeoutMs(idleTimeoutMs()),
                heaps);
    }

    /**
     * Re-run a suite that died of its own heap or a memory kill, as {@link RetryLoop#afterRun}
     * decides. {@code heap} is the {@code -Xmx} the first forks ran at; {@code undispatched} is what
     * a pool left queued, run with the dying workers' classes.
     */
    private TestSummary retrySuite(
            Path javaHome,
            String classpath,
            long heap,
            TestProgressListener listener,
            TestSummary first,
            List<String> undispatched) {
        if (!SuiteRetry.planned(jvmArgs)) return first;
        boolean ranOut = heapRun.ranOut();
        // A run that passed at its planned heap lets a heap learned from an older, larger peak come down.
        if (heapRun.green(!first.failures().isEmpty())) heaps.relax(filledKey(javaHome));
        return retries(javaHome, classpath, listener).afterRun(first, selection(), undispatched, ranOut, heap);
    }

    /** Re-run a launch that died of its heap or a memory kill before it reported a run; a pinned heap is reported with the pin named. */
    private TestSummary retryLaunch(
            Path javaHome, String classpath, long heap, TestProgressListener listener, TestLauncherFailure e) {
        if (!SuiteRetry.planned(jvmArgs)) throw SuiteRetry.unplanned(e, moduleLabel, jvmArgs);
        WorkerFate.Cause cause = SuiteRetry.cause(e, heapRun.ranOut());
        if (cause != WorkerFate.Cause.HEAP_EXHAUSTED && cause != WorkerFate.Cause.KILLED_FOR_MEMORY) throw e;
        boolean killed = cause == WorkerFate.Cause.KILLED_FOR_MEMORY;
        TestSummary second = retries(javaHome, classpath, listener).afterDeath(selection(), killed, heap);
        if (second == null) throw e;
        return second;
    }

    /** The classes this run was asked for. */
    private List<String> selection() {
        return onlyClasses != null ? onlyClasses : classNames;
    }

    private HeapScope.@Nullable Key filledKey(@Nullable Path javaHome) {
        int jdk = javaHome == null ? Runtime.version().feature() : JvmOptions.hostFeature(javaHome);
        return SuiteRetry.key(SuiteRetry.project(inferredModuleDir), moduleLabel, jdk);
    }

    /**
     * The orchestrator's retry loop over this run's forks: each rung a suite JVM at the heap the
     * {@link HeapLadder} climbs to, the learned heaps told what filled and what sufficed.
     */
    private RetryLoop retries(Path javaHome, String classpath, TestProgressListener listener) {
        HeapScope.Key key = filledKey(javaHome);
        return new RetryLoop(SuiteRetry.POLICY, new RetryLoop.Forks() {
            @Override
            public @Nullable Long nextHeap(List<Long> ranOut) {
                return HeapLadder.next(ranOut, WorkerLeases.engine().capacityBytes(), LearnedHeaps.testRamCeiling());
            }

            @Override
            public void filled(long heap) {
                heaps.note(key, heap);
            }

            @Override
            public void sufficed(long heap) {
                heaps.good(key, heap);
            }

            @Override
            public void climbing(long next, long failed, boolean killed) {
                HeapNotes.note(HeapNotes.line(next, failed, killed));
            }

            @Override
            public RetryPolicy.Share share(List<String> classes) {
                return JUnitLauncher.this.share(classes);
            }

            @Override
            public RetryLoop.Rung fork(List<String> classes, long heap) throws IOException, InterruptedException {
                heapRun.climbed();
                heapRun.forget();
                var crash = new CaptureBuffer();
                Rung attempt;
                try {
                    attempt = rung(javaHome, classpath, classes, listener, heap, crash);
                } catch (TestLauncherFailure e) {
                    if (SuiteRetry.cause(e, heapRun.ranOut()) != WorkerFate.Cause.HEAP_EXHAUSTED) throw e;
                    return new RetryLoop.Rung.Died(e, e.exit(), e.output(), e.command());
                }
                return new RetryLoop.Rung.Ran(attempt.run(), heapRun.ranOut(), attempt.held()::restore, crash.text());
            }

            @Override
            public RuntimeException exhausted(List<Long> ranOut, RetryLoop.Rung.Died last) {
                return TestLauncherFailure.heap(
                        moduleLabel,
                        last.exit(),
                        last.output(),
                        last.command(),
                        SuiteRetry.exhausted(
                                "test runner",
                                ranOut,
                                WorkerLeases.engine().capacityBytes(),
                                LearnedHeaps.testRamCeiling()));
            }
        });
    }

    /** What the run so far recorded for {@code classes}; a rung for them must replace it, not add to it. */
    private RetryPolicy.Share share(List<String> classes) {
        MarkdownTestReport.Tally tally = Objects.requireNonNull(runMd, "runMd").tally(classes);
        return new RetryPolicy.Share(tally.passed(), tally.skipped(), tally.classes());
    }

    /**
     * What the reports held for a rung's classes, taken out so the rung's run replaces it. {@link
     * #restore} puts it back in place of the rung's run when that run is discarded, so the report
     * and the summary name the same classes.
     */
    private record Held(
            MarkdownTestReport md,
            MarkdownTestReport.Held mdHeld,
            @Nullable XmlTestReport xml,
            XmlTestReport.@Nullable Held xmlHeld) {
        void restore() {
            md.restore(mdHeld);
            if (xml != null && xmlHeld != null) xml.restore(xmlHeld);
        }
    }

    /** One rung's run and the record it displaced. */
    private record Rung(TestSummary run, Held held) {}

    /**
     * One suite JVM for {@code classes} at {@code heap}; its non-protocol output lands in {@code
     * crash}. A fork that throws reported nothing that outlives it, and the record it displaced is
     * put back before the throw. A rung reruns classes after a memory failure, so its fork is leased
     * at its whole heap ({@link HeapScope#fullLease}).
     */
    private Rung rung(
            Path javaHome,
            String classpath,
            List<String> classes,
            TestProgressListener listener,
            long heap,
            CaptureBuffer crash)
            throws IOException, InterruptedException {
        Long previous = heapOverride;
        List<String> previousOnly = onlyClasses;
        heapOverride = heap;
        if (classes != null && !classes.isEmpty()) onlyClasses = classes;
        try {
            List<String> names = onlyClasses != null ? onlyClasses : classNames;
            // The rung's results replace what the run that ran out of heap recorded for its classes.
            XmlTestReport xml = runXml;
            MarkdownTestReport md = Objects.requireNonNull(runMd, "runMd");
            Held held = new Held(md, md.forget(names), xml, xml == null ? null : xml.forget(names));
            var aggregator = new ResultAggregator(listener, 0, SuiteReport.all(xml, md), moduleLabel, secrets());
            List<String> retryFlags = jvmFlags(JvmRole.SUITE, 1, testTmpDir);
            Path classesDir = Objects.requireNonNull(testClassesDir, "testClassesDir");
            List<String> args = withTagArgs(List.of("--scan-classpath=" + classesDir), names, List.of());
            try {
                HeapScope.Fork<TestSummary> fork =
                        () -> forkSuite(javaHome, classpath, retryFlags, args, aggregator, crash, listener);
                return new Rung(HeapScope.fullLease(fork), held);
            } catch (TestLauncherFailure | IOException | InterruptedException e) {
                held.restore();
                throw e;
            }
        } finally {
            heapOverride = previous;
            onlyClasses = previousOnly;
        }
    }

    // -------- parallel pull-queue ---------------------------------------

    private TestSummary runParallel(
            Path javaHome,
            String classpath,
            Path testClassesDir,
            int workers,
            @Nullable IntSupplier widen,
            TestProgressListener listener,
            @Nullable Path testResultsDir,
            @Nullable List<String> preDiscovered)
            throws IOException, InterruptedException {
        // 1. Discovery — one fork, list-only mode, harvest class FQCNs (skip if auto already did).
        List<String> classes;
        if (preDiscovered != null) {
            classes = preDiscovered;
        } else {
            Discovery discovery = discoverClasses(javaHome, classpath, testClassesDir, listener, List.of());
            if (discovery.crashed()) return discovery.verdict(moduleLabel);
            classes = discovery.classes();
        }
        if (classes.isEmpty()) {
            // A plan whose tests are not under a class (features, scripts) has an empty class
            // list. Running nothing and stamping "no tests" skips those tests on the next build.
            // The one-shot path executes that plan whole.
            return runSingle(javaHome, classpath, testClassesDir, listener, testResultsDir);
        }

        // [test] serial-tags partition (class-level): classes bearing a serial tag leave the
        // sharded pool and run on one trailing worker. Partitioned by class-list subtraction —
        // a second list-only discovery with the serial tags excluded — so tag expressions
        // never enter the picture.
        List<String> serialClasses = List.of();
        if (!fork.serialTags().isEmpty()) {
            // noop listener: the full discovery already reported totals; this view must not grow
            // the denominator a second time.
            Discovery view = discoverClasses(
                    javaHome, classpath, testClassesDir, TestProgressListener.noop(), fork.serialTags());
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
        runXml = xml;
        MarkdownTestReport md = new MarkdownTestReport(keepOutput);
        runMd = md;

        PullWorkerPool pool = new PullWorkerPool(this, javaHome, classpath, testClassesDir, listener);
        TestSummary summary;
        try {
            summary = classes.isEmpty()
                    ? new TestSummary(0, 0, 0, 0, List.of())
                    : pool.run(workers, classes, xml, md, 0, widen);
            if (!classes.isEmpty()) pooled = pool.started();
        } catch (TestLauncherFailure e) {
            if (!SuiteRetry.planned(jvmArgs) || heapOverride != null) {
                throw SuiteRetry.unplanned(e, moduleLabel, jvmArgs);
            }
            summary = retryLaunch(javaHome, classpath, poolHeap(pool, workers), listener, e);
        }
        if (SuiteRetry.planned(jvmArgs) && heapOverride == null) {
            summary = retrySuite(javaHome, classpath, poolHeap(pool, workers), listener, summary, pool.undispatched());
        }
        if (!serialClasses.isEmpty() && RunShortfall.nextPoolRuns(summary, SessionCancel.cancelled())) {
            TestSummary serial = pool.run(1, serialClasses, xml, md, Math.max(workers, pooled), null);
            summary = summary.plus(serial);
        }
        writeXml(xml, testResultsDir);
        publishTests(md, testClassesDir, testResultsDir);
        return summary;
    }

    /**
     * The heap {@code pool}'s workers ran at, or the planned one when it started none. A worker
     * records its peak as it exits, so the heap planned after the pool is not the one that ran out.
     */
    private long poolHeap(PullWorkerPool pool, int workers) {
        long ran = pool.heap();
        return ran > 0 ? ran : HeapFlags.parseXmx(jvmFlags(JvmRole.PULL_WORKER, workers, testTmpDir));
    }

    /** Fold this launch's JUnit events into the request's sink, which {@code jk-results.md} drains. */
    void publishTests(MarkdownTestReport md, Path testClassesDir, @Nullable Path testResultsDir) {
        if (md == null || !publishResults) return;
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
        md.publish(key, label, cls -> xmlFile(cls, testResultsDir));
    }

    private static String leafName(String path) {
        if (path == null || path.isBlank()) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Step one of parallel mode: list every top-level test class without running anything, the
     * classes {@code extraExcludes} tag left out (the serial-tag partition's view). Uses {@code
     * Launcher.discover} (not {@code execute}) so this completes in 100–300 ms even for big suites.
     */
    private Discovery discoverClasses(
            Path javaHome,
            String classpath,
            Path testClassesDir,
            TestProgressListener listener,
            List<String> extraExcludes)
            throws IOException, InterruptedException {
        var listing = new DiscoveryListing();
        var crash = new CaptureBuffer();
        List<String> flags = jvmFlags(JvmRole.DISCOVERY, 1, testTmpDir);
        List<String> args =
                withTagArgs(List.of("--list-only", "--scan-classpath=" + testClassesDir), List.of(), extraExcludes);
        WorkerExit exit;
        try {
            exit = SuiteRetry.listing(
                    filledKey(javaHome),
                    () -> PluginLoader.run(
                            javaHome,
                            classpath,
                            flags,
                            PROTOCOL_PREFIX,
                            args,
                            testEnv,
                            workDir(),
                            listing.handler(listener),
                            line -> crash.add(secrets().redact(line)),
                            0L,
                            heaps));
        } catch (PluginProcess.HandlerFailure e) {
            // The parent's own decoder ended the listing: a handler row, and no trust in the list.
            return Discovery.handlerFailed(listing, crash.text(), e.handler());
        }
        return Discovery.ended(listing, exit, crash.text(), PluginLoader.command(javaHome, classpath, flags, args));
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
}

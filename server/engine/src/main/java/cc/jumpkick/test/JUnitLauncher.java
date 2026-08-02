// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.run.TestSummary;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Forks {@code TestRunner} child JVM(s): one-shot when {@code workers=1}, else discovery + N
 * pull-queue workers (RUN/DONE on stdin). Protocol lines are {@code ##JKT:}-prefixed JSONL.
 */
public final class JUnitLauncher {

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

    /**
     * Extra environment for the test JVM. Used to isolate nested-engine suites ({@code jk-cli}) so
     * {@code EngineTestExtension} cannot force-stop the host engine that is running {@code jk test}, and
     * to hand a suite a sandboxed {@code JK_HOME}without one a forked test JVM inherits the
     * engine's environment and runs against the developer's real {@code ~/.jk}, reading the real library
     * catalog and able to write the real local m2.
     */
    private Map<String, String> testEnv = Map.of();

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
    private List<String> runnerFlags(int concurrency) {
        List<String> flags = new ArrayList<>(cc.jumpkick.engine.plugin.JvmOptions.workerFlags(concurrency));
        flags.add("-Djk.plugin.class=" + RUNNER_PLUGIN_CLASS);
        // Suite JVMs: no AOT train-on-miss (nested engines / compiler workers); still map caches.
        flags.add("-Djk.aot.train=off");
        // CLI integration tests use FFM (EngineClient / MemoryProbe) and JUnit autodetection of
        // EngineTestExtension — match Gradle's:cli:test jvmArgs / systemProperty setup.
        if (!testEnv.isEmpty()) {
            flags.add("--enable-native-access=ALL-UNNAMED");
            flags.add("-Djunit.jupiter.extensions.autodetection.enabled=true");
            // Match Gradle:cli:test — force soft-fail TempDir strategy + short /tmp factory.
            // Nested engines hardlink into @TempDir caches; macOS then fails Standard delete and
            // marks the test failed on cleanup even when assertions passed. Soft-fail strategy +
            // NEVER cleanup mode keep the suite green (dirs are under /tmp and ephemeral).
            flags.add(
                    "-Djunit.jupiter.tempdir.deletion.strategy.default=cc.jumpkick.cli.engine.JkTempDirDeletionStrategy");
            flags.add("-Djunit.jupiter.tempdir.factory.default=cc.jumpkick.cli.engine.JkTempDirFactory");
            flags.add("-Djunit.jupiter.tempdir.cleanup.mode.default=never");
            String jkHome = testEnv.get("JK_HOME");
            if (jkHome != null && !jkHome.isBlank()) {
                // Sibling of test-jk-home: <module>/target/test-shared-cache (SharedTestCache).
                Path shared = Path.of(jkHome).getParent().resolve("test-shared-cache");
                flags.add("-Djk.test.cache.dir=" + shared);
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
    private Path inferredModuleDir;

    /**
     * {@code.../target/classes/test} → module root ({@code.../}). Null when layout is nonstandard.
     */
    static Path inferModuleDir(Path testClassesDir) {
        if (testClassesDir == null) return null;
        Path p = testClassesDir.toAbsolutePath().normalize();
        // .../target/classes/test
        if (!"test".equals(name(p))) return null;
        p = p.getParent(); // classes
        if (p == null || !"classes".equals(name(p))) return null;
        p = p.getParent(); // target
        if (p == null || !"target".equals(name(p))) return null;
        return p.getParent();
    }

    private static String name(Path p) {
        Path f = p.getFileName();
        return f == null ? "" : f.toString();
    }

    /**
     * Quarkus {@code @QuarkusTest} uses {@code BootstrapAppModelFactory} which needs a Maven
     * project root. JK owns resolve via {@code jk-lock.toml}; when the module has {@code [quarkus]} but
     * no {@code pom.xml}, write a minimal tooling POM (coordinates + platform BOM import only).
     */
    static void ensureQuarkusToolingPom(Path moduleDir) {
        if (moduleDir == null || !Files.isDirectory(moduleDir)) return;
        Path pom = moduleDir.resolve("pom.xml");
        Path jkToml = moduleDir.resolve("jk.toml");
        if (!Files.isRegularFile(jkToml)) return;
        try {
            String toml = Files.readString(jkToml);
            if (!java.util.regex.Pattern.compile("(?m)^\\s*\\[quarkus]\\s*$")
                    .matcher(toml)
                    .find()) {
                return;
            }
            // Only ever replace a pom jk itself generated (the marker comment below) or a raw
            // scaffold whose ${group}/${name} placeholders never interpolated. A user's real
            // pom — even one using a ${quarkus.version} property — is theirs.
            if (Files.isRegularFile(pom)) {
                String existing = Files.readString(pom);
                boolean jkGenerated = existing.contains("Generated by jk for Quarkus");
                boolean rawScaffold = existing.contains("${group}") && existing.contains("${name}");
                if (!jkGenerated && !rawScaffold) {
                    return;
                }
                // jk-generated poms are rewritten every time — cheap, and it keeps the
                // tooling pom in sync when [quarkus] version or coordinates change.
            }
            String group = tomlField(toml, "group", "com.example");
            String name = tomlField(toml, "name", moduleDir.getFileName().toString());
            String version = tomlField(toml, "version", "0.1.0");
            String qv = quarkusVersion(toml, cc.jumpkick.model.ToolDefaults.QUARKUS_PLATFORM_VERSION);
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!-- Generated by jk for Quarkus @QuarkusTest tooling. Resolve is owned by jk-lock.toml. -->
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                      <properties>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                        <maven.compiler.release>17</maven.compiler.release>
                        <quarkus.platform.version>%s</quarkus.platform.version>
                      </properties>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>io.quarkus.platform</groupId>
                            <artifactId>quarkus-bom</artifactId>
                            <version>${quarkus.platform.version}</version>
                            <type>pom</type>
                            <scope>import</scope>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                      <dependencies>
                        <dependency>
                          <groupId>io.quarkus</groupId>
                          <artifactId>quarkus-rest</artifactId>
                        </dependency>
                        <dependency>
                          <groupId>io.quarkus</groupId>
                          <artifactId>quarkus-arc</artifactId>
                        </dependency>
                        <dependency>
                          <groupId>io.quarkus</groupId>
                          <artifactId>quarkus-junit5</artifactId>
                          <scope>test</scope>
                        </dependency>
                      </dependencies>
                    </project>
                    """.formatted(xmlEscape(group), xmlEscape(name), xmlEscape(version), xmlEscape(qv));
            Files.writeString(pom, xml);
        } catch (IOException ignored) {
            // best-effort; @QuarkusTest will fail with a clearer bootstrap error if missing
        }
    }

    private static String tomlField(String toml, String key, String def) {
        // Match `key = "value"` or `key = value` at line start (project / quarkus tables).
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(?m)^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*=\\s*\"([^\"]+)\"")
                .matcher(toml);
        if (m.find()) return m.group(1).trim();
        m = java.util.regex.Pattern.compile("(?m)^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*=\\s*(\\S+)")
                .matcher(toml);
        if (m.find()) return m.group(1).trim().replace("\"", "");
        return def;
    }

    private static String quarkusVersion(String toml, String def) {
        java.util.regex.Matcher table =
                java.util.regex.Pattern.compile("(?m)^\\s*\\[quarkus]\\s*$").matcher(toml);
        if (!table.find()) return def;
        // Scan only until the next table header — a `version` in a later table (e.g.
        // [project]) must not become the platform BOM version.
        String rest = toml.substring(table.end());
        java.util.regex.Matcher nextTable =
                java.util.regex.Pattern.compile("(?m)^\\s*\\[").matcher(rest);
        if (nextTable.find()) rest = rest.substring(0, nextTable.start());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"")
                .matcher(rest);
        if (m.find()) return m.group(1).trim();
        return def;
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

    /** JUnit Platform tag filters forwarded to the runner. */
    public JUnitLauncher withTagFilters(List<String> include, List<String> exclude) {
        this.includeTags = include == null ? List.of() : List.copyOf(include);
        this.excludeTags = exclude == null ? List.of() : List.copyOf(exclude);
        return this;
    }

    private List<String> withTagArgs(List<String> base) {
        var out = new ArrayList<>(base);
        if (!includeTags.isEmpty()) out.add("--include-tag=" + String.join(",", includeTags));
        if (!excludeTags.isEmpty()) out.add("--exclude-tag=" + String.join(",", excludeTags));
        return out;
    }

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
                Map.of(),
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
            Path testResultsDir)
            throws IOException, InterruptedException {
        return run(
                javaHome,
                testClassesDir,
                runtimeClasspath,
                cacheRoot,
                workers,
                workerJarProps,
                Map.of(),
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
            Map<String, String> testEnv,
            TestProgressListener listener,
            Path testResultsDir)
            throws IOException, InterruptedException {
        Objects.requireNonNull(javaHome, "javaHome");
        Objects.requireNonNull(testClassesDir, "testClassesDir");
        Objects.requireNonNull(runtimeClasspath, "runtimeClasspath");
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(listener, "listener");
        // workers: 0 = auto (Mill-like min(jobs, classes) + heap clamp); ≥1 = explicit.
        if (workers < 0) throw new IllegalArgumentException("workers must be >= 0 (0 = auto)");
        this.workerJarProps = workerJarProps == null ? Map.of() : Map.copyOf(workerJarProps);
        // Quarkus PathTestHelper only recognizes Maven/Gradle/IDE test-dir fragments. JK uses
        // target/classes/test + target/classes/main — register via TEST_TO_MAIN_MAPPINGS (BootstrapConstants).
        Map<String, String> env = new LinkedHashMap<>();
        if (testEnv != null) env.putAll(testEnv);
        env.putIfAbsent(
                "TEST_TO_MAIN_MAPPINGS", "classes" + File.separator + "test" + ":classes" + File.separator + "main");
        this.testEnv = Map.copyOf(env);
        this.inferredModuleDir = inferModuleDir(testClassesDir);
        ensureQuarkusToolingPom(this.inferredModuleDir);

        Path runnerJar = locateRunner(cacheRoot);
        var classpathBase = new LinkedHashSet<Path>();
        classpathBase.add(testClassesDir);
        classpathBase.addAll(runtimeClasspath);
        // Thin pure-jk workers: expand .classpath sidecar / findPluginSdk so PluginMain is on -cp
        // (JK-1347). Gradle-vendored runners already contain PluginMain; extra entries are harmless.
        classpathBase.addAll(cc.jumpkick.compile.WorkerClasspath.paths(runnerJar));
        String classpath = joinClasspath(classpathBase);
        Path javaBinary = javaBinary(javaHome);

        int resolvedWorkers = workers;
        List<String> preDiscovered = null;
        if (workers == 0) {
            // Discover once so auto can size the pool; reuse the list when W>1.
            preDiscovered = discoverClasses(javaBinary, classpath, testClassesDir, listener);
            resolvedWorkers = TestWorkers.resolve(0, preDiscovered.size(), TestWorkers.effectiveJobs());
        } else if (workers > 1) {
            resolvedWorkers = TestWorkers.resolve(workers, Integer.MAX_VALUE, TestWorkers.effectiveJobs());
        }

        if (resolvedWorkers <= 1) {
            return runSingle(javaBinary, classpath, testClassesDir, listener, testResultsDir);
        }
        // W>1 + Jupiter in-process parallel is a known double-parallelism footgun.
        List<Path> cpForDetect = new ArrayList<>();
        cpForDetect.add(testClassesDir);
        if (runtimeClasspath != null) cpForDetect.addAll(runtimeClasspath);
        if (JupiterParallelDetect.enabled(cpForDetect)) {
            listener.onWarning("jupiter-parallel", JupiterParallelDetect.stackWarning(resolvedWorkers));
        }
        return runParallel(
                javaBinary, classpath, testClassesDir, resolvedWorkers, listener, testResultsDir, preDiscovered);
    }

    // -------- single-worker ---------------------------------------------

    private TestSummary runSingle(
            Path javaBinary, String classpath, Path testClassesDir, TestProgressListener listener, Path testResultsDir)
            throws IOException, InterruptedException {
        XmlTestReport xml = testResultsDir != null ? new XmlTestReport() : null;
        MarkdownTestReport md = testResultsDir != null ? new MarkdownTestReport() : null;
        var aggregator = new ResultAggregator(listener, /* workerId */ 0, xml, md, moduleLabel);
        // Capture the worker's non-protocol output so a hard crash (uncaught
        // throwable / System.exit before any test event) can be explained instead
        // of surfacing only as "runner exited N".
        var crash = new CaptureBuffer();
        int exit = cc.jumpkick.engine.plugin.PluginLoader.run(
                javaBinary,
                classpath,
                runnerFlags(1),
                PROTOCOL_PREFIX,
                withTagArgs(List.of("--scan-classpath=" + testClassesDir)),
                testEnv,
                inferredModuleDir,
                aggregator::accept,
                line -> {
                    crash.add(line);
                    listener.onUserOutput(0, line);
                });
        TestSummary result = aggregator.toResult(exit, crash.text());
        if (xml != null) {
            try {
                xml.writeAll(testResultsDir);
            } catch (IOException e) {
                /* non-fatal: tests ran, just report writing failed */
            }
        }
        if (md != null) {
            try {
                md.writeAll(testResultsDir.getParent());
            } catch (IOException e) {
                /* non-fatal */
            }
        }
        return result;
    }

    // -------- parallel pull-queue ---------------------------------------

    private TestSummary runParallel(
            Path javaBinary,
            String classpath,
            Path testClassesDir,
            int workers,
            TestProgressListener listener,
            Path testResultsDir)
            throws IOException, InterruptedException {
        return runParallel(javaBinary, classpath, testClassesDir, workers, listener, testResultsDir, null);
    }

    private TestSummary runParallel(
            Path javaBinary,
            String classpath,
            Path testClassesDir,
            int workers,
            TestProgressListener listener,
            Path testResultsDir,
            List<String> preDiscovered)
            throws IOException, InterruptedException {
        // 1. Discovery — one fork, list-only mode, harvest class FQCNs (skip if auto already did).
        List<String> classes = preDiscovered != null
                ? preDiscovered
                : discoverClasses(javaBinary, classpath, testClassesDir, listener);
        if (classes.isEmpty()) {
            return new TestSummary(0, 0, 0, 0, List.of());
        }
        // Don't waste workers on small suites — N workers > N classes leaves
        // some idle waiting for a class that'll never come.
        int actualWorkers = Math.min(workers, classes.size());
        actualWorkers = TestWorkers.clampByHeap(actualWorkers);

        // One shared report per format — all worker threads write into them (both are thread-safe).
        XmlTestReport xml = testResultsDir != null ? new XmlTestReport() : null;
        MarkdownTestReport md = testResultsDir != null ? new MarkdownTestReport() : null;

        var queue = new ConcurrentLinkedDeque<>(classes);
        var aggregators = new java.util.ArrayList<ResultAggregator>();
        var workerThreads = new ArrayList<Thread>();
        int[] exits = new int[actualWorkers];
        var captures = new ArrayList<CaptureBuffer>();

        for (int w = 0; w < actualWorkers; w++) {
            final int workerId = w + 1;
            final int idx = w;
            List<String> args =
                    withTagArgs(List.of("--pull", "--worker=" + workerId, "--scan-classpath=" + testClassesDir));
            var agg = new ResultAggregator(listener, workerId, xml, md, moduleLabel);
            aggregators.add(agg);
            final var crash = new CaptureBuffer();
            captures.add(crash);
            final int totalWorkers = actualWorkers;
            var t = new Thread(
                    () -> exits[idx] = driveWorker(
                            javaBinary, classpath, workerId, totalWorkers, args, queue, agg, listener, crash),
                    "jk-test-worker-" + workerId);
            t.start();
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
        var allFailures = new ArrayList<TestSummary.Failure>();
        for (var agg : aggregators) {
            var r = agg.snapshot();
            total += r.total();
            succeeded += r.succeeded();
            failed += r.failed();
            skipped += r.skipped();
            classCount += r.classes();
            allFailures.addAll(r.failures());
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
                    List.of(new TestSummary.Failure(
                            "(test run)", "", "runner exited " + worstExit, crash.toString(), moduleLabel, "", 0)));
        }
        if (xml != null) {
            try {
                xml.writeAll(testResultsDir);
            } catch (IOException e) {
                /* non-fatal: tests ran, just report writing failed */
            }
        }
        if (md != null) {
            try {
                md.writeAll(testResultsDir.getParent());
            } catch (IOException e) {
                /* non-fatal */
            }
        }
        return new TestSummary(total, succeeded, failed, skipped, classCount, allFailures);
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
            CaptureBuffer crash) {
        // Pull protocol: each "ready" pulls the next class from the shared queue.
        java.util.function.BiConsumer<String, PluginProcess.Conversation> handler = (json, convo) -> {
            String event = Jsonl.str(json, "event");
            if ("ready".equals(event)) {
                String next = queue.pollFirst();
                if (next != null) {
                    convo.send("RUN " + next);
                } else {
                    convo.send("DONE");
                    convo.closeInput();
                }
            } else {
                aggregator.accept(json);
            }
        };
        java.util.function.Consumer<String> passthrough = line -> {
            crash.add(line);
            listener.onUserOutput(workerId, line);
        };

        try {
            // Mill-class isolation: each worker gets its own java.io.tmpdir when W>1.
            List<String> flags = new ArrayList<>(runnerFlags(totalWorkers));
            Map<String, String> env = testEnv;
            if (totalWorkers > 1) {
                try {
                    Path tmp = Files.createTempDirectory("jk-tw-" + workerId + "-");
                    flags.add("-Djava.io.tmpdir=" + tmp);
                    env = new LinkedHashMap<>(testEnv);
                    env.put("TMPDIR", tmp.toString());
                    env.put("TMP", tmp.toString());
                    env.put("TEMP", tmp.toString());
                } catch (IOException ignored) {
                    // best-effort isolation
                }
            }
            return cc.jumpkick.engine.plugin.PluginLoader.converse(
                    javaBinary,
                    classpath,
                    // N test JVMs run at once → divide the heap cap by N so they fit.
                    flags,
                    PROTOCOL_PREFIX,
                    args,
                    env,
                    inferredModuleDir,
                    handler,
                    passthrough);
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
        cc.jumpkick.engine.plugin.PluginLoader.run(
                javaBinary,
                classpath,
                runnerFlags(1),
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
     * jar into the CAS — typically by running {@code./gradlew:test-runner:installLocalCas} in jk's
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
            return PluginJar.TEST_RUNNER.locate(cc.jumpkick.cache.JkStores.cas(cacheRoot));
        } catch (IllegalStateException e) {
            throw new IOException("jk test: " + e.getMessage(), e);
        }
    }

    private static String joinClasspath(Iterable<Path> entries) {
        var sb = new StringBuilder();
        for (Path p : entries) {
            if (sb.length() > 0) sb.append(File.pathSeparator);
            sb.append(p);
        }
        return sb.toString();
    }

    private static Path javaBinary(Path javaHome) {
        return javaHome.resolve("bin").resolve(HostPlatform.isWindows() ? "java.exe" : "java");
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
        private final XmlTestReport xmlReport;
        private final MarkdownTestReport mdReport;
        private final String moduleLabel;
        private long succeeded;
        private long failed;
        private long skipped;
        private final List<TestSummary.Failure> failures = new ArrayList<>();
        // Tests whose `dynamic_registered` event we observed at execute-time
        // — i.e., @ParameterizedTest / @TestFactory / @TestTemplate /
        // @RepeatedTest invocations that weren't in the static plan. Used
        // to mark their later `finished`/`skipped` events as wasStatic=false
        // so progress UIs can keep a stable static-plan denominator.
        private final java.util.Set<String> dynamicIds = new java.util.HashSet<>();
        // Distinct classes with at least one executed (finished/skipped) test — the
        // class-rate ETA prior's denominator. Workers partition by class, so
        // per-worker counts sum without overlap.
        private final java.util.Set<String> executedClasses = new java.util.HashSet<>();

        /** Test-friendly ctor: no listener, no worker id, no reports. */
        ResultAggregator() {
            this(TestProgressListener.noop(), 0, null, null, "");
        }

        ResultAggregator(TestProgressListener listener, int workerId) {
            this(listener, workerId, null, null, "");
        }

        ResultAggregator(
                TestProgressListener listener, int workerId, XmlTestReport xmlReport, MarkdownTestReport mdReport) {
            this(listener, workerId, xmlReport, mdReport, "");
        }

        ResultAggregator(
                TestProgressListener listener,
                int workerId,
                XmlTestReport xmlReport,
                MarkdownTestReport mdReport,
                String moduleLabel) {
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
                        dynamicIds.add(Jsonl.str(json, "id"));
                    }
                }
                case "warning" -> listener.onWarning(Jsonl.str(json, "code"), Jsonl.str(json, "message"));
                case "started" -> onStarted(json);
                case "finished" -> onFinished(json);
                case "skipped" -> onSkipped(json);
                default -> {}
            }
        }

        private void onStarted(String json) {
            boolean isTest = "TEST".equals(Jsonl.str(json, "type"));
            listener.onTestStarted(Jsonl.str(json, "id"), Jsonl.str(json, "display"), isTest, workerId);
        }

        private void onFinished(String json) {
            boolean isTest = "TEST".equals(Jsonl.str(json, "type"));
            String id = Jsonl.str(json, "id");
            String status = Jsonl.str(json, "status");
            String display = Jsonl.str(json, "display");
            if (display == null) display = id;
            long duration = Jsonl.intValue(json, "duration_ms", 0);
            boolean wasStatic = isTest && !dynamicIds.contains(id);
            if (isTest) {
                String cls = classFromUniqueId(id);
                if (!cls.isEmpty()) executedClasses.add(cls);
                switch (status != null ? status : "") {
                    case "SUCCESSFUL" -> succeeded++;
                    case "FAILED" -> captureFailure(id, display, json);
                    case "ABORTED" -> skipped++;
                    default -> {}
                }
            } else if ("FAILED".equals(status)) {
                // A container-level failure (class initializer / @BeforeAll / engine):
                // no per-test event follows, so without capturing it the run would
                // surface only as a bare "runner exited N". Record it with its stack.
                captureFailure(id, display + " (container)", json);
            }
            listener.onTestFinished(id, display, status, isTest, wasStatic, duration, workerId);
            if (isTest) {
                String throwable = Jsonl.nested(json, "throwable");
                if ("ABORTED".equals(status)) {
                    if (xmlReport != null) xmlReport.recordSkipped(id, display, "aborted");
                    if (mdReport != null) mdReport.recordSkipped(id, display, "aborted");
                } else {
                    if (xmlReport != null) xmlReport.recordFinished(id, display, duration, throwable);
                    if (mdReport != null) mdReport.recordFinished(id, display, duration, throwable);
                }
            }
        }

        /** Record a FAILED test/container: count it and keep its summary + full stack. */
        private void captureFailure(String id, String display, String json) {
            failed++;
            String throwableJson = Jsonl.nested(json, "throwable");
            String exClass = throwableJson != null ? Jsonl.str(throwableJson, "class") : null;
            if (exClass == null) exClass = "?";
            String message = throwableJson != null ? Jsonl.str(throwableJson, "message") : null;
            if (message == null) message = "";
            // The runner emits the full stack trace under "stack"; keep it so the
            // build can print it (we used to read only class + message).
            String stack = throwableJson != null ? Jsonl.str(throwableJson, "stack") : null;
            String className = classFromUniqueId(id);
            failures.add(new TestSummary.Failure(
                    display, exClass, message, stack == null ? "" : stack, moduleLabel, className, workerId));
            listener.onFailure(id, display, exClass, message, workerId);
        }

        private void onSkipped(String json) {
            boolean isTest = "TEST".equals(Jsonl.str(json, "type"));
            String id = Jsonl.str(json, "id");
            boolean wasStatic = isTest && !dynamicIds.contains(id);
            if (isTest) {
                skipped++;
                String cls = classFromUniqueId(id);
                if (!cls.isEmpty()) executedClasses.add(cls);
            }
            String reason = Jsonl.str(json, "reason");
            listener.onTestSkipped(
                    id, Jsonl.str(json, "display"), reason != null ? reason : "", isTest, wasStatic, workerId);
            if (isTest) {
                String display = Jsonl.str(json, "display");
                if (xmlReport != null) xmlReport.recordSkipped(id, display, reason);
                if (mdReport != null) mdReport.recordSkipped(id, display, reason);
            }
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
                        List.of(new TestSummary.Failure(
                                "(test run)",
                                "",
                                "runner exited " + exitCode,
                                crashOutput == null ? "" : crashOutput,
                                moduleLabel,
                                "",
                                workerId)));
            }
            return new TestSummary(total, succeeded, failed, skipped, executedClasses.size(), List.copyOf(failures));
        }

        /** Snapshot of just the counters — used by the parallel-merge path. */
        synchronized TestSummary snapshot() {
            long total = succeeded + failed + skipped;
            return new TestSummary(total, succeeded, failed, skipped, executedClasses.size(), List.copyOf(failures));
        }
    }

    /** Extract {@code com.example.FooTest} from a JUnit Platform unique id segment {@code [class:…]}. */
    public static String classFromUniqueId(String id) {
        if (id == null || id.isBlank()) return "";
        int i = id.indexOf("[class:");
        if (i < 0) return "";
        int start = i + "[class:".length();
        int end = id.indexOf(']', start);
        if (end < 0) return "";
        return id.substring(start, end).trim();
    }

    /**
     * Bounded, thread-safe tail of a worker's non-protocol output. Kept so a hard crash (uncaught
     * throwable / {@code System.exit} before any test event) can be explained — the runner prints the
     * stack to stderr, which is otherwise dropped unless {@code --verbose}. Capped to the last {@link
     * #MAX_LINES} lines so a chatty-then-crashing worker can't blow up memory.
     */
    static final class CaptureBuffer {
        private static final int MAX_LINES = 400;
        private final java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();

        synchronized void add(String line) {
            if (line == null) return;
            lines.addLast(line);
            if (lines.size() > MAX_LINES) lines.removeFirst();
        }

        synchronized boolean isEmpty() {
            return lines.isEmpty();
        }

        synchronized String text() {
            return String.join("\n", lines);
        }
    }
}

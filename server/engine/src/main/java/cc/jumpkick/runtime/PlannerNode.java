// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.BUILD_OUTCOME;
import static cc.jumpkick.runtime.BuildPlanner.LOCKFILE;
import static cc.jumpkick.runtime.BuildPlanner.NODE_HOME;
import static cc.jumpkick.runtime.BuildPlanner.NODE_INSTALLED;
import static cc.jumpkick.runtime.BuildPlanner.NODE_OUT;
import static cc.jumpkick.runtime.BuildPlanner.TEST_RESULT;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.NodeProject;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.model.TestFailureMode;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.PackageManager;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The steps of a node build: {@code node-install} (the frozen install, vouched for by a stamp),
 * {@code node-build} (the build command, its output directory cached like a classes tree) and
 * {@code node-test} (the test script, the module's fast tier). A dedicated node module plans them
 * as its whole build; a JVM module with a node build beside its sources plans them ahead of its own.
 */
final class PlannerNode {

    /** The install stamp under the module's {@code target/}: the {@code node-install} key {@code node_modules} answers to. */
    static final String INSTALL_STAMP = "node-install.stamp";

    /** The JUnit XML a node test step writes under the module's {@code surefire-reports/}. */
    static final String TEST_REPORT = "TEST-node.xml";

    private PlannerNode() {}

    /** What one node build's steps share: where it is, what it is, and the module it belongs to. */
    record Unit(BuildPlanner.Inputs in, JkBuild project, Path moduleDir, Path nodeDir, NodeProject node) {

        /** {@code group:name}, the label a test failure carries. */
        String module() {
            return project.project().group() + ":" + project.project().name();
        }

        /** The build's output directory. */
        Path out() {
            return nodeDir.resolve(node.out());
        }

        /** The stamp {@code node_modules} is vouched for by. */
        Path stamp() {
            return PlannerNode.stamp(moduleDir);
        }

        /** The environment a step's process starts with; {@code production} for a build. */
        Map<String, String> env(NodeHome home, boolean production) {
            return NodeEnv.of(in, project, node, home, moduleDir, production);
        }

        boolean rebuild() {
            return in.session().config().rebuildOr(false);
        }

        ActionCache actionCache() {
            return new ActionCache(JkStores.cacheCas(in.cache()), CacheTree.ACTIONS.under(in.cache()));
        }
    }

    /** Whether no node step runs: {@code --skip-node}, {@code JK_SKIP_NODE} or {@code [node] skip}. */
    static boolean skipped(BuildPlanner.Inputs in, JkBuild project) {
        return in.session().skipNode() || project.node().skip();
    }

    /**
     * Add the node steps for {@code unit} to {@code b} and return the leaves the plan's terminal
     * must keep: the build (unless the plan only tests) and the tests (unless they are skipped or
     * the plan only compiles). A skipped node build plans one step that says so.
     */
    static List<String> plan(BuildPlan.Builder b, Unit unit) {
        BuildPlanner.Inputs in = unit.in();
        List<String> leaves = new ArrayList<>();
        boolean packages = !in.testOnly() && !in.compileOnly();
        if (skipped(in, unit.project())) {
            b.addTask(skippedStep(unit));
            leaves.add(TaskNames.NODE_BUILD);
            if (packages) {
                b.addTask(packageStep(unit));
                leaves.add(TaskNames.NODE_PACKAGE);
            }
            return leaves;
        }
        b.addTask(installStep(unit));
        if (!in.testOnly()) {
            b.addTask(buildStep(unit));
            leaves.add(TaskNames.NODE_BUILD);
        }
        if (packages) {
            b.addTask(packageStep(unit));
            leaves.add(TaskNames.NODE_PACKAGE);
        }
        if (!in.compileOnly() && !PlannerResources.skipJUnit(in) && unit.node().test() != null) {
            b.addTask(testStep(unit));
            leaves.add(TaskNames.NODE_TEST);
        }
        if (leaves.isEmpty()) leaves.add(TaskNames.NODE_INSTALL);
        return leaves;
    }

    /**
     * The module's resource jar: the build output under {@link NodePackaging#classpathRoot}, or a
     * step that says nothing is packaged when the module names no root and nothing depends on it.
     */
    static Task packageStep(Unit unit) {
        return Task.builder(TaskNames.NODE_PACKAGE)
                .stage(BuildStage.PACKAGE)
                .label("Packaging")
                .kind(TaskKind.CPU)
                .requires(TaskNames.NODE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = unit.project();
                    String root = NodePackaging.classpathRoot(project, unit.moduleDir());
                    if (root == null) {
                        ctx.label("not packaged · nothing depends on this module and [node] classpath-root is unset");
                        ctx.progress(1);
                        return;
                    }
                    Path out = ctx.get(NODE_OUT).orElse(null);
                    if (out == null || !Files.isDirectory(out)) {
                        throw new IOException(unit.module() + ": no "
                                + unit.node().out() + "/ to package — run the build once without --skip-node");
                    }
                    BuildLayout layout = BuildLayout.of(unit.moduleDir(), project);
                    Path jar = layout.mainJar();
                    NodeKeys.Keyed keyed = NodeKeys.pkg(unit.nodeDir(), out, root, project.manifest());
                    Path cacheRoot = unit.in().cache();
                    String where = root.isEmpty() ? "the jar root" : root + "/";
                    if (PlannerSupport.restorePackaged(cacheRoot, keyed.key(), jar.getParent())) {
                        ctx.label(jar.getFileName() + " up-to-date · " + where);
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label(
                            "package " + jar.getFileName() + " · " + unit.node().out() + "/ under " + where);
                    NodePackaging.packageJar(
                            out, root, layout.targetDir().resolve("node-jar"), jar, project.manifest());
                    PlannerSupport.storePackaged(
                            cacheRoot,
                            keyed.taskId(),
                            keyed.key(),
                            keyed.inputs().entrySet().stream()
                                    .map(e -> e.getKey() + "=" + e.getValue())
                                    .toList(),
                            jar.getParent(),
                            List.of(jar),
                            !unit.in().ephemeralActions());
                    PlannerPackage.writeSidecarPom(project, layout, jar);
                    ctx.put(BUILD_OUTCOME, "built");
                    ctx.progress(1);
                })
                .build();
    }

    /** The one step of a skipped node build: the output on disk, if any, is what the build has. */
    static Task skippedStep(Unit unit) {
        return Task.builder(TaskNames.NODE_BUILD)
                .stage(BuildStage.COMPILE)
                .label("Node build")
                .kind(TaskKind.SYNC)
                .requires(TaskNames.PARSE_BUILD)
                .weight(EffortWeights.TOKEN)
                .ticks(1)
                .execute(ctx -> {
                    Path out = unit.out();
                    if (Files.isDirectory(out)) {
                        ctx.put(NODE_OUT, out);
                        ctx.label("skipped · using " + unit.node().out() + "/ as it is");
                    } else {
                        ctx.label("skipped · no " + unit.node().out() + "/ yet");
                    }
                    ctx.cached();
                    ctx.progress(1);
                })
                .build();
    }

    static Task installStep(Unit unit) {
        return Task.builder(TaskNames.NODE_INSTALL)
                .stage(BuildStage.RESOLVE)
                .label("Install")
                .kind(TaskKind.IO)
                .requires(TaskNames.ENSURE_NODE)
                .weight(EffortWeights.TOKEN)
                .ticks(1)
                .execute(ctx -> {
                    NodeHome home = ctx.require(NODE_HOME);
                    String override = unit.project().node().install();
                    if (override == null) requireLockfile(unit, home.packageManager());
                    NodeKeys.Keyed keyed =
                            NodeKeys.install(unit.nodeDir(), override, PlannerNodeSetup.token(ctx.require(LOCKFILE)));
                    ctx.put(NODE_INSTALLED, keyed.key());
                    if (!unit.rebuild() && installed(unit.nodeDir(), unit.stamp(), keyed.key())) {
                        ctx.label("node_modules up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label(home.packageManager().id() + " install");
                    Map<String, String> env = unit.env(home, false);
                    List<String> argv = NodeCommands.install(home, override, env.getOrDefault("PATH", ""));
                    NodeProcess.Result r = NodeProcess.run(ctx, argv, unit.nodeDir(), env);
                    if (!r.ok()) throw failure(home.packageManager().id() + " install", r);
                    // A project with no dependencies installs nothing; the directory is still what the stamp vouches
                    // for.
                    Files.createDirectories(unit.nodeDir().resolve("node_modules"));
                    Files.createDirectories(unit.stamp().getParent());
                    Files.writeString(unit.stamp(), keyed.key(), StandardCharsets.UTF_8);
                    ctx.progress(1);
                })
                .build();
    }

    /** {@code node_modules} is on disk in {@code nodeDir} and {@code stamp} vouches for it under {@code key}. */
    static boolean installed(Path nodeDir, Path stamp, String key) throws IOException {
        if (!Files.isDirectory(nodeDir.resolve("node_modules"))) return false;
        return Files.isRegularFile(stamp)
                && Files.readString(stamp, StandardCharsets.UTF_8).equals(key);
    }

    /** The install stamp of {@code moduleDir}'s node build. */
    static Path stamp(Path moduleDir) {
        return BuildLayout.moduleTargetDir(moduleDir).resolve(INSTALL_STAMP);
    }

    /** A frozen install needs the lockfile; a node build without one fails naming the command that writes it. */
    private static void requireLockfile(Unit unit, PackageManager manager) throws IOException {
        for (String name : NodeKeys.LOCKFILES) {
            if (Files.isRegularFile(unit.nodeDir().resolve(name))) return;
        }
        String lockfile =
                switch (manager) {
                    case NPM -> "package-lock.json";
                    case PNPM -> "pnpm-lock.yaml";
                    case YARN -> "yarn.lock";
                    case BUN -> "bun.lock";
                };
        throw new IOException(unit.project().project().name() + ": no " + lockfile + " — run " + manager.id()
                + " install once and commit it");
    }

    static Task buildStep(Unit unit) {
        return Task.builder(TaskNames.NODE_BUILD)
                .stage(BuildStage.COMPILE)
                .label("Node build")
                .kind(TaskKind.CPU)
                .requires(TaskNames.NODE_INSTALL)
                .ticks(1)
                .execute(ctx -> {
                    NodeTable.Command command = unit.node().build();
                    if (command == null) {
                        ctx.label("nothing to build");
                        ctx.progress(1);
                        return;
                    }
                    requireScript(unit, command);
                    NodeHome home = ctx.require(NODE_HOME);
                    Map<String, String> env = unit.env(home, true);
                    NodeKeys.Keyed keyed = NodeKeys.build(
                            unit.nodeDir(),
                            unit.node(),
                            command,
                            NodeEnv.keyed(unit.project(), unit.node(), unit.moduleDir(), true),
                            ctx.require(NODE_INSTALLED),
                            PlannerNodeSetup.token(ctx.require(LOCKFILE)));
                    Path out = unit.out();
                    ActionCache cache = unit.actionCache();
                    Optional<ActionCache.ActionRecord> hit =
                            unit.rebuild() ? Optional.empty() : cache.lookup(keyed.key());
                    if (hit.isPresent() && cache.restore(hit.get(), out)) {
                        ctx.put(NODE_OUT, out);
                        ctx.label(unit.node().out() + "/ up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label(describe(command));
                    List<String> argv = NodeCommands.command(home, command, List.of(), env.getOrDefault("PATH", ""));
                    NodeProcess.Result r = NodeProcess.run(ctx, argv, unit.nodeDir(), env);
                    if (!r.ok()) {
                        String exited = exited(describe(command), r);
                        IOException failed = new IOException(exited);
                        // A step that reported its own errors has its exception dropped; the exit goes with them.
                        if (!r.diagnostics().isEmpty()) {
                            for (NodeProcess.Diagnostic d : r.diagnostics()) ctx.error("node", d.render());
                            ctx.error("node", exited);
                        }
                        throw failed;
                    }
                    if (!Files.isDirectory(out)) {
                        throw new IOException(describe(command) + " wrote no "
                                + unit.node().out() + "/ — set [node] out to the directory it writes");
                    }
                    cache.store(keyed.taskId(), keyed.key(), keyed.inputs(), out);
                    ctx.put(NODE_OUT, out);
                    ctx.put(BUILD_OUTCOME, "built");
                    ctx.progress(1);
                })
                .build();
    }

    static Task testStep(Unit unit) {
        return Task.builder(TaskNames.NODE_TEST)
                .stage(BuildStage.TEST)
                .label("Node tests")
                .kind(TaskKind.CPU)
                .requires(TaskNames.NODE_INSTALL)
                .ticks(1)
                .execute(ctx -> {
                    String script = unit.node().test();
                    String body = script == null ? null : NodeProject.script(unit.nodeDir(), script);
                    if (script == null || body == null) {
                        ctx.label("no tests");
                        ctx.progress(1);
                        return;
                    }
                    NodeHome home = ctx.require(NODE_HOME);
                    Map<String, String> env = unit.env(home, false);
                    NodeKeys.Keyed keyed = NodeKeys.test(
                            unit.nodeDir(),
                            unit.node(),
                            script,
                            NodeEnv.keyed(unit.project(), unit.node(), unit.moduleDir(), false),
                            ctx.require(NODE_INSTALLED),
                            PlannerNodeSetup.token(ctx.require(LOCKFILE)));
                    ActionCache cache = unit.actionCache();
                    boolean coverage = unit.in().session().coverage();
                    if (!unit.rebuild() && !coverage && replayGreen(ctx, cache, keyed.key())) {
                        ctx.progress(1);
                        return;
                    }
                    Path xml = BuildLayout.of(unit.moduleDir(), unit.project())
                            .testResultsDir()
                            .resolve(TEST_REPORT);
                    Files.deleteIfExists(xml);
                    Files.createDirectories(xml.getParent());
                    NodeTestReport.Runner runner = NodeTestReport.runner(body);
                    NodeTestReport.Wiring wiring = NodeTestReport.wiring(
                            runner,
                            xml,
                            NodeProject.dependsOn(unit.nodeDir(), "jest-junit"),
                            env.getOrDefault("NODE_OPTIONS", ""));
                    Map<String, String> runEnv = new LinkedHashMap<>(env);
                    runEnv.putAll(wiring.env());
                    ctx.label("run " + script);
                    List<String> argv = NodeCommands.command(
                            home, NodeTable.Command.run(script), wiring.extraArgs(), env.getOrDefault("PATH", ""));
                    NodeProcess.Result r = NodeProcess.run(ctx, argv, unit.nodeDir(), runEnv);
                    TestSummary summary = summary(NodeTestReport.read(xml, unit.module()), r, unit.module(), script);
                    ctx.put(TEST_RESULT, summary);
                    if (summary.allPassed() && r.ok()) {
                        cache.storeWithOutputs(
                                keyed.taskId(),
                                keyed.key(),
                                keyed.inputs(),
                                TestStamp.outcome(summary.total(), summary.succeeded(), summary.skipped(), 0));
                        ctx.progress(1);
                        return;
                    }
                    recordFailures(ctx, unit, summary, r);
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * {@code jk explain}'s view of a node build: each step CACHED when its key's record (or, for the
     * install, its stamp) is in place, else RUN with what changed since the last record. The keys
     * are the build's, from the same bodies.
     */
    static List<TaskForecast.Task> forecast(
            JkBuild project,
            Path moduleDir,
            Path nodeDir,
            Lockfile lock,
            ActionCache cache,
            boolean skipTests,
            boolean skipNode)
            throws IOException {
        List<TaskForecast.Task> steps = new ArrayList<>();
        NodeProject node = NodeProject.infer(nodeDir, project.node());
        if (skipNode || project.node().skip()) {
            steps.add(new TaskForecast.Task(TaskNames.NODE_BUILD, TaskForecast.Status.CACHED, "skipped", null));
            return steps;
        }
        String token = PlannerNodeSetup.token(lock);
        NodeKeys.Keyed install = NodeKeys.install(nodeDir, project.node().install(), token);
        steps.add(
                installed(nodeDir, stamp(moduleDir), install.key())
                        ? new TaskForecast.Task(
                                TaskNames.NODE_INSTALL,
                                TaskForecast.Status.CACHED,
                                "node_modules up-to-date",
                                short8(install.key()))
                        : new TaskForecast.Task(
                                TaskNames.NODE_INSTALL,
                                TaskForecast.Status.RUN,
                                node.packageManager() + " install",
                                null));
        NodeTable.Command command = node.build();
        if (command != null) {
            NodeKeys.Keyed build = NodeKeys.build(
                    nodeDir, node, command, NodeEnv.keyed(project, node, moduleDir, true), install.key(), token);
            steps.add(forecastStep(cache, build, TaskNames.NODE_BUILD, describe(command), nodeDir.resolve(node.out())));
        }
        if (!skipTests && node.test() != null && NodeProject.script(nodeDir, node.test()) != null) {
            NodeKeys.Keyed test = NodeKeys.test(
                    nodeDir, node, node.test(), NodeEnv.keyed(project, node, moduleDir, false), install.key(), token);
            steps.add(forecastStep(cache, test, TaskNames.NODE_TEST, "run " + node.test(), null));
        }
        return steps;
    }

    /** {@code jk explain}'s module row for {@code unit} when it is a dedicated node module, else {@code null}. */
    static TaskForecast.@Nullable Module forecastModule(
            BuildGraph.BuildUnit unit, Path lockFile, ActionCache cache, boolean skipTests) {
        JkBuild project = unit.manifest();
        Path dir = unit.dir();
        if (NodeShape.kind(project, dir) != NodeShape.Kind.MODULE) return null;
        List<TaskForecast.Task> steps = new ArrayList<>();
        if (!Files.isRegularFile(lockFile)) {
            steps.add(new TaskForecast.Task(
                    TaskNames.NODE_INSTALL, TaskForecast.Status.RUN, "not locked yet (run `jk build`)", null));
            return new TaskForecast.Module(dir, unit.coord(), steps, 0, 0, false, false);
        }
        try {
            Path nodeDir = Objects.requireNonNull(NodeShape.nodeDir(project, dir), "node directory");
            boolean skipNode = SessionContext.current().skipNode();
            steps.addAll(forecast(project, dir, nodeDir, LockfileReader.read(lockFile), cache, skipTests, skipNode));
        } catch (Exception e) {
            steps.add(new TaskForecast.Task(
                    TaskNames.NODE_BUILD,
                    TaskForecast.Status.RUN,
                    "could not predict (" + e.getClass().getSimpleName() + ")",
                    null));
        }
        return new TaskForecast.Module(dir, unit.coord(), steps, 0, 0, false, false);
    }

    /**
     * One step priced by its key: CACHED when the record is there and, for a step with an {@code
     * out} directory, every file it recorded is on disk; a restore is work the build does.
     */
    private static TaskForecast.Task forecastStep(
            ActionCache cache, NodeKeys.Keyed keyed, String name, String what, @Nullable Path out) throws IOException {
        Optional<ActionCache.ActionRecord> record = cache.lookup(keyed.key());
        if (record.isPresent()) {
            if (out == null || onDisk(record.get(), out)) {
                return new TaskForecast.Task(
                        name, TaskForecast.Status.CACHED, what + " up-to-date", short8(keyed.key()));
            }
            return new TaskForecast.Task(
                    name, TaskForecast.Status.RUN, what + " · restore " + out.getFileName() + "/", null);
        }
        String why = cache.lastFor(keyed.taskId())
                .map(prior -> NodeKeys.missReason(prior.inputs(), keyed.inputs()))
                .orElse("");
        return new TaskForecast.Task(name, TaskForecast.Status.RUN, why.isEmpty() ? what : what + " · " + why, null);
    }

    /** Every output {@code record} holds is a file under {@code out}. */
    private static boolean onDisk(ActionCache.ActionRecord record, Path out) {
        for (String rel : record.outputs().keySet()) {
            if (!Files.isRegularFile(out.resolve(rel))) return false;
        }
        return true;
    }

    private static String short8(String key) {
        return key.length() > 8 ? key.substring(0, 8) : key;
    }

    /** The counts of a passing run stored on its key, replayed; false when there is no green record. */
    private static boolean replayGreen(TaskContext ctx, ActionCache cache, String key) throws IOException {
        Optional<ActionCache.ActionRecord> marker = cache.lookup(key);
        if (marker.isEmpty() || !TestStamp.green(marker.get())) return false;
        ctx.label(TaskNames.TESTS_UP_TO_DATE);
        ctx.cached();
        TestSummary previous = PlannerTest.stampedSummary(marker.get());
        if (previous != null) ctx.put(TEST_RESULT, previous);
        return true;
    }

    /**
     * The run's summary: the report when the runner wrote one, else one test that is the script
     * itself. A failing exit with a report that shows no failure counts the script as failed.
     */
    static TestSummary summary(@Nullable TestSummary report, NodeProcess.Result r, String module, String script) {
        if (report != null && (r.ok() || report.failed() > 0)) return report;
        if (r.ok()) return new TestSummary(1, 1, 0, 0, List.of());
        TestFailureInfo failure = new TestFailureInfo(
                module, "node", script, script, "", "`" + script + "` exited " + r.exit(), String.join("\n", r.tail()));
        long total = report == null ? 1 : report.total() + 1;
        long succeeded = report == null ? 0 : report.succeeded();
        long skipped = report == null ? 0 : report.skipped();
        return new TestSummary(total, succeeded, 1, skipped, List.of(failure));
    }

    /** Report each failure; fail the step unless the run or the module reports test failures without failing. */
    private static void recordFailures(TaskContext ctx, Unit unit, TestSummary summary, NodeProcess.Result r) {
        for (TestFailureInfo f : summary.failures()) ctx.error("test-failure", f.message(), f);
        String failed = summary.failed() + " test failure" + (summary.failed() == 1 ? "" : "s");
        if (TestLaunch.failureMode(unit.in(), unit.project()) == TestFailureMode.REPORT) {
            ctx.warn(TestLaunch.FAILURES_REPORTED, failed + " reported, not failing: [test] failures = \"report\"");
            return;
        }
        throw new RuntimeException(failed + (r.ok() ? "" : " (exit " + r.exit() + ")"));
    }

    /** A {@code run} command names a script {@code package.json} has. */
    private static void requireScript(Unit unit, NodeTable.Command command) throws IOException {
        if (command.kind() != NodeTable.Command.Kind.RUN) return;
        if (NodeProject.script(unit.nodeDir(), command.value()) == null) {
            throw new IOException(unit.project().project().name() + ": package.json has no `" + command.value()
                    + "` script — add it or set [node] build");
        }
    }

    private static String describe(NodeTable.Command command) {
        return switch (command.kind()) {
            case RUN -> "run " + command.value();
            case NPX -> "npx " + command.value();
            case EXEC -> command.value();
        };
    }

    /** A failed command, with its exit and last lines. */
    private static IOException failure(String what, NodeProcess.Result r) {
        return new IOException(exited(what, r));
    }

    private static String exited(String what, NodeProcess.Result r) {
        StringBuilder sb =
                new StringBuilder("`").append(what).append("` exited ").append(r.exit());
        for (String line : r.tail()) sb.append('\n').append("  ").append(line);
        return sb.toString();
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.BUILD_OUTCOME;
import static cc.jumpkick.runtime.BuildPlanner.LAYOUT;
import static cc.jumpkick.runtime.BuildPlanner.NODE_HOME;
import static cc.jumpkick.runtime.BuildPlanner.NODE_INSTALLED;
import static cc.jumpkick.runtime.BuildPlanner.NODE_OUTPUT;
import static cc.jumpkick.runtime.BuildPlanner.PARSED_BUILD;
import static cc.jumpkick.runtime.BuildPlanner.TEST_RUN;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.NodeProject;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.model.TestFailureMode;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.PackageManager;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.task.NodeOutput;
import cc.jumpkick.task.TestRun;
import cc.jumpkick.task.TestStamp;
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
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The steps of one plan's node build: {@code node-install} (the frozen install, vouched for by a
 * stamp), the {@code [[node.steps]]}, {@code node-build} (the build command, its output directory
 * cached like a classes tree), {@code node-test} (the test script, the module's fast tier) and the
 * packaging. A dedicated node module plans them as its whole build; a JVM module with a node build
 * beside its sources plans them ahead of its own. Built once per plan; each step's predictor sits
 * beside its body and prices from {@link NodeForecast}.
 *
 * <p>A declared step is a keyed task between the install and the step its {@code before} names, in
 * declaration order within one {@code before}. A step with outputs is cached and restored like
 * {@code out/}; one without is a check, cached as passed. A step of the test tier never holds the
 * build back: it runs beside it, and {@code --skip-tests} leaves it out.
 */
final class NodeArm {

    private final BuildPlanner.Inputs in;
    private final JkBuild project;
    private final Path moduleDir;
    private final Path nodeDir;
    private final NodeProject node;
    private final ActionCache actionCache;

    /** The plan runs the test tier: neither {@code jk compile} nor skipped tests. */
    private final boolean tests;

    NodeArm(BuildPlanner.Inputs in, JkBuild project, Path nodeDir, ActionCache actionCache, boolean tests) {
        this.in = in;
        this.project = project;
        this.moduleDir = in.dir();
        this.nodeDir = nodeDir;
        this.node = NodeProject.infer(nodeDir, project.node());
        this.actionCache = actionCache;
        this.tests = tests;
    }

    /**
     * Add the node steps to {@code b} and return the leaves the plan's terminal must keep: the
     * build (unless the plan only tests), the resource jar and the tests (unless they are skipped or
     * the plan only compiles). A skipped node build plans one step that says so.
     */
    List<String> plan(BuildPlan.Builder b) {
        Planned planned = steps(b);
        List<String> leaves = new ArrayList<>(planned.leaves());
        // The resource jar is what a JVM sibling compiles, tests and runs against, so every goal
        // packages it; a module nothing depends on packages nothing.
        b.addTask(packageStep(planned.last()));
        leaves.add(TaskNames.NODE_PACKAGE);
        return leaves;
    }

    /**
     * A JVM module's own node build ({@code src/main/node}): its steps, kept beside the module's
     * plan, and {@code node-stage}, which package-jar waits on, when the plan packages.
     */
    void planSideBySide(BuildPlan.Builder b) {
        Planned planned = steps(b);
        b.alsoKeep(planned.leaves().toArray(String[]::new));
        if (!in.testOnly() && !in.compileOnly()) b.addTask(stageStep(planned.last()));
    }

    /** The steps short of packaging: the last one packaging waits on, and the leaves to keep. */
    private record Planned(String last, List<String> leaves) {}

    /** The last declared step before the build, the tests and the package; {@code null} where there is none. */
    private record Declared(
            @Nullable String beforeBuild,
            @Nullable String beforeTest,
            @Nullable String beforePackage,
            List<String> leaves) {}

    private Planned steps(BuildPlan.Builder b) {
        List<String> leaves = new ArrayList<>();
        if (in.session().skipNode() || project.node().skip()) {
            b.addTask(skippedStep());
            leaves.add(TaskNames.NODE_BUILD);
            return new Planned(TaskNames.NODE_BUILD, leaves);
        }
        b.addTask(installStep());
        boolean build = !in.testOnly();
        boolean testStep = tests && node.test() != null;
        Declared steps = declared(b, build, testStep);
        if (build) {
            b.addTask(buildStep(orInstall(steps.beforeBuild())));
            leaves.add(TaskNames.NODE_BUILD);
        }
        if (testStep) {
            b.addTask(testStep(orInstall(steps.beforeTest())));
            leaves.add(TaskNames.NODE_TEST);
        }
        leaves.addAll(steps.leaves());
        if (leaves.isEmpty()) leaves.add(TaskNames.NODE_INSTALL);
        String last = steps.beforePackage() != null
                ? steps.beforePackage()
                : build ? TaskNames.NODE_BUILD : TaskNames.NODE_INSTALL;
        return new Planned(last, leaves);
    }

    private static String orInstall(@Nullable String step) {
        return step == null ? TaskNames.NODE_INSTALL : step;
    }

    /**
     * Add the declared steps to {@code b}. {@code build}: the plan builds; {@code testStep}: it
     * plans {@code node-test}.
     */
    private Declared declared(BuildPlan.Builder b, boolean build, boolean testStep) {
        List<NodeTable.Step> steps = project.node().steps();
        List<String> leaves = new ArrayList<>();
        String buildChain = chain(b, steps, NodeTable.Before.BUILD, TaskNames.NODE_INSTALL, tests, leaves);
        String testChain = tests ? chain(b, steps, NodeTable.Before.TEST, buildChain, true, leaves) : buildChain;
        String packageChain = build
                ? chain(b, steps, NodeTable.Before.PACKAGE, TaskNames.NODE_BUILD, tests, leaves)
                : TaskNames.NODE_BUILD;
        String beforeBuild = buildChain.equals(TaskNames.NODE_INSTALL) ? null : buildChain;
        String beforeTest = testChain.equals(TaskNames.NODE_INSTALL) ? null : testChain;
        if (!build && beforeBuild != null && !(testStep && beforeTest != null)) leaves.add(beforeBuild);
        if (!testStep && beforeTest != null && !beforeTest.equals(beforeBuild)) leaves.add(beforeTest);
        String beforePackage = packageChain.equals(TaskNames.NODE_BUILD) ? null : packageChain;
        return new Declared(beforeBuild, beforeTest, beforePackage, leaves);
    }

    /**
     * Plan the build-tier steps of {@code group} as a chain from {@code start}, and its test-tier
     * steps beside it when {@code withTests}; return the chain's end.
     */
    private String chain(
            BuildPlan.Builder b,
            List<NodeTable.Step> steps,
            NodeTable.Before group,
            String start,
            boolean withTests,
            List<String> leaves) {
        String end = start;
        BuildStage stage =
                switch (group) {
                    case BUILD -> BuildStage.GENERATE;
                    case TEST -> BuildStage.COMPILE;
                    case PACKAGE -> BuildStage.PACKAGE;
                };
        for (NodeTable.Step step : steps) {
            if (step.before() != group) continue;
            if (step.tier() == NodeTable.Tier.TEST) {
                if (!withTests) continue;
                BuildStage checkStage = group == NodeTable.Before.PACKAGE ? BuildStage.PACKAGE : BuildStage.TEST;
                b.addTask(declaredStep(step, checkStage, end));
                leaves.add(NodeKeys.stepTask(step));
                continue;
            }
            b.addTask(declaredStep(step, stage, end));
            end = NodeKeys.stepTask(step);
        }
        if (group == NodeTable.Before.PACKAGE && !end.equals(start)) leaves.add(end);
        return end;
    }

    // ---- predictors ------------------------------------------------------------------------

    /** A node step's verdict in a dedicated node module, every step priced together once; null elsewhere. */
    private TaskForecast.@Nullable Task forecastVerdict(ForecastScope s, String step) throws Exception {
        return s.nodeVerdict(step, () -> NodeForecast.forecast(s));
    }

    /** node-package's price in a dedicated node module: after a build that runs, else by its own key. */
    private TaskForecast.@Nullable Task forecastPackage(ForecastScope s) throws IOException {
        Path dir = s.nodeDir();
        if (dir == null) return null;
        return NodeForecast.forecastPackage(s.project, s.dir, dir, s.cache, s.runs(TaskNames.NODE_BUILD));
    }

    // ---- steps -----------------------------------------------------------------------------

    private Task declaredStep(NodeTable.Step step, BuildStage stage, String requires) {
        return Task.builder(NodeKeys.stepTask(step))
                .forecast(StepForecast.priced(s -> forecastVerdict(ForecastScope.of(s), NodeKeys.stepTask(step))))
                .stage(stage)
                .label(step.name())
                .kind(TaskKind.CPU)
                .requires(requires)
                .weight(EffortWeights.TOKEN)
                .ticks(1)
                .execute(ctx -> runDeclared(ctx, step))
                .build();
    }

    private void runDeclared(TaskContext ctx, NodeTable.Step step) throws Exception {
        NodeHome home = ctx.require(NODE_HOME);
        NodeTable.Command command = step.command();
        requireScript(command);
        String fetched = command.kind() == NodeTable.Command.Kind.NPX ? fetched(step) : null;
        NodeKeys.Keyed keyed = key(ctx, step, fetched);
        Optional<ActionCache.ActionRecord> hit = rebuild() ? Optional.empty() : actionCache.lookup(keyed.key());
        if (hit.isPresent() && (step.outputs().isEmpty() || actionCache.restoreFiles(hit.get(), nodeDir))) {
            ctx.label(step.name() + " up-to-date");
            ctx.cached();
            ctx.progress(1);
            return;
        }
        Map<String, String> env = env(home, false);
        List<String> argv = fetched != null
                ? NodeCommands.fetch(home, fetched, command.value())
                : NodeCommands.command(home, command, List.of(), env.getOrDefault("PATH", ""));
        ctx.label(NodeCommands.describe(command));
        NodeProcess.Result r = NodeProcess.run(ctx, argv, nodeDir, env);
        if (!r.ok()) {
            String exited = step.name() + ": " + r.exited(NodeCommands.describe(command));
            for (NodeProcess.Diagnostic d : r.diagnostics()) ctx.error("node", d.render());
            if (step.tier() == NodeTable.Tier.TEST && TestLaunch.failureMode(in, project) == TestFailureMode.REPORT) {
                ctx.warn(
                        TestLaunch.FAILURES_REPORTED,
                        exited + " — reported, not failing: [test] failures = \"report\"");
                ctx.progress(1);
                return;
            }
            throw new IOException(exited);
        }
        List<Path> files = new ArrayList<>();
        for (String out : step.outputs()) {
            Path path = nodeDir.resolve(out).normalize();
            if (Files.isRegularFile(path)) {
                files.add(path);
            } else if (Files.isDirectory(path)) {
                PathUtil.forEachRegularFile(path, (file, attrs) -> files.add(file));
            } else {
                throw new IOException(step.name() + ": `" + NodeCommands.describe(command) + "` wrote no " + out
                        + " — fix the step or its outputs");
            }
        }
        // The tree the key hashed, hashed again: a source the tool read that moved while it ran
        // is not what the key names, so the outputs stand for this build and are not recorded.
        Map<String, String> after = key(ctx, step, fetched).inputs();
        List<Path> moved = new ArrayList<>();
        for (String name : union(keyed.inputs().keySet(), after.keySet())) {
            if (!Objects.equals(keyed.inputs().get(name), after.get(name))) moved.add(Path.of(name));
        }
        if (!moved.isEmpty()) {
            ctx.warn("inputs-moved", InputGuard.describe(step.name(), moved));
            ctx.progress(1);
            return;
        }
        if (files.isEmpty()) actionCache.storeWithOutputs(keyed.taskId(), keyed.key(), keyed.inputs(), Map.of());
        else if (PackagingCache.admitted(ctx, actionCache.root(), files, keyed.taskId()))
            actionCache.storeArtifacts(keyed.taskId(), keyed.key(), keyed.inputs(), nodeDir, files);
        ctx.progress(1);
    }

    private NodeKeys.Keyed key(TaskContext ctx, NodeTable.Step step, @Nullable String fetched) throws IOException {
        return NodeKeys.step(
                nodeDir,
                moduleDir,
                node,
                step,
                NodeKeys.outputs(project, node),
                NodeEnv.keyed(project, node, moduleDir, false),
                ctx.require(NODE_INSTALLED),
                PlannerNodeSetup.token(ctx.require(PARSED_BUILD).lockfile()),
                fetched,
                project.node().inputs());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new TreeSet<>(a);
        all.addAll(b);
        return all;
    }

    /**
     * The {@code pkg@version} an unlocked npx fetches, or {@code null} when the install holds the
     * package. A package the install lacks is refused unless the step allows it.
     */
    private @Nullable String fetched(NodeTable.Step step) throws Exception {
        String value = step.command().value();
        String pkg = NodeCommands.npxPackage(value);
        if (NodeCommands.locked(nodeDir, pkg)) return null;
        if (!step.allowUnlocked()) throw unlocked(pkg, step.name());
        String version = NodeCommands.npxVersion(value);
        if (version == null) version = new PackageManagerResolver().latest(pkg);
        return pkg + "@" + version;
    }

    /** An npx of {@code pkg}, which the install does not hold, refused with both ways out. */
    private IOException unlocked(String pkg, @Nullable String step) {
        return new IOException(project.project().name() + ": npx " + pkg
                + " is not in the lockfile — add it as a devDependency ("
                + node.packageManager() + " add -D " + pkg + ")"
                + (step == null ? "" : " or set allow-unlocked = true on step " + step));
    }

    /**
     * Check {@code [node] exports} against what the build writes and publish them with {@code out}:
     * every export names {@code out/}, a step's output, or a path under one of them.
     */
    private void publish(TaskContext ctx, @Nullable Path out) throws IOException {
        Map<String, Path> published = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : project.node().exports().entrySet()) {
            Path path = nodeDir.resolve(e.getValue()).normalize();
            boolean written = false;
            for (String output : NodeKeys.outputs(project, node)) {
                if (path.startsWith(nodeDir.resolve(output).normalize())) written = true;
            }
            if (!written) {
                throw new IOException("[node] exports." + e.getKey() + " = \"" + e.getValue()
                        + "\" is not out/ or a step's output — export what the build writes");
            }
            published.put(e.getKey(), path);
        }
        ctx.put(NODE_OUTPUT, new NodeOutput(out, published));
    }

    /**
     * Hand a JVM module's own node output to its packaging: staged under {@code [node]
     * classpath-root} for the jar, or left where it is for a {@code [war]} module, whose war reads it.
     */
    private Task stageStep(String after) {
        return Task.builder(TaskNames.NODE_STAGE)
                .forecast(StepForecast.RIDES)
                .stage(BuildStage.PACKAGE)
                .label("Node output")
                .kind(TaskKind.IO)
                .requires(after)
                .ticks(1)
                .execute(ctx -> {
                    Path out = ctx.get(NODE_OUTPUT).map(NodeOutput::out).orElse(out());
                    if (!Files.isDirectory(out)) {
                        throw new IOException(
                                module() + ": no " + node.out() + "/ to package; build once without --skip-node");
                    }
                    Path stage = NodePackaging.sideBySideStage(project, ctx.require(LAYOUT));
                    if (stage == null) {
                        String root = project.node().webappRoot();
                        ctx.label(node.out() + "/ → the war"
                                + (root == null || root.isBlank() ? " root" : " under " + root));
                    } else {
                        String root = NodePackaging.sideBySideRoot(project);
                        NodePackaging.stage(out, stage, root);
                        ctx.label(node.out() + "/ → " + (root.isEmpty() ? "the jar root" : root + "/"));
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * The module's resource jar: the build output under {@link NodePackaging#classpathRoot}, or a
     * step that says nothing is packaged when the module names no root and nothing depends on it.
     */
    private Task packageStep(String after) {
        return Task.builder(TaskNames.NODE_PACKAGE)
                .forecast(StepForecast.priced(s -> forecastPackage(ForecastScope.of(s))))
                .stage(BuildStage.PACKAGE)
                .label("Packaging")
                .kind(TaskKind.CPU)
                .requires(after)
                .ticks(1)
                .execute(ctx -> {
                    String root = NodePackaging.classpathRoot(project, moduleDir);
                    String start = node.start();
                    String serves = start == null ? "" : " · start: " + start;
                    if (root == null) {
                        ctx.label(
                                start != null
                                        ? "server · start: " + start
                                        : "not packaged · no module depends on it and [node] classpath-root is unset");
                        ctx.progress(1);
                        return;
                    }
                    Path out = ctx.get(NODE_OUTPUT).map(NodeOutput::out).orElse(null);
                    if (out == null || !Files.isDirectory(out)) {
                        throw new IOException(
                                module() + ": no " + node.out() + "/ to package; build once without --skip-node");
                    }
                    BuildLayout layout = BuildLayout.of(moduleDir, project);
                    Path jar = layout.mainJar();
                    InputGuard guard = InputGuard.of(List.of(out));
                    NodeKeys.Keyed keyed = NodeKeys.pkg(nodeDir, out, root, project.manifest());
                    Path cacheRoot = in.cache();
                    String where = root.isEmpty() ? "the jar root" : root + "/";
                    if (PackagingCache.restorePackaged(cacheRoot, keyed.key(), jar.getParent())) {
                        ctx.label(jar.getFileName() + " up-to-date · " + where + serves);
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + jar.getFileName() + " · " + node.out() + "/ under " + where + serves);
                    NodePackaging.packageJar(
                            out, root, layout.targetDir().resolve("node-jar"), jar, project.manifest());
                    PackagingCache.storePackaged(
                            ctx,
                            guard,
                            cacheRoot,
                            keyed.taskId(),
                            keyed.key(),
                            keyed.inputs().entrySet().stream()
                                    .map(e -> e.getKey() + "=" + e.getValue())
                                    .toList(),
                            jar.getParent(),
                            List.of(jar),
                            !in.ephemeralActions(),
                            List.of());
                    PackageSteps.writeSidecarPom(project, layout, jar);
                    ctx.put(BUILD_OUTCOME, "built");
                    ctx.progress(1);
                })
                .build();
    }

    /** The one step of a skipped node build: the output on disk, if any, is what the build has. */
    private Task skippedStep() {
        return Task.builder(TaskNames.NODE_BUILD)
                .forecast(StepForecast.priced(s -> forecastVerdict(ForecastScope.of(s), TaskNames.NODE_BUILD)))
                .stage(BuildStage.COMPILE)
                .label("Node build")
                .kind(TaskKind.SYNC)
                .requires(TaskNames.PARSE_BUILD)
                .weight(EffortWeights.TOKEN)
                .ticks(1)
                .execute(ctx -> {
                    Path out = out();
                    if (Files.isDirectory(out)) {
                        publish(ctx, out);
                        ctx.label("skipped · using " + node.out() + "/ as it is");
                    } else {
                        ctx.label("skipped · no " + node.out() + "/ yet");
                    }
                    ctx.cached();
                    ctx.progress(1);
                })
                .build();
    }

    private Task installStep() {
        return Task.builder(TaskNames.NODE_INSTALL)
                .forecast(StepForecast.priced(s -> forecastVerdict(ForecastScope.of(s), TaskNames.NODE_INSTALL)))
                .stage(BuildStage.RESOLVE)
                .label("Install")
                .kind(TaskKind.IO)
                .requires(TaskNames.ENSURE_NODE)
                .weight(EffortWeights.TOKEN)
                .ticks(1)
                .execute(ctx -> {
                    NodeHome home = ctx.require(NODE_HOME);
                    String override = project.node().install();
                    if (override == null) requireLockfile(home.packageManager());
                    NodeKeys.Keyed keyed = NodeKeys.install(
                            nodeDir,
                            override,
                            PlannerNodeSetup.token(ctx.require(PARSED_BUILD).lockfile()));
                    ctx.put(NODE_INSTALLED, keyed.key());
                    Path stamp = NodeKeys.stamp(moduleDir);
                    if (!rebuild() && NodeKeys.installed(nodeDir, stamp, keyed.key())) {
                        ctx.label("node_modules up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label(home.packageManager().id() + " install");
                    Map<String, String> env = env(home, false);
                    List<String> argv = NodeCommands.install(home, override, env.getOrDefault("PATH", ""));
                    NodeProcess.Result r = NodeProcess.run(ctx, argv, nodeDir, env);
                    if (!r.ok())
                        throw new IOException(r.exited(home.packageManager().id() + " install"));
                    // A project with no dependencies installs nothing; the directory is still what the stamp vouches
                    // for.
                    Files.createDirectories(nodeDir.resolve("node_modules"));
                    Files.createDirectories(stamp.getParent());
                    Files.writeString(stamp, keyed.key(), StandardCharsets.UTF_8);
                    ctx.progress(1);
                })
                .build();
    }

    /** A frozen install needs the lockfile; a node build without one fails naming the command that writes it. */
    private void requireLockfile(PackageManager manager) throws IOException {
        for (String name : NodeKeys.LOCKFILES) {
            if (Files.isRegularFile(nodeDir.resolve(name))) return;
        }
        String lockfile =
                switch (manager) {
                    case NPM -> "package-lock.json";
                    case PNPM -> "pnpm-lock.yaml";
                    case YARN -> "yarn.lock";
                    case BUN -> "bun.lock";
                };
        throw new IOException(project.project().name() + ": no " + lockfile + "; run " + manager.id()
                + " install once and commit it");
    }

    private Task buildStep(String requires) {
        return Task.builder(TaskNames.NODE_BUILD)
                .forecast(StepForecast.priced(s -> forecastVerdict(ForecastScope.of(s), TaskNames.NODE_BUILD)))
                .stage(BuildStage.COMPILE)
                .label("Node build")
                .kind(TaskKind.CPU)
                .requires(requires)
                .ticks(1)
                .execute(ctx -> {
                    NodeTable.Command command = node.build();
                    if (command == null) {
                        publish(ctx, null);
                        ctx.label("nothing to build");
                        ctx.progress(1);
                        return;
                    }
                    requireScript(command);
                    if (command.kind() == NodeTable.Command.Kind.NPX) {
                        String pkg = NodeCommands.npxPackage(command.value());
                        if (!NodeCommands.locked(nodeDir, pkg)) throw unlocked(pkg, null);
                    }
                    NodeHome home = ctx.require(NODE_HOME);
                    NodeKeys.Keyed keyed = NodeKeys.build(
                            nodeDir,
                            node,
                            command,
                            NodeEnv.keyed(project, node, moduleDir, true),
                            ctx.require(NODE_INSTALLED),
                            PlannerNodeSetup.token(ctx.require(PARSED_BUILD).lockfile()),
                            NodeKeys.afterBuild(project),
                            project.node().inputs());
                    Path out = out();
                    Optional<ActionCache.ActionRecord> hit =
                            rebuild() ? Optional.empty() : actionCache.lookup(keyed.key());
                    if (hit.isPresent() && actionCache.restore(hit.get(), out)) {
                        publish(ctx, out);
                        ctx.label(node.out() + "/ up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label(NodeCommands.describe(command));
                    // Built only to run: it writes the registry credential to a file NodeProcess.run deletes.
                    Map<String, String> env = env(home, true);
                    List<String> argv = NodeCommands.command(home, command, List.of(), env.getOrDefault("PATH", ""));
                    NodeProcess.Result r = NodeProcess.run(ctx, argv, nodeDir, env);
                    if (!r.ok()) {
                        String exited = r.exited(NodeCommands.describe(command));
                        IOException failed = new IOException(exited);
                        // A step that reported its own errors has its exception dropped; the exit goes with them.
                        if (!r.diagnostics().isEmpty()) {
                            for (NodeProcess.Diagnostic d : r.diagnostics()) ctx.error("node", d.render());
                            ctx.error("node", exited);
                        }
                        throw failed;
                    }
                    if (!Files.isDirectory(out)) {
                        throw new IOException(NodeCommands.describe(command) + " wrote no " + node.out()
                                + "/; set [node] out to the directory it writes");
                    }
                    actionCache.store(keyed.taskId(), keyed.key(), keyed.inputs(), out);
                    publish(ctx, out);
                    ctx.put(BUILD_OUTCOME, "built");
                    ctx.progress(1);
                })
                .build();
    }

    private Task testStep(String requires) {
        return Task.builder(TaskNames.NODE_TEST)
                .forecast(StepForecast.priced(s -> forecastVerdict(ForecastScope.of(s), TaskNames.NODE_TEST)))
                .stage(BuildStage.TEST)
                .label("Node tests")
                .kind(TaskKind.CPU)
                .requires(requires)
                .ticks(1)
                .execute(ctx -> {
                    String script = node.test();
                    String body = script == null ? null : NodeProject.script(nodeDir, script);
                    if (script == null || body == null) {
                        ctx.label("no tests");
                        ctx.progress(1);
                        return;
                    }
                    NodeHome home = ctx.require(NODE_HOME);
                    NodeKeys.Keyed keyed = NodeKeys.test(
                            nodeDir,
                            node,
                            script,
                            NodeEnv.keyed(project, node, moduleDir, false),
                            ctx.require(NODE_INSTALLED),
                            PlannerNodeSetup.token(ctx.require(PARSED_BUILD).lockfile()),
                            NodeKeys.afterTest(project),
                            project.node().inputs());
                    boolean coverage = in.session().coverage();
                    if (!rebuild() && !coverage && replayGreen(ctx, actionCache, keyed.key())) {
                        ctx.progress(1);
                        return;
                    }
                    Path xml =
                            BuildLayout.of(moduleDir, project).testResultsDir().resolve(NodeTestReport.TEST_REPORT);
                    Files.deleteIfExists(xml);
                    Files.deleteIfExists(NodeTestReport.jestJson(xml));
                    Files.createDirectories(xml.getParent());
                    NodeTestReport.Runner runner = NodeTestReport.runner(body);
                    Map<String, String> env = env(home, false);
                    NodeTestReport.Wiring wiring =
                            NodeTestReport.wiring(runner, xml, env.getOrDefault("NODE_OPTIONS", ""));
                    Map<String, String> runEnv = new LinkedHashMap<>(env);
                    runEnv.putAll(wiring.env());
                    ctx.label("run " + script);
                    List<String> argv = NodeCommands.command(
                            home, NodeTable.Command.run(script), wiring.extraArgs(), env.getOrDefault("PATH", ""));
                    NodeProcess.Result r = NodeProcess.run(ctx, argv, nodeDir, runEnv);
                    TestSummary summary =
                            NodeTestReport.summary(NodeTestReport.read(xml, module()), r, module(), script);
                    ctx.put(TEST_RUN, TestRun.of(summary));
                    if (summary.allPassed() && r.ok()) {
                        actionCache.storeWithOutputs(
                                keyed.taskId(),
                                keyed.key(),
                                keyed.inputs(),
                                TestStamp.outcome(summary.total(), summary.succeeded(), summary.skipped(), 0));
                        ctx.progress(1);
                        return;
                    }
                    recordFailures(ctx, summary, r);
                    ctx.progress(1);
                })
                .build();
    }

    /** The counts of a passing run stored on its key, replayed; false when there is no green record. */
    private static boolean replayGreen(TaskContext ctx, ActionCache cache, String key) throws IOException {
        Optional<ActionCache.ActionRecord> marker = cache.lookup(key);
        if (marker.isEmpty() || !TestStamp.green(marker.get())) return false;
        ctx.label(TaskNames.TESTS_UP_TO_DATE);
        ctx.cached();
        TestSummary previous = PlannerTest.stampedSummary(marker.get());
        if (previous != null) ctx.put(TEST_RUN, TestRun.of(previous));
        return true;
    }

    /** Report each failure; fail the step unless the run or the module reports test failures without failing. */
    private void recordFailures(TaskContext ctx, TestSummary summary, NodeProcess.Result r) {
        for (TestFailureInfo f : summary.failures()) ctx.error("test-failure", f.message(), f);
        String failed = summary.failed() + " test failure" + (summary.failed() == 1 ? "" : "s");
        if (TestLaunch.failureMode(in, project) == TestFailureMode.REPORT) {
            ctx.warn(TestLaunch.FAILURES_REPORTED, failed + " reported, not failing: [test] failures = \"report\"");
            return;
        }
        throw new RuntimeException(failed + (r.ok() ? "" : " (exit " + r.exit() + ")"));
    }

    /** A {@code run} command names a script {@code package.json} has. */
    private void requireScript(NodeTable.Command command) throws IOException {
        if (command.kind() != NodeTable.Command.Kind.RUN) return;
        if (NodeProject.script(nodeDir, command.value()) == null) {
            throw new IOException(project.project().name() + ": package.json has no `" + command.value()
                    + "` script; add it or set [node] build");
        }
    }

    /** {@code group:name}, the label a test failure carries. */
    private String module() {
        return project.project().group() + ":" + project.project().name();
    }

    /** The build's output directory. */
    private Path out() {
        return nodeDir.resolve(node.out());
    }

    /** The environment a step's process starts with; {@code production} for a build. */
    private Map<String, String> env(NodeHome home, boolean production) throws IOException {
        return NodeEnv.of(in, project, node, home, moduleDir, production);
    }

    private boolean rebuild() {
        return in.session().config().rebuildOr(false);
    }
}

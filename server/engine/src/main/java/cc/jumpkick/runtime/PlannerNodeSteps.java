// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.LOCKFILE;
import static cc.jumpkick.runtime.BuildPlanner.NODE_EXPORTS;
import static cc.jumpkick.runtime.BuildPlanner.NODE_HOME;
import static cc.jumpkick.runtime.BuildPlanner.NODE_INSTALLED;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.model.TestFailureMode;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The {@code [[node.steps]]} of a node build: each a keyed task between the install and the step
 * its {@code before} names, in declaration order within one {@code before}. A step with outputs
 * is cached and restored like {@code out/}; one without is a check, cached as passed. A step of
 * the test tier never holds the build back: it runs beside it, and {@code --skip-tests} leaves it
 * out.
 */
final class PlannerNodeSteps {

    private PlannerNodeSteps() {}

    /**
     * What the steps leave the node build and its tests to wait on ({@code null}: the install), and
     * the steps nothing else in the plan waits on.
     */
    /** The last step before the build, the tests and the package; {@code null} where there is none. */
    record Planned(
            @Nullable String beforeBuild,
            @Nullable String beforeTest,
            @Nullable String beforePackage,
            List<String> leaves) {}

    /**
     * Add {@code unit}'s steps to {@code b}. {@code build}: the plan builds; {@code tests}: it runs
     * the test tier; {@code testStep}: it plans {@code node-test}.
     */
    static Planned plan(BuildPlan.Builder b, PlannerNode.Unit unit, boolean build, boolean tests, boolean testStep) {
        List<NodeTable.Step> steps = unit.project().node().steps();
        List<String> leaves = new ArrayList<>();
        String buildChain = chain(b, unit, steps, NodeTable.Before.BUILD, TaskNames.NODE_INSTALL, tests, leaves);
        String testChain = tests ? chain(b, unit, steps, NodeTable.Before.TEST, buildChain, true, leaves) : buildChain;
        String packageChain = build
                ? chain(b, unit, steps, NodeTable.Before.PACKAGE, TaskNames.NODE_BUILD, tests, leaves)
                : TaskNames.NODE_BUILD;
        String beforeBuild = buildChain.equals(TaskNames.NODE_INSTALL) ? null : buildChain;
        String beforeTest = testChain.equals(TaskNames.NODE_INSTALL) ? null : testChain;
        if (!build && beforeBuild != null && !(testStep && beforeTest != null)) leaves.add(beforeBuild);
        if (!testStep && beforeTest != null && !beforeTest.equals(beforeBuild)) leaves.add(beforeTest);
        String beforePackage = packageChain.equals(TaskNames.NODE_BUILD) ? null : packageChain;
        return new Planned(beforeBuild, beforeTest, beforePackage, leaves);
    }

    /**
     * Plan the build-tier steps of {@code group} as a chain from {@code start}, and its test-tier
     * steps beside it when {@code tests}; return the chain's end.
     */
    private static String chain(
            BuildPlan.Builder b,
            PlannerNode.Unit unit,
            List<NodeTable.Step> steps,
            NodeTable.Before group,
            String start,
            boolean tests,
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
                if (!tests) continue;
                BuildStage checkStage = group == NodeTable.Before.PACKAGE ? BuildStage.PACKAGE : BuildStage.TEST;
                b.addTask(task(unit, step, checkStage, end));
                leaves.add(NodeKeys.stepTask(step));
                continue;
            }
            b.addTask(task(unit, step, stage, end));
            end = NodeKeys.stepTask(step);
        }
        if (group == NodeTable.Before.PACKAGE && !end.equals(start)) leaves.add(end);
        return end;
    }

    private static Task task(PlannerNode.Unit unit, NodeTable.Step step, BuildStage stage, String requires) {
        return Task.builder(NodeKeys.stepTask(step))
                .stage(stage)
                .label(step.name())
                .kind(TaskKind.CPU)
                .requires(requires)
                .weight(EffortWeights.TOKEN)
                .ticks(1)
                .execute(ctx -> run(ctx, unit, step))
                .build();
    }

    private static void run(TaskContext ctx, PlannerNode.Unit unit, NodeTable.Step step) throws Exception {
        NodeHome home = ctx.require(NODE_HOME);
        NodeTable.Command command = step.command();
        PlannerNode.requireScript(unit, command);
        String fetched = command.kind() == NodeTable.Command.Kind.NPX ? fetched(unit, step) : null;
        NodeKeys.Keyed keyed = NodeKeys.step(
                unit.nodeDir(),
                unit.moduleDir(),
                unit.node(),
                step,
                outputs(unit),
                NodeEnv.keyed(unit.project(), unit.node(), unit.moduleDir(), false),
                ctx.require(NODE_INSTALLED),
                PlannerNodeSetup.token(ctx.require(LOCKFILE)),
                fetched);
        ActionCache cache = unit.actionCache();
        Optional<ActionCache.ActionRecord> hit = unit.rebuild() ? Optional.empty() : cache.lookup(keyed.key());
        if (hit.isPresent() && (step.outputs().isEmpty() || cache.restoreFiles(hit.get(), unit.nodeDir()))) {
            ctx.label(step.name() + " up-to-date");
            ctx.cached();
            ctx.progress(1);
            return;
        }
        Map<String, String> env = unit.env(home, false);
        List<String> argv = fetched != null
                ? NodeCommands.fetch(home, fetched, command.value())
                : NodeCommands.command(home, command, List.of(), env.getOrDefault("PATH", ""));
        ctx.label(PlannerNode.describe(command));
        NodeProcess.Result r = NodeProcess.run(ctx, argv, unit.nodeDir(), env);
        if (!r.ok()) {
            String exited = step.name() + ": " + PlannerNode.exited(PlannerNode.describe(command), r);
            for (NodeProcess.Diagnostic d : r.diagnostics()) ctx.error("node", d.render());
            if (step.tier() == NodeTable.Tier.TEST
                    && TestLaunch.failureMode(unit.in(), unit.project()) == TestFailureMode.REPORT) {
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
            Path path = unit.nodeDir().resolve(out).normalize();
            if (Files.isRegularFile(path)) {
                files.add(path);
            } else if (Files.isDirectory(path)) {
                PathUtil.forEachRegularFile(path, (file, attrs) -> files.add(file));
            } else {
                throw new IOException(step.name() + ": `" + PlannerNode.describe(command) + "` wrote no " + out
                        + " — fix the step or its outputs");
            }
        }
        if (files.isEmpty()) cache.storeWithOutputs(keyed.taskId(), keyed.key(), keyed.inputs(), Map.of());
        else cache.storeArtifacts(keyed.taskId(), keyed.key(), keyed.inputs(), unit.nodeDir(), files);
        ctx.progress(1);
    }

    /**
     * The {@code pkg@version} an unlocked npx fetches, or {@code null} when the install holds the
     * package. A package the install lacks is refused unless the step allows it.
     */
    private static @Nullable String fetched(PlannerNode.Unit unit, NodeTable.Step step) throws Exception {
        String value = step.command().value();
        String pkg = NodeCommands.npxPackage(value);
        if (NodeCommands.locked(unit.nodeDir(), pkg)) return null;
        if (!step.allowUnlocked()) throw unlocked(unit, pkg, step.name());
        String version = NodeCommands.npxVersion(value);
        if (version == null) version = new PackageManagerResolver().latest(pkg);
        return pkg + "@" + version;
    }

    /** An npx of {@code pkg}, which the install does not hold, refused with both ways out. */
    static IOException unlocked(PlannerNode.Unit unit, String pkg, @Nullable String step) {
        return new IOException(unit.project().project().name() + ": npx " + pkg
                + " is not in the lockfile — add it as a devDependency ("
                + unit.node().packageManager() + " add -D " + pkg + ")"
                + (step == null ? "" : " or set allow-unlocked = true on step " + step));
    }

    /** The outputs of the steps {@code node-build} runs before: every step but a build-tier one ahead of it. */
    static List<String> afterBuild(JkBuild project) {
        List<String> later = new ArrayList<>();
        for (NodeTable.Step s : project.node().steps()) {
            if (s.before() != NodeTable.Before.BUILD || s.tier() != NodeTable.Tier.BUILD) later.addAll(s.outputs());
        }
        return later;
    }

    /** The outputs of the steps {@code node-test} runs before: the package steps and the test tier's. */
    static List<String> afterTest(JkBuild project) {
        List<String> later = new ArrayList<>();
        for (NodeTable.Step s : project.node().steps()) {
            if (s.before() == NodeTable.Before.PACKAGE || s.tier() == NodeTable.Tier.TEST) later.addAll(s.outputs());
        }
        return later;
    }

    /** Every path a step or the build writes, relative to the node directory: no step's input. */
    static List<String> outputs(PlannerNode.Unit unit) {
        List<String> outputs = new ArrayList<>();
        outputs.add(unit.node().out());
        for (NodeTable.Step s : unit.project().node().steps()) outputs.addAll(s.outputs());
        return outputs;
    }

    /**
     * Check {@code [node] exports} against what the build writes and publish it: every export names
     * {@code out/}, a step's output, or a path under one of them.
     */
    static void publishExports(TaskContext ctx, PlannerNode.Unit unit) throws IOException {
        Map<String, String> exports = unit.project().node().exports();
        if (exports.isEmpty()) return;
        Map<String, Path> published = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : exports.entrySet()) {
            Path path = unit.nodeDir().resolve(e.getValue()).normalize();
            boolean written = false;
            for (String out : outputs(unit)) {
                if (path.startsWith(unit.nodeDir().resolve(out).normalize())) written = true;
            }
            if (!written) {
                throw new IOException("[node] exports." + e.getKey() + " = \"" + e.getValue()
                        + "\" is not out/ or a step's output — export what the build writes");
            }
            published.put(e.getKey(), path);
        }
        ctx.put(NODE_EXPORTS, published);
    }
}

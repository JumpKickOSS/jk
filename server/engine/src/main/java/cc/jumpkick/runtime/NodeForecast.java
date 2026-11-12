// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.NodeProject;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk explain}'s view of a node build: each step CACHED when its key's record (or, for the
 * install, its stamp) is in place, else RUN with what changed since the last record. The keys are
 * the build's, from the same {@link NodeKeys} bodies; {@link NodeArm}'s predictors read them here.
 */
final class NodeForecast {

    private NodeForecast() {}

    /** Every node step of {@code s}'s dedicated node module, against the module's lock token. */
    static List<TaskForecast.Task> forecast(ForecastScope s) throws IOException {
        return forecast(
                s.project,
                s.dir,
                Objects.requireNonNull(s.nodeDir(), "node directory"),
                LockfileReader.read(s.lockFile),
                s.actionCache,
                s.skipTests,
                SessionContext.current().skipNode());
    }

    /** Every node step of the build in {@code nodeDir}, as the build will key it. */
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
                NodeKeys.installed(nodeDir, NodeKeys.stamp(moduleDir), install.key())
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
        List<String> outputs = NodeKeys.outputs(project, node);
        for (NodeTable.Step step : project.node().steps()) {
            if (skipTests && step.tier() == NodeTable.Tier.TEST) continue;
            if (step.command().kind() == NodeTable.Command.Kind.NPX
                    && !NodeCommands.locked(
                            nodeDir, NodeCommands.npxPackage(step.command().value()))) {
                steps.add(new TaskForecast.Task(
                        NodeKeys.stepTask(step), TaskForecast.Status.RUN, "npx · not in the lockfile", null));
                continue;
            }
            NodeKeys.Keyed keyed = NodeKeys.step(
                    nodeDir,
                    moduleDir,
                    node,
                    step,
                    outputs,
                    NodeEnv.keyed(project, node, moduleDir, false),
                    install.key(),
                    token,
                    null,
                    project.node().inputs());
            steps.add(forecastStep(cache, keyed, NodeKeys.stepTask(step), NodeCommands.describe(step.command()), null));
        }
        NodeTable.Command command = node.build();
        if (command != null) {
            NodeKeys.Keyed build = NodeKeys.build(
                    nodeDir,
                    node,
                    command,
                    NodeEnv.keyed(project, node, moduleDir, true),
                    install.key(),
                    token,
                    NodeKeys.afterBuild(project),
                    project.node().inputs());
            steps.add(forecastStep(
                    cache, build, TaskNames.NODE_BUILD, NodeCommands.describe(command), nodeDir.resolve(node.out())));
        }
        if (!skipTests && node.test() != null && NodeProject.script(nodeDir, node.test()) != null) {
            NodeKeys.Keyed test = NodeKeys.test(
                    nodeDir,
                    node,
                    node.test(),
                    NodeEnv.keyed(project, node, moduleDir, false),
                    install.key(),
                    token,
                    NodeKeys.afterTest(project),
                    project.node().inputs());
            steps.add(forecastStep(cache, test, TaskNames.NODE_TEST, "run " + node.test(), null));
        }
        return steps;
    }

    /**
     * {@code node-package} as the step will run it: nothing to package (a server, or no root and no
     * dependant), RUN after a build that runs, else by the step's own key in the packaging cache.
     */
    static TaskForecast.Task forecastPackage(
            JkBuild project, Path moduleDir, Path nodeDir, Path cacheRoot, boolean buildRuns) throws IOException {
        NodeProject node = NodeProject.infer(nodeDir, project.node());
        String start = node.start();
        String serves = start == null ? "" : " · start: " + start;
        String root = NodePackaging.classpathRoot(project, moduleDir);
        if (root == null) {
            return new TaskForecast.Task(
                    TaskNames.NODE_PACKAGE,
                    TaskForecast.Status.CACHED,
                    start != null ? "server · start: " + start : "not packaged · nothing depends on this module",
                    null);
        }
        Path jar = BuildLayout.of(moduleDir, project).mainJar();
        Path out = nodeDir.resolve(node.out());
        if (buildRuns || !Files.isDirectory(out)) {
            return new TaskForecast.Task(
                    TaskNames.NODE_PACKAGE,
                    TaskForecast.Status.RUN,
                    "package " + jar.getFileName() + " after node-build" + serves,
                    null);
        }
        NodeKeys.Keyed keyed = NodeKeys.pkg(nodeDir, out, root, project.manifest());
        // The step restores from its record; a record that is there is no work, whatever the jar on disk.
        return PackagingCache.packagingActionCache(cacheRoot)
                        .lookup(keyed.key())
                        .isPresent()
                ? new TaskForecast.Task(
                        TaskNames.NODE_PACKAGE,
                        TaskForecast.Status.CACHED,
                        jar.getFileName() + " up-to-date" + serves,
                        short8(keyed.key()))
                : new TaskForecast.Task(
                        TaskNames.NODE_PACKAGE,
                        TaskForecast.Status.RUN,
                        "package " + jar.getFileName() + " · " + node.out() + "/ changed" + serves,
                        null);
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
}

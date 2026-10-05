// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compat.ToolProgress;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.NodeProject;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.DevReady;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk run} and {@code jk dev} of a node build. {@code run}: the module's {@code start} command
 * under the locked Node.js, from the node directory, with the module's {@code [env]}, {@code PORT}
 * from {@code .env} and {@code NODE_ENV=production} laid over the caller's environment; a module
 * with only a static output has nothing to start. {@code dev}: its {@code dev} script the same way,
 * without {@code NODE_ENV}, probed at its framework's dev port.
 */
public final class NodeRun {

    /**
     * Where the request that plans a run reports a Node.js it has to install first: the client's
     * download bar under {@code jk run} and {@code jk dev}, nothing when unbound.
     */
    public static final ScopedValue<ToolProgress> PROGRESS = ScopedValue.newInstance();

    private NodeRun() {}

    /**
     * A node build's dev server: its argv under the locked Node.js, the directory it runs in, what it
     * gets over the caller's environment, and the port it answers on ({@code null} when unknown).
     */
    public record Dev(
            List<String> argv,
            Path dir,
            Map<String, String> env,
            @Nullable Integer port,
            String display) {

        public Dev {
            argv = List.copyOf(argv);
            env = Map.copyOf(env);
        }

        /** {@code http://localhost:<port>}, or empty without a port. */
        public String url() {
            return port == null ? "" : "http://localhost:" + port;
        }
    }

    /** The run plan for the node module in {@code dir}; an error plan when it has nothing to start. */
    public static ExecPlan plan(Path dir, JkBuild project, @Nullable String callerPath)
            throws IOException, InterruptedException {
        Path nodeDir = NodeShape.nodeDir(project, dir);
        if (nodeDir == null) return ExecPlan.error("run", "no node build in " + dir);
        NodeProject node = NodeProject.infer(nodeDir, project.node());
        if (node.start() == null) {
            return ExecPlan.error(
                    "run",
                    project.project().name() + " builds static files and has nothing to start — a JVM module that"
                            + " depends on it serves them; to run a server set [node] start, or add a start script"
                            + " to package.json");
        }
        NodeHome home = home(dir);
        String path = path(home, nodeDir, callerPath);
        List<String> argv = NodeCommands.program(home, NodeCommands.split(node.start()), path);
        Map<String, String> env = env(dir, project, home, path);
        env.putIfAbsent("NODE_ENV", "production");
        return plan(
                "run",
                argv,
                nodeDir,
                String.join(" ", NodeCommands.split(node.start())),
                List.of(),
                ExecPlan.Probe.NONE,
                env);
    }

    /**
     * The dev server of the node build in {@code moduleDir} — a node module, or a JVM module's
     * {@code src/main/node} — or {@code null} when its {@code package.json} has no dev script.
     */
    public static @Nullable Dev dev(Path moduleDir, JkBuild project, @Nullable String callerPath)
            throws IOException, InterruptedException {
        Path nodeDir = NodeShape.nodeDir(project, moduleDir);
        if (nodeDir == null) return null;
        NodeProject node = NodeProject.infer(nodeDir, project.node());
        if (node.dev() == null) return null;
        NodeHome home = home(moduleDir);
        String path = path(home, nodeDir, callerPath);
        List<String> argv = NodeCommands.command(
                home, new NodeTable.Command(NodeTable.Command.Kind.RUN, node.dev()), List.of(), path);
        return new Dev(
                argv,
                nodeDir,
                env(moduleDir, project, home, path),
                node.devPort(),
                node.packageManager() + " run " + node.dev());
    }

    /** {@code jk dev} in a node module: its dev server in the app's place, no JVM, nothing to restart. */
    public static ExecPlan devPlan(
            Path dir, JkBuild project, @Nullable String callerPath, List<ExecPlan.Sidecar> sidecars)
            throws IOException, InterruptedException {
        Dev dev = dev(dir, project, callerPath);
        if (dev == null) {
            return ExecPlan.error(
                    "dev",
                    project.project().name() + " has no dev script — add one to package.json, or set [node] dev");
        }
        ExecPlan.Probe ready = dev.url().isEmpty()
                ? ExecPlan.Probe.NONE
                : new ExecPlan.Probe(dev.url(), "", DevReady.DEFAULT_TIMEOUT_MILLIS);
        return plan("dev", dev.argv(), dev.dir(), dev.display(), sidecars, ready, dev.env());
    }

    private static ExecPlan plan(
            String kind,
            List<String> argv,
            Path dir,
            String display,
            List<ExecPlan.Sidecar> sidecars,
            ExecPlan.Probe ready,
            Map<String, String> env) {
        return new ExecPlan(
                null,
                "",
                kind,
                argv,
                dir.toString(),
                display,
                "",
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                "",
                false,
                "",
                "",
                "",
                List.of(),
                List.of(),
                "",
                sidecars,
                ready,
                env,
                Map.of());
    }

    /** The locked Node.js for the workspace of {@code dir}, provisioned when it is not installed yet. */
    private static NodeHome home(Path dir) throws IOException, InterruptedException {
        Path lockFile = LockPaths.lockFile(dir);
        NodePin pin =
                Files.isRegularFile(lockFile) ? LockfileReader.read(lockFile).node() : null;
        if (pin == null) throw new IOException("Node.js is not locked yet — run `jk build` first");
        return PlannerNodeSetup.ensure(PlannerNodeSetup.provisioning.get(), pin, PROGRESS.orElse(ToolProgress.NONE));
    }

    private static String path(NodeHome home, Path nodeDir, @Nullable String callerPath) {
        Path bin = nodeDir.resolve("node_modules").resolve(".bin");
        return home.path(SearchPath.prepend(bin.toString(), callerPath));
    }

    /** What a node process gets over the caller's environment. */
    static Map<String, String> env(Path dir, JkBuild project, NodeHome home, String path) {
        Map<String, String> env =
                new LinkedHashMap<>(WorkerEnv.declared(project.build().env(), dir, BuildLayout.moduleTargetDir(dir)));
        String port = BuildEnv.forModule(dir).apply("PORT");
        if (port != null && !port.isBlank()) env.putIfAbsent("PORT", port);
        env.put("NODE_HOME", home.home().toString());
        env.put("PATH", path);
        return env;
    }
}

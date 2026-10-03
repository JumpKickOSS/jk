// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.NodeProject;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkBuild;
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
 * {@code jk run} of a node module: its {@code start} command under the locked Node.js, from the node
 * directory, with the module's {@code [env]}, {@code PORT} from {@code .env} and {@code
 * NODE_ENV=production} laid over the caller's environment. A module with only a static output has
 * nothing to start.
 */
public final class NodeRun {

    private NodeRun() {}

    /** The run plan for the node module in {@code dir}; an error plan when it has nothing to start. */
    public static ExecPlan plan(Path dir, JkBuild project, boolean dev, @Nullable String callerPath)
            throws IOException, InterruptedException {
        String kind = dev ? "dev" : "run";
        Path nodeDir = NodeShape.nodeDir(project, dir);
        if (nodeDir == null) return ExecPlan.error(kind, "no node build in " + dir);
        if (dev) {
            return ExecPlan.error(
                    kind, "jk dev does not run a node module yet — run its dev script (`npm run dev`) in " + nodeDir);
        }
        NodeProject node = NodeProject.infer(nodeDir, project.node());
        if (node.start() == null) {
            return ExecPlan.error(
                    kind,
                    project.project().name() + " builds static files and has nothing to start — a JVM module that"
                            + " depends on it serves them; to run a server set [node] start, or add a start script"
                            + " to package.json");
        }
        Path lockFile = LockPaths.lockFile(dir);
        NodePin pin =
                Files.isRegularFile(lockFile) ? LockfileReader.read(lockFile).node() : null;
        if (pin == null) return ExecPlan.error(kind, "Node.js is not locked yet — run `jk build` first");
        NodeHome home = PlannerNodeSetup.ensure(PlannerNodeSetup.provisioning.get(), pin, bytes -> {});
        Path bin = nodeDir.resolve("node_modules").resolve(".bin");
        String path = home.path(SearchPath.prepend(bin.toString(), callerPath));
        List<String> argv = NodeCommands.program(home, NodeCommands.split(node.start()), path);
        return new ExecPlan(
                null,
                "",
                kind,
                argv,
                nodeDir.toString(),
                "node " + String.join(" ", NodeCommands.split(node.start())),
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
                List.of(),
                ExecPlan.Probe.NONE,
                env(dir, project, home, path));
    }

    /** What the server gets over the caller's environment. */
    static Map<String, String> env(Path dir, JkBuild project, NodeHome home, String path) {
        Map<String, String> env =
                new LinkedHashMap<>(WorkerEnv.declared(project.build().env(), dir, BuildLayout.moduleTargetDir(dir)));
        String port = BuildEnv.forModule(dir).apply("PORT");
        if (port != null && !port.isBlank()) env.putIfAbsent("PORT", port);
        env.putIfAbsent("NODE_ENV", "production");
        env.put("NODE_HOME", home.home().toString());
        env.put("PATH", path);
        return env;
    }
}

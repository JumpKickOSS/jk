// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.LOCKFILE;
import static cc.jumpkick.runtime.BuildPlanner.NODE_HOME;

import cc.jumpkick.compat.NodeProvisioning;
import cc.jumpkick.compat.ToolProgress;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.NodeResolution;
import cc.jumpkick.node.PackageManagerSpec;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.util.function.Supplier;

/** {@code ensure-node}: the locked Node.js and package manager, on disk and on the task context. */
public final class PlannerNodeSetup {

    /** Test seam: where {@code ensure-node} provisions from; production reads the store and nodejs.org. */
    static volatile Supplier<NodeProvisioning> provisioning = NodeProvisioning::new;

    private PlannerNodeSetup() {}

    /** Publishes {@link BuildPlanner#NODE_HOME} from the lock's {@code [node]} pin. */
    static Task ensureNodeStep() {
        return Task.builder(TaskNames.ENSURE_NODE)
                .stage(BuildStage.RESOLVE)
                .label("Node.js")
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve Node.js");
                    NodePin pin = ctx.require(LOCKFILE).node();
                    if (pin == null) {
                        throw new IllegalStateException("jk-lock.toml pins no Node.js — run `jk lock`");
                    }
                    NodeProvisioning from = provisioning.get();
                    boolean onDisk = from.managed(pin.version()).isPresent();
                    NodeHome home = ensure(from, pin, new ToolPlanProgress(ctx));
                    ctx.put(NODE_HOME, home);
                    if (onDisk) ctx.cached();
                    ctx.progress(1);
                })
                .build();
    }

    /** The home {@code pin} names, with its package manager. */
    public static NodeHome ensure(NodeProvisioning provisioning, NodePin pin, ToolProgress progress)
            throws IOException, InterruptedException {
        NodeHome home;
        try {
            home = provisioning.ensure(
                    new NodeResolution(pin.version(), pin.npm(), null, pin.sha256()),
                    NodeProvisioning.Policy.DEFAULT,
                    progress);
        } catch (IOException e) {
            throw notInstalled("Node.js " + pin.version(), "node:" + pin.version(), e);
        }
        if (pin.packageManager() == null) return home;
        PackageManagerSpec manager = PackageManagerSpec.parse(pin.packageManager());
        try {
            return provisioning.withManager(home, manager, progress);
        } catch (IOException e) {
            throw notInstalled(manager.toString(), manager.manager().id() + ":" + manager.version(), e);
        }
    }

    /** The JDK's wording for a pin that is not on disk and cannot be fetched. */
    private static IOException notInstalled(String what, String toolSpec, IOException cause) {
        boolean offline = SessionContext.current().config().offlineOr(false);
        return new IOException(
                what + " is not installed — run `jk tool install " + toolSpec + "`"
                        + (offline ? " without --offline" : "") + " (" + cause.getMessage() + ")",
                cause);
    }

    /** The action-key token of the lock's Node.js: {@code node:<exact>[+<pm>@<exact>]}, or {@code none}. */
    public static String token(Lockfile lock) {
        NodePin pin = lock.node();
        return pin == null ? "none" : pin.token();
    }
}

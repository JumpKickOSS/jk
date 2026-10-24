// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.compat.NodeProvisioning;
import cc.jumpkick.compat.ToolProgress;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.node.NodeCaches;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.runtime.PlannerNodeSetup;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.ToolUse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * What the graph worker reads JavaScript and TypeScript with when a workspace has a front end: the
 * Node.js its lock pins (provisioned the way a build provisions it) and oxc's parser and resolver,
 * installed once per version under the store's tools. A workspace without a Node.js pin, or an
 * offline run with oxc not yet installed, gets none, and the worker's scanner reads its scripts.
 */
final class GraphScripts {

    private GraphScripts() {}

    static final String OXC_PARSER = "0.155.0";
    static final String OXC_RESOLVER = "11.24.2";

    private static final ReentrantLock INSTALL = new ReentrantLock();

    /** The Node.js executable and the directory whose {@code node_modules} holds oxc. */
    record Tools(Path node, Path oxc) {}

    static @Nullable Tools of(Path root) {
        try {
            Path lock = LockPaths.lockFile(root);
            if (!Files.isRegularFile(lock)) return null;
            NodePin pin = LockfileReader.read(lock).node();
            if (pin == null) return null;
            NodeHome home = PlannerNodeSetup.ensure(new NodeProvisioning(), pin, ToolProgress.NONE);
            Path oxc = JkDirs.store().resolve("tools").resolve("graph-oxc").resolve(OXC_PARSER + "-" + OXC_RESOLVER);
            if (!installed(oxc) && !install(home, oxc)) return null;
            ToolUse.record(JkDirs.store().resolve("tools"), "graph-oxc", oxc);
            return new Tools(home.node(), oxc);
        } catch (IOException | RuntimeException unavailable) {
            Log.debug("graph: no Node.js tools for " + root + "; scripts are scanned", unavailable);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean installed(Path dir) {
        Path modules = dir.resolve("node_modules");
        return Files.isDirectory(modules.resolve("oxc-parser")) && Files.isDirectory(modules.resolve("oxc-resolver"));
    }

    /** npm-installs both packages into a sibling directory, then moves it into place. */
    private static boolean install(NodeHome home, Path dir) throws IOException, InterruptedException {
        if (SessionContext.current().offline()) return false;
        INSTALL.lock();
        try {
            if (installed(dir)) return true;
            Files.createDirectories(dir.getParent());
            Path staging = Files.createTempDirectory(dir.getParent(), dir.getFileName() + ".");
            try {
                ProcessBuilder pb = new ProcessBuilder(List.of(
                                home.npm().toString(),
                                "install",
                                "--no-audit",
                                "--no-fund",
                                "--no-package-lock",
                                "--prefix",
                                staging.toString(),
                                "oxc-parser@" + OXC_PARSER,
                                "oxc-resolver@" + OXC_RESOLVER))
                        .directory(staging.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.environment().put("PATH", home.path(pb.environment().get("PATH")));
                pb.environment().putAll(NodeCaches.env(home.packageManager(), JkDirs.cache(), JkDirs.store()));
                pb.environment().put("npm_config_update_notifier", "false");
                Process p = pb.start();
                if (!p.waitFor(5, TimeUnit.MINUTES)) {
                    p.destroyForcibly();
                    return false;
                }
                if (p.exitValue() != 0 || !installed(staging)) return false;
                Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE);
                return true;
            } finally {
                PathUtil.deleteRecursively(staging);
            }
        } finally {
            INSTALL.unlock();
        }
    }
}

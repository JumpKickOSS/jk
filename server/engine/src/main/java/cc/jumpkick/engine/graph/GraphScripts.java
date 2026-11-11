// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.compat.NodeProvisioning;
import cc.jumpkick.compat.ToolProgress;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.node.NodeCaches;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.runtime.PlannerNodeSetup;
import cc.jumpkick.util.ToolUse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * What the graph worker reads JavaScript and TypeScript with when a workspace has a front end: the
 * Node.js its lock pins (provisioned the way a build provisions it) and oxc's parser and resolver,
 * installed once per lock under the store's tools. The packages come from the {@code package.json}
 * and {@code package-lock.json} shipped beside this class, installed with {@code npm ci}, so every
 * tarball is checked against the sha512 the lock records. A workspace without a Node.js pin, or an
 * offline run with oxc not yet installed, gets none, and the worker's scanner reads its scripts.
 */
final class GraphScripts {

    private GraphScripts() {}

    /** The npm project oxc installs from, as resources beside this class. */
    static final List<String> PROJECT_FILES = List.of("package.json", "package-lock.json");

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
            Path oxc = JkDirs.store().resolve("tools").resolve("graph-oxc").resolve(lockId());
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

    /** The install directory's name: the head of the shipped lock's SHA-256, so a new lock is a new install. */
    static String lockId() throws IOException {
        return Hashing.sha256Hex(resource("package-lock.json")).substring(0, 16);
    }

    /** Writes the shipped {@code package.json} and {@code package-lock.json} into {@code dir}. */
    static void stage(Path dir) throws IOException {
        for (String name : PROJECT_FILES) Files.write(dir.resolve(name), resource(name));
    }

    /** The install: exactly what the staged lock pins, each tarball checked against its integrity, no scripts run. */
    static List<String> command(Path npm, Path staging) {
        return List.of(
                npm.toString(),
                "ci",
                "--no-audit",
                "--no-fund",
                // Nothing the packages declare runs: oxc ships prebuilt bindings.
                "--ignore-scripts",
                "--prefix",
                staging.toString());
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = GraphScripts.class.getResourceAsStream("oxc/" + name)) {
            if (in == null) throw new IOException("the engine jar carries no oxc/" + name);
            return in.readAllBytes();
        }
    }

    /** npm-installs the shipped lock into a sibling directory, then moves it into place. */
    private static boolean install(NodeHome home, Path dir) throws IOException, InterruptedException {
        if (SessionContext.current().offline()) return false;
        // Interruptible: one workspace's install must not hold every other graph request uncancellable.
        INSTALL.lockInterruptibly();
        try {
            if (installed(dir)) return true;
            Files.createDirectories(dir.getParent());
            Path staging = Files.createTempDirectory(dir.getParent(), dir.getFileName() + ".");
            try {
                stage(staging);
                ProcessBuilder pb = new ProcessBuilder(command(home.npm(), staging))
                        .directory(staging.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.environment().put("PATH", home.path(pb.environment().get("PATH")));
                pb.environment().putAll(NodeCaches.env(home.packageManager(), JkDirs.cache(), JkDirs.store()));
                pb.environment().put("npm_config_update_notifier", "false");
                // Registered with the request, so a cancel stops it; and stopped here on an interrupt.
                Process p = JobWorkers.start(pb);
                try {
                    if (!p.waitFor(5, TimeUnit.MINUTES)) {
                        p.destroyForcibly();
                        return false;
                    }
                } catch (InterruptedException e) {
                    p.destroyForcibly();
                    throw e;
                }
                if (p.exitValue() != 0 || !installed(staging)) return false;
                AtomicWrites.publish(staging, dir);
                return true;
            } finally {
                PathUtil.deleteRecursively(staging);
            }
        } finally {
            INSTALL.unlock();
        }
    }
}

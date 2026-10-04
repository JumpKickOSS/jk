// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.MainClassScanner;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.NodeRun;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk dev} at a workspace root: every runnable member — each JVM application, each node
 * module with a dev script — as one stack. The plan names them; the client asks each JVM member for
 * its own dev plan and runs each node member's dev server, carried here as a sidecar of the same
 * name, beside the root's own {@code [dev.sidecars]}.
 */
final class DevStack {

    private DevStack() {}

    /**
     * The stack plan for {@code root}, or {@code null} when it has at most one runnable member — the
     * caller then runs that member as {@code jk dev} in it would.
     */
    static @Nullable ExecPlan plan(
            Path root, JkBuild rootBuild, Map<Path, JkBuild> modules, Map<String, String> clientEnv)
            throws IOException, InterruptedException {
        Map<String, String> members = new LinkedHashMap<>();
        Map<Path, JkBuild> nodeMembers = new LinkedHashMap<>();
        String path = clientEnv.getOrDefault("PATH", System.getenv("PATH"));
        for (var e : modules.entrySet()) {
            Path dir = e.getKey();
            JkBuild module = e.getValue();
            if (NodeShape.kind(module, dir) == NodeShape.Kind.MODULE) {
                if (NodeRun.dev(dir, module, path) == null) continue;
                nodeMembers.put(dir, module);
            } else if (!application(dir, module)) {
                continue;
            }
            members.put(module.project().name(), dir.toString());
        }
        if (members.size() <= 1) return null;
        List<ExecPlan.Sidecar> sidecars = new ArrayList<>(DevSidecars.stack(root, rootBuild, nodeMembers, clientEnv));
        return ExecPlan.devStack(root, members, sidecars);
    }

    /** The one runnable member of a root that has exactly one, else {@code null}. */
    static @Nullable Path single(Map<Path, JkBuild> modules, @Nullable String path)
            throws IOException, InterruptedException {
        List<Path> runnable = new ArrayList<>();
        for (var e : modules.entrySet()) {
            boolean node = NodeShape.kind(e.getValue(), e.getKey()) == NodeShape.Kind.MODULE;
            if (node ? NodeRun.dev(e.getKey(), e.getValue(), path) != null : application(e.getKey(), e.getValue())) {
                runnable.add(e.getKey());
            }
        }
        return runnable.size() == 1 ? runnable.getFirst() : null;
    }

    /** A JVM module with a declared main, or exactly one main class in what it has built. */
    private static boolean application(Path dir, JkBuild module) throws IOException {
        String main = module.mainClass();
        if (main != null && !main.isBlank()) return true;
        BuildLayout layout = BuildLayout.of(dir, module);
        List<String> found = new ArrayList<>();
        if (Files.isDirectory(layout.classesDir())) found.addAll(MainClassScanner.scan(layout.classesDir()));
        if (found.isEmpty() && Files.isRegularFile(layout.mainJar()))
            found.addAll(MainClassScanner.scan(layout.mainJar()));
        return found.size() == 1;
    }
}

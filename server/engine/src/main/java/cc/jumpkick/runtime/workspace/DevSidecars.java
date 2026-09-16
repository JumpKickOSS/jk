// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Sidecar;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code [dev]} for one module's dev plan. The sidecars: the workspace root's entries first, then
 * the module's, the module winning a name clash; {@code cwd} made absolute against the manifest
 * that declared it; {@code env} the {@code .env} values the real environment does not already set,
 * then the entry's own table on top. The app's own probe: the module's alone — the root does not
 * know which of its members' apps is running. Nothing here touches an action key — none of it is
 * a task.
 */
final class DevSidecars {

    private DevSidecars() {}

    /** {@code [dev] ready} / {@code ready-pattern} / {@code ready-timeout} on the wire, or {@link ExecPlan.Probe#NONE}. */
    static ExecPlan.Probe appReady(JkBuild module) {
        return ExecPlan.Probe.of(module.build().devReady());
    }

    static List<ExecPlan.Sidecar> resolve(Path moduleDir, JkBuild module, Map<String, String> clientEnv)
            throws IOException {
        Map<String, ExecPlan.Sidecar> byName = new LinkedHashMap<>();
        Path root = WorkspaceLocator.findRoot(moduleDir).orElse(null);
        if (root != null && !root.equals(moduleDir) && Files.isRegularFile(ManifestPaths.manifestIn(root))) {
            JkBuild rootBuild = JkBuildParser.parse(ManifestPaths.manifestIn(root));
            for (Sidecar s : rootBuild.build().devSidecars()) {
                byName.put(s.name(), resolve(root, s, clientEnv));
            }
        }
        for (Sidecar s : module.build().devSidecars()) {
            byName.put(s.name(), resolve(moduleDir, s, clientEnv));
        }
        return List.copyOf(byName.values());
    }

    private static ExecPlan.Sidecar resolve(Path declaredIn, Sidecar s, Map<String, String> clientEnv) {
        EnvLookup lookup = EnvLookup.forModule(declaredIn, clientEnv::get);
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : lookup.fileNames()) {
            if (!clientEnv.containsKey(name)) {
                String value = lookup.get(name);
                if (value != null) env.put(name, value);
            }
        }
        env.putAll(s.env());
        return new ExecPlan.Sidecar(
                s.name(),
                s.command(),
                declaredIn.resolve(s.cwd()).toAbsolutePath().normalize().toString(),
                env,
                ExecPlan.Probe.of(s.ready()),
                s.frontDoor(),
                s.restart());
    }
}

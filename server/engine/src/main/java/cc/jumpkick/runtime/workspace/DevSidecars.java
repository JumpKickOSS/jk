// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code [dev.sidecars]} for one module's dev plan: the workspace root's entries first, then the
 * module's, the module winning a name clash; {@code cwd} made absolute against the manifest that
 * declared it; {@code env} the {@code .env} values the real environment does not already set, then
 * the entry's own table on top. Nothing here touches an action key — sidecars are not tasks.
 */
final class DevSidecars {

    private DevSidecars() {}

    static List<ExecPlan.Sidecar> resolve(Path moduleDir, JkBuild module, Map<String, String> clientEnv)
            throws IOException {
        Map<String, ExecPlan.Sidecar> byName = new LinkedHashMap<>();
        Path root = WorkspaceLocator.findRoot(moduleDir).orElse(null);
        if (root != null && !root.equals(moduleDir) && Files.isRegularFile(root.resolve(ManifestPaths.MANIFEST))) {
            JkBuild rootBuild = JkBuildParser.parse(root.resolve(ManifestPaths.MANIFEST));
            for (JkBuild.Sidecar s : rootBuild.build().devSidecars()) {
                byName.put(s.name(), resolve(root, s, clientEnv));
            }
        }
        for (JkBuild.Sidecar s : module.build().devSidecars()) {
            byName.put(s.name(), resolve(moduleDir, s, clientEnv));
        }
        return List.copyOf(byName.values());
    }

    private static ExecPlan.Sidecar resolve(Path declaredIn, JkBuild.Sidecar s, Map<String, String> clientEnv) {
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
                s.ready() == null ? "" : s.ready(),
                s.readyPattern() == null ? "" : s.readyPattern(),
                s.readyTimeoutMillis(),
                s.frontDoor(),
                s.restart());
    }
}

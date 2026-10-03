// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.NodeProject;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.PackageManager;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The environment a node step's process starts with, assembled in this one place: the module's
 * worker environment ({@code [env]}), the request's {@code NODE_ENV} and framework variables, the
 * package managers' caches under jk's store, {@code CI=true}, and the provisioned Node first on
 * {@code PATH}.
 */
final class NodeEnv {

    private NodeEnv() {}

    /**
     * The step's environment. {@code production}: a build, which gets {@code NODE_ENV=production}
     * unless the module or the request set it.
     */
    static Map<String, String> of(
            BuildPlanner.Inputs in,
            JkBuild project,
            NodeProject node,
            NodeHome home,
            Path moduleDir,
            boolean production)
            throws IOException {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("npm_config_cache", in.cache().resolve("npm").toString());
        // pnpm's own key; npm warns about every config it does not know.
        if (home.packageManager() == PackageManager.PNPM) {
            vars.put(
                    "npm_config_store_dir", JkDirs.store().resolve("pnpm-store").toString());
        }
        vars.put("npm_config_update_notifier", "false");
        vars.putAll(NodeNetwork.env(BuildLayout.moduleTargetDir(moduleDir).resolve("node")));
        vars.putAll(keyed(project, node, moduleDir, false));
        Map<String, String> env = new LinkedHashMap<>(
                WorkerEnv.forModule(project.build().env(), moduleDir, BuildLayout.moduleTargetDir(moduleDir))
                        .with(vars)
                        .environment());
        if (production) env.putIfAbsent("NODE_ENV", "production");
        env.put("PATH", home.path(env.get("PATH")));
        return env;
    }

    /**
     * The variables a node step's key reads, as {@link #of} hands them over: {@code CI}, the
     * module's {@code [env]}, the request's {@code NODE_ENV} and framework variables, and for a
     * {@code production} build the {@code NODE_ENV} default. The forecast keys on these too.
     */
    static Map<String, String> keyed(JkBuild project, NodeProject node, Path moduleDir, boolean production) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("CI", "true");
        vars.putAll(WorkerEnv.declared(project.build().env(), moduleDir, BuildLayout.moduleTargetDir(moduleDir)));
        List<String> prefixes = new ArrayList<>(BuildEnv.NODE_PREFIXES);
        prefixes.addAll(node.envPrefixes());
        vars.putAll(BuildEnv.nodeFromRequest(prefixes));
        if (production) vars.putIfAbsent("NODE_ENV", "production");
        return vars;
    }
}

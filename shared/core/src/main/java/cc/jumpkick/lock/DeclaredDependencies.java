// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * How many distinct modules the manifests feeding a lock declare — every scope of the owner and
 * of each workspace member — which is the size of the graph a first lock solves, readable before
 * any lock exists. A manifest that does not parse contributes what was read before it; a missing
 * owner manifest counts nothing.
 */
public final class DeclaredDependencies {

    private DeclaredDependencies() {}

    public static int countDistinct(Path lockOwnerDir) {
        Path owner = lockOwnerDir.toAbsolutePath().normalize();
        Path rootToml = ManifestPaths.manifestIn(owner);
        if (!Files.isRegularFile(rootToml)) return 0;
        Set<String> modules = new HashSet<>();
        try {
            JkBuild root = JkBuildParser.parseLocal(rootToml);
            collect(root, modules);
            if (root.isWorkspaceRoot()) {
                for (JkBuild member : WorkspaceLoader.loadModules(owner, root).values()) collect(member, modules);
            }
        } catch (IOException | RuntimeException e) {
            Log.debug("countDistinct: counted the manifests that parsed", e);
        }
        return modules.size();
    }

    private static void collect(JkBuild build, Set<String> into) {
        for (List<Dependency> deps : build.dependencies().byScope().values()) {
            for (Dependency d : deps) into.add(d.module());
        }
    }
}

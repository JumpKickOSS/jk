// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The module directories a workspace root declares — the root itself when it is a standalone project. */
public final class WorkspaceModules {

    private WorkspaceModules() {}

    public static List<Path> of(Path root) throws IOException {
        Path manifest = root.resolve(ManifestPaths.MANIFEST);
        List<Path> out = new ArrayList<>();
        if (!Files.isRegularFile(manifest)) return out;
        JkBuild build = JkBuildParser.parse(manifest);
        if (build.workspace() != null && !build.workspace().modules().isEmpty()) {
            for (String m : build.workspace().modules()) out.add(root.resolve(m));
        } else {
            out.add(root);
        }
        return out;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The workspace's member directories in path order, for the loader's member layer; the root alone when it has none. */
final class WorkspaceMembers {

    private WorkspaceMembers() {}

    static List<Path> of(Path root) throws IOException {
        Path manifest = ManifestPaths.manifestIn(root);
        List<Path> out = new ArrayList<>();
        if (!Files.isRegularFile(manifest)) return out;
        JkBuild build;
        try {
            build = JkBuildParser.parse(manifest);
        } catch (RuntimeException unparseable) {
            return out; // parse-build reports it
        }
        if (build.workspace() != null) for (String m : build.workspace().modules()) out.add(root.resolve(m));
        out.sort(null);
        return out;
    }
}

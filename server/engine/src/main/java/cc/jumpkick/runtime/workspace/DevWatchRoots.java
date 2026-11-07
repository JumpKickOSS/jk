// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.host.ManifestNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What {@code jk dev} watches: the app module's {@code src/}, and the {@code src/} of every workspace
 * member it runs on. The app runs from those members' classes, and the session's compile of the
 * workspace recompiles them, so an edit to a service the app injects restarts it like an edit to the
 * app's own code.
 */
final class DevWatchRoots {

    private DevWatchRoots() {}

    static List<String> of(Path dir, WorkspaceClasspath.Result siblings) {
        Set<String> roots = new LinkedHashSet<>();
        addSourceRoot(roots, dir);
        for (Path output : siblings.siblingClosureClasses()) {
            Path member = output.toAbsolutePath().normalize().getParent();
            while (member != null && !Files.isRegularFile(member.resolve(ManifestNames.MANIFEST))) {
                member = member.getParent();
            }
            if (member != null) addSourceRoot(roots, member);
        }
        return List.copyOf(roots);
    }

    private static void addSourceRoot(Set<String> roots, Path module) {
        Path src = module.resolve("src");
        if (Files.isDirectory(src)) roots.add(src.toString());
    }
}

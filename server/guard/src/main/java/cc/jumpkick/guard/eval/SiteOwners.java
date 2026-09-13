// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.extract.WorkspaceFacts;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Which workspace member compiled each class a workspace-scoped suite can report. A module suite
 * reports its own module's classes, and their sites resolve against that module's source roots; a
 * workspace suite reports any member's, and a site resolves against the member that owns the class
 * — spelled under the suite's module it would name a file that does not exist.
 *
 * @param root the workspace root
 * @param dirByClass the owning module directory of every class in a member's main index, by internal name
 */
public record SiteOwners(Path root, Map<String, Path> dirByClass) {

    public SiteOwners {
        dirByClass = Map.copyOf(dirByClass);
    }

    /** The owners as the members' main indexes record them; a member without an index owns nothing. */
    public static SiteOwners of(Path root, List<Path> modules) {
        return new SiteOwners(root, WorkspaceFacts.classModuleDirs(root, modules));
    }

    /** The directory of the member whose index holds {@code binaryName}, or {@code null} when none does. */
    public @Nullable Path dirOf(String binaryName) {
        return dirByClass.get(binaryName.replace('.', '/'));
    }
}

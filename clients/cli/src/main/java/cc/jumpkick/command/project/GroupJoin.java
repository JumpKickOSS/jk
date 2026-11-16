// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.SearchCeiling;
import cc.jumpkick.config.WorkspaceGroup;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * A project {@code jk new} creates below a group root ({@link WorkspaceGroup}) joins the root's
 * {@code [workspaces] members}: on a terminal it asks first (yes by default); with no one to ask, or
 * under {@code -y}, it is listed, as a new module is listed in its workspace. {@code --no-module}
 * creates it apart from either.
 */
final class GroupJoin {

    /** Set while a {@code jk new --no-module} runs: the new project joins nothing. */
    private static final ThreadLocal<Boolean> APART = ThreadLocal.withInitial(() -> false);

    private GroupJoin() {}

    /** A body that may throw what {@code jk new} throws. */
    interface Body {
        int run() throws IOException;
    }

    /** Run {@code body} with {@link #offer} declining when {@code apart}. */
    static int unless(boolean apart, Body body) throws IOException {
        APART.set(apart);
        try {
            return body.run();
        } finally {
            APART.remove();
        }
    }

    /**
     * The group root a new project at {@code target} sits below: the nearest strict ancestor that
     * declares {@code [workspaces]}, below any other project and inside the repository; {@code null}
     * when none does.
     */
    static @Nullable Path enclosing(Path target) {
        SearchCeiling ceiling = SearchCeiling.current();
        Path start = WorkingDirectory.resolve(target);
        for (Path dir = ceiling.above(start); dir != null; dir = ceiling.above(dir)) {
            if (WorkspaceGroup.declares(dir)) return dir;
            if (Files.exists(dir.resolve(ManifestPaths.MANIFEST))) return null; // inside another project
            if (Files.exists(dir.resolve(".git"))) return null; // left the repository
        }
        return null;
    }

    /**
     * Offer to list {@code target} in the group root above it. Returns the tail the settle line
     * carries ({@code added to the group's members}, or how to add it), or {@code null} when no group
     * root encloses it or one already lists it.
     */
    static @Nullable String offer(Path target) {
        if (APART.get()) return null;
        Path root = enclosing(target);
        if (root == null || WorkspaceGroup.listing(target) != null) return null;
        String rel =
                root.relativize(WorkingDirectory.resolve(target)).toString().replace('\\', '/');
        boolean add = Confirm.assumeYes()
                || !Confirm.isInteractiveTerminal()
                || Confirm.of("Add " + rel + " to the group's [workspaces] members?", true)
                        .ask();
        if (!add)
            return "not in the group's members; add \"" + rel + "\" to [workspaces] members to build it from the root";
        try {
            WorkspaceGroup.addMember(root, rel);
            return "added to the group's members";
        } catch (IOException | JkBuildParseException e) {
            return "not added to the group's members (" + e.getMessage() + ")";
        }
    }
}

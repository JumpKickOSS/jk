// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.WorkspaceScan;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The workspace root the plan job admitted last ran in, process-wide: what the idle trim keeps
 * warm, since the next build on this machine is most likely that workspace's, and what tells a
 * job that it entered another workspace.
 */
public final class LastBuiltRoot {

    private static final AtomicReference<@Nullable Path> LAST = new AtomicReference<>();

    private LastBuiltRoot() {}

    /**
     * Note the workspace that owns {@code dir} as the last built, and return the root of the one
     * noted before it when that was a different workspace; null when the workspace is unchanged,
     * none was noted yet, or {@code dir} is blank or not a path of this host (which keeps the
     * previous note). A directory no workspace lists is its own root.
     */
    public static @Nullable Path note(String dir) {
        if (dir.isBlank()) return null;
        Path root;
        try {
            Path at = Path.of(dir).toAbsolutePath().normalize();
            root = WorkspaceScan.findRoot(at).orElse(at);
        } catch (InvalidPathException ignored) {
            return null;
        }
        Path previous = LAST.getAndSet(root);
        return previous == null || previous.equals(root) ? null : previous;
    }

    /** The last noted workspace root, or null before any plan job ran. */
    public static @Nullable Path get() {
        return LAST.get();
    }
}

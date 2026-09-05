// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.run.TaskNames;
import java.nio.file.Path;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Generation policy for Class-C ("heavy ship") outputs: native images, OCI tarballs, and fat
 * assembly jars.
 *
 * <p>Rebuilding one of these mints a new action key and leaves the previous one rooting its blob
 * forever, so each task keeps a bounded generation list and drops the rest at store time. That is a
 * bound on staleness, not a size policy — the action cache as a whole is bounded by
 * {@link ActionCachePrune}. Released artifacts are promoted into the long-lived store CAS (see
 * {@link cc.jumpkick.cache.ActionPromote}).
 *
 * <p>Defaults: 2 generations of native binaries, 1 generation of OCI images, 2 generations of fat
 * assembly jars.
 */
public final class HeavyActionPolicy {

    public static final int NATIVE_GENERATIONS = 2;
    public static final int IMAGE_GENERATIONS = 1;
    public static final int ASSEMBLY_GENERATIONS = 2;

    private HeavyActionPolicy() {}

    /** Task name segment before {@code @} in a qualified task id, or the whole id. */
    public static String taskName(String taskId) {
        if (taskId == null || taskId.isBlank()) return "";
        int at = taskId.indexOf('@');
        String name = at < 0 ? taskId : taskId.substring(0, at);
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * How many action-key generations to keep for this task (including the current pointer).
     * Non-Class-C returns {@link Integer#MAX_VALUE} (no generation trim).
     */
    public static int generations(@Nullable String taskId) {
        return switch (taskName(taskId)) {
            case TaskNames.NATIVE_IMAGE -> NATIVE_GENERATIONS;
            case TaskNames.WRITE_IMAGE -> IMAGE_GENERATIONS;
            case TaskNames.PACKAGE_ASSEMBLY, TaskNames.PACKAGE_MINIFIED -> ASSEMBLY_GENERATIONS;
            default -> Integer.MAX_VALUE;
        };
    }

    /** Generation-list sidecar for {@code taskId} under {@code tasksDir} ({@code <taskId>.gens}). */
    public static Path gensFile(Path tasksDir, @Nullable String taskId) {
        return tasksDir.resolve(taskId + ".gens");
    }
}

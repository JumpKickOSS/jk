// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.run.TaskNames;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Class-C ("heavy ship") action-cache policy: native images, OCI tarballs, and fat assembly jars.
 *
 * <p>These are large, low hit-rate under normal edit loops, and compete with modular compile/test
 * cache. Generations + short TTL + a share of the cache budget keep them from eating the pool.
 * Released artifacts are promoted into the long-lived store CAS (see {@link ActionPromote}).
 *
 * <p>Defaults: 50 % of the action-cache budget, 3-day unused TTL, 2 generations of native binaries,
 * 1 generation of OCI images, 2 generations of fat assembly jars.
 */
public final class HeavyActionPolicy {

    /** Fraction of {@code max-cache-size} reserved as a hard ceiling for Class-C blob bytes. */
    public static final double BUDGET_FRACTION = 0.50;

    /** Unused Class-C action keys older than this are deleted on prune. */
    public static final Duration TTL = Duration.ofDays(3);

    public static final int NATIVE_GENERATIONS = 2;
    public static final int IMAGE_GENERATIONS = 1;
    public static final int ASSEMBLY_GENERATIONS = 2;

    private static final Set<String> HEAVY_TASKS = Set.of(
            TaskNames.NATIVE_IMAGE, TaskNames.WRITE_IMAGE, TaskNames.PACKAGE_ASSEMBLY, TaskNames.PACKAGE_MINIFIED);

    private HeavyActionPolicy() {}

    /** Task name segment before {@code @} in a qualified task id, or the whole id. */
    public static String taskName(String taskId) {
        if (taskId == null || taskId.isBlank()) return "";
        int at = taskId.indexOf('@');
        String name = at < 0 ? taskId : taskId.substring(0, at);
        return name.toLowerCase(Locale.ROOT);
    }

    public static boolean isClassC(String taskId) {
        return HEAVY_TASKS.contains(taskName(taskId));
    }

    /**
     * How many action-key generations to keep for this task (including the current pointer).
     * Non-Class-C returns {@link Integer#MAX_VALUE} (no generation trim).
     */
    public static int generations(String taskId) {
        return switch (taskName(taskId)) {
            case TaskNames.NATIVE_IMAGE -> NATIVE_GENERATIONS;
            case TaskNames.WRITE_IMAGE -> IMAGE_GENERATIONS;
            case TaskNames.PACKAGE_ASSEMBLY, TaskNames.PACKAGE_MINIFIED -> ASSEMBLY_GENERATIONS;
            default -> Integer.MAX_VALUE;
        };
    }

    /** Class-C byte budget from the overall cache budget (0 if uncapped/unknown). */
    public static long classCBudgetBytes(long maxCacheSizeBytes) {
        if (maxCacheSizeBytes <= 0) return 0L;
        return Math.max(0L, Math.round(maxCacheSizeBytes * BUDGET_FRACTION));
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import org.jspecify.annotations.Nullable;

/**
 * How hard the volume jk lives on is pressing, as the factor every retention window is multiplied
 * by. One {@code statfs} answers it, so it is cheap enough to ask on every maintenance tick.
 *
 * <p>Both the share of the volume that is free and the bytes that are free count, and the worse of
 * the two decides: a terabyte disk at 4% free has 40 GiB and no room to be generous, while a small
 * disk at 25% free still has too little in absolute terms to keep every old build around.
 */
public final class DiskPressure {

    private static final long GIB = 1024L * 1024L * 1024L;

    private DiskPressure() {}

    /** No pressure: retention windows are the configured ones. */
    public static final double NONE = 1.0;

    /** Below this factor the volume is in trouble: expired entries go now and a pass reclaims to a watermark. */
    public static final double EMERGENCY = 0.1;

    /**
     * The multiplier for retention windows on a volume with {@code space}: {@code 1.0} with plenty of
     * room, shrinking in steps to {@code 0.1} when little is left. {@code null} (no probe) is no pressure.
     */
    public static double factor(JkCacheConfig.@Nullable DiskSpace space) {
        if (space == null || space.totalBytes() <= 0) return NONE;
        double free = (double) space.freeBytes() / space.totalBytes();
        long freeBytes = space.freeBytes();
        if (free < 0.05 || freeBytes < 10 * GIB) return EMERGENCY;
        if (free < 0.10 || freeBytes < 20 * GIB) return 0.25;
        if (free < 0.20 || freeBytes < 30 * GIB) return 0.5;
        return NONE;
    }

    /**
     * The most the artifact store's blobs may hold: 10 GiB, or 8% of the volume when that is less, so a
     * small disk is not asked to give up more than a twelfth of itself. No probe means 10 GiB.
     */
    public static long budgetBytes(JkCacheConfig.@Nullable DiskSpace space) {
        long cap = 10 * GIB;
        if (space == null || space.totalBytes() <= 0) return cap;
        return Math.min(cap, (long) (space.totalBytes() * 0.08));
    }

    /** True when {@code factor} is at the emergency level. */
    public static boolean emergency(double factor) {
        return factor <= EMERGENCY;
    }
}

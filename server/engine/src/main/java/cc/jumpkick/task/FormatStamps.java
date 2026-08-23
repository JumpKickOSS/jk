// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.util.function.Function;

/**
 * The format-stamp count cap. Stamps are empty marker files — zero data bytes and {@code Blocks: 0}
 * — so the tier costs inodes and dirents that no byte report can see, and a count is the only
 * honest bound. Retention itself lives in {@link CacheTier#FORMAT_STAMPS}; this is just the number,
 * which {@code /api/cache} also publishes as {@code formatStampsMax}.
 */
public final class FormatStamps {

    /** Default cap (dev / non-CI). */
    public static final int DEFAULT_MAX_FILES = 512_000;

    /** Cap when {@code CI=1} or {@code CI=true}: CI checkouts are wider and shorter-lived. */
    public static final int CI_MAX_FILES = 1_000_000;

    private FormatStamps() {}

    public static int maxFiles() {
        return maxFiles(System::getenv);
    }

    /** Testable: {@code CI=1} / {@code CI=true} (case-insensitive) → 1M, otherwise 512k. */
    public static int maxFiles(Function<String, String> env) {
        String ci = env.apply("CI");
        if ("1".equals(ci) || (ci != null && "true".equalsIgnoreCase(ci))) {
            return CI_MAX_FILES;
        }
        return DEFAULT_MAX_FILES;
    }
}

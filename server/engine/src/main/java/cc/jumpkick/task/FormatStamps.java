// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.util.function.Function;

/**
 * The format-stamp count cap. Stamps are empty marker files — zero data bytes and {@code Blocks: 0}
 * — so the tier costs inodes and dirents that no byte report can see, and a count is the only
 * honest bound. Retention itself is {@link CacheTier}'s bound for that tier; this is just the number,
 * which {@code /api/cache} also publishes as {@code formatStampsMax}.
 */
public final class FormatStamps {

    /**
     * Default cap (dev / non-CI). One stamp per source ever formatted, across every checkout that
     * shares the cache: the jk tree is ~2,000 sources, so this holds thirty of it. The number is a
     * count of inodes and dirents, which is what a stamp actually costs — a byte budget would read
     * zero here no matter how many there are.
     */
    public static final int DEFAULT_MAX_FILES = 65_536;

    /** Cap when {@code CI=1} or {@code CI=true}: CI checkouts are wider and shorter-lived. */
    public static final int CI_MAX_FILES = 131_072;

    private FormatStamps() {}

    public static int maxFiles() {
        return maxFiles(System::getenv);
    }

    /** Testable: {@code CI=1} / {@code CI=true} (case-insensitive) → the CI cap. */
    public static int maxFiles(Function<String, String> env) {
        String ci = env.apply("CI");
        if ("1".equals(ci) || (ci != null && "true".equalsIgnoreCase(ci))) {
            return CI_MAX_FILES;
        }
        return DEFAULT_MAX_FILES;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

/**
 * CLI-only request flag for {@code --no-timeline} (global). The engine writes the chrome profile
 * under {@code target/}; the CLI only forwards this preference on the session envelope. No
 * terminal announcement.
 */
public final class TimelineOpts {

    private static final ThreadLocal<Boolean> NO_TIMELINE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private TimelineOpts() {}

    public static void setNoTimeline(boolean noTimeline) {
        NO_TIMELINE.set(noTimeline);
    }

    public static void clear() {
        NO_TIMELINE.remove();
    }

    public static boolean noTimeline() {
        return Boolean.TRUE.equals(NO_TIMELINE.get());
    }
}

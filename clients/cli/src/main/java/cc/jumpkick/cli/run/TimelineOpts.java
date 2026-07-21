// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

/**
 * CLI-only request flag for {@code --no-timeline}. The engine writes the chrome profile; the CLI
 * only forwards this preference on the session envelope (and may announce the path the engine
 * reports).
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

    /** One-line discoverability when the engine reports a written timeline path. */
    public static void announce(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) return;
        System.err.println(
                "Timeline: "
                        + absolutePath
                        + "  (Perfetto or chrome://tracing; CI: archive this file. Disable: --no-timeline or JK_CHROME_PROFILE=off)");
    }
}

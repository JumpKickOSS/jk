// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import cc.jumpkick.host.HostLoad;
import java.util.Locale;

/**
 * The wall bound on one file's formatting, in milliseconds, and why it is what it is.
 *
 * <p>The {@linkplain FormatWatchdog#DEFAULT_TIMEOUT_MS default} is a reading of a quiet host, and
 * a host fifteen engines share is not one: a break search that takes 100&nbsp;ms alone takes
 * seconds when every core is oversubscribed several times over, and a bound that does not grow
 * with the load gives up on files nothing is wrong with. So the default is stretched by the
 * {@linkplain HostLoad#factor load per online processor} — rounded up, at most {@link
 * HostLoad#MAX_FACTOR} times — and {@code why} says so in the verdict of a file that still blew it,
 * so a slow host is told apart from a slow file. A limit set by hand is taken as written and
 * carries no note.
 *
 * @param ms the bound; {@code 0} or less is off
 * @param why how the bound came to differ from the default, or {@code ""} for the default or an
 *     explicit value
 */
record FormatTimeout(long ms, String why) {

    /** The default, stretched for {@code loadAverage} over {@code processors} as {@link HostLoad#factor} stretches a bound. */
    static FormatTimeout forHost(double loadAverage, int processors) {
        int factor = HostLoad.factor(loadAverage, processors);
        long ms = FormatWatchdog.DEFAULT_TIMEOUT_MS * factor;
        if (factor == 1) return new FormatTimeout(ms, "");
        String why = String.format(
                Locale.ROOT,
                "the %d ms default stretched %d× for a load average of %.1f over %d processors",
                FormatWatchdog.DEFAULT_TIMEOUT_MS,
                factor,
                loadAverage,
                processors);
        return new FormatTimeout(ms, why);
    }

    /** A bound set by hand ({@code jk.format.file-timeout-ms}): as written, whatever the load. */
    static FormatTimeout explicit(long ms) {
        return new FormatTimeout(ms, "");
    }

    /** {@code (limit 6000 ms, the 3000 ms default stretched 2× …)} — the parenthetical a verdict carries. */
    String describe() {
        return "(limit " + ms + " ms" + (why.isEmpty() ? "" : ", " + why) + ")";
    }
}

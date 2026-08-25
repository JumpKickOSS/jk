// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import java.time.Duration;
import java.util.ArrayList;
import org.jspecify.annotations.NullMarked;

/**
 * The CLI's duration vocabularies. Four faces, each deliberate — a clock, a human-friendly took
 * line, a coarse forecast, and a status table — and exactly one spelling of each, so a fifth face
 * has to be argued for here rather than hand-rolled beside a call site.
 *
 * <ul>
 *   <li>{@link #clock} — {@code 14s} / {@code 1m 02s} / {@code 1h 05m 09s}: zero-padded ticking
 *       faces (live tree header, drain elapsed, plain ETA).
 *   <li>{@link #human} — {@code 712ms} / {@code 3.1s} / {@code 2m 4s} / {@code 1d 12h 13m 5s}:
 *       settled "took" durations.
 *   <li>{@link #coarseFloor} — {@code <1s} / {@code 8s} / {@code 1m 20s}: predicted durations,
 *       floored so a forecast never over-states.
 *   <li>{@link #omitZero} — {@code 1d 4h 12s} / {@code 22s} / {@code —} when negative: the status
 *       table, zero components dropped.
 * </ul>
 */
@NullMarked
public final class DurationText {

    private DurationText() {}

    /**
     * {@code 14s} / {@code 1m 02s} / {@code 1h 05m 09s} from a whole-second counter. Dual-clock
     * faces (elapsed + remaining on one header) share one tick boundary by deriving both from the
     * same seconds value.
     */
    public static String clock(long totalSec) {
        long s = Math.max(0L, totalSec);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + "h " + String.format("%02d", m) + "m " + String.format("%02d", sec) + "s";
        if (m > 0) return m + "m " + String.format("%02d", sec) + "s";
        return sec + "s";
    }

    /** {@link #clock} from millis: negatives clamp to zero, sub-second remainders floor away. */
    public static String clockMillis(long millis) {
        return clock(Math.max(0L, millis) / 1000L);
    }

    /**
     * Human-friendly settled duration: {@code 712ms}, {@code 3.1s}, {@code 2m 4s}, {@code 1h 3m
     * 2s}, {@code 1d 12h 13m 5s}.
     */
    public static String human(Duration d) {
        long ms = d.toMillis();
        if (ms < 1000) return ms + "ms";
        long totalSec = d.toSeconds();
        if (totalSec < 60) return String.format("%.1fs", ms / 1000.0);
        long days = totalSec / 86400;
        long hours = (totalSec % 86400) / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m " + seconds + "s";
        if (hours > 0) return hours + "h " + minutes + "m " + seconds + "s";
        return minutes + "m " + seconds + "s";
    }

    /** {@code <1s} / {@code 8s} / {@code 1m 20s} — coarse floored prediction (never over-states). */
    public static String coarseFloor(long millis) {
        if (millis <= 0) return "<1s";
        long s = millis / 1000; // floor: don't over-state
        if (s == 0) return "<1s"; // a sub-second cache-verify pass
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    /** {@code 1d 4h 12s} / {@code 22s} / {@code 38ms}; {@code —} when negative. Zero components dropped. */
    public static String omitZero(long millis) {
        if (millis < 0) return "—";
        if (millis < 1000) return millis + "ms";
        long totalSec = millis / 1000;
        long days = totalSec / 86_400;
        totalSec %= 86_400;
        long hours = totalSec / 3_600;
        totalSec %= 3_600;
        long mins = totalSec / 60;
        long secs = totalSec % 60;
        ArrayList<String> parts = new ArrayList<>(4);
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (mins > 0) parts.add(mins + "m");
        if (secs > 0 || parts.isEmpty()) parts.add(secs + "s");
        return String.join(" ", parts);
    }
}

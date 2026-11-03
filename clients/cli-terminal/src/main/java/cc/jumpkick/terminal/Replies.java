// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.time.Duration;

/** Collects a terminal's raw reply to a query, which the key decoder would otherwise eat. */
final class Replies {

    /** Longest reply kept; anything past it is a runaway, not an answer. */
    private static final int MAX = 512;

    private Replies() {}

    /**
     * Bytes (as Latin-1 chars) arriving after {@code first} at the latest, until {@code idle} passes
     * with none. Empty when nothing arrived.
     */
    static String collect(Keys.ByteFeed feed, Duration first, Duration idle) {
        int b = feed.read(first);
        if (b < 0) return "";
        StringBuilder sb = new StringBuilder();
        while (b >= 0 && sb.length() < MAX) {
            sb.append((char) (b & 0xFF));
            b = feed.read(idle);
        }
        return sb.toString();
    }
}

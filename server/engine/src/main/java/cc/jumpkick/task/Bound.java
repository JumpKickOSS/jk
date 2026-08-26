// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.time.Duration;

/**
 * What holds one cache tier down: an optional retention window, plus a cap for when the
 * window alone is not enough.
 *
 * <p>The window runs <strong>unconditionally</strong> — a cache that never approaches any limit
 * must still shed work nobody will ask for again, and the bytes it frees are bytes the cap need not
 * take from something live. Only then does the cap act. Same order {@link ActionCachePrune} uses.
 *
 * @param kind how the tier is laid out on disk, which decides what a single victim is
 * @param window age past which an entry goes regardless of size; {@code null} disables it
 * @param cap what to do when the window was not enough
 * @param reason why the tier is deliberately unbounded; non-blank exactly when {@code kind} is
 *     {@link Kind#UNBOUNDED}
 */
public record Bound(Kind kind, Duration window, Cap cap, String reason) {

    /** How the tier is laid out, and therefore what one victim is. */
    public enum Kind {
        /** A flat or sharded tree of files. One victim is one file. */
        FILES,
        /** A directory of self-contained trees. One victim is one child tree. */
        SUBTREES,
        /** Two levels of the above; the cap applies <em>per parent</em>, not globally. */
        NESTED_SUBTREES,
        /** Bounded by {@link ActionCachePrune} in the same pass, on its own Policy. */
        DELEGATED,
        /** Deliberately unbounded. {@link #reason} says why, and it is checked. */
        UNBOUNDED
    }

    /** What the cap does once the window has run. */
    public sealed interface Cap {
        /** Keep at most {@code max} survivors, oldest mtime out first. */
        record Count(int max) implements Cap {}

        /**
         * Over {@code budgetBytes}, delete the whole tier. For tiers whose mtime is <em>not</em> a
         * use clock, where ranking victims would be guesswork: a reset costs one rebuild of
         * something derived, and it only ever fires in a tail no real workspace reaches.
         */
        record ResetOverBytes(long budgetBytes) implements Cap {}

        /** Delete the tier every pass. For aliases and links that are recreated on demand. */
        record ResetAlways() implements Cap {}

        /** The window is the whole policy. */
        record None() implements Cap {}
    }

    public static Bound files(Duration window, Cap cap) {
        return new Bound(Kind.FILES, window, cap, "");
    }

    public static Bound subtrees(Duration window, Cap cap) {
        return new Bound(Kind.SUBTREES, window, cap, "");
    }

    public static Bound nestedSubtrees(Duration window, Cap cap) {
        return new Bound(Kind.NESTED_SUBTREES, window, cap, "");
    }

    public static Bound delegated() {
        return new Bound(Kind.DELEGATED, null, new Cap.None(), "");
    }

    /** {@code reason} is required, and {@link CacheRetention} asserts it — see the enum's javadoc. */
    public static Bound unbounded(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("an unbounded tier must say why");
        }
        return new Bound(Kind.UNBOUNDED, null, new Cap.None(), reason);
    }

    public static Cap countCap(int max) {
        return new Cap.Count(max);
    }

    public static Cap resetOverBytes(long budgetBytes) {
        return new Cap.ResetOverBytes(budgetBytes);
    }

    public static Cap resetAlways() {
        return new Cap.ResetAlways();
    }

    public static Cap none() {
        return new Cap.None();
    }
}

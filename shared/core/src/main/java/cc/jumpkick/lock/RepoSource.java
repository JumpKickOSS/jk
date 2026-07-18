// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

/**
 * Split of a lockfile artifact {@code source} on the first {@code '+'} ({@code "<name>+<url>"},
 * or synthetic forms with no {@code '+'}).
 *
 * <p>{@link #name()} is strict ({@code null} unless {@code 0 < plus < len-1}); {@link #url()} is
 * lenient (tail after any {@code plus > 0}, else the whole string). The two disagree on a trailing
 * {@code '+'} ({@code "central+"} → name null, url empty) — intentional for each caller's contract.
 */
public record RepoSource(String source) {

    /** Parse a lockfile source string. Splits (lazily, per accessor) on the first {@code '+'}. */
    public static RepoSource parse(String source) {
        return new RepoSource(source);
    }

    /** Strict name before {@code '+'}, or {@code null} if missing/first/last. */
    public String name() {
        if (source == null) return null;
        int plus = source.indexOf('+');
        if (plus <= 0 || plus >= source.length() - 1) return null;
        return source.substring(0, plus);
    }

    /** Lenient URL after first {@code '+'} when {@code plus > 0}, else the whole string. */
    public String url() {
        if (source == null) return null;
        int plus = source.indexOf('+');
        return plus > 0 ? source.substring(plus + 1) : source;
    }
}

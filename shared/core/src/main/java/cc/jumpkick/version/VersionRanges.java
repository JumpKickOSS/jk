// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.version;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The version range grammar, read the same way for a range a dependency's POM declares and one
 * {@code jk.toml} writes. Accepts Maven's bracket spelling ({@code [1.0,2.0)}, {@code [1.0]},
 * {@code (,2.0]}), the ISO 31-11 spelling of an exclusive bound that Gradle writes into module
 * metadata ({@code [1.0,2.0[}, {@code ]1.0,2.0]}), unions of bracket ranges ({@code
 * [1.0,2.0),[3.0,4.0]}), and comma-separated comparator lists ({@code >=1.0, <2.0}).
 */
public final class VersionRanges {

    private VersionRanges() {}

    /** What a range is read into: the resolver's version sets, or nothing when only the spelling is checked. */
    public interface Sets<S> {

        S all();

        /** The versions between two bounds; a null bound leaves that side open and is never inclusive. */
        S between(@Nullable String min, boolean minInclusive, @Nullable String max, boolean maxInclusive);

        S union(S a, S b);

        S intersect(S a, S b);
    }

    /**
     * {@code spec} read into {@code sets}.
     *
     * @throws IllegalArgumentException when {@code spec} is not a range
     */
    public static <S> S read(String spec, Sets<S> sets) {
        String trimmed = spec.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("malformed Maven range: empty");
        if (isRangeOpen(trimmed.charAt(0))) {
            return readBracketRanges(trimmed, sets);
        }
        S result = null;
        for (String part : trimmed.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            S one = readComparator(token, sets);
            result = result == null ? one : sets.intersect(result, one);
        }
        if (result == null) throw new IllegalArgumentException("malformed Maven range: " + spec);
        return result;
    }

    /**
     * Refuse {@code spec} unless {@link #read} reads it into sets the resolver can build: a bracket
     * range whose lower bound sits above its upper one names no version and is refused as well.
     *
     * @throws IllegalArgumentException naming what is wrong with {@code spec}
     */
    public static void check(String spec) {
        read(spec, CHECKED);
    }

    private static final Sets<Boolean> CHECKED = new Sets<>() {
        @Override
        public Boolean all() {
            return true;
        }

        @Override
        public Boolean between(@Nullable String min, boolean minInclusive, @Nullable String max, boolean maxInclusive) {
            if (min != null && max != null) {
                int cmp = Versions.compare(min, max);
                if (cmp > 0 || (cmp == 0 && (!minInclusive || !maxInclusive))) {
                    throw new IllegalArgumentException("empty range: " + (minInclusive ? '[' : '(') + min + "," + max
                            + (maxInclusive ? ']' : ')'));
                }
            }
            return true;
        }

        @Override
        public Boolean union(Boolean a, Boolean b) {
            return true;
        }

        @Override
        public Boolean intersect(Boolean a, Boolean b) {
            return true;
        }
    };

    /**
     * One or more adjacent bracket ranges, unioned. Each range runs from its opening bracket to the
     * first closing one; versions carry no brackets, so no nesting is possible.
     */
    private static <S> S readBracketRanges(String spec, Sets<S> sets) {
        S acc = null;
        int i = 0;
        while (i < spec.length()) {
            while (i < spec.length() && (spec.charAt(i) == ',' || Character.isWhitespace(spec.charAt(i)))) {
                i++;
            }
            if (i >= spec.length()) break;
            if (!isRangeOpen(spec.charAt(i))) {
                throw new IllegalArgumentException("malformed Maven range: " + spec);
            }
            int j = i + 1;
            while (j < spec.length() && !isRangeClose(spec.charAt(j))) j++;
            if (j >= spec.length()) {
                throw new IllegalArgumentException("malformed Maven range: " + spec);
            }
            S part = readBracketRange(spec.substring(i, j + 1), sets);
            acc = acc == null ? part : sets.union(acc, part);
            i = j + 1;
        }
        if (acc == null) {
            throw new IllegalArgumentException("malformed Maven range: " + spec);
        }
        return acc;
    }

    /** {@code [} and {@code (} open a range; so does {@code ]}, the ISO spelling of an exclusive lower bound. */
    private static boolean isRangeOpen(char c) {
        return c == '[' || c == '(' || c == ']';
    }

    /** {@code ]} and {@code )} close a range; so does {@code [}, the ISO spelling of an exclusive upper bound. */
    private static boolean isRangeClose(char c) {
        return c == ']' || c == ')' || c == '[';
    }

    /**
     * One bracket range. A bound is inclusive when its bracket points at the version ({@code [a}
     * and {@code b]}) and exclusive when it points away ({@code (a}, {@code ]a}, {@code b)},
     * {@code b[}): Maven's and the ISO 31-11 spelling both read this way.
     */
    private static <S> S readBracketRange(String spec, Sets<S> sets) {
        boolean minInclusive = spec.charAt(0) == '[';
        boolean maxInclusive = spec.charAt(spec.length() - 1) == ']';
        String inner = spec.substring(1, spec.length() - 1);
        int comma = inner.indexOf(',');
        if (comma < 0) {
            // One version is Maven's exact pin, [1.0]; with no version, or a bound that points
            // away from it, the range names nothing.
            if (!minInclusive || !maxInclusive) {
                throw new IllegalArgumentException("malformed Maven range: " + spec);
            }
            String only = version(inner, spec);
            return sets.between(only, true, only, true);
        }
        String minStr = inner.substring(0, comma);
        String maxStr = inner.substring(comma + 1);
        String min = minStr.isBlank() ? null : version(minStr, spec);
        String max = maxStr.isBlank() ? null : version(maxStr, spec);
        if (min == null && max == null) return sets.all();
        if (min == null) return sets.between(null, false, Objects.requireNonNull(max), maxInclusive);
        if (max == null) return sets.between(min, minInclusive, null, false);
        return sets.between(min, minInclusive, max, maxInclusive);
    }

    /**
     * One comparator: {@code >=1.0}, {@code >1.0}, {@code <=2}, {@code <2}, {@code =1.0} or a bare
     * version. The version after the operator is required, so {@code >=} alone is refused, and so
     * is {@code >=1.0 <2.0}, a space written for the comma, which would read as one version.
     */
    private static <S> S readComparator(String spec, Sets<S> sets) {
        if (spec.startsWith(">=")) return sets.between(version(spec.substring(2), spec), true, null, false);
        if (spec.startsWith(">")) return sets.between(version(spec.substring(1), spec), false, null, false);
        if (spec.startsWith("<=")) return sets.between(null, false, version(spec.substring(2), spec), true);
        if (spec.startsWith("<")) return sets.between(null, false, version(spec.substring(1), spec), false);
        String exact = version(spec.startsWith("=") ? spec.substring(1) : spec, spec);
        return sets.between(exact, true, exact, true);
    }

    /** {@code raw} trimmed, refused when nothing is left or a space sits inside it: no version holds one. */
    private static String version(String raw, String spec) {
        String version = raw.trim();
        if (version.isEmpty())
            throw new IllegalArgumentException("malformed version range: " + spec + " names no version");
        if (version.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("malformed version range: " + spec + " holds a space inside a version");
        }
        return version;
    }
}

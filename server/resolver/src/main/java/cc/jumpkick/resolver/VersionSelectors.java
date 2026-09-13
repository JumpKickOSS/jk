// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.version.Versions;
import java.util.Objects;

/**
 * Converts jk's {@link VersionSelector} (caret-by-default semantics from {@code jk.toml}) into the
 * resolver's {@link VersionSet}, and maps POM-declared version strings onto PubGrub constraints.
 */
public final class VersionSelectors {

    private VersionSelectors() {}

    public static VersionSet toVersionSet(VersionSelector selector) {
        return switch (selector) {
            case VersionSelector.Caret c -> caretRange(c.version());
            case VersionSelector.Exact e -> VersionSet.exact(e.version());
            case VersionSelector.Tilde t -> tildeRange(t.version());
            case VersionSelector.Range r -> parseRange(r.raw());
            case VersionSelector.Latest l -> VersionSet.ALL;
            // Same unbounded set as `latest`; the difference is that a snapshot package's candidate
            // window is not narrowed to stable releases. See MavenPackageSource#setSnapshotPackages.
            case VersionSelector.Snapshot s -> VersionSet.ALL;
        };
    }

    /**
     * Constraint for a version string found in a Maven POM dependency declaration (R4).
     *
     * <ul>
     *   <li>Bare versions ({@code 1.2.3}) → {@code atLeast} (highest-wins / Gradle-style lower
     *       bound).
     *   <li>Maven bracket ranges ({@code [1.0,2.0)}, {@code (,2.0]}) and multi-ranges ({@code
     *       [1.0,2.0),[3.0,4.0]}) → {@link #parseRange}.
     *   <li>Comparator lists ({@code >=1.0, <2.0}) → {@link #parseRange}.
     * </ul>
     */
    public static VersionSet constraintFromPomVersion(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("POM dependency version must not be blank");
        }
        String trimmed = version.trim();
        if (looksLikeMavenRange(trimmed)) {
            return parseRange(trimmed);
        }
        // Highest-version-wins: a POM-declared version is a lower bound.
        return VersionSet.atLeast(trimmed, true);
    }

    /** True when {@code spec} is Maven-range or comparator syntax rather than a bare version. */
    static boolean looksLikeMavenRange(String spec) {
        char c = spec.charAt(0);
        if (c == '[' || c == '(' || c == '<' || c == '>') return true;
        // "=1.0" as comparator (rare in POMs; bare "1.0" is atLeast).
        if (c == '=' && spec.length() > 1) return true;
        return false;
    }

    /**
     * Parse a range expression. Accepts Maven-bracket syntax ({@code [1.0,2.0)}, {@code [1.0]},
     * {@code (,2.0]}), multi-ranges ({@code [1.0,2.0),[3.0,4.0]}), and comma-separated comparator
     * lists ({@code ">=1.0, <2.0"}).
     */
    public static VersionSet parseRange(String spec) {
        String trimmed = spec.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("(")) {
            return parseMavenBracketRanges(trimmed);
        }
        VersionSet result = VersionSet.ALL;
        for (String part : trimmed.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            result = result.intersect(parseComparator(token));
        }
        return result;
    }

    /**
     * One or more adjacent Maven bracket ranges, unioned. Single range delegates to {@link
     * #parseMavenBracketRange}; multi-range splits on the boundary between {@code )} / {@code ]} and
     * the next {@code (} / {@code [}.
     */
    static VersionSet parseMavenBracketRanges(String spec) {
        VersionSet acc = null;
        int i = 0;
        while (i < spec.length()) {
            while (i < spec.length() && (spec.charAt(i) == ',' || Character.isWhitespace(spec.charAt(i)))) {
                i++;
            }
            if (i >= spec.length()) break;
            char open = spec.charAt(i);
            if (open != '[' && open != '(') {
                throw new IllegalArgumentException("malformed Maven range: " + spec);
            }
            int depth = 0;
            int j = i;
            for (; j < spec.length(); j++) {
                char ch = spec.charAt(j);
                if (ch == '[' || ch == '(') depth++;
                else if (ch == ']' || ch == ')') {
                    depth--;
                    if (depth == 0) {
                        j++; // include close
                        break;
                    }
                }
            }
            if (depth != 0) {
                throw new IllegalArgumentException("malformed Maven range: " + spec);
            }
            VersionSet part = parseMavenBracketRange(spec.substring(i, j));
            acc = acc == null ? part : acc.union(part);
            i = j;
        }
        if (acc == null) {
            throw new IllegalArgumentException("malformed Maven range: " + spec);
        }
        return acc;
    }

    static VersionSet parseMavenBracketRange(String spec) {
        char open = spec.charAt(0);
        char close = spec.charAt(spec.length() - 1);
        if ((open != '[' && open != '(') || (close != ']' && close != ')')) {
            throw new IllegalArgumentException("malformed Maven range: " + spec);
        }
        boolean minInclusive = open == '[';
        boolean maxInclusive = close == ']';
        String inner = spec.substring(1, spec.length() - 1);
        int comma = inner.indexOf(',');
        if (comma < 0) {
            String only = inner.trim();
            if (only.isEmpty()) return VersionSet.ALL;
            return VersionSet.exact(only);
        }
        String minStr = inner.substring(0, comma).trim();
        String maxStr = inner.substring(comma + 1).trim();
        String min = minStr.isEmpty() ? null : minStr;
        String max = maxStr.isEmpty() ? null : maxStr;
        if (min == null && max == null) return VersionSet.ALL;
        if (min == null) return VersionSet.lessThan(Objects.requireNonNull(max), maxInclusive);
        if (max == null) return VersionSet.atLeast(min, minInclusive);
        return VersionSet.between(min, minInclusive, max, maxInclusive);
    }

    static VersionSet parseComparator(String spec) {
        if (spec.startsWith(">=")) return VersionSet.atLeast(spec.substring(2).trim(), true);
        if (spec.startsWith(">")) return VersionSet.atLeast(spec.substring(1).trim(), false);
        if (spec.startsWith("<=")) return VersionSet.lessThan(spec.substring(2).trim(), true);
        if (spec.startsWith("<")) return VersionSet.lessThan(spec.substring(1).trim(), false);
        if (spec.startsWith("=")) return VersionSet.exact(spec.substring(1).trim());
        return VersionSet.exact(spec);
    }

    /**
     * Cargo-style caret semantics: increment the leading non-zero segment, zero out everything after
     * it, exclusive upper. {@code 1.2.3 → [1.2.3, 2.0.0)}, {@code 0.2.3 → [0.2.3, 0.3.0)}, {@code
     * 0.0.3 → [0.0.3, 0.0.4)}. Pre-release anchors use the numeric core for bound math and keep the
     * full string as the inclusive lower bound ({@code ^1.2.3-RC1 → [1.2.3-RC1, 2.0.0)} — includes
     * RC1 itself). Non-numeric versions fall back to an exact match.
     */
    static VersionSet caretRange(String version) {
        String core = Versions.numericCore(version);
        if (core.isEmpty()) return VersionSet.exact(version);
        String[] parts = core.split("\\.");
        int leading = 0;
        while (leading < parts.length && parts[leading].equals("0")) leading++;
        if (leading >= parts.length) return VersionSet.exact(version);
        try {
            int n = Integer.parseInt(parts[leading]);
            StringBuilder upper = new StringBuilder();
            for (int i = 0; i < leading; i++) upper.append("0.");
            upper.append(n + 1);
            for (int i = leading + 1; i < parts.length; i++) upper.append(".0");
            return VersionSet.between(version, true, upper.toString(), false);
        } catch (NumberFormatException e) {
            return VersionSet.exact(version);
        }
    }

    /**
     * Cargo-style tilde semantics: lock the major (and minor, if present), allow patches. {@code
     * ~1.2.3 → [1.2.3, 1.3.0)}, {@code ~1.2 → [1.2, 1.3.0)}, {@code ~1 → [1, 2.0.0)}. Pre-release
     * anchors use the numeric core for upper-bound segments ({@code ~1.2.3-RC1 → [1.2.3-RC1, 1.3.0)}).
     */
    static VersionSet tildeRange(String version) {
        String core = Versions.numericCore(version);
        if (core.isEmpty()) return VersionSet.exact(version);
        String[] parts = core.split("\\.");
        try {
            if (parts.length == 1) {
                int n = Integer.parseInt(parts[0]);
                return VersionSet.between(version, true, String.valueOf(n + 1), false);
            }
            int minor = Integer.parseInt(parts[1]);
            StringBuilder upper = new StringBuilder(parts[0]).append('.').append(minor + 1);
            for (int i = 2; i < parts.length; i++) upper.append(".0");
            return VersionSet.between(version, true, upper.toString(), false);
        } catch (NumberFormatException e) {
            return VersionSet.exact(version);
        }
    }
}
